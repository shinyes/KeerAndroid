package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoEditorWorkflowPersistenceState(
    val editorSessionKey: String = "",
    val editorContent: String = "",
    val editorSelectedTags: List<String> = emptyList(),
    val editorSelectedCollaborators: List<String> = emptyList(),
    val uploadResourceIdentifiers: List<String> = emptyList(),
    val uploadTasks: List<MemoEditorUploadTaskPersistenceState> = emptyList(),
    val imageSectionExpanded: Boolean? = null,
    val attachmentSectionExpanded: Boolean? = null,
    val taskSectionExpanded: Boolean? = null,
    val lastUploadTaskSequence: Long = 0L,
)

@Serializable
data class MemoEditorUploadTaskPersistenceState(
    val id: String = "",
    val sequence: Long = 0L,
    val filename: String = "",
    val uploadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: String = "",
    val errorMessage: String? = null,
    val sourceUri: String = "",
    val targetMemoIdentifier: String = "",
)
