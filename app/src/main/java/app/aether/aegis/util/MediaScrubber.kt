package app.aether.aegis.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Strips identifying metadata from outbound media before it leaves
 * the device to a recipient who is NOT a trusted contact.
 *
 * Policy: "any media you send to anywhere but
 * trusted contacts must be scrubbed — no exceptions, no toggle." The
 * sos/duress forensic channel is the one deliberate carve-out and
 * is handled by the caller (a `forensic` flag), NOT here.
 *
 * The guarantee is **structural, not best-effort**:
 *  - Images: decode → re-encode. BitmapFactory yields pixels only, so
 *    EXIF (GPS, device model, capture time), XMP, ICC, and embedded
 *    EXIF thumbnails are all gone — `compress` writes a brand-new file
 *    with no metadata. The re-encode IS the defense.
 *  - Audio/Video: track-copy remux into a fresh MP4 container. We copy
 *    the elementary streams but deliberately never call
 *    `setLocation()` or carry any metadata atom (location, creation
 *    time, encoder tag), so the container comes out clean.
 *
 * Anything we cannot prove clean returns **null** so the caller FAILS
 * CLOSED (blocks the send) rather than risk a leak. Arbitrary files
 * (PDF, docx, …) aren't decodable here and so fail closed by design.
 */
object MediaScrubber {
    private const val TAG = "MediaScrubber"

    /** Upper bound on the re-encoded image's long side. Bounds peak
     *  memory on huge source images and incidentally sheds forensic
     *  detail; 4096 keeps shared photos visually intact. */
    private const val MAX_IMAGE_SIDE = 4096

    /**
     * Re-encode [srcPath] to a metadata-free copy alongside it.
     * Returns the scrubbed path, or null if the image can't be decoded
     * (caller fails closed). PNG/alpha sources stay PNG to keep
     * transparency; everything else becomes JPEG q92.
     */
    fun scrubImage(srcPath: String): String? = runCatching {
        val src = File(srcPath)

        // First pass: read dimensions only, pick a power-of-two
        // downsample so we never inflate a 100 MP image into RAM.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(srcPath, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest > 0 && longest / sample > MAX_IMAGE_SIDE) sample *= 2

        val raw = BitmapFactory.decodeFile(
            srcPath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val keepAlpha = raw.hasAlpha()
        val fmt = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val ext = if (keepAlpha) "png" else "jpg"
        val dest = File(src.parentFile, "scrub_${System.nanoTime()}.$ext")
        FileOutputStream(dest).use { out -> raw.compress(fmt, 92, out) }
        runCatching { raw.recycle() }
        dest.absolutePath
    }.getOrElse {
        Log.w(TAG, "scrubImage failed for $srcPath", it)
        null
    }

    /**
     * Remux the audio/video tracks of [srcPath] into a fresh MP4 with
     * no container metadata. Returns the scrubbed path, or null for
     * inputs the platform muxer can't rebuild (caller fails closed).
     */
    fun scrubAvContainer(srcPath: String): String? {
        val src = File(srcPath)
        val dest = File(src.parentFile, "scrub_${System.nanoTime()}.mp4")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(srcPath)
            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Add every track by its format, then read samples
            // interleaved and route each to its muxer track. We copy
            // streams verbatim but emit NO metadata — that's the scrub.
            val trackMap = HashMap<Int, Int>()
            var maxInputSize = 1 shl 20 // 1 MB floor
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = maxOf(
                        maxInputSize,
                        format.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE),
                    )
                }
                trackMap[i] = muxer.addTrack(format)
                extractor.selectTrack(i)
            }
            muxer.start()

            val buffer = ByteBuffer.allocate(maxInputSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                val track = extractor.sampleTrackIndex
                info.presentationTimeUs = extractor.sampleTime
                // Map extractor's SAMPLE_FLAG_SYNC to the muxer's
                // keyframe flag so seeking still works post-remux.
                info.flags = if (extractor.sampleFlags and
                    MediaExtractor.SAMPLE_FLAG_SYNC != 0
                ) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(trackMap.getValue(track), buffer, info)
                extractor.advance()
            }
            muxer.stop()
            dest.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "scrubAvContainer failed for $srcPath", t)
            runCatching { dest.delete() }
            null
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
        }
    }

    /**
     * Scrub [srcPath] and replace it **in place**, returning true once
     * [srcPath] holds a provably-clean copy, or false (fail-closed) if
     * it couldn't be proven clean — the caller MUST block the send.
     *
     * Why in-place rather than a sibling file: the original design wrote
     * a `scrub_*.jpg` next to the source and sent THAT, while the DB row
     * (recordSentAttachment), the at-rest seal (sealOutgoingAttachment),
     * the message bubble, and orphan-cleanup (pruneOrphanAppFiles) all
     * still pointed at the *original* path. That desync had two teeth:
     * the scrubbed file was an untracked orphan that cleanup could (and
     * the core's async XFTP upload would race) delete mid-transfer →
     * "stuck receiving 0.0/0.4 MB"; and the original lingered UNscrubbed
     * in app_files (a privacy regression on top). Collapsing to one file
     * that every subsystem already references removes both.
     *
     * Extension stays the source's: scrubImage keeps PNG only for alpha
     * sources, which are exactly the ones Attachments staged as `.png`,
     * so the in-place bytes match the on-disk name in the common cases.
     * Decoders are content-based regardless, so a residual mismatch is
     * cosmetic.
     */
    fun scrubInPlace(srcPath: String, isImage: Boolean): Boolean {
        val scrubbed = (if (isImage) scrubImage(srcPath) else scrubAvContainer(srcPath))
            ?: return false
        return runCatching {
            val tmp = File(scrubbed)
            val dst = File(srcPath)
            // Same-dir replace. File.renameTo maps to rename(2), which
            // atomically clobbers an existing target on POSIX; fall back
            // to copy+delete on the rare FS where it won't.
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrElse {
            Log.w(TAG, "scrubInPlace replace failed for $srcPath", it)
            // Never leave the scrubbed temp behind as an orphan.
            runCatching { File(scrubbed).delete() }
            false
        }
    }
}
