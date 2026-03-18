package site.lcyk.keer.data.service

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class MemoImportDedupStore(
    private val file: File,
) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun readKeys(
        nowMillis: Long,
        ttlMillis: Long,
        maxEntries: Int,
    ): Set<String> = synchronized(lock) {
        val snapshot = readSnapshot()
        val pruned = prune(snapshot.entries, nowMillis, ttlMillis, maxEntries)
        if (pruned.size != snapshot.entries.size) {
            writeSnapshot(MemoImportDedupSnapshot(pruned))
        }
        pruned.keys
    }

    fun upsertImported(
        keys: Collection<String>,
        nowMillis: Long,
        ttlMillis: Long,
        maxEntries: Int,
    ) = synchronized(lock) {
        if (keys.isEmpty()) {
            val snapshot = readSnapshot()
            val pruned = prune(snapshot.entries, nowMillis, ttlMillis, maxEntries)
            if (pruned.size != snapshot.entries.size) {
                writeSnapshot(MemoImportDedupSnapshot(pruned))
            }
            return@synchronized
        }
        val snapshot = readSnapshot()
        val entries = snapshot.entries.toMutableMap()
        keys.asSequence()
            .map { key -> key.trim() }
            .filter { key -> key.isNotEmpty() }
            .forEach { key ->
                entries[key] = nowMillis
            }
        val pruned = prune(entries, nowMillis, ttlMillis, maxEntries)
        writeSnapshot(MemoImportDedupSnapshot(pruned))
    }

    private fun prune(
        entries: Map<String, Long>,
        nowMillis: Long,
        ttlMillis: Long,
        maxEntries: Int,
    ): Map<String, Long> {
        val safeMaxEntries = maxEntries.coerceAtLeast(1)
        val safeTtlMillis = ttlMillis.coerceAtLeast(1L)
        val expireBefore = nowMillis - safeTtlMillis
        val mutable = entries
            .asSequence()
            .filter { (key, touchedAt) ->
                key.isNotBlank() && touchedAt >= expireBefore
            }
            .associate { (key, touchedAt) -> key to touchedAt }
            .toMutableMap()
        if (mutable.size <= safeMaxEntries) {
            return mutable
        }
        val overflowKeys = mutable.entries
            .sortedBy { (_, touchedAt) -> touchedAt }
            .take(mutable.size - safeMaxEntries)
            .map { (key, _) -> key }
        overflowKeys.forEach(mutable::remove)
        return mutable
    }

    private fun readSnapshot(): MemoImportDedupSnapshot {
        if (!file.exists()) {
            return MemoImportDedupSnapshot()
        }
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) {
                MemoImportDedupSnapshot()
            } else {
                json.decodeFromString(MemoImportDedupSnapshot.serializer(), raw)
            }
        }.getOrElse {
            MemoImportDedupSnapshot()
        }
    }

    private fun writeSnapshot(snapshot: MemoImportDedupSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(MemoImportDedupSnapshot.serializer(), snapshot))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

@Serializable
internal data class MemoImportDedupSnapshot(
    val entries: Map<String, Long> = emptyMap(),
)
