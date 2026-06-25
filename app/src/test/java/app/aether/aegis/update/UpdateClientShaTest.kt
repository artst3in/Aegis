package app.aether.aegis.update

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the SHA-256 the updater verifies downloads against.
 *
 * Since the OTA channel moved to GitHub Releases, the integrity hash is
 * the APK's SHA-256 carried in the release manifest (release assets have
 * no git-blob SHA). [UpdateClient.sha256Of] must match a standard
 * `sha256sum`, or a finished download would be wrongly rejected as
 * corrupt. These pin the implementation against known `sha256sum`
 * vectors.
 */
class UpdateClientShaTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String {
        val f: File = tmp.newFile()
        f.writeBytes(bytes)
        return UpdateClient.sha256Of(f)
    }

    @Test fun empty_file_matches_canonical_sha256() {
        // sha256sum of a 0-byte file — the well-known empty digest.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)),
        )
    }

    @Test fun hello_newline_matches_sha256sum() {
        // printf 'hello\n' | sha256sum
        assertEquals(
            "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03",
            sha256("hello\n".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test fun doc_string_matches_sha256sum() {
        // printf 'what is up, doc?' | sha256sum
        assertEquals(
            "af4e812d13b4eae6da91024f15f7679dae49d40aee3fb4c7216b56d04193f46c",
            sha256("what is up, doc?".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test fun the_sha_is_deterministic() {
        val a = sha256("aegis".toByteArray())
        val b = sha256("aegis".toByteArray())
        assertEquals(a, b)
        assertEquals("598f7a741a1e3a05654d346033571fda567af6dc2bf099b34b930171519d995f", a)
    }
}
