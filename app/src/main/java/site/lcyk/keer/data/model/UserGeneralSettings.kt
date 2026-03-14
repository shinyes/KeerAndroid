package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class MemoEditGesture {
    NONE,
    SINGLE,
    DOUBLE,
    LONG,
}

@Serializable
data class UserGeneralSettings(
    val memoVisibility: MemoVisibility = MemoVisibility.PRIVATE,
    val memoEditGesture: MemoEditGesture = MemoEditGesture.NONE,
    val memoColumns: List<MemoColumnConfig> = emptyList(),
)
