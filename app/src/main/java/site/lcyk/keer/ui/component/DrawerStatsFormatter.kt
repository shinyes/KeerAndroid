package site.lcyk.keer.ui.component

internal fun formatDrawerStatsText(
    memoCount: Int,
    days: Long,
    memoLabel: String,
    dayLabel: String,
): String {
    return "$memoCount$memoLabel $days$dayLabel"
}
