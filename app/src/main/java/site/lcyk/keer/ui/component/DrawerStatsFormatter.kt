package site.lcyk.keer.ui.component

internal fun formatDrawerStatsText(
    memoCount: Int,
    tagCount: Int,
    days: Long,
    memoLabel: String,
    tagLabel: String,
    dayLabel: String,
): String {
    return "$memoCount$memoLabel $tagCount$tagLabel $days$dayLabel"
}
