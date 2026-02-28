package site.lcyk.keer.util

class ForegroundSyncCoordinator {
    private var suppressNextResume = false
    private var syncInFlight = false

    fun requestAppStartSync(): Boolean {
        suppressNextResume = true
        return requestSync()
    }

    fun requestResumeSync(): Boolean {
        if (suppressNextResume) {
            suppressNextResume = false
            return false
        }
        return requestSync()
    }

    fun completeSync() {
        syncInFlight = false
    }

    private fun requestSync(): Boolean {
        if (syncInFlight) {
            return false
        }
        syncInFlight = true
        return true
    }
}
