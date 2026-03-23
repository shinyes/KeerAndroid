package site.lcyk.keer.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test
import site.lcyk.keer.data.model.DailyUsageStat

class DrawerStatsDaysResolverTest {
    @Test
    fun resolveDrawerStatsActiveDays_countsOnlyDaysWithMemoActivity() {
        val matrix = listOf(
            DailyUsageStat.initialMatrix.first().copy(count = 0),
            DailyUsageStat.initialMatrix[1].copy(count = 2),
            DailyUsageStat.initialMatrix[2].copy(count = 0),
            DailyUsageStat.initialMatrix[3].copy(count = 5),
        )

        val days = resolveDrawerStatsActiveDays(matrix)

        assertEquals(2L, days)
    }

    @Test
    fun resolveDrawerStatsActiveDays_returnsZero_whenNoMemoActivity() {
        val matrix = listOf(
            DailyUsageStat.initialMatrix.first().copy(count = 0),
            DailyUsageStat.initialMatrix[1].copy(count = 0),
        )

        val days = resolveDrawerStatsActiveDays(matrix)

        assertEquals(0L, days)
    }
}
