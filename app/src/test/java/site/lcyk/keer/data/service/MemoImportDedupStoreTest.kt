package site.lcyk.keer.data.service

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoImportDedupStoreTest {
    @Test
    fun upsertImported_prunesExpiredEntries() {
        val tempDir = Files.createTempDirectory("memo-dedup-store-test").toFile()
        try {
            val store = MemoImportDedupStore(File(tempDir, "dedup.json"))
            store.upsertImported(
                keys = setOf("old-key"),
                nowMillis = 1_000L,
                ttlMillis = 10_000L,
                maxEntries = 100,
            )
            store.upsertImported(
                keys = setOf("new-key"),
                nowMillis = 20_000L,
                ttlMillis = 10_000L,
                maxEntries = 100,
            )

            val keys = store.readKeys(
                nowMillis = 20_000L,
                ttlMillis = 10_000L,
                maxEntries = 100,
            )

            assertEquals(setOf("new-key"), keys)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun upsertImported_prunesOverflowByOldestTimestamp() {
        val tempDir = Files.createTempDirectory("memo-dedup-store-test").toFile()
        try {
            val store = MemoImportDedupStore(File(tempDir, "dedup.json"))
            store.upsertImported(
                keys = setOf("k1"),
                nowMillis = 1_000L,
                ttlMillis = Long.MAX_VALUE,
                maxEntries = 3,
            )
            store.upsertImported(
                keys = setOf("k2"),
                nowMillis = 2_000L,
                ttlMillis = Long.MAX_VALUE,
                maxEntries = 3,
            )
            store.upsertImported(
                keys = setOf("k3"),
                nowMillis = 3_000L,
                ttlMillis = Long.MAX_VALUE,
                maxEntries = 3,
            )
            store.upsertImported(
                keys = setOf("k4"),
                nowMillis = 4_000L,
                ttlMillis = Long.MAX_VALUE,
                maxEntries = 3,
            )

            val keys = store.readKeys(
                nowMillis = 4_000L,
                ttlMillis = Long.MAX_VALUE,
                maxEntries = 3,
            )

            assertEquals(3, keys.size)
            assertTrue(keys.contains("k2"))
            assertTrue(keys.contains("k3"))
            assertTrue(keys.contains("k4"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
