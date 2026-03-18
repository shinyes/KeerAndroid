package site.lcyk.keer.viewmodel

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

enum class MemoUiScope {
    FEED,
    EXPLORE,
    GROUP_CHAT,
    DRAWER,
}

enum class UiInteractionType {
    PULL_REFRESH,
    LIST_SCROLL,
    DRAWER_TRANSITION,
    DRAWER_HIDDEN,
}

@Singleton
class UiInteractionGate @Inject constructor() {
    private val _activeInteractions =
        MutableStateFlow<Map<MemoUiScope, Set<UiInteractionType>>>(emptyMap())
    val activeInteractions: StateFlow<Map<MemoUiScope, Set<UiInteractionType>>> =
        _activeInteractions.asStateFlow()

    fun setActive(scope: MemoUiScope, type: UiInteractionType, active: Boolean) {
        _activeInteractions.update { current ->
            val currentForScope = current[scope].orEmpty()
            val updatedForScope = if (active) {
                currentForScope + type
            } else {
                currentForScope - type
            }
            buildMap {
                putAll(current)
                if (updatedForScope.isEmpty()) {
                    remove(scope)
                } else {
                    put(scope, updatedForScope)
                }
            }
        }
    }

    fun observeScopeFrozen(scope: MemoUiScope): Flow<Boolean> {
        return activeInteractions
            .map { activeByScope ->
                val scopeInteractions = activeByScope[scope].orEmpty()
                val freezeByScope = when (scope) {
                    MemoUiScope.DRAWER -> {
                        scopeInteractions.contains(UiInteractionType.DRAWER_TRANSITION) ||
                            scopeInteractions.contains(UiInteractionType.DRAWER_HIDDEN)
                    }
                    else -> {
                        scopeInteractions
                            .filterNot { interaction -> interaction == UiInteractionType.DRAWER_HIDDEN }
                            .isNotEmpty()
                    }
                }
                freezeByScope ||
                    (scope != MemoUiScope.DRAWER &&
                        activeByScope[MemoUiScope.DRAWER].orEmpty()
                            .contains(UiInteractionType.DRAWER_TRANSITION))
            }
            .distinctUntilChanged()
    }
}
