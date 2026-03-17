package site.lcyk.keer.data.repository

internal class SyncApplyPipeline(
    private val chunkSize: Int,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be > 0" }
    }

    fun <T> split(items: List<T>): List<List<T>> {
        if (items.isEmpty()) {
            return emptyList()
        }
        return items.chunked(chunkSize)
    }
}

