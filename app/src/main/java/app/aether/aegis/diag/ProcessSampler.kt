package app.aether.aegis.diag

import android.net.TrafficStats
import android.os.Debug
import android.os.Process
import java.io.File

/**
 * Per-second snapshot of Aegis's own process state — pid, memory,
 * thread count, CPU%, network throughput, and per-thread CPU%.
 *
 * Sandbox confines us to inspecting our own process via /proc/self,
 * /proc/[other-pid] is not. That's fine — the entire point is "what
 * is Aegis doing right now", not a system-wide task manager.
 *
 * CPU% works in two stages: ProcessSampler.start captures a baseline,
 * then sample(elapsedMs) compares the current /proc counters against
 * the previous reading and returns the delta as a fraction of wall-
 * clock elapsed. Per-thread CPU% uses the same formula on
 * /proc/self/task/<tid>/stat — that file exists for every native
 * thread the process has, including Chromium WebView render threads
 * and SimpleX's Haskell runtime workers, so the view is complete and
 * not limited to the JVM's Thread.getAllStackTraces() set.
 */
class ProcessSampler {

    private var prevTotalJiffies: Long = -1
    private var prevWallMs: Long = -1
    private val prevThreadJiffies = HashMap<Int, Long>()
    private var prevRxBytes: Long = -1
    private var prevTxBytes: Long = -1

    /** Take a fresh snapshot. First call returns CPU% = 0 because
     *  there's no prior reading to compare against; every call after
     *  returns sensible CPU% deltas. Network throughput uses the
     *  same delta pattern. Memory and thread count are absolute. */
    fun sample(): ProcessSnapshot {
        val nowWall = System.currentTimeMillis()
        val deltaWallMs = if (prevWallMs > 0) (nowWall - prevWallMs).coerceAtLeast(1L) else 1L

        // Process-level CPU. /proc/self/stat utime+stime is in jiffies
        // (sysconf(_SC_CLK_TCK), 100 Hz on every Android we ship to →
        // each jiffy = 10 ms).
        val procStat = readStat(null) ?: Triple(0L, 0L, '?')
        val totalJiffies = procStat.first + procStat.second
        val totalCpuPct = if (prevTotalJiffies >= 0) {
            jiffiesToCpuPct(totalJiffies - prevTotalJiffies, deltaWallMs)
        } else 0f

        // Per-thread CPU. /proc/self/task lists every native tid.
        val tids = listTids()
        val threads = tids.mapNotNull { tid ->
            val s = readStat(tid) ?: return@mapNotNull null
            val (tu, ts, state) = s
            val tJiffies = tu + ts
            val pct = prevThreadJiffies[tid]?.let { prev ->
                jiffiesToCpuPct((tJiffies - prev).coerceAtLeast(0L), deltaWallMs)
            } ?: 0f
            prevThreadJiffies[tid] = tJiffies
            ThreadSample(
                tid = tid,
                name = readComm(tid) ?: "tid-$tid",
                state = stateLabel(state),
                cpuPct = pct,
            )
        }
        // Prune the prev-jiffies map of threads that have died, so it
        // doesn't grow forever as transient threads cycle.
        val live = tids.toHashSet()
        prevThreadJiffies.keys.retainAll(live)

        // Memory. /proc/self/statm is cheap (no IPC, single read,
        // no GC walks). Debug.MemoryInfo is more accurate but takes
        // tens of ms — too heavy for a 1 Hz tick.
        val (vmSizeKb, rssKb) = readStatm()
        val javaUsedKb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
        val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024

        // Network — TrafficStats per-uid totals are kernel-tracked,
        // cheap to read.
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        val rxKbps = if (prevRxBytes >= 0) bytesToKbps(rx - prevRxBytes, deltaWallMs) else 0f
        val txKbps = if (prevTxBytes >= 0) bytesToKbps(tx - prevTxBytes, deltaWallMs) else 0f

        prevTotalJiffies = totalJiffies
        prevWallMs = nowWall
        prevRxBytes = rx
        prevTxBytes = tx

        return ProcessSnapshot(
            pid = Process.myPid(),
            uid = uid,
            vmSizeKb = vmSizeKb,
            rssKb = rssKb,
            javaUsedKb = javaUsedKb,
            nativeKb = nativeKb,
            threadCount = tids.size,
            totalCpuPct = totalCpuPct,
            rxKbps = rxKbps,
            txKbps = txKbps,
            threads = threads.sortedByDescending { it.cpuPct },
        )
    }

    private fun jiffiesToCpuPct(deltaJiffies: Long, deltaWallMs: Long): Float {
        // jiffy = 10 ms (HZ=100). So delta_cpu_ms = delta_jiffies * 10.
        // CPU% across all cores = (cpu_ms / wall_ms) * 100.
        val cpuMs = deltaJiffies * JIFFY_MS
        return (cpuMs.toFloat() / deltaWallMs.toFloat()) * 100f
    }

    private fun bytesToKbps(deltaBytes: Long, deltaWallMs: Long): Float {
        if (deltaBytes <= 0) return 0f
        val kb = deltaBytes / 1024f
        val sec = deltaWallMs / 1000f
        return if (sec > 0f) kb / sec else 0f
    }

    /** Returns (utime, stime, state) or null if the file vanished
     *  (tid died between listTids and the read — common for short-
     *  lived workers). */
    private fun readStat(tid: Int?): Triple<Long, Long, Char>? {
        val path = if (tid == null) "/proc/self/stat" else "/proc/self/task/$tid/stat"
        return runCatching {
            val content = File(path).readText()
            // Format: pid (comm) state ppid ...
            // comm may contain spaces or parens, so split on the LAST ')'.
            val close = content.lastIndexOf(')')
            if (close < 0) return@runCatching null
            val after = content.substring(close + 2).split(' ')
            // After "(comm) ":
            //   [0] state
            //   [1] ppid    [2] pgrp    [3] session [4] tty_nr  [5] tpgid
            //   [6] flags   [7] minflt  [8] cminflt [9] majflt  [10] cmajflt
            //   [11] utime  [12] stime  ...
            val state = after[0].firstOrNull() ?: '?'
            val utime = after[11].toLong()
            val stime = after[12].toLong()
            Triple(utime, stime, state)
        }.getOrNull()
    }

    /** /proc/self/task/<tid>/comm is the kernel-level thread name,
     *  truncated to 15 chars + newline. Most accurate name source —
     *  matches what `top -H` would show. */
    private fun readComm(tid: Int): String? = runCatching {
        File("/proc/self/task/$tid/comm").readText().trim()
    }.getOrNull()

    private fun listTids(): List<Int> = runCatching {
        File("/proc/self/task").list()
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** Returns (vmSizeKb, rssKb). statm columns are in pages (4 KB). */
    private fun readStatm(): Pair<Long, Long> = runCatching {
        val content = File("/proc/self/statm").readText().trim()
        val parts = content.split(' ')
        val pageSizeKb = 4L  // every Android we ship to
        val vmPages = parts[0].toLong()
        val rssPages = parts[1].toLong()
        (vmPages * pageSizeKb) to (rssPages * pageSizeKb)
    }.getOrDefault(0L to 0L)

    private fun stateLabel(c: Char): String = when (c) {
        'R' -> "Run"
        'S' -> "Sleep"
        'D' -> "Disk"
        'Z' -> "Zombie"
        'T' -> "Stop"
        't' -> "Trace"
        'X' -> "Dead"
        'I' -> "Idle"
        else -> c.toString()
    }

    private companion object {
        private const val JIFFY_MS = 10L  // HZ=100 on every Android we ship to
    }
}

data class ProcessSnapshot(
    val pid: Int,
    val uid: Int,
    /** Virtual memory size in KB. Mostly a curiosity — typical Android
     *  processes report 5–15 GB of VM here from mapped libraries and
     *  pre-reserved heap regions; RSS is the meaningful number. */
    val vmSizeKb: Long,
    /** Resident set size in KB — physical memory actually held. */
    val rssKb: Long,
    /** Java/Dalvik heap currently used in KB. */
    val javaUsedKb: Long,
    /** Native heap allocations in KB (from Debug.getNativeHeapAllocatedSize). */
    val nativeKb: Long,
    val threadCount: Int,
    /** Process-wide CPU% summed across all cores. Can exceed 100 on
     *  multi-core devices when several threads are hot at once. */
    val totalCpuPct: Float,
    val rxKbps: Float,
    val txKbps: Float,
    val threads: List<ThreadSample>,
)

data class ThreadSample(
    val tid: Int,
    /** Kernel-side comm name, truncated to 15 chars by the kernel.
     *  Matches what `top -H` / `ps -T` would show. */
    val name: String,
    /** Single-letter state from /proc/self/task/<tid>/stat mapped to
     *  a short human label (Run / Sleep / Disk / Idle / …). */
    val state: String,
    /** This thread's CPU% as a fraction of wall-clock since the
     *  previous sample. Sum across all threads should approach
     *  ProcessSnapshot.totalCpuPct. */
    val cpuPct: Float,
)
