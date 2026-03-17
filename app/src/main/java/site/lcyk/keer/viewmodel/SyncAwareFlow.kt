package site.lcyk.keer.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.debounceWithSyncState(
    syncing: Flow<Boolean>,
    idleDelayMillis: Long,
    syncingDelayMillis: Long,
): Flow<T> {
    return syncing
        .distinctUntilChanged()
        .flatMapLatest { isSyncing ->
            val delayMillis = if (isSyncing) syncingDelayMillis else idleDelayMillis
            this.debounce(delayMillis)
        }
}
