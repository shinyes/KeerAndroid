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

fun buildDailyUsageMatrixFromDates(
    dates: List<LocalDate>,
    today: LocalDate = LocalDate.now(),
    emptyFallback: List<DailyUsageStat> = DailyUsageStat.initialMatrix,
): List<DailyUsageStat> {
    if (dates.isEmpty()) return emptyFallback

    val countMap = HashMap<LocalDate, Int>(dates.size * 2)
    var earliest = dates.first()
    for (date in dates) {
        countMap[date] = (countMap[date] ?: 0) + 1
        if (date.isBefore(earliest)) {
            earliest = date
        }
    }

    if (earliest.isAfter(today)) {
        earliest = today
    }

    val days = java.time.temporal.ChronoUnit.DAYS.between(earliest, today).toInt()
    return (0..days).map { offset ->
        val date = earliest.plusDays(offset.toLong())
        DailyUsageStat(date = date, count = countMap[date] ?: 0)
    }
}

fun buildHeatmapTimeline(
    matrix: List<DailyUsageStat>,
    locale: Locale = Locale.getDefault(),
    firstDayOfWeek: DayOfWeek = WeekFields.of(locale).firstDayOfWeek,
): HeatmapTimeline {
    if (matrix.isEmpty()) return HeatmapTimeline.EMPTY

    val startDate = matrix.first().date
    val endDate = matrix.last().date
    val statByDate = HashMap<LocalDate, DailyUsageStat>(matrix.size * 2)
    matrix.forEach { stat -> statByDate[stat.date] = stat }

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
                statByDate[date] ?: DailyUsageStat(date = date, count = 0)
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
