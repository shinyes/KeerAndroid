package site.lcyk.keer.data.model

import java.time.LocalDate

data class DailyUsageStat(
    val date: LocalDate,
    val count: Int = 0
)