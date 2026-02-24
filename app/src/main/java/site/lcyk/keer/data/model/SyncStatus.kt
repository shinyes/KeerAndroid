package site.lcyk.keer.data.model

data class SyncStatus(
    val syncing: Boolean = false,
    val unsyncedCount: Int = 0,
    val errorMessage: String? = null,
    val uploadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val uploadedFiles: Int = 0,
    val totalFiles: Int = 0
) {
    val progress: Float?
        get() = when {
            totalBytes > 0L -> {
                (uploadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            }

            totalFiles > 0 -> {
                (uploadedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            }

            else -> null
        }
}
