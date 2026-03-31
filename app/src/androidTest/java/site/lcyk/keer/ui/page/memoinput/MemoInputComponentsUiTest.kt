package site.lcyk.keer.ui.page.memoinput

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.ui.test.MemoInputTestActivity
import site.lcyk.keer.viewmodel.MemoEditorUploadFeedbackState
import site.lcyk.keer.viewmodel.UploadTaskState
import site.lcyk.keer.viewmodel.UploadTaskStatus
import site.lcyk.keer.viewmodel.buildMemoEditorUploadsState

@RunWith(AndroidJUnit4::class)
class MemoInputComponentsUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MemoInputTestActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun memoInputEditor_taskHeaderActionsInvokeCallbacks() {
        var cancelAllCount = 0
        var retryAllCount = 0
        var clearFailedCount = 0

        composeTestRule.setContent {
            MaterialTheme {
                MemoInputEditor(
                    text = TextFieldValue("Draft"),
                    onTextChange = {},
                    focusRequester = remember { FocusRequester() },
                    fillAvailableHeight = false,
                    validMimeTypePrefixes = emptySet(),
                    onDroppedText = {},
                    uploadsState = buildMemoEditorUploadsState(
                        uploadResources = emptyList(),
                        uploadTasks = listOf(
                            uploadingTask(id = "task-uploading", sequence = 1L),
                            failedTask(id = "task-failed", sequence = 2L),
                        ),
                    ),
                    onRemoveUploadResource = {},
                    onCancelUploadTask = {},
                    onCancelActiveUploadTasks = { cancelAllCount++ },
                    onRetryFailedUploadTasks = { retryAllCount++ },
                    onClearFailedUploadTasks = { clearFailedCount++ },
                    onRetryUploadTask = {},
                    onDismissUploadTask = {},
                )
            }
        }

        composeTestRule.onNodeWithText(
            context.getString(R.string.cancel_active_uploads),
        ).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(
            context.getString(R.string.retry_failed_uploads),
        ).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(
            context.getString(R.string.clear_failed_uploads),
        ).assertIsDisplayed().performClick()

        assertEquals(1, cancelAllCount)
        assertEquals(1, retryAllCount)
        assertEquals(1, clearFailedCount)
    }

    @Test
    fun memoInputEditor_taskItemActionsInvokeCallbacks() {
        var cancelledTaskId: String? = null
        var retriedTaskId: String? = null
        var dismissedTaskId: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                MemoInputEditor(
                    text = TextFieldValue("Draft"),
                    onTextChange = {},
                    focusRequester = remember { FocusRequester() },
                    fillAvailableHeight = false,
                    validMimeTypePrefixes = emptySet(),
                    onDroppedText = {},
                    uploadsState = buildMemoEditorUploadsState(
                        uploadResources = emptyList(),
                        uploadTasks = listOf(
                            uploadingTask(id = "task-uploading", sequence = 1L),
                            failedTask(id = "task-failed", sequence = 2L),
                        ),
                    ),
                    onRemoveUploadResource = {},
                    onCancelUploadTask = { cancelledTaskId = it },
                    onCancelActiveUploadTasks = {},
                    onRetryFailedUploadTasks = {},
                    onClearFailedUploadTasks = {},
                    onRetryUploadTask = { retriedTaskId = it },
                    onDismissUploadTask = { dismissedTaskId = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.cancel),
        ).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.retry),
        ).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.close),
        ).assertIsDisplayed().performClick()

        assertEquals("task-uploading", cancelledTaskId)
        assertEquals("task-failed", retriedTaskId)
        assertEquals("task-failed", dismissedTaskId)
    }

    @Test
    fun memoInputEditor_resourceSectionsUseMinimalChrome() {
        composeTestRule.setContent {
            MaterialTheme {
                MemoInputEditor(
                    text = TextFieldValue("Draft"),
                    onTextChange = {},
                    focusRequester = remember { FocusRequester() },
                    fillAvailableHeight = false,
                    validMimeTypePrefixes = emptySet(),
                    onDroppedText = {},
                    uploadsState = buildMemoEditorUploadsState(
                        uploadResources = listOf(imageResource("image-1")),
                        uploadTasks = emptyList(),
                    ),
                    onRemoveUploadResource = {},
                    onCancelUploadTask = {},
                    onCancelActiveUploadTasks = {},
                    onRetryFailedUploadTasks = {},
                    onClearFailedUploadTasks = {},
                    onRetryUploadTask = {},
                    onDismissUploadTask = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText(
            context.getString(R.string.clear_all),
        ).assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription(
            context.getString(R.string.expand),
        ).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(
            context.getString(R.string.upload_section_images, 1),
        ).assertCountEquals(0)
    }

    @Test
    fun memoUploadFeedbackSnackbarEffect_showsRecentCompletionMessage() {
        val snackbarHostState = SnackbarHostState()

        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    SnackbarHost(hostState = snackbarHostState)
                    MemoUploadFeedbackSnackbarEffect(
                        hostState = snackbarHostState,
                        feedbackState = MemoEditorUploadFeedbackState(
                            showRecentCompletionHint = true,
                            recentlyCompletedResourceCount = 2,
                            recentCompletionTriggerId = "trigger-1",
                            shouldShowRecentCompletionSnackbar = true,
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(
            context.getString(R.string.upload_recently_added_many, 2),
        ).assertIsDisplayed()
    }

    private fun imageResource(identifier: String): ResourceEntity = ResourceEntity(
        identifier = identifier,
        remoteId = "$identifier-remote",
        accountKey = "account-key",
        date = Instant.parse("2026-03-31T10:00:00Z"),
        filename = "$identifier.jpg",
        uri = "content://$identifier",
        mimeType = "image/jpeg",
    )

    private fun uploadingTask(
        id: String,
        sequence: Long,
    ): UploadTaskState = UploadTaskState(
        id = id,
        sequence = sequence,
        filename = "$id.bin",
        uploadedBytes = 10L,
        totalBytes = 100L,
        status = UploadTaskStatus.UPLOADING,
        sourceUri = "content://$id",
        targetMemoIdentifier = "memo-target",
    )

    private fun failedTask(
        id: String,
        sequence: Long,
    ): UploadTaskState = UploadTaskState(
        id = id,
        sequence = sequence,
        filename = "$id.bin",
        uploadedBytes = 10L,
        totalBytes = 100L,
        status = UploadTaskStatus.FAILED,
        errorMessage = "boom",
        sourceUri = "content://$id",
        targetMemoIdentifier = "memo-target",
    )
}
