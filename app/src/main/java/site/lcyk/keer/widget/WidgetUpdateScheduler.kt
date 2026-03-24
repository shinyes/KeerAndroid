package site.lcyk.keer.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.TimeUnit

object WidgetUpdateScheduler {
    private const val WIDGET_UPDATE_WORK = "keer_widget_update_work"

    fun scheduleWidgetUpdates(context: Context) {
        val updateRequest = PeriodicWorkRequestBuilder<KeerGlanceWidgetReceiver.WidgetUpdateWorker>(
            30, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WIDGET_UPDATE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            updateRequest
        )
    }

    fun cancelWidgetUpdates(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WIDGET_UPDATE_WORK)
    }

    fun cancelWidgetUpdatesIfNoWidgets(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<WidgetCheckWorker>().build()
        )
    }

    /**
     * Worker to check if widget updates should be cancelled
     */
    class WidgetCheckWorker(
        context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {
        override fun doWork(): Result {
            return try {
                val manager = GlanceAppWidgetManager(applicationContext)
                val hasMemosWidget = runBlocking {
                    manager.getGlanceIds(KeerGlanceWidget::class.java).isNotEmpty()
                }
                val hasMemoryWidget = runBlocking {
                    manager.getGlanceIds(MemoryGlanceWidget::class.java).isNotEmpty()
                }

                if (!hasMemosWidget && !hasMemoryWidget) {
                    cancelWidgetUpdates(applicationContext)
                }
                Result.success()
            } catch (e: Exception) {
                Timber.w(e, "Widget check worker failed")
                Result.retry()
            }
        }
    }

    suspend fun updateAllWidgets(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val memosWidgetIds = manager.getGlanceIds(KeerGlanceWidget::class.java)
        val memoryWidgetIds = manager.getGlanceIds(MemoryGlanceWidget::class.java)

        memosWidgetIds.forEach { glanceId ->
            KeerGlanceWidget().update(context, glanceId)
        }
        memoryWidgetIds.forEach { glanceId ->
            MemoryGlanceWidget().update(context, glanceId)
        }
    }
}
