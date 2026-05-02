package io.github.mikai233.asteria.patch

import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class PatchArtifactStoreTest {
    @Test
    fun localFileStoreKeepsSameNamedArtifactsByChecksum() = runBlocking {
        val store = LocalFilePatchArtifactStore(createTempDirectory("asteria-patch-artifacts"))
        val firstBytes = "first".encodeToByteArray()
        val secondBytes = "second".encodeToByteArray()

        val first = store.save("fix.jar", firstBytes)
        val second = store.save("fix.jar", secondBytes)

        assertContentEquals(firstBytes, store.load(first))
        assertContentEquals(secondBytes, store.load(second))
    }

    @Test
    fun artifactChecksumMismatchFailsFast() {
        val artifact = PatchArtifact(
            name = "fix.jar",
            checksum = patchArtifactSha256Checksum("expected".encodeToByteArray()),
        )

        assertFailsWith<IllegalArgumentException> {
            verifyPatchArtifactChecksum(artifact, "actual".encodeToByteArray())
        }
    }
}
