package site.lcyk.keer

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import site.lcyk.keer.data.map.AmapMapRuntime
import site.lcyk.keer.data.service.DebugLogManager
import site.lcyk.keer.data.service.DebugLogTree
import site.lcyk.keer.data.work.SyncInitializer
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class KeerApp: Application(), Configuration.Provider {
    @Inject
    lateinit var debugLogManager: DebugLogManager

    @Inject
    lateinit var syncInitializer: SyncInitializer

    companion object {
        lateinit var INSTANCE: KeerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        
        // Initialize Timber logging
        if (Timber.forest().none { it is Timber.DebugTree }) {
            Timber.plant(Timber.DebugTree())
        }
        if (Timber.forest().none { it is DebugLogTree }) {
            Timber.plant(DebugLogTree(debugLogManager))
        }

        AmapMapRuntime.initialize(
            applicationContext = this,
            apiKey = BuildConfig.AMAP_API_KEY,
        )
        
        // Initialize continuous stream sync
        SyncInitializer.initialize(syncInitializer)
        syncInitializer.initialize()
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .build()
}
