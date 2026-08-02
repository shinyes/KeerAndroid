package site.lcyk.keer.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapTimelineBuilderTest {
    @Test
    fun buildDailyUsageMatrixFromDates_countsActiveDatesOnly() {
        val result = buildDailyUsageMatrixFromDates(
            dates = listOf(
                LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 3, 22),
                LocalDate.of(2026, 3, 22),
            ),
        )

        assertEquals(
            mapOf(
                LocalDate.of(2026, 3, 20) to 1,
                LocalDate.of(2026, 3, 22) to 2,
            ),
            result,
        )
    }

    @Test
    fun buildDailyUsageMatrixFromDates_returnsEmpty_whenNoDates() {
        val result = buildDailyUsageMatrixFromDates(dates = emptyList())

        assertEquals(emptyMap<LocalDate, Int>(), result)
    }

    @Test
    fun buildHeatmapTimeline_marksMonthLabelsAndCrossYearYearLabels() {
        val start = LocalDate.of(2024, 12, 1)
        val end = LocalDate.of(2025, 2, 5)
        val matrix = mapOf(
            start to 1,
            end to 1,
        )

        val timeline = buildHeatmapTimeline(
            matrix = matrix,
            latest = end,
            locale = Locale.US,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )
        val labels = timeline.columns
            .filter { column -> column.monthLabel != null }
            .map { column -> column.monthLabel to column.yearLabel }

        assertTrue(labels.contains("Dec" to "2024"))
        assertTrue(labels.contains("Feb" to null))
    }

    @Test
    fun buildHeatmapTimeline_hidesJanuaryMonthLabelButKeepsYear() {
        val start = LocalDate.of(2024, 12, 15)
        val end = LocalDate.of(2025, 1, 15)
        val matrix = mapOf(
            start to 1,
            end to 1,
        )

        val timeline = buildHeatmapTimeline(
            matrix = matrix,
            latest = end,
            locale = Locale.US,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )

        val januaryColumn = timeline.columns.firstOrNull { column ->
            column.cells.any { stat ->
                stat?.date?.let { date ->
                    date.monthValue == 1 && date.dayOfMonth == 1
                } == true
            }
        }

        assertEquals("2025", januaryColumn?.yearLabel)
        assertEquals(null, januaryColumn?.monthLabel)
    }
}
