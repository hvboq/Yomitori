package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CompletionManifestStoreTest {
    @Test
    fun `completed checkpoint failure retains validated primary and prior recovery until retry`() {
        val files = mutableMapOf<String, ByteArray>()
        var failCompleted = false
        var checkpointsWritten = 0
        val store = CompletionManifestStore(
            readFiles = { files.toMap() },
            write = { name, bytes ->
                val checkpoint = CompletionManifestCodec.isCheckpoint(name)
                if (checkpoint) checkpointsWritten++
                val fail = failCompleted && checkpoint && checkpointsWritten == 2
                files[name] = if (fail) bytes.copyOf(bytes.size / 2) else bytes
                !fail
            },
            delete = { files.remove(it) },
        )
        store.commit(setOf("001.png"))
        val previous = files.filterKeys(CompletionManifestCodec::isCheckpoint).keys.single()
        val previousBytes = files.getValue(previous).copyOf()
        checkpointsWritten = 0
        failCompleted = true
        shouldThrow<IllegalStateException> { store.commit(setOf("001.png", "002.png")) }
        files.getValue(previous).contentEquals(previousBytes) shouldBe true
        CompletionManifestCodec.decode(files.getValue(CompletionManifestCodec.PRIMARY)) shouldBe
            setOf("001.png", "002.png")
        CompletionManifestCodec.resolve(files) shouldBe setOf("001.png", "002.png")
        failCompleted = false
        store.commit(setOf("001.png", "002.png"))
        CompletionManifestCodec.resolve(files) shouldBe setOf("001.png", "002.png")
        files.filterKeys(CompletionManifestCodec::isCheckpoint).size shouldBe 1
    }

    @Test
    fun `partial checkpoint write after primary corruption preserves the sole good recovery copy`() {
        val files = mutableMapOf<String, ByteArray>()
        var failCheckpoint = false
        val store = CompletionManifestStore(
            readFiles = { files.toMap() },
            write = { name, bytes ->
                val fail = failCheckpoint && CompletionManifestCodec.isCheckpoint(name)
                files[name] = if (fail) bytes.copyOf(bytes.size / 2) else bytes
                !fail
            },
            delete = { files.remove(it) },
        )
        store.commit(setOf("001.png"))
        val originalCheckpoint = files.filterKeys(CompletionManifestCodec::isCheckpoint).keys.single()
        val checkpointBytes = files.getValue(originalCheckpoint).copyOf()
        files[CompletionManifestCodec.PRIMARY] = "001.png\n002.png".toByteArray()
        failCheckpoint = true
        shouldThrow<IllegalStateException> { store.commit(setOf("001.png", "002.png")) }
        files.getValue(originalCheckpoint).contentEquals(checkpointBytes) shouldBe true
        CompletionManifestCodec.resolve(files) shouldBe setOf("001.png")
    }

    @Test
    fun `partial primary and falsely successful checkpoint writes never lose committed pages`() {
        val files = mutableMapOf<String, ByteArray>()
        var failure = ""
        val store = CompletionManifestStore(
            readFiles = { files.toMap() },
            write = { name, bytes ->
                val truncate = (failure == "primary" && name == CompletionManifestCodec.PRIMARY) ||
                    (failure == "checkpoint" && CompletionManifestCodec.isCheckpoint(name))
                files[name] = if (truncate) bytes.copyOf(bytes.size - 1) else bytes
                true // Provider reports success, but verification must reject the incomplete bytes.
            },
            delete = { files.remove(it) },
        )
        store.commit(setOf("001.png"))
        failure = "primary"
        shouldThrow<IllegalStateException> { store.commit(setOf("001.png", "002.png")) }
        CompletionManifestCodec.resolve(files) shouldBe setOf("001.png")
        failure = "checkpoint"
        shouldThrow<IllegalStateException> { store.commit(setOf("001.png", "002.png")) }
        CompletionManifestCodec.resolve(files) shouldBe setOf("001.png")
        failure = ""
        store.commit(setOf("001.png", "002.png"))
        CompletionManifestCodec.resolve(files) shouldBe setOf("001.png", "002.png")
        files.filterKeys(CompletionManifestCodec::isCheckpoint).size shouldBe 1
    }
}
