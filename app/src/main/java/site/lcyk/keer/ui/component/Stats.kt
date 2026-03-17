package site.lcyk.keer.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import site.lcyk.keer.R
import site.lcyk.keer.ext.string
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun Stats(
    memoCount: Int,
    tagCount: Int,
    startDate: LocalDate?,
) {
    val today = LocalDate.now()
    val days = remember(startDate, today) {
        startDate?.let { ChronoUnit.DAYS.between(it, today) } ?: 0
    }

    Row(
        Modifier
            .padding(20.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                memoCount.toString(),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                R.string.memo.string.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                tagCount.toString(),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                R.string.tag.string.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                days.toString(),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                R.string.day.string.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
