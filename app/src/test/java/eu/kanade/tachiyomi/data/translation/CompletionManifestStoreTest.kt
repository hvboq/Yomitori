package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException

class CompletionManifestStoreTest {
    @Test
    fun `changed pages use initial snapshot and three verified writes before cleaning old checkpoints`() {
        val previous = setOf("001.png")
        val updated = previous + "002.png"
        val oldCheckpoint = CompletionManifestCodec.CHECKPOINT_PREFIX + "11111111-1111-1111-1111-111111111111"
        val files = mutableMapOf(
            CompletionManifestCodec.PRIMARY to CompletionManifestCodec.encode(previous),
            oldCheckpoint to CompletionManifestCodec.encode(previous),
        )
        val events = mutableListOf<String>()
        val snapshots = mutableListOf<Map<String, ByteArray>>()
        val writes = mutableListOf<Pair<String, ByteArray>>()
        val deleted = mutableListOf<String>()
        val store = CompletionManifestStore(
            readFiles = {
                events += "read"
                files.toMap().also(snapshots::add)
            },
            write = { name, bytes ->
                events += "write:$name"
                writes += name to bytes.copyOf()
                files[name] = bytes.copyOf()
                true
            },
            delete = { name ->
                events += "delete:$name"
                deleted += name
                files.remove(name)
            },
        )

        store.commit(updated)

        withClue("One authoritative initial read plus three independent write readbacks; cleanup needs no fifth read") {
            snapshots.size shouldBe 4
        }
        writes.size shouldBe 3
        val recovery = writes[0].first
        val completed = writes[2].first
        CompletionManifestCodec.isCheckpoint(recovery) shouldBe true
        writes[1].first shouldBe CompletionManifestCodec.PRIMARY
        CompletionManifestCodec.isCheckpoint(completed) shouldBe true
        (recovery != completed && recovery != oldCheckpoint && completed != oldCheckpoint) shouldBe true
        writes.map { CompletionManifestCodec.decode(it.second) } shouldBe listOf(previous, updated, updated)
        snapshots[0].keys shouldBe setOf(CompletionManifestCodec.PRIMARY, oldCheckpoint)
        for (index in writes.indices) {
            CompletionManifestCodec.decode(snapshots[index + 1].getValue(writes[index].first)) shouldBe
                if (index == 0) previous else updated
        }
        events.take(7) shouldBe listOf(
            "read",
            "write:$recovery",
            "read",
            "write:${CompletionManifestCodec.PRIMARY}",
            "read",
            "write:$completed",
            "read",
        )
        events.drop(7).toSet() shouldBe setOf("delete:$oldCheckpoint", "delete:$recovery")
        deleted.toSet() shouldBe setOf(oldCheckpoint, recovery)
        files.keys shouldBe setOf(CompletionManifestCodec.PRIMARY, completed)
        CompletionManifestCodec.resolve(files) shouldBe updated
    }

    @Test
    fun `invalid or unreadable final readback keeps prior recovery and performs no cleanup`() {
        for (fault in listOf("invalid bytes", "read failure")) {
            withClue(fault) {
                val previous = setOf("001.png")
                val updated = previous + "002.png"
                val oldCheckpoint = CompletionManifestCodec.CHECKPOINT_PREFIX + "11111111-1111-1111-1111-111111111111"
                val oldBytes = CompletionManifestCodec.encode(previous)
                val files = mutableMapOf(
                    CompletionManifestCodec.PRIMARY to oldBytes.copyOf(),
                    oldCheckpoint to oldBytes.copyOf(),
                )
                val writes = mutableListOf<String>()
                val deleted = mutableListOf<String>()
                val readFailure = IOException("Synthetic final completion readback failure")
                var reads = 0
                val store = CompletionManifestStore(
                    readFiles = {
                        reads++
                        if (reads == 4 && fault == "read failure") throw readFailure
                        files.toMap().let { snapshot ->
                            if (reads == 4) {
                                val completed = writes.last()
                                snapshot + (completed to snapshot.getValue(completed).dropLast(1).toByteArray())
                            } else {
                                snapshot
                            }
                        }
                    },
                    write = { name, bytes ->
                        writes += name
                        files[name] = bytes.copyOf()
                        true
                    },
                    delete = { name ->
                        deleted += name
                        files.remove(name)
                    },
                )

                val failure = shouldThrow<Exception> { store.commit(updated) }

                if (fault == "read failure") {
                    (failure === readFailure) shouldBe true
                } else {
                    (failure is IllegalStateException) shouldBe true
                }
                reads shouldBe 4
                writes.size shouldBe 3
                deleted shouldBe emptyList()
                files.getValue(oldCheckpoint).contentEquals(oldBytes) shouldBe true
                CompletionManifestCodec.decode(files.getValue(writes.first())) shouldBe previous
                CompletionManifestCodec.decode(files.getValue(CompletionManifestCodec.PRIMARY)) shouldBe updated
                CompletionManifestCodec.resolve(files) shouldBe updated
            }
        }
    }

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
