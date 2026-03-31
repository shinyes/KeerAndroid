package site.lcyk.keer.viewmodel

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoEditorWorkflowPersistenceState
import site.lcyk.keer.data.model.MemoQuotePreview
import site.lcyk.keer.util.buildMemoQuoteDescriptor
import site.lcyk.keer.util.MemoQuoteDescriptor
import site.lcyk.keer.util.MemoQuoteSourceKind
import site.lcyk.keer.util.mergeTagsWithCollaboratorsAndQuote
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.resolveMemoFromQuoteDescriptor
import site.lcyk.keer.util.resolveMemoQuoteDescriptor
import site.lcyk.keer.util.storedMemoQuotePreviewOrNull
import site.lcyk.keer.util.toMemoQuotePreview

data class MemoDisplayMeta(
    val displayTags: List<String> = emptyList(),
    val collaboratorIds: List<String> = emptyList(),
    val quoteDescriptor: MemoQuoteDescriptor? = null,
)

data class MemoSurfaceMetaState(
    val displayMeta: MemoDisplayMeta = MemoDisplayMeta(),
    val hydrationState: UiHydrationState = UiHydrationState(),
)

data class MemoSurfaceLookupState(
    val meta: MemoSurfaceMetaState = MemoSurfaceMetaState(),
    val activeQuoteDescriptor: MemoQuoteDescriptor? = null,
)

data class MemoResolvedQuoteLookupState(
    val activeQuoteDescriptor: MemoQuoteDescriptor? = null,
    val quotedMemo: MemoEntity? = null,
)

data class MemoQuoteSurfaceState(
    val activeDescriptor: MemoQuoteDescriptor? = null,
    val submitDescriptor: MemoQuoteDescriptor? = null,
    val preview: MemoQuotePreview? = null,
)

data class MemoSurfacePresentationState(
    val meta: MemoSurfaceMetaState = MemoSurfaceMetaState(),
    val quote: MemoQuoteSurfaceState = MemoQuoteSurfaceState(),
)

data class MemoEditorSurfaceState(
    val presentation: MemoSurfacePresentationState = MemoSurfacePresentationState(),
    val initialEditorSeed: MemoEditorSeed = MemoEditorSeed(),
    val initialRestoreState: MemoEditorRestoreState = MemoEditorRestoreState(),
)

data class MemoEditorScreenState(
    val quotePreview: MemoQuotePreview? = null,
    val quoteDescriptorForSubmit: MemoQuoteDescriptor? = null,
    val hydrationState: UiHydrationState = UiHydrationState(),
    val initialRestoreState: MemoEditorRestoreState = MemoEditorRestoreState(),
    val initialFields: MemoEditorFieldsState = MemoEditorFieldsState(),
    val session: MemoEditorSessionState = MemoEditorSessionState(),
)

data class MemoEditorResolvedScreenState(
    val lookup: MemoResolvedQuoteLookupState = MemoResolvedQuoteLookupState(),
    val screen: MemoEditorScreenState = MemoEditorScreenState(),
)

data class MemoViewSurfaceState(
    val presentation: MemoSurfacePresentationState = MemoSurfacePresentationState(),
    val displayQuotePreview: MemoQuotePreview? = null,
)

data class MemoViewResolvedScreenState(
    val lookup: MemoResolvedQuoteLookupState = MemoResolvedQuoteLookupState(),
    val screen: MemoViewSurfaceState = MemoViewSurfaceState(),
)

data class DraftEditorScreenState(
    val hydrationState: UiHydrationState = UiHydrationState(),
    val initialRestoreState: MemoEditorRestoreState = MemoEditorRestoreState(),
    val initialFields: MemoEditorFieldsState = MemoEditorFieldsState(),
    val session: MemoEditorSessionState = MemoEditorSessionState(),
)

data class MemoQuotePresentation(
    val activeDescriptor: MemoQuoteDescriptor? = null,
    val submitDescriptor: MemoQuoteDescriptor? = null,
    val preview: MemoQuotePreview? = null,
)

data class MemoEditorSeed(
    val content: String = "",
    val tags: List<String> = emptyList(),
    val collaboratorIds: List<String> = emptyList(),
)

data class MemoEditorBaseline(
    val content: String = "",
    val tags: List<String> = emptyList(),
    val collaboratorIds: List<String> = emptyList(),
    val resourceIdentifiers: List<String> = emptyList(),
)

data class MemoEditorRestoreState(
    val content: String = "",
    val selectedTags: List<String> = emptyList(),
    val selectedCollaborators: List<String> = emptyList(),
    val baseline: MemoEditorBaseline = MemoEditorBaseline(),
)

data class MemoEditorFieldsState(
    val content: String = "",
    val selectedTags: List<String> = emptyList(),
    val selectedCollaborators: List<String> = emptyList(),
    val baseline: MemoEditorBaseline = MemoEditorBaseline(),
)

data class MemoEditorPersistedContentState(
    val sessionKey: String = "",
    val content: String = "",
    val selectedTags: List<String> = emptyList(),
    val selectedCollaborators: List<String> = emptyList(),
)

data class MemoEditorSessionState(
    val initialFields: MemoEditorFieldsState = MemoEditorFieldsState(),
    val resetFields: MemoEditorFieldsState = MemoEditorFieldsState(),
)

data class MemoEditorSubmitState(
    val content: String = "",
    val trimmedContent: String = "",
    val mergedTags: List<String> = emptyList(),
    val resourceIdentifiers: List<String> = emptyList(),
    val hasPayload: Boolean = false,
    val hasBlockingUpload: Boolean = false,
    val canSubmit: Boolean = false,
)

data class MemoEditorUploadEntryState(
    val targetMemoIdentifier: String? = null,
    val focusDelayMillis: Long = 0L,
    val showKeyboardAfterUpload: Boolean = false,
)

data class MemoEditorUploadResourceItemState(
    val resource: ResourceEntity,
    val isHighlighted: Boolean = false,
)

enum class MemoEditorUploadSectionKind {
    IMAGES,
    ATTACHMENTS,
}

data class MemoEditorUploadSectionState(
    val kind: MemoEditorUploadSectionKind,
    val items: List<MemoEditorUploadResourceItemState> = emptyList(),
    val totalCount: Int = 0,
    val highlightedCount: Int = 0,
    val canClearAll: Boolean = false,
    val canCollapse: Boolean = false,
    val defaultExpanded: Boolean = true,
    val isExpanded: Boolean = true,
)

data class MemoEditorUploadTaskSectionState(
    val items: List<MemoEditorUploadTaskItemState> = emptyList(),
    val totalCount: Int = 0,
    val activeCount: Int = 0,
    val failedCount: Int = 0,
    val canRetryFailedTasks: Boolean = false,
    val canClearFailedTasks: Boolean = false,
    val canCancelActiveTasks: Boolean = false,
    val canCollapse: Boolean = false,
    val defaultExpanded: Boolean = true,
    val isExpanded: Boolean = true,
)

data class MemoEditorUploadFeedbackState(
    val showRecentCompletionHint: Boolean = false,
    val recentlyCompletedResourceCount: Int = 0,
    val recentCompletionTriggerId: String? = null,
    val shouldShowRecentCompletionSnackbar: Boolean = false,
)

enum class MemoEditorUploadsSummaryKind {
    READY,
    ACTIVE,
    FAILED,
    MIXED,
}

data class MemoEditorUploadsActionsState(
    val canRetryFailedTasks: Boolean = false,
    val canClearFailedTasks: Boolean = false,
    val canCancelActiveTasks: Boolean = false,
)

data class MemoEditorUploadsSummaryState(
    val hasVisibleContent: Boolean = false,
    val kind: MemoEditorUploadsSummaryKind = MemoEditorUploadsSummaryKind.READY,
    val totalResourceCount: Int = 0,
    val imageResourceCount: Int = 0,
    val attachmentResourceCount: Int = 0,
    val activeTaskCount: Int = 0,
    val failedTaskCount: Int = 0,
    val recentlyCompletedResourceCount: Int = 0,
    val canRetryFailedTasks: Boolean = false,
    val canClearFailedTasks: Boolean = false,
)

data class MemoEditorUploadsState(
    val resources: List<ResourceEntity> = emptyList(),
    val tasks: List<UploadTaskState> = emptyList(),
    val resourceIdentifiers: List<String> = emptyList(),
    val hasActiveUpload: Boolean = false,
    val imageResources: List<ResourceEntity> = emptyList(),
    val attachmentResources: List<ResourceEntity> = emptyList(),
    val imageItems: List<MemoEditorUploadResourceItemState> = emptyList(),
    val attachmentItems: List<MemoEditorUploadResourceItemState> = emptyList(),
    val imageSection: MemoEditorUploadSectionState = MemoEditorUploadSectionState(
        kind = MemoEditorUploadSectionKind.IMAGES,
    ),
    val attachmentSection: MemoEditorUploadSectionState = MemoEditorUploadSectionState(
        kind = MemoEditorUploadSectionKind.ATTACHMENTS,
    ),
    val taskItems: List<MemoEditorUploadTaskItemState> = emptyList(),
    val taskSection: MemoEditorUploadTaskSectionState = MemoEditorUploadTaskSectionState(),
    val activeTaskCount: Int = 0,
    val failedTaskCount: Int = 0,
    val canRetryFailedTasks: Boolean = false,
    val canClearFailedTasks: Boolean = false,
    val summary: MemoEditorUploadsSummaryState = MemoEditorUploadsSummaryState(),
    val feedback: MemoEditorUploadFeedbackState = MemoEditorUploadFeedbackState(),
    val actions: MemoEditorUploadsActionsState = MemoEditorUploadsActionsState(),
)

data class MemoEditorUploadTaskItemState(
    val task: UploadTaskState,
    val canRetry: Boolean = false,
    val canDismiss: Boolean = false,
    val canCancel: Boolean = false,
)

data class MemoEditorUploadWorkflowState(
    val entry: MemoEditorUploadEntryState = MemoEditorUploadEntryState(),
    val uploads: MemoEditorUploadsState = MemoEditorUploadsState(),
)

data class MemoEditorDismissState(
    val shouldShowDiscardConfirmation: Boolean = false,
    val shouldDismiss: Boolean = false,
    val draftPersistenceValue: String? = null,
)

data class MemoEditorCompletionState(
    val fields: MemoEditorFieldsState = MemoEditorFieldsState(),
    val draftPersistenceValue: String? = null,
)

data class MemoEditorRestoreWorkflowState(
    val fields: MemoEditorFieldsState = MemoEditorFieldsState(),
    val uploadResources: List<ResourceEntity> = emptyList(),
    val cleanup: MemoEditorWorkflowCleanupState = MemoEditorWorkflowCleanupState(),
)

data class MemoEditorWorkflowCleanupState(
    val clearUploads: Boolean = false,
    val clearUploadTasks: Boolean = false,
    val stopLocationTracking: Boolean = false,
    val clearPrefetchedLocation: Boolean = false,
    val resetPendingLocationPermission: Boolean = false,
    val refreshLocalSnapshot: Boolean = false,
    val hideKeyboard: Boolean = false,
)

data class MemoEditorWorkflowState(
    val dismiss: MemoEditorDismissState = MemoEditorDismissState(),
    val completion: MemoEditorCompletionState = MemoEditorCompletionState(),
    val cleanup: MemoEditorWorkflowCleanupState = MemoEditorWorkflowCleanupState(),
)

internal fun buildMemoDisplayMeta(
    memo: MemoEntity?,
): MemoDisplayMeta {
    val resolvedMemo = memo ?: return MemoDisplayMeta()
    return MemoDisplayMeta(
        displayTags = buildMemoCardDisplayTags(resolvedMemo.tags),
        collaboratorIds = buildMemoCardCollaboratorIds(resolvedMemo.tags),
        quoteDescriptor = resolvedMemo.resolveMemoQuoteDescriptor(),
    )
}

internal fun buildMemoSurfaceMetaState(
    hasTarget: Boolean,
    liveValueAvailable: Boolean,
    displayMemo: MemoEntity?,
): MemoSurfaceMetaState {
    return MemoSurfaceMetaState(
        displayMeta = buildMemoDisplayMeta(displayMemo),
        hydrationState = buildRetainedHydrationState(
            hasTarget = hasTarget,
            liveValueAvailable = liveValueAvailable,
            retainedValueAvailable = displayMemo != null,
        ),
    )
}

internal fun buildMemoSurfaceLookupState(
    hasTarget: Boolean,
    liveValueAvailable: Boolean,
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
): MemoSurfaceLookupState {
    val metaState = buildMemoSurfaceMetaState(
        hasTarget = hasTarget,
        liveValueAvailable = liveValueAvailable,
        displayMemo = displayMemo,
    )
    return MemoSurfaceLookupState(
        meta = metaState,
        activeQuoteDescriptor = buildActiveMemoQuoteDescriptor(
            displayMemo = displayMemo,
            requestedQuoteDescriptor = requestedQuoteDescriptor,
        ),
    )
}

internal fun resolveQuotedMemoLookup(
    activeQuoteDescriptor: MemoQuoteDescriptor?,
    primaryQuotedMemo: MemoEntity?,
    memos: List<MemoEntity>,
): MemoEntity? {
    val descriptor = activeQuoteDescriptor ?: return null
    return primaryQuotedMemo ?: resolveMemoFromQuoteDescriptor(
        descriptor = descriptor,
        memos = memos,
    )
}

internal fun buildResolvedQuoteLookupState(
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
    primaryQuotedMemo: MemoEntity?,
    memos: List<MemoEntity>,
): MemoResolvedQuoteLookupState {
    val activeQuoteDescriptor = buildActiveMemoQuoteDescriptor(
        displayMemo = displayMemo,
        requestedQuoteDescriptor = requestedQuoteDescriptor,
    )
    return MemoResolvedQuoteLookupState(
        activeQuoteDescriptor = activeQuoteDescriptor,
        quotedMemo = resolveQuotedMemoLookup(
            activeQuoteDescriptor = activeQuoteDescriptor,
            primaryQuotedMemo = primaryQuotedMemo,
            memos = memos,
        ),
    )
}

internal fun buildRequestedLocalQuoteDescriptor(
    source: String?,
): MemoQuoteDescriptor? {
    val normalizedSource = source?.trim().orEmpty()
    if (normalizedSource.isEmpty()) {
        return null
    }
    return MemoQuoteDescriptor(
        sourceKind = MemoQuoteSourceKind.LOCAL,
        source = normalizedSource,
    )
}

internal fun buildQuotedMemoLookupIdentifier(
    currentMemoIdentifier: String,
    descriptor: MemoQuoteDescriptor,
): String? {
    val normalizedCurrentIdentifier = currentMemoIdentifier.trim()
    val normalizedSource = descriptor.source.trim()
    if (normalizedSource.isEmpty()) {
        return null
    }
    return when {
        normalizedCurrentIdentifier.startsWith("group:") -> {
            val payload = normalizedCurrentIdentifier.removePrefix("group:")
            val separatorIndex = payload.indexOf(':')
            if (separatorIndex <= 0) {
                null
            } else {
                "group:${payload.substring(0, separatorIndex)}:$normalizedSource"
            }
        }
        normalizedCurrentIdentifier.startsWith("explore:") -> {
            "explore:$normalizedSource"
        }
        else -> normalizedSource
    }
}

internal fun buildMemoQuoteSurfaceState(
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
    quotedMemo: MemoEntity?,
): MemoQuoteSurfaceState {
    val presentation = buildMemoQuotePresentation(
        displayMemo = displayMemo,
        requestedQuoteDescriptor = requestedQuoteDescriptor,
        quotedMemo = quotedMemo,
    )
    return MemoQuoteSurfaceState(
        activeDescriptor = presentation.activeDescriptor,
        submitDescriptor = presentation.submitDescriptor,
        preview = presentation.preview,
    )
}

internal fun buildMemoSurfacePresentationState(
    hasTarget: Boolean,
    liveValueAvailable: Boolean,
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
    quotedMemo: MemoEntity?,
): MemoSurfacePresentationState {
    return MemoSurfacePresentationState(
        meta = buildMemoSurfaceMetaState(
            hasTarget = hasTarget,
            liveValueAvailable = liveValueAvailable,
            displayMemo = displayMemo,
        ),
        quote = buildMemoQuoteSurfaceState(
            displayMemo = displayMemo,
            requestedQuoteDescriptor = requestedQuoteDescriptor,
            quotedMemo = quotedMemo,
        ),
    )
}

internal fun buildMemoEditorSurfaceState(
    hasTarget: Boolean,
    liveValueAvailable: Boolean,
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
    quotedMemo: MemoEntity?,
): MemoEditorSurfaceState {
    return MemoEditorSurfaceState(
        presentation = buildMemoSurfacePresentationState(
            hasTarget = hasTarget,
            liveValueAvailable = liveValueAvailable,
            displayMemo = displayMemo,
            requestedQuoteDescriptor = requestedQuoteDescriptor,
            quotedMemo = quotedMemo,
        ),
        initialEditorSeed = buildMemoEditorSeed(displayMemo),
        initialRestoreState = buildMemoEditorRestoreState(displayMemo),
    )
}

internal fun buildMemoEditorScreenState(
    hasTarget: Boolean,
    liveValueAvailable: Boolean,
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
    quotedMemo: MemoEntity?,
): MemoEditorScreenState {
    val surfaceState = buildMemoEditorSurfaceState(
        hasTarget = hasTarget,
        liveValueAvailable = liveValueAvailable,
        displayMemo = displayMemo,
        requestedQuoteDescriptor = requestedQuoteDescriptor,
        quotedMemo = quotedMemo,
    )
    return MemoEditorScreenState(
        quotePreview = surfaceState.presentation.quote.preview,
        quoteDescriptorForSubmit = surfaceState.presentation.quote.submitDescriptor,
        hydrationState = surfaceState.presentation.meta.hydrationState,
        initialRestoreState = surfaceState.initialRestoreState,
        initialFields = buildMemoEditorFieldsState(surfaceState.initialRestoreState),
        session = buildMemoEditorSessionState(surfaceState.initialRestoreState),
    )
}

internal fun buildMemoEditorResolvedScreenState(
    hasTarget: Boolean,
    liveValueAvailable: Boolean,
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
    primaryQuotedMemo: MemoEntity?,
    memos: List<MemoEntity>,
): MemoEditorResolvedScreenState {
    val lookupState = buildResolvedQuoteLookupState(
        displayMemo = displayMemo,
        requestedQuoteDescriptor = requestedQuoteDescriptor,
        primaryQuotedMemo = primaryQuotedMemo,
        memos = memos,
    )
    return MemoEditorResolvedScreenState(
        lookup = lookupState,
        screen = buildMemoEditorScreenState(
            hasTarget = hasTarget,
            liveValueAvailable = liveValueAvailable,
            displayMemo = displayMemo,
            requestedQuoteDescriptor = requestedQuoteDescriptor,
            quotedMemo = lookupState.quotedMemo,
        ),
    )
}

internal fun buildDraftEditorScreenState(
    persistedDraft: String?,
    forcedTags: List<String>,
    hasLiveDraft: Boolean,
    currentContent: String = persistedDraft.orEmpty(),
): DraftEditorScreenState {
    val normalizedDraft = persistedDraft.orEmpty()
    val restoreState = buildMemoEditorRestoreState(
        content = normalizedDraft,
        tags = forcedTags,
        collaboratorIds = emptyList(),
    )
    return DraftEditorScreenState(
        hydrationState = buildDraftHydrationState(
            hasLiveDraft = hasLiveDraft,
            currentContent = currentContent,
        ),
        initialRestoreState = restoreState,
        initialFields = buildMemoEditorFieldsState(restoreState),
        session = buildMemoEditorSessionState(
            restoreState = restoreState,
            resetTags = forcedTags,
        ),
    )
}

internal fun buildMemoViewSurfaceState(
    hasTarget: Boolean,
    liveValueAvailable: Boolean,
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
    quotedMemo: MemoEntity?,
    retainedQuotePreview: MemoQuotePreview?,
): MemoViewSurfaceState {
    val presentation = buildMemoSurfacePresentationState(
        hasTarget = hasTarget,
        liveValueAvailable = liveValueAvailable,
        displayMemo = displayMemo,
        requestedQuoteDescriptor = requestedQuoteDescriptor,
        quotedMemo = quotedMemo,
    )
    return MemoViewSurfaceState(
        presentation = presentation,
        displayQuotePreview = presentation.quote.preview ?: retainedQuotePreview,
    )
}

internal fun buildMemoViewResolvedScreenState(
    hasTarget: Boolean,
    liveValueAvailable: Boolean,
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
    primaryQuotedMemo: MemoEntity?,
    memos: List<MemoEntity>,
    retainedQuotePreview: MemoQuotePreview?,
): MemoViewResolvedScreenState {
    val lookupState = buildResolvedQuoteLookupState(
        displayMemo = displayMemo,
        requestedQuoteDescriptor = requestedQuoteDescriptor,
        primaryQuotedMemo = primaryQuotedMemo,
        memos = memos,
    )
    return MemoViewResolvedScreenState(
        lookup = lookupState,
        screen = buildMemoViewSurfaceState(
            hasTarget = hasTarget,
            liveValueAvailable = liveValueAvailable,
            displayMemo = displayMemo,
            requestedQuoteDescriptor = requestedQuoteDescriptor,
            quotedMemo = lookupState.quotedMemo,
            retainedQuotePreview = retainedQuotePreview,
        ),
    )
}

internal fun buildMemoEditorSeed(
    memo: MemoEntity?,
): MemoEditorSeed {
    val resolvedMemo = memo ?: return MemoEditorSeed()
    return MemoEditorSeed(
        content = resolvedMemo.content,
        tags = buildMemoEditorSelectedTags(resolvedMemo.tags),
        collaboratorIds = buildMemoEditorCollaboratorIds(resolvedMemo.tags),
    )
}

internal fun buildMemoEditorBaseline(
    seed: MemoEditorSeed,
    resourceIdentifiers: List<String> = emptyList(),
): MemoEditorBaseline {
    return MemoEditorBaseline(
        content = seed.content,
        tags = normalizeTagList(seed.tags),
        collaboratorIds = seed.collaboratorIds
            .map(::normalizeCollaboratorId)
            .filter { it.isNotEmpty() }
            .distinct(),
        resourceIdentifiers = resourceIdentifiers
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .distinct(),
    )
}

internal fun buildMemoEditorBaseline(
    content: String,
    tags: List<String>,
    collaboratorIds: List<String>,
    resourceIdentifiers: List<String> = emptyList(),
): MemoEditorBaseline {
    return buildMemoEditorBaseline(
        seed = MemoEditorSeed(
            content = content,
            tags = tags,
            collaboratorIds = collaboratorIds,
        ),
        resourceIdentifiers = resourceIdentifiers,
    )
}

internal fun buildMemoEditorBaseline(
    memo: MemoEntity?,
    resourceIdentifiers: List<String> = emptyList(),
): MemoEditorBaseline {
    return buildMemoEditorBaseline(
        seed = buildMemoEditorSeed(memo),
        resourceIdentifiers = resourceIdentifiers,
    )
}

internal fun buildMemoEditorRestoreState(
    seed: MemoEditorSeed,
    resourceIdentifiers: List<String> = emptyList(),
): MemoEditorRestoreState {
    val normalizedTags = normalizeTagList(seed.tags)
    val normalizedCollaborators = seed.collaboratorIds
        .map(::normalizeCollaboratorId)
        .filter { it.isNotEmpty() }
        .distinct()
    return MemoEditorRestoreState(
        content = seed.content,
        selectedTags = normalizedTags,
        selectedCollaborators = normalizedCollaborators,
        baseline = buildMemoEditorBaseline(
            seed = seed.copy(
                tags = normalizedTags,
                collaboratorIds = normalizedCollaborators,
            ),
            resourceIdentifiers = resourceIdentifiers,
        ),
    )
}

internal fun buildMemoEditorRestoreState(
    content: String,
    tags: List<String>,
    collaboratorIds: List<String>,
    resourceIdentifiers: List<String> = emptyList(),
): MemoEditorRestoreState {
    return buildMemoEditorRestoreState(
        seed = MemoEditorSeed(
            content = content,
            tags = tags,
            collaboratorIds = collaboratorIds,
        ),
        resourceIdentifiers = resourceIdentifiers,
    )
}

internal fun buildMemoEditorRestoreState(
    memo: MemoEntity?,
    resourceIdentifiers: List<String> = emptyList(),
): MemoEditorRestoreState {
    return buildMemoEditorRestoreState(
        seed = buildMemoEditorSeed(memo),
        resourceIdentifiers = resourceIdentifiers,
    )
}

internal fun buildMemoEditorFieldsState(
    restoreState: MemoEditorRestoreState,
): MemoEditorFieldsState {
    return MemoEditorFieldsState(
        content = restoreState.content,
        selectedTags = restoreState.selectedTags,
        selectedCollaborators = restoreState.selectedCollaborators,
        baseline = restoreState.baseline,
    )
}

internal fun buildMemoEditorSessionKey(
    vararg parts: String?,
): String {
    return parts
        .mapNotNull { part -> part?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(":")
}

internal fun buildMemoEditorPersistedContentState(
    persistenceState: MemoEditorWorkflowPersistenceState,
): MemoEditorPersistedContentState {
    return buildMemoEditorPersistedContentState(
        sessionKey = persistenceState.editorSessionKey,
        content = persistenceState.editorContent,
        selectedTags = persistenceState.editorSelectedTags,
        selectedCollaborators = persistenceState.editorSelectedCollaborators,
    )
}

internal fun buildMemoEditorPersistedContentState(
    sessionKey: String,
    content: String,
    selectedTags: List<String>,
    selectedCollaborators: List<String>,
): MemoEditorPersistedContentState {
    return MemoEditorPersistedContentState(
        sessionKey = sessionKey.trim(),
        content = content,
        selectedTags = normalizeTagList(selectedTags),
        selectedCollaborators = selectedCollaborators
            .map(::normalizeCollaboratorId)
            .filter { it.isNotEmpty() }
            .distinct(),
    )
}

internal fun buildMemoEditorEffectiveRestoreState(
    baseRestoreState: MemoEditorRestoreState,
    persistedContentState: MemoEditorPersistedContentState,
    expectedSessionKey: String,
    hasPersistedWorkflowPayload: Boolean = false,
): MemoEditorRestoreState {
    val normalizedSessionKey = expectedSessionKey.trim()
    if (normalizedSessionKey.isEmpty() || persistedContentState.sessionKey != normalizedSessionKey) {
        return baseRestoreState
    }
    val shouldApplyPersistedContent = persistedContentState.content.isNotEmpty() ||
        persistedContentState.selectedTags.isNotEmpty() ||
        persistedContentState.selectedCollaborators.isNotEmpty() ||
        hasPersistedWorkflowPayload
    if (!shouldApplyPersistedContent) {
        return baseRestoreState
    }
    return baseRestoreState.copy(
        content = persistedContentState.content,
        selectedTags = persistedContentState.selectedTags,
        selectedCollaborators = persistedContentState.selectedCollaborators,
    )
}

internal fun buildMemoEditorSessionState(
    restoreState: MemoEditorRestoreState,
    resetContent: String = "",
    resetTags: List<String> = emptyList(),
    resetCollaboratorIds: List<String> = emptyList(),
): MemoEditorSessionState {
    return MemoEditorSessionState(
        initialFields = buildMemoEditorFieldsState(restoreState),
        resetFields = buildMemoEditorFieldsState(
            buildMemoEditorRestoreState(
                content = resetContent,
                tags = resetTags,
                collaboratorIds = resetCollaboratorIds,
            ),
        ),
    )
}

internal fun buildMemoEditorDismissState(
    isDirty: Boolean,
    persistDraft: Boolean,
    currentContent: String,
): MemoEditorDismissState {
    return if (isDirty) {
        MemoEditorDismissState(
            shouldShowDiscardConfirmation = true,
        )
    } else {
        MemoEditorDismissState(
            shouldDismiss = true,
            draftPersistenceValue = buildMemoEditorDraftPersistenceValue(
                persistDraft = persistDraft,
                currentContent = currentContent,
            ),
        )
    }
}

internal fun buildMemoEditorCompletionState(
    sessionState: MemoEditorSessionState,
    persistDraft: Boolean,
    currentContent: String,
    clearDraft: Boolean = false,
): MemoEditorCompletionState {
    return MemoEditorCompletionState(
        fields = sessionState.resetFields,
        draftPersistenceValue = buildMemoEditorDraftPersistenceValue(
            persistDraft = persistDraft,
            currentContent = currentContent,
            clearDraft = clearDraft,
        ),
    )
}

internal fun buildMemoEditorDismissWorkflowState(
    isDirty: Boolean,
    persistDraft: Boolean,
    currentContent: String,
    clearUploadsOnDismiss: Boolean = false,
    clearUploadTasksOnDismiss: Boolean = false,
    stopLocationTrackingOnDismiss: Boolean = false,
    clearPrefetchedLocationOnDismiss: Boolean = false,
    resetPendingLocationPermissionOnDismiss: Boolean = false,
    hideKeyboardOnDismiss: Boolean = false,
): MemoEditorWorkflowState {
    val dismissState = buildMemoEditorDismissState(
        isDirty = isDirty,
        persistDraft = persistDraft,
        currentContent = currentContent,
    )
    return MemoEditorWorkflowState(
        dismiss = dismissState,
        cleanup = if (dismissState.shouldDismiss) {
            MemoEditorWorkflowCleanupState(
                clearUploads = clearUploadsOnDismiss,
                clearUploadTasks = clearUploadTasksOnDismiss,
                stopLocationTracking = stopLocationTrackingOnDismiss,
                clearPrefetchedLocation = clearPrefetchedLocationOnDismiss,
                resetPendingLocationPermission = resetPendingLocationPermissionOnDismiss,
                hideKeyboard = hideKeyboardOnDismiss,
            )
        } else {
            MemoEditorWorkflowCleanupState()
        },
    )
}

internal fun buildMemoEditorCompletionWorkflowState(
    sessionState: MemoEditorSessionState,
    persistDraft: Boolean,
    currentContent: String,
    clearDraft: Boolean = false,
    clearUploads: Boolean = false,
    clearUploadTasks: Boolean = false,
    stopLocationTracking: Boolean = false,
    clearPrefetchedLocation: Boolean = false,
    resetPendingLocationPermission: Boolean = false,
    refreshLocalSnapshot: Boolean = false,
    hideKeyboard: Boolean = false,
): MemoEditorWorkflowState {
    return MemoEditorWorkflowState(
        completion = buildMemoEditorCompletionState(
            sessionState = sessionState,
            persistDraft = persistDraft,
            currentContent = currentContent,
            clearDraft = clearDraft,
        ),
        cleanup = MemoEditorWorkflowCleanupState(
            clearUploads = clearUploads,
            clearUploadTasks = clearUploadTasks,
            stopLocationTracking = stopLocationTracking,
            clearPrefetchedLocation = clearPrefetchedLocation,
            resetPendingLocationPermission = resetPendingLocationPermission,
            refreshLocalSnapshot = refreshLocalSnapshot,
            hideKeyboard = hideKeyboard,
        ),
    )
}

internal fun buildMemoEditorRestoreWorkflowState(
    restoreState: MemoEditorRestoreState,
    uploadResources: List<ResourceEntity> = emptyList(),
    clearUploads: Boolean = true,
    clearUploadTasks: Boolean = true,
): MemoEditorRestoreWorkflowState {
    return MemoEditorRestoreWorkflowState(
        fields = buildMemoEditorFieldsState(restoreState),
        uploadResources = uploadResources.map { it.copy() },
        cleanup = MemoEditorWorkflowCleanupState(
            clearUploads = clearUploads,
            clearUploadTasks = clearUploadTasks,
        ),
    )
}

internal fun buildMemoEditorUploadTargetIdentifier(
    memoIdentifier: String?,
    displayMemo: MemoEntity? = null,
): String? {
    return memoIdentifier?.trim().orEmpty().ifEmpty {
        displayMemo?.identifier?.trim().orEmpty()
    }.ifEmpty {
        null
    }
}

internal fun buildMemoEditorUploadsState(
    uploadResources: List<ResourceEntity>,
    uploadTasks: List<UploadTaskState>,
    highlightedResourceIdentifiers: List<String> = emptyList(),
    imageSectionExpanded: Boolean? = null,
    attachmentSectionExpanded: Boolean? = null,
    taskSectionExpanded: Boolean? = null,
): MemoEditorUploadsState {
    val resources = uploadResources.map { it.copy() }
    val tasks = uploadTasks.toList()
    val sanitizedHighlightedResourceIdentifiers = highlightedResourceIdentifiers
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .distinct()
    val highlightedResourceIdentifierSet = sanitizedHighlightedResourceIdentifiers.toSet()
    val sortedTaskItems = buildMemoEditorUploadTaskItems(tasks)
    val activeTaskCount = tasks.count { task ->
        task.status == UploadTaskStatus.PREPARING || task.status == UploadTaskStatus.UPLOADING
    }
    val failedTaskCount = tasks.count { task -> task.status == UploadTaskStatus.FAILED }
    val imageItems = buildMemoEditorUploadResourceItems(
        resources = resources,
        highlightedResourceIdentifierSet = highlightedResourceIdentifierSet,
        imageOnly = true,
    )
    val attachmentItems = buildMemoEditorUploadResourceItems(
        resources = resources,
        highlightedResourceIdentifierSet = highlightedResourceIdentifierSet,
        imageOnly = false,
    )
    val imageSection = buildMemoEditorUploadSectionState(
        kind = MemoEditorUploadSectionKind.IMAGES,
        items = imageItems,
        expandedPreference = imageSectionExpanded,
    )
    val attachmentSection = buildMemoEditorUploadSectionState(
        kind = MemoEditorUploadSectionKind.ATTACHMENTS,
        items = attachmentItems,
        expandedPreference = attachmentSectionExpanded,
    )
    val canRetryFailedTasks = sortedTaskItems.any { it.canRetry }
    val canClearFailedTasks = failedTaskCount > 0
    val actions = buildMemoEditorUploadsActionsState(
        activeTaskCount = activeTaskCount,
        canRetryFailedTasks = canRetryFailedTasks,
        canClearFailedTasks = canClearFailedTasks,
    )
    val feedback = buildMemoEditorUploadFeedbackState(
        highlightedResourceIdentifiers = sanitizedHighlightedResourceIdentifiers,
    )
    val taskSection = buildMemoEditorUploadTaskSectionState(
        items = sortedTaskItems,
        activeTaskCount = activeTaskCount,
        failedTaskCount = failedTaskCount,
        actions = actions,
        expandedPreference = taskSectionExpanded,
    )
    return MemoEditorUploadsState(
        resources = resources,
        tasks = tasks,
        resourceIdentifiers = buildMemoEditorResourceIdentifiers(uploadResources),
        hasActiveUpload = activeTaskCount > 0,
        imageResources = imageItems.map { it.resource },
        attachmentResources = attachmentItems.map { it.resource },
        imageItems = imageItems,
        attachmentItems = attachmentItems,
        imageSection = imageSection,
        attachmentSection = attachmentSection,
        taskItems = sortedTaskItems,
        taskSection = taskSection,
        activeTaskCount = activeTaskCount,
        failedTaskCount = failedTaskCount,
        canRetryFailedTasks = canRetryFailedTasks,
        canClearFailedTasks = canClearFailedTasks,
        summary = buildMemoEditorUploadsSummaryState(
            imageItems = imageItems,
            attachmentItems = attachmentItems,
            activeTaskCount = activeTaskCount,
            failedTaskCount = failedTaskCount,
        ),
        feedback = feedback,
        actions = actions,
    )
}

internal fun buildMemoEditorUploadResourceItems(
    resources: List<ResourceEntity>,
    highlightedResourceIdentifierSet: Set<String>,
    imageOnly: Boolean,
): List<MemoEditorUploadResourceItemState> {
    return resources
        .filter { resource ->
            val isImage = resource.mimeType?.startsWith("image/") == true
            if (imageOnly) isImage else !isImage
        }
        .map { resource ->
            MemoEditorUploadResourceItemState(
                resource = resource,
                isHighlighted = highlightedResourceIdentifierSet.contains(resource.identifier),
            )
        }
}

internal fun buildMemoEditorUploadsSummaryState(
    imageItems: List<MemoEditorUploadResourceItemState>,
    attachmentItems: List<MemoEditorUploadResourceItemState>,
    activeTaskCount: Int,
    failedTaskCount: Int,
): MemoEditorUploadsSummaryState {
    val totalResourceCount = imageItems.size + attachmentItems.size
    val recentlyCompletedResourceCount = (imageItems + attachmentItems).count { it.isHighlighted }
    return MemoEditorUploadsSummaryState(
        hasVisibleContent = totalResourceCount > 0 || activeTaskCount > 0 || failedTaskCount > 0,
        kind = buildMemoEditorUploadsSummaryKind(
            activeTaskCount = activeTaskCount,
            failedTaskCount = failedTaskCount,
        ),
        totalResourceCount = totalResourceCount,
        imageResourceCount = imageItems.size,
        attachmentResourceCount = attachmentItems.size,
        activeTaskCount = activeTaskCount,
        failedTaskCount = failedTaskCount,
        recentlyCompletedResourceCount = recentlyCompletedResourceCount,
        canRetryFailedTasks = failedTaskCount > 0,
        canClearFailedTasks = failedTaskCount > 0,
    )
}

internal fun buildMemoEditorUploadsSummaryKind(
    activeTaskCount: Int,
    failedTaskCount: Int,
): MemoEditorUploadsSummaryKind {
    return when {
        activeTaskCount > 0 && failedTaskCount > 0 -> MemoEditorUploadsSummaryKind.MIXED
        activeTaskCount > 0 -> MemoEditorUploadsSummaryKind.ACTIVE
        failedTaskCount > 0 -> MemoEditorUploadsSummaryKind.FAILED
        else -> MemoEditorUploadsSummaryKind.READY
    }
}

internal fun buildMemoEditorUploadSectionState(
    kind: MemoEditorUploadSectionKind,
    items: List<MemoEditorUploadResourceItemState>,
    expandedPreference: Boolean? = null,
): MemoEditorUploadSectionState {
    val highlightedCount = items.count { it.isHighlighted }
    val defaultExpanded = when (kind) {
        MemoEditorUploadSectionKind.IMAGES -> items.size <= 4 || highlightedCount > 0
        MemoEditorUploadSectionKind.ATTACHMENTS -> items.size <= 3 || highlightedCount > 0
    }
    return MemoEditorUploadSectionState(
        kind = kind,
        items = items,
        totalCount = items.size,
        highlightedCount = highlightedCount,
        canClearAll = items.isNotEmpty(),
        canCollapse = items.size > 1,
        defaultExpanded = defaultExpanded,
        isExpanded = expandedPreference ?: defaultExpanded,
    )
}

internal fun buildMemoEditorUploadTaskSectionState(
    items: List<MemoEditorUploadTaskItemState>,
    activeTaskCount: Int,
    failedTaskCount: Int,
    actions: MemoEditorUploadsActionsState,
    expandedPreference: Boolean? = null,
): MemoEditorUploadTaskSectionState {
    val defaultExpanded = activeTaskCount > 0 || failedTaskCount > 0
    return MemoEditorUploadTaskSectionState(
        items = items,
        totalCount = items.size,
        activeCount = activeTaskCount,
        failedCount = failedTaskCount,
        canRetryFailedTasks = actions.canRetryFailedTasks,
        canClearFailedTasks = actions.canClearFailedTasks,
        canCancelActiveTasks = actions.canCancelActiveTasks,
        canCollapse = items.size > 1,
        defaultExpanded = defaultExpanded,
        isExpanded = expandedPreference ?: defaultExpanded,
    )
}

internal fun buildMemoEditorUploadFeedbackState(
    highlightedResourceIdentifiers: List<String>,
): MemoEditorUploadFeedbackState {
    val recentlyCompletedResourceCount = highlightedResourceIdentifiers.size
    val recentCompletionTriggerId = highlightedResourceIdentifiers.lastOrNull()
    return MemoEditorUploadFeedbackState(
        showRecentCompletionHint = recentlyCompletedResourceCount > 0,
        recentlyCompletedResourceCount = recentlyCompletedResourceCount,
        recentCompletionTriggerId = recentCompletionTriggerId,
        shouldShowRecentCompletionSnackbar = recentCompletionTriggerId != null,
    )
}

internal fun buildMemoEditorUploadsActionsState(
    activeTaskCount: Int,
    canRetryFailedTasks: Boolean,
    canClearFailedTasks: Boolean,
): MemoEditorUploadsActionsState {
    return MemoEditorUploadsActionsState(
        canRetryFailedTasks = canRetryFailedTasks,
        canClearFailedTasks = canClearFailedTasks,
        canCancelActiveTasks = activeTaskCount > 0,
    )
}

internal fun buildMemoEditorUploadTaskItemState(
    task: UploadTaskState,
): MemoEditorUploadTaskItemState {
    val canRetry = task.status == UploadTaskStatus.FAILED && !task.sourceUri.isNullOrBlank()
    val canCancel = task.status == UploadTaskStatus.PREPARING || task.status == UploadTaskStatus.UPLOADING
    return MemoEditorUploadTaskItemState(
        task = task,
        canRetry = canRetry,
        canDismiss = task.status == UploadTaskStatus.FAILED,
        canCancel = canCancel,
    )
}

internal fun buildMemoEditorUploadTaskItems(
    tasks: List<UploadTaskState>,
): List<MemoEditorUploadTaskItemState> {
    return tasks
        .sortedWith(
            compareBy<UploadTaskState>(
                ::buildMemoEditorUploadTaskSortPriority,
                { it.sequence },
            )
        )
        .map(::buildMemoEditorUploadTaskItemState)
}

internal fun buildMemoEditorUploadTaskSortPriority(
    task: UploadTaskState,
): Int {
    return when (task.status) {
        UploadTaskStatus.UPLOADING -> 0
        UploadTaskStatus.PREPARING -> 1
        UploadTaskStatus.FAILED -> 2
    }
}

internal fun buildMemoEditorUploadWorkflowState(
    memoIdentifier: String?,
    displayMemo: MemoEntity? = null,
    uploadResources: List<ResourceEntity>,
    uploadTasks: List<UploadTaskState>,
    highlightedResourceIdentifiers: List<String> = emptyList(),
    focusDelayMillis: Long = 0L,
    showKeyboardAfterUpload: Boolean = false,
    imageSectionExpanded: Boolean? = null,
    attachmentSectionExpanded: Boolean? = null,
    taskSectionExpanded: Boolean? = null,
): MemoEditorUploadWorkflowState {
    return MemoEditorUploadWorkflowState(
        entry = MemoEditorUploadEntryState(
            targetMemoIdentifier = buildMemoEditorUploadTargetIdentifier(
                memoIdentifier = memoIdentifier,
                displayMemo = displayMemo,
            ),
            focusDelayMillis = focusDelayMillis,
            showKeyboardAfterUpload = showKeyboardAfterUpload,
        ),
        uploads = buildMemoEditorUploadsState(
            uploadResources = uploadResources,
            uploadTasks = uploadTasks,
            highlightedResourceIdentifiers = highlightedResourceIdentifiers,
            imageSectionExpanded = imageSectionExpanded,
            attachmentSectionExpanded = attachmentSectionExpanded,
            taskSectionExpanded = taskSectionExpanded,
        ),
    )
}

internal fun buildMemoEditorResourceIdentifiers(
    resources: List<ResourceEntity>,
): List<String> {
    return resources
        .map { resource ->
            resource.remoteId?.trim().orEmpty().ifEmpty {
                resource.identifier.trim()
            }
        }
        .filter { it.isNotEmpty() }
        .distinct()
}

internal fun buildMemoEditorCanSubmit(
    content: String,
    uploadsState: MemoEditorUploadsState,
): Boolean {
    return (content.isNotEmpty() || uploadsState.resources.isNotEmpty()) && !uploadsState.hasActiveUpload
}

internal fun buildMemoEditorSubmitState(
    content: String,
    selectedTags: List<String>,
    selectedCollaborators: List<String>,
    quoteDescriptor: MemoQuoteDescriptor?,
    uploadsState: MemoEditorUploadsState,
): MemoEditorSubmitState {
    val normalizedCollaborators = selectedCollaborators
        .map(::normalizeCollaboratorId)
        .filter { it.isNotEmpty() }
        .distinct()
    val mergedTags = mergeTagsWithCollaboratorsAndQuote(
        normalizeTagList(selectedTags),
        normalizedCollaborators,
        quoteDescriptor,
    )
    val trimmedContent = content.trim()
    return MemoEditorSubmitState(
        content = content,
        trimmedContent = trimmedContent,
        mergedTags = mergedTags,
        resourceIdentifiers = uploadsState.resourceIdentifiers,
        hasPayload = trimmedContent.isNotEmpty() || uploadsState.resourceIdentifiers.isNotEmpty(),
        hasBlockingUpload = uploadsState.hasActiveUpload,
        canSubmit = buildMemoEditorCanSubmit(
            content = content,
            uploadsState = uploadsState,
        ),
    )
}

internal fun buildMemoEditorDirtyState(
    baseline: MemoEditorBaseline,
    content: String,
    selectedTags: List<String>,
    selectedCollaborators: List<String>,
    resourceIdentifiers: List<String>,
): Boolean {
    val normalizedCollaborators = selectedCollaborators
        .map(::normalizeCollaboratorId)
        .filter { it.isNotEmpty() }
        .distinct()
    val normalizedResourceIdentifiers = resourceIdentifiers
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .distinct()
    return content != baseline.content ||
        normalizeTagList(selectedTags) != baseline.tags ||
        normalizedCollaborators != baseline.collaboratorIds ||
        normalizedResourceIdentifiers != baseline.resourceIdentifiers
}

internal fun buildActiveMemoQuoteDescriptor(
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
): MemoQuoteDescriptor? {
    return if (displayMemo != null) {
        displayMemo.resolveMemoQuoteDescriptor()
    } else {
        requestedQuoteDescriptor
    }
}

internal fun buildMemoQuotePresentation(
    displayMemo: MemoEntity?,
    requestedQuoteDescriptor: MemoQuoteDescriptor?,
    quotedMemo: MemoEntity?,
): MemoQuotePresentation {
    val activeDescriptor = buildActiveMemoQuoteDescriptor(displayMemo, requestedQuoteDescriptor)
    return MemoQuotePresentation(
        activeDescriptor = activeDescriptor,
        submitDescriptor = quotedMemo?.let(::buildMemoQuoteDescriptor) ?: activeDescriptor,
        preview = quotedMemo?.toMemoQuotePreview() ?: displayMemo?.storedMemoQuotePreviewOrNull(),
    )
}

internal fun buildRetainedHydrationState(
    hasTarget: Boolean,
    liveValueAvailable: Boolean,
    retainedValueAvailable: Boolean,
): UiHydrationState {
    return UiHydrationState(
        hasWarmSnapshot = retainedValueAvailable,
        isHydrating = hasTarget && !liveValueAvailable && retainedValueAvailable,
        isStale = false,
    )
}

internal fun buildDraftHydrationState(
    hasLiveDraft: Boolean,
    currentContent: String,
): UiHydrationState {
    return buildRetainedHydrationState(
        hasTarget = true,
        liveValueAvailable = hasLiveDraft,
        retainedValueAvailable = currentContent.isNotEmpty(),
    )
}

internal fun buildMemoEditorDraftPersistenceValue(
    persistDraft: Boolean,
    currentContent: String,
    clearDraft: Boolean = false,
): String? {
    if (!persistDraft) {
        return null
    }
    return if (clearDraft) "" else currentContent
}

