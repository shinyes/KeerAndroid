package site.lcyk.keer

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import site.lcyk.keer.data.map.AmapMapRuntime
import site.lcyk.keer.data.service.DebugLogManager
import site.lcyk.keer.data.service.DebugLogTree
import site.lcyk.keer.data.service.SyncCoordinator
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class KeerApp: Application(), Configuration.Provider {
    @Inject
    lateinit var debugLogManager: DebugLogManager

    @Inject
    lateinit var syncCoordinator: SyncCoordinator

    companion object {
        lateinit var INSTANCE: KeerApp
            private set

        private const val SYNC_INITIALIZATION_DELAY_MILLIS = 1_500L
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this

        // Initialize Timber logging. Only plant the logcat DebugTree for debug builds so
        // release doesn't pay string-formatting costs on every Timber call (e.g. per-resource
        // preview trace). The in-app DebugLogTree stays planted — it is gated by the app
        // debug-log setting and powers the Debug Log page.
        if (BuildConfig.DEBUG && Timber.forest().none { it is Timber.DebugTree }) {
            Timber.plant(Timber.DebugTree())
        }
        if (Timber.forest().none { it is DebugLogTree }) {
            Timber.plant(DebugLogTree(debugLogManager))
        }

        AmapMapRuntime.initialize(
            applicationContext = this,
            apiKey = BuildConfig.AMAP_API_KEY,
        )

        // Defer starting the continuous stream-sync session until after the first frame so
        // cold-start UI doesn't compete with the sync loop for CPU/network/IO.
        Handler(Looper.getMainLooper()).postDelayed(
            { syncCoordinator.startStreamSessions() },
            SYNC_INITIALIZATION_DELAY_MILLIS,
        )
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .build()
}
