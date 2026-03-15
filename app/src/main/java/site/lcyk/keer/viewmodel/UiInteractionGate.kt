package site.lcyk.keer.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class UiInteractionType {
    PULL_REFRESH,
    LIST_SCROLL,
    DRAWER_TRANSITION,
}

class UiInteractionGate {
    private val _activeInteractions = MutableStateFlow(emptySet<UiInteractionType>())
    val activeInteractions: StateFlow<Set<UiInteractionType>> = _activeInteractions.asStateFlow()

    fun setActive(type: UiInteractionType, active: Boolean) {
        _activeInteractions.update { current ->
            if (active) {
                current + type
            } else {
                current - type
            }
        }
    }
}
