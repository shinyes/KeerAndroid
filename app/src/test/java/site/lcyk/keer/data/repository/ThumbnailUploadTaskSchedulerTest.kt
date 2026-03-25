package site.lcyk.keer.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailUploadTaskSchedulerTest {
    @Test
    fun enqueueWhileRunning_coalescesToSingleRerun() {
        val scheduler = ThumbnailUploadTaskScheduler()

        assertTrue(scheduler.enqueue("resource-1"))
        assertTrue(scheduler.takePending("resource-1"))

        assertFalse(scheduler.enqueue("resource-1"))
        assertTrue(scheduler.finishAndShouldRestart("resource-1"))

        assertTrue(scheduler.takePending("resource-1"))
        assertFalse(scheduler.takePending("resource-1"))
        assertFalse(scheduler.finishAndShouldRestart("resource-1"))
    }

    @Test
    fun duplicateEnqueueBeforeProcessing_doesNotCreateExtraCycle() {
        val scheduler = ThumbnailUploadTaskScheduler()

        assertTrue(scheduler.enqueue("resource-2"))
        assertFalse(scheduler.enqueue("resource-2"))

        assertTrue(scheduler.takePending("resource-2"))
        assertFalse(scheduler.takePending("resource-2"))
        assertFalse(scheduler.finishAndShouldRestart("resource-2"))
    }

    @Test
    fun differentResources_canRunIndependently() {
        val scheduler = ThumbnailUploadTaskScheduler()

        assertTrue(scheduler.enqueue("resource-a"))
        assertTrue(scheduler.enqueue("resource-b"))

        assertTrue(scheduler.takePending("resource-a"))
        assertTrue(scheduler.takePending("resource-b"))
        assertFalse(scheduler.finishAndShouldRestart("resource-a"))
        assertFalse(scheduler.finishAndShouldRestart("resource-b"))
    }
}
