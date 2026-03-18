package site.lcyk.keer.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class SyncFreezeController(
    scope: CoroutineScope,
    syncing: Flow<Boolean>,
    interactionFrozen: Flow<Boolean>,
    onFrozenChanged: (Boolean) -> Unit,
) {
    init {
        scope.launch {
            combine(
                syncing.distinctUntilChanged(),
                interactionFrozen.distinctUntilChanged(),
            ) { syncingNow, interactionNow ->
                syncingNow || interactionNow
            }
                .distinctUntilChanged()
                .collect { frozen ->
                    onFrozenChanged(frozen)
                }
        }
    }
}
