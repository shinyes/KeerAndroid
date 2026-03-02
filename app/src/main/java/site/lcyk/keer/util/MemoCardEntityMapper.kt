package site.lcyk.keer.util

import android.net.Uri
import android.webkit.MimeTypeMap
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Memo
import java.time.Instant
import java.util.Locale

fun Memo.toMemoEntityForCard(
    identifier: String,
    accountKey: String,
    needsSync: Boolean = false,
    lastModified: Instant = updatedAt ?: date,
    lastSyncedAt: Instant? = updatedAt ?: date
): MemoEntity {
    val mappedResources = resources.map { resource ->
        ResourceEntity(
            identifier = "$identifier:resource:${resource.remoteId}",
            remoteId = resource.remoteId,
            accountKey = accountKey,
            date = resource.date,
            filename = resource.filename,
            uri = resource.uri,
            localUri = resource.localUri,
            mimeType = resource.mimeType,
            thumbnailUri = resource.thumbnailUri,
            thumbnailLocalUri = resource.thumbnailLocalUri,
            memoId = identifier
        )
    }
    val contentProjection = if (mappedResources.isEmpty()) {
        projectMemoContentResources(
            rawContent = content,
            accountKey = accountKey,
            memoIdentifier = identifier,
            resourceIdentifierPrefix = identifier,
            memoDate = date
        )
    } else {
        MemoContentResourcesProjection(
            displayContent = content,
            resources = emptyList()
        )
    }
    val entity = MemoEntity(
        identifier = identifier,
        remoteId = remoteId,
        accountKey = accountKey,
        content = contentProjection.displayContent,
        date = date,
        visibility = visibility,
        pinned = pinned,
        archived = archived,
        latitude = latitude,
        longitude = longitude,
        needsSync = needsSync,
        isDeleted = false,
        lastModified = lastModified,
        lastSyncedAt = lastSyncedAt
    )
    entity.resources = if (mappedResources.isNotEmpty()) mappedResources else contentProjection.resources
    entity.tags = tags
    return entity
}

private data class MemoContentResourcesProjection(
    val displayContent: String,
    val resources: List<ResourceEntity>
)

private fun projectMemoContentResources(
    rawContent: String,
    accountKey: String,
    memoIdentifier: String,
    resourceIdentifierPrefix: String,
    memoDate: Instant
): MemoContentResourcesProjection {
    if (rawContent.isBlank()) {
        return MemoContentResourcesProjection(
            displayContent = rawContent,
            resources = emptyList()
        )
    }

    val keptLines = mutableListOf<String>()
    val derivedResources = mutableListOf<ResourceEntity>()
    var derivedIndex = 0

    rawContent.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        val match = parseStandaloneMarkdownResource(line)
        if (match != null) {
            val parsedUri = runCatching { Uri.parse(match.url) }.getOrNull()
            if (parsedUri != null && shouldConvertToResource(parsedUri, match.isImage)) {
                derivedIndex += 1
                val filename = resolveResourceFilename(parsedUri, derivedIndex, match.isImage)
                val mimeType = resolveResourceMimeType(filename, match.isImage)
                val localUri = if (parsedUri.scheme.equals("file", ignoreCase = true)) parsedUri.toString() else null
                val thumbnail = if (match.isImage) match.url else null
                derivedResources += ResourceEntity(
                    identifier = "$resourceIdentifierPrefix:derived:$derivedIndex",
                    remoteId = null,
                    accountKey = accountKey,
                    date = memoDate,
                    filename = filename,
                    uri = match.url,
                    localUri = localUri,
                    mimeType = mimeType,
                    thumbnailUri = thumbnail,
                    thumbnailLocalUri = localUri,
                    memoId = memoIdentifier
                )
                return@forEach
            }
        }
        keptLines += rawLine
    }

    if (derivedResources.isEmpty()) {
        return MemoContentResourcesProjection(
            displayContent = rawContent,
            resources = emptyList()
        )
    }

    return MemoContentResourcesProjection(
        displayContent = keptLines.joinToString("\n").trimEnd(),
        resources = derivedResources
    )
}

private data class ParsedMarkdownResource(
    val url: String,
    val isImage: Boolean
)

private fun parseStandaloneMarkdownResource(line: String): ParsedMarkdownResource? {
    if (line.isEmpty()) {
        return null
    }
    standaloneMarkdownImagePattern.matchEntire(line)?.let { match ->
        return ParsedMarkdownResource(
            url = match.groupValues[1],
            isImage = true
        )
    }
    standaloneMarkdownLinkPattern.matchEntire(line)?.let { match ->
        return ParsedMarkdownResource(
            url = match.groupValues[1],
            isImage = false
        )
    }
    return null
}

private fun isSupportedResourceUri(uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
    return scheme == "http" || scheme == "https" || scheme == "file"
}

private fun shouldConvertToResource(uri: Uri, isImage: Boolean): Boolean {
    if (!isSupportedResourceUri(uri)) {
        return false
    }
    if (isImage) {
        return true
    }
    val path = uri.path.orEmpty()
    if (path.contains("/file/")) {
        return true
    }
    val filename = uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: return false
    return resolveResourceMimeType(Uri.decode(filename), isImage = false) != null
}

private fun resolveResourceFilename(uri: Uri, index: Int, isImage: Boolean): String {
    val fromPath = uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    if (!fromPath.isNullOrBlank()) {
        return Uri.decode(fromPath)
    }
    return if (isImage) {
        "image_$index"
    } else {
        "attachment_$index"
    }
}

private fun resolveResourceMimeType(filename: String, isImage: Boolean): String? {
    val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
    if (ext.isNotBlank()) {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { mime ->
            return mime
        }
    }
    return if (isImage) "image/*" else null
}

private val standaloneMarkdownImagePattern = Regex("""^\s*!\[[^\]]*]\(([^)\s]+)(?:\s+["'][^"']*["'])?\)\s*$""")
private val standaloneMarkdownLinkPattern = Regex("""^\s*\[[^\]]+]\(([^)\s]+)(?:\s+["'][^"']*["'])?\)\s*$""")
