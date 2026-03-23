package site.lcyk.keer.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapTimelineBuilderTest {
    @Test
    fun buildDailyUsageMatrixFromDates_spansFromEarliestDateToToday() {
        val today = LocalDate.of(2026, 3, 23)
        val result = buildDailyUsageMatrixFromDates(
            dates = listOf(
                LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 3, 22),
                LocalDate.of(2026, 3, 22),
            ),
            today = today,
            emptyFallback = emptyList(),
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 3, 21),
                LocalDate.of(2026, 3, 22),
                LocalDate.of(2026, 3, 23),
            ),
            result.map { it.date },
        )
        assertEquals(listOf(1, 0, 2, 0), result.map { it.count })
    }

    @Test
    fun buildDailyUsageMatrixFromDates_usesFallbackWhenNoDates() {
        val fallback = listOf(DailyUsageStat(date = LocalDate.of(2026, 1, 1), count = 0))
        val result = buildDailyUsageMatrixFromDates(
            dates = emptyList(),
            today = LocalDate.of(2026, 3, 23),
            emptyFallback = fallback,
        )

        assertEquals(fallback, result)
    }

    @Test
    fun buildHeatmapTimeline_marksMonthLabelsAndCrossYearYearLabels() {
        val start = LocalDate.of(2024, 12, 1)
        val end = LocalDate.of(2025, 2, 5)
        val matrix = (0..java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt()).map { offset ->
            DailyUsageStat(date = start.plusDays(offset.toLong()), count = 0)
        }

        val timeline = buildHeatmapTimeline(
            matrix = matrix,
            locale = Locale.US,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )
        val labels = timeline.columns
            .filter { column -> column.monthLabel != null }
            .map { column -> column.monthLabel to column.yearLabel }

        assertTrue(labels.contains("Dec" to "2024"))
        assertTrue(labels.contains("Jan" to "2025"))
        assertTrue(labels.contains("Feb" to null))
    }
}
