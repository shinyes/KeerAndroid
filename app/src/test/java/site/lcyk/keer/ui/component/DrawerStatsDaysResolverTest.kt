package site.lcyk.keer.ui.component

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerStatsDaysResolverTest {
    @Test
    fun resolveDrawerStatsActiveDays_countsOnlyDaysWithMemoActivity() {
        val matrix = mapOf(
            LocalDate.of(2026, 1, 2) to 2,
            LocalDate.of(2026, 1, 4) to 5,
        )

        val days = resolveDrawerStatsActiveDays(matrix)

        assertEquals(2L, days)
    }

    @Test
    fun resolveDrawerStatsActiveDays_returnsZero_whenNoMemoActivity() {
        val days = resolveDrawerStatsActiveDays(emptyMap())

        assertEquals(0L, days)
    }
}
