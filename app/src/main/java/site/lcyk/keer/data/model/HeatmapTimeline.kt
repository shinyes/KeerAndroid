package site.lcyk.keer.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

data class HeatmapTimeline(
    val columns: List<HeatmapWeekColumn>,
) {
    companion object {
        val EMPTY = HeatmapTimeline(emptyList())
    }
}

data class HeatmapWeekColumn(
    val weekStart: LocalDate,
    val cells: List<DailyUsageStat?>,
    val monthLabel: String?,
    val yearLabel: String?,
)

/**
 * 稀疏活跃日统计：date -> count，仅含 count > 0 的日期。
 * 相比旧的稠密矩阵（earliest..today 每天一条），大幅减少对象分配与内存占用。
 */
typealias DailyUsageMatrix = Map<LocalDate, Int>

fun buildDailyUsageMatrixFromDates(
    dates: List<LocalDate>,
): DailyUsageMatrix {
    if (dates.isEmpty()) return emptyMap()
    val countMap = HashMap<LocalDate, Int>(dates.size)
    for (date in dates) {
        countMap[date] = (countMap[date] ?: 0) + 1
    }
    return countMap
}

fun buildHeatmapTimeline(
    matrix: DailyUsageMatrix,
    latest: LocalDate = LocalDate.now(),
    locale: Locale = Locale.getDefault(),
    firstDayOfWeek: DayOfWeek = WeekFields.of(locale).firstDayOfWeek,
): HeatmapTimeline {
    if (matrix.isEmpty()) return HeatmapTimeline.EMPTY

    val startDate = matrix.keys.min()
    val endDate = if (latest.isAfter(startDate)) latest else startDate

    val alignedStart = startDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    val alignedEnd = endDate.with(TemporalAdjusters.nextOrSame(firstDayOfWeek.plus(6)))

    val columns = ArrayList<HeatmapWeekColumn>()
    var currentWeekStart = alignedStart
    var lastLabeledYear: Int? = null

    while (!currentWeekStart.isAfter(alignedEnd)) {
        val cells = List(7) { dayIndex ->
            val date = currentWeekStart.plusDays(dayIndex.toLong())
            if (date.isBefore(startDate) || date.isAfter(endDate)) {
                null
            } else {
                DailyUsageStat(date = date, count = matrix[date] ?: 0)
            }
        }

        val monthAnchor = cells
            .asSequence()
            .mapNotNull { it?.date }
            .firstOrNull { date -> date.dayOfMonth == 1 }

        val monthLabel = monthAnchor?.let { anchor ->
            if (anchor.monthValue == 1) {
                null
            } else {
                anchor.month.getDisplayName(TextStyle.SHORT, locale)
            }
        }
        val yearLabel = monthAnchor?.let { anchor ->
            if (lastLabeledYear == null || lastLabeledYear != anchor.year) {
                lastLabeledYear = anchor.year
                anchor.year.toString()
            } else {
                null
            }
        }

        columns += HeatmapWeekColumn(
            weekStart = currentWeekStart,
            cells = cells,
            monthLabel = monthLabel,
            yearLabel = yearLabel,
        )

        currentWeekStart = currentWeekStart.plusWeeks(1)
    }

    return HeatmapTimeline(columns = columns)
}
