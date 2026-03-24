package site.lcyk.keer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import site.lcyk.keer.data.service.DebugLogManager
import site.lcyk.keer.data.service.DebugLogTree
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class KeerApp: Application() {
    @Inject
    lateinit var debugLogManager: DebugLogManager

    companion object {
        lateinit var INSTANCE: KeerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        if (Timber.forest().none { it is Timber.DebugTree }) {
            Timber.plant(Timber.DebugTree())
        }
        if (Timber.forest().none { it is DebugLogTree }) {
            Timber.plant(DebugLogTree(debugLogManager))
        }
    }
}
