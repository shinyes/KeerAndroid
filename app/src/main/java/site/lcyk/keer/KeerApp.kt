package site.lcyk.keer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
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
    lateinit var workerFactory: HiltWorkerFactory
    
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
        
        // Initialize WorkManager-based periodic sync
        SyncInitializer.initialize(syncInitializer)
        syncInitializer.initialize()
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
