package site.lcyk.keer.viewmodel

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoQuotePreview
import site.lcyk.keer.util.buildMemoQuoteDescriptor
import site.lcyk.keer.util.MemoQuoteDescriptor
import site.lcyk.keer.util.MemoQuoteSourceKind
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
