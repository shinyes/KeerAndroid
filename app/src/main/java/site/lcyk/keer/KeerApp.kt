package site.lcyk.keer

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
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
        @SuppressLint("StaticFieldLeak")
        lateinit var CONTEXT: Context
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        CONTEXT = base?.applicationContext ?: this
    }

    override fun onCreate() {
        super.onCreate()
        CONTEXT = applicationContext
        if (Timber.forest().none { it is Timber.DebugTree }) {
            Timber.plant(Timber.DebugTree())
        }
        if (Timber.forest().none { it is DebugLogTree }) {
            Timber.plant(DebugLogTree(debugLogManager))
        }
    }
}
