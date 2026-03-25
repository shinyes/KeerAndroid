package site.lcyk.keer.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.skydoves.sandwich.ApiResponse
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.service.SyncCoordinator
import site.lcyk.keer.data.service.SyncTrigger

/**
 * WorkManager worker for handling background sync operations.
 * 
 * This worker integrates with the existing [SyncCoordinator] to perform
 * sync operations in the background with proper lifecycle management.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncCoordinator: SyncCoordinator
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val domainStrings = inputData.getStringArray(SyncWorker.KEY_DOMAINS)
                ?: SyncCoordinator.FULL_DOMAINS.map { it.name }.toTypedArray()
            
            val domains = domainStrings.mapNotNull { 
                try {
                    SyncDomain.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }.toSet()

            val force = inputData.getBoolean(SyncWorker.KEY_FORCE, false)

            // Perform sync with WorkManager cancellation support
            val result = syncCoordinator.sync(
                force = force,
                domains = domains,
                trigger = SyncTrigger.AUTO
            )

            when (result) {
                is ApiResponse.Success -> Result.success()
                is ApiResponse.Failure -> {
                    // Check if cancelled
                    if (isStopped) {
                        Result.failure()
                    } else {
                        // Retry with exponential backoff
                        Result.retry()
                    }
                }
            }
        } catch (e: CancellationException) {
            // WorkManager cancelled this work
            Result.failure()
        } catch (e: Exception) {
            // Unexpected error - retry
            Result.retry()
        }
    }

    companion object {
        const val KEY_DOMAINS = "sync_domains"
        const val KEY_FORCE = "sync_force"
    }
}
