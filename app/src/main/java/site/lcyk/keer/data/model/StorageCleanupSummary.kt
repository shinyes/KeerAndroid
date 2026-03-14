package site.lcyk.keer.data.model

data class StorageCleanupSummary(
    val scannedKeys: Int = 0,
    val deletedKeys: Int = 0,
    val failedKeys: Int = 0,
)
