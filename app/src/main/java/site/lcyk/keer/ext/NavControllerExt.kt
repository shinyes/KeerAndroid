package site.lcyk.keer.ext

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController

fun NavController.popBackStackIfLifecycleIsResumed(lifecycleOwner: LifecycleOwner? = null) {
    if (lifecycleOwner?.lifecycle?.currentState === Lifecycle.State.RESUMED) {
        popBackStack()
    }
}