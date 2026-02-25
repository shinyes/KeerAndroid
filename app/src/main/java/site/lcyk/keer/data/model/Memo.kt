package site.lcyk.keer.data.model

import java.time.Instant

enum class MemoVisibility {
    PRIVATE,
    PROTECTED,
    PUBLIC
}

data class Memo(
    override val remoteId: String,
    override val content: String,
    override val date: Instant,
    override val pinned: Boolean,
    override val visibility: MemoVisibility,
    override val resources: List<Resource>,
    override val tags: List<String>,
    val creator: User? = null,
    override val archived: Boolean = false,
    val updatedAt: Instant? = null,
) : MemoRepresentable
