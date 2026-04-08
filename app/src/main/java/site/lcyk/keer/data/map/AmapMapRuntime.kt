package site.lcyk.keer.data.map

import android.content.Context
import com.amap.api.maps.MapsInitializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AmapMapRuntimeState(
    val available: Boolean = false,
    val errorMessage: String? = null,
)

object AmapMapRuntime {
    private val mutableState = MutableStateFlow(AmapMapRuntimeState())
    val state: StateFlow<AmapMapRuntimeState> = mutableState.asStateFlow()

    fun initialize(
        applicationContext: Context,
        apiKey: String,
    ) {
        if (apiKey.isBlank()) {
            mutableState.value = AmapMapRuntimeState(
                available = false,
                errorMessage = "AMap API key is not configured.",
            )
            return
        }

        runCatching {
            MapsInitializer.updatePrivacyShow(applicationContext, true, true)
            MapsInitializer.updatePrivacyAgree(applicationContext, true)
            MapsInitializer.setApiKey(apiKey)
            MapsInitializer.initialize(applicationContext)
        }.onSuccess {
            mutableState.value = AmapMapRuntimeState(available = true)
        }.onFailure { throwable ->
            mutableState.value = AmapMapRuntimeState(
                available = false,
                errorMessage = throwable.message ?: "Failed to initialize AMap SDK.",
            )
        }
    }
}
