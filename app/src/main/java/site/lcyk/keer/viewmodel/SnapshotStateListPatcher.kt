package site.lcyk.keer.viewmodel

import androidx.compose.runtime.snapshots.SnapshotStateList

internal fun <T, K> SnapshotStateList<T>.patchByKey(
    target: List<T>,
    keySelector: (T) -> K,
) {
    if (size == target.size) {
        var orderStable = true
        for (index in indices) {
            if (keySelector(this[index]) != keySelector(target[index])) {
                orderStable = false
                break
            }
        }
        if (orderStable) {
            for (index in indices) {
                val next = target[index]
                if (this[index] != next) {
                    this[index] = next
                }
            }
            return
        }
    }

    clear()
    addAll(target)
}
