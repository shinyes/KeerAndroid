package site.lcyk.keer.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class SyncFreezeController(
    scope: CoroutineScope,
    interactionFrozen: Flow<Boolean>,
    onFrozenChanged: (Boolean) -> Unit,
) {
    init {
        scope.launch {
            interactionFrozen
                .distinctUntilChanged()
                .collect { frozen ->
                    onFrozenChanged(frozen)
                }
        }
    }
}
