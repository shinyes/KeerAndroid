package site.lcyk.keer.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Utility class to update all widget instances when memos are changed
 * Uses WorkManager to avoid memory leaks from unscoped coroutines
 */
object WidgetUpdater {
    /**
     * Update all widget instances using WorkManager to prevent memory leaks.
     * Uses enqueueUniqueWork + REPLACE so bursts of memo changes coalesce into a single
     * pending update instead of piling up duplicate workers.
     */
    fun updateWidgets(context: Context) {
        val updateRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
            .setInitialDelay(0, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WIDGET_UPDATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            updateRequest,
        )
    }

    private const val WIDGET_UPDATE_WORK_NAME = "keer_widget_update"

    /**
     * Worker to perform widget updates in a safe, lifecycle-aware manner
     */
    class WidgetUpdateWorker(
        context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {
        override fun doWork(): Result {
            return try {
                runBlocking {
                    WidgetUpdateScheduler.updateAllWidgets(applicationContext)
                }
                Result.success()
            } catch (e: Exception) {
                Timber.w(e, "Widget update worker failed")
                Result.retry()
            }
        }
    }
}
