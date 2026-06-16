package app.aether.aegis.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-formatter boundary tests (SPEC_TESTING P2).
 *
 * Low stakes individually, but these run in storage/diagnostics readouts
 * and a unit boundary off-by-one looks sloppy in a tool the user trusts
 * to report sizes accurately.
 */
class FormattersTest {

    @Test fun humanSize_unit_boundaries() {
        assertEquals("512 B", humanSize(512))
        assertEquals("1 KB", humanSize(1024))
        assertEquals("1 MB", humanSize(1024L * 1024))
    }

    @Test fun formatBytes_unit_boundaries() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test fun zero_bytes_renders_cleanly() {
        assertEquals("0 B", humanSize(0))
        assertEquals("0 B", formatBytes(0))
    }
}
