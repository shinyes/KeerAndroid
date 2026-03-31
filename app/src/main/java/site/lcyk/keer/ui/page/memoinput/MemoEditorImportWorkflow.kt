package site.lcyk.keer.ui.page.memoinput

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CaptureVideo
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.R

data class MemoEditorImportWorkflowState(
    val pickVisualMedia: () -> Unit,
    val pickAttachments: () -> Unit,
    val takePhoto: () -> Unit,
    val captureVideo: () -> Unit,
)

private const val DEFAULT_PICK_VISUAL_MEDIA_MAX_ITEMS = 20

internal fun normalizePickedUris(uris: List<Uri>): List<Uri> {
    return uris
        .map(Uri::normalizeScheme)
        .distinctBy(Uri::toString)
}

private fun persistReadPermissionIfPossible(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    } catch (_: SecurityException) {
        // Photo Picker and app-owned FileProvider URIs may not support persistable permissions.
    } catch (_: UnsupportedOperationException) {
        // Some providers don't expose persistable grants; uploads can still proceed immediately.
    }
}

@Composable
fun rememberMemoEditorImportWorkflowState(
    context: Context,
    onUploadUris: (List<Uri>) -> Unit,
    onErrorMessage: (String) -> Unit,
): MemoEditorImportWorkflowState {
    val latestUploadUris by rememberUpdatedState(onUploadUris)
    val latestErrorMessage by rememberUpdatedState(onErrorMessage)

    val handlePickedUris: (List<Uri>) -> Unit = remember(context) {
        { pickedUris ->
            val normalizedUris = normalizePickedUris(pickedUris)
            if (normalizedUris.isNotEmpty()) {
                normalizedUris.forEach { uri ->
                    persistReadPermissionIfPossible(context, uri)
                }
                latestUploadUris(normalizedUris)
            }
        }
    }

    val pickVisualMediaLauncher = rememberLauncherForActivityResult(
        PickMultipleVisualMedia(DEFAULT_PICK_VISUAL_MEDIA_MAX_ITEMS)
    ) { uris ->
        handlePickedUris(uris)
    }

    val pickAttachmentsLauncher = rememberLauncherForActivityResult(
        OpenMultipleDocuments()
    ) { uris ->
        handlePickedUris(uris)
    }

    var photoImageUri by remember { mutableStateOf<Uri?>(null) }
    val takePhotoLauncher = rememberLauncherForActivityResult(TakePicture()) { success ->
        if (success) {
            photoImageUri?.let { uri ->
                latestUploadUris(listOf(uri))
            }
        }
    }

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    val captureVideoLauncher = rememberLauncherForActivityResult(CaptureVideo()) { success ->
        if (success) {
            videoUri?.let { uri ->
                latestUploadUris(listOf(uri))
            }
        }
    }

    return remember(context) {
        MemoEditorImportWorkflowState(
            pickVisualMedia = {
                pickVisualMediaLauncher.launch(
                    PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)
                )
            },
            pickAttachments = {
                pickAttachmentsLauncher.launch(arrayOf("*/*"))
            },
            takePhoto = {
                runCatching {
                    KeerFileProvider.getImageUri(context)
                }.onSuccess { uri ->
                    photoImageUri = uri
                    takePhotoLauncher.launch(uri)
                }.onFailure { error ->
                    latestErrorMessage(
                        error.localizedMessage ?: context.getString(R.string.unable_to_take_picture)
                    )
                }
            },
            captureVideo = {
                runCatching {
                    KeerFileProvider.getVideoUri(context)
                }.onSuccess { uri ->
                    videoUri = uri
                    captureVideoLauncher.launch(uri)
                }.onFailure { error ->
                    latestErrorMessage(
                        error.localizedMessage ?: context.getString(R.string.unable_to_record_video)
                    )
                }
            },
        )
    }
}
