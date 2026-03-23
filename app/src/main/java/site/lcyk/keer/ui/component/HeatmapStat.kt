package site.lcyk.keer.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import site.lcyk.keer.data.model.DailyUsageStat
import java.time.LocalDate

@Composable
fun HeatmapStat(
    day: DailyUsageStat,
    modifier: Modifier = Modifier,
) {
    val borderWidth = if (day.date == LocalDate.now()) 1.dp else 0.dp
    val color = when (day.count) {
        0 -> Color(0xffeaeaea)
        1 -> Color(0xff9be9a8)
        2 -> Color(0xff40c463)
        in 3..4 -> Color(0xff30a14e)
        else -> Color(0xff216e39)
    }
    var resolvedModifier = modifier
        .clip(RoundedCornerShape(2.dp))
        .background(color = color)
    if (day.date == LocalDate.now()) {
        resolvedModifier = resolvedModifier.border(
            borderWidth,
            MaterialTheme.colorScheme.onBackground,
            shape = RoundedCornerShape(2.dp)
        )
    }

    Box(modifier = resolvedModifier)
}
