package site.lcyk.keer.data.service

object SyncTriggerPolicy {
    fun shouldSkipSync(
        force: Boolean,
        trigger: SyncTrigger,
        hasPendingWork: Boolean,
        nowMillis: Long,
        lastSyncAttemptMillis: Long,
        idleSyncIntervalMillis: Long,
        pendingCoalesceMillis: Long,
        foregroundCoalesceMillis: Long,
        backoffUntilMillis: Long
    ): Boolean {
        if (force) {
            return false
        }
        if (nowMillis < backoffUntilMillis) {
            return true
        }
        val elapsed = nowMillis - lastSyncAttemptMillis
        if (trigger.isForegroundTrigger()) {
            return elapsed <= foregroundCoalesceMillis
        }
        if (hasPendingWork) {
            return elapsed <= pendingCoalesceMillis
        }
        return elapsed <= idleSyncIntervalMillis
    }

    fun calculateBackoffUntil(
        nowMillis: Long,
        consecutiveFailures: Int,
        baseBackoffMillis: Long,
        maxBackoffMillis: Long
    ): Long {
        if (consecutiveFailures <= 0) {
            return nowMillis
        }
        val exponent = (consecutiveFailures - 1).coerceAtMost(8)
        val multiplier = 1L shl exponent
        val duration = (baseBackoffMillis * multiplier).coerceAtMost(maxBackoffMillis)
        return nowMillis + duration
    }
}
