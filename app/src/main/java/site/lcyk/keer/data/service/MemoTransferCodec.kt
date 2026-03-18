package site.lcyk.keer.data.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import site.lcyk.keer.data.model.MemoVisibility
import java.time.Instant

private const val memoTransferFormatV2 = "keer.memo.transfer.v2"

@Serializable
internal data class MemoTransferDocument(
    val format: String = memoTransferFormatV2,
    val exportedAt: String,
    val source: MemoTransferSource? = null,
    val memos: List<MemoTransferMemo> = emptyList(),
)

@Serializable
internal data class MemoTransferSource(
    val host: String? = null,
    val userId: String? = null,
    val username: String? = null,
)

@Serializable
internal data class MemoTransferAttachment(
    val path: String,
    val filename: String,
    val mimeType: String? = null,
)

@Serializable
internal data class MemoTransferMemo(
    val importId: String? = null,
    val content: String,
    val createdAt: String? = null,
    val visibility: String? = null,
    val tags: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val attachments: List<MemoTransferAttachment> = emptyList(),
)

internal data class MemoImportAttachment(
    val path: String,
    val filename: String,
    val mimeType: String?,
)

internal data class MemoImportEntry(
    val importId: String?,
    val content: String,
    val createdAt: Instant?,
    val visibility: MemoVisibility,
    val tags: List<String>,
    val latitude: Double?,
    val longitude: Double?,
    val pinned: Boolean,
    val archived: Boolean,
    val attachments: List<MemoImportAttachment>,
)

internal object MemoTransferCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(document: MemoTransferDocument): String {
        return json.encodeToString(MemoTransferDocument.serializer(), document)
    }

    fun decodeImportEntries(raw: String): List<MemoImportEntry> {
        val root = json.parseToJsonElement(raw)
        val memoArray = when (root) {
            is JsonArray -> root
            is JsonObject -> extractMemoArray(root)
            else -> throw IllegalArgumentException("Unsupported memo import format")
        }
        return memoArray.mapNotNull(::parseMemoEntry)
    }

    private fun extractMemoArray(root: JsonObject): JsonArray {
        val memoArray = root["memos"] ?: root["items"] ?: root["data"]
        if (memoArray is JsonArray) {
            return memoArray
        }
        if (looksLikeSingleMemo(root)) {
            return JsonArray(listOf(root))
        }
        throw IllegalArgumentException("Unsupported memo import format")
    }

    private fun looksLikeSingleMemo(root: JsonObject): Boolean {
        return contentCandidateKeys.any(root::containsKey)
    }

    private fun parseMemoEntry(element: JsonElement): MemoImportEntry? {
        val payload = element as? JsonObject ?: return null
        val content = contentCandidateKeys
            .asSequence()
            .mapNotNull { key -> payload[key].primitiveStringOrNull() }
            .firstOrNull()
            ?.replace("\r\n", "\n")
            ?: return null
        if (content.isBlank()) {
            return null
        }
        val visibility = parseVisibility(payload[visibilityCandidateKey].primitiveStringOrNull())
        val tags = parseTags(payload[tagsCandidateKey])
        val createdAt = parseCreatedAt(payload)
        val importId = parseImportId(payload)
        val latitude = payload[latitudeCandidateKey].primitiveDoubleOrNull()
            ?: payload[latitudeShortCandidateKey].primitiveDoubleOrNull()
        val longitude = payload[longitudeCandidateKey].primitiveDoubleOrNull()
            ?: payload[longitudeShortCandidateKey].primitiveDoubleOrNull()
            ?: payload[longitudeAltCandidateKey].primitiveDoubleOrNull()
        return MemoImportEntry(
            importId = importId,
            content = content,
            createdAt = createdAt,
            visibility = visibility,
            tags = tags,
            latitude = latitude,
            longitude = longitude,
            pinned = payload[pinnedCandidateKey].primitiveBooleanOrNull() ?: false,
            archived = payload[archivedCandidateKey].primitiveBooleanOrNull() ?: false,
            attachments = parseAttachments(payload[attachmentsCandidateKey]),
        )
    }

    private fun parseCreatedAt(payload: JsonObject): Instant? {
        for (key in createdAtCandidateKeys) {
            val instant = payload[key].primitiveInstantOrNull()
            if (instant != null) {
                return instant
            }
        }
        return null
    }

    private fun parseVisibility(raw: String?): MemoVisibility {
        return when (raw?.trim()?.uppercase()) {
            "PUBLIC" -> MemoVisibility.PUBLIC
            "PROTECTED" -> MemoVisibility.PROTECTED
            else -> MemoVisibility.PRIVATE
        }
    }

    private fun parseImportId(payload: JsonObject): String? {
        return importIdCandidateKeys
            .asSequence()
            .mapNotNull { key -> payload[key].primitiveStringOrNull() }
            .map { value -> value.trim() }
            .firstOrNull()
            ?.takeIf { value -> value.isNotEmpty() }
    }

    private fun parseTags(element: JsonElement?): List<String> {
        return when (element) {
            is JsonArray -> element.mapNotNull { tag ->
                tag.primitiveStringOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            is JsonPrimitive -> element.primitiveStringOrNull()
                ?.split(',', ';')
                ?.asSequence()
                ?.map { tag -> tag.trim() }
                ?.filter { tag -> tag.isNotEmpty() }
                ?.toList()
            else -> emptyList()
        }?.distinct().orEmpty()
    }

    private fun parseAttachments(element: JsonElement?): List<MemoImportAttachment> {
        val array = element as? JsonArray ?: return emptyList()
        val attachments = mutableListOf<MemoImportAttachment>()
        array.forEach { raw ->
            val item = raw as? JsonObject ?: return@forEach
            val path = attachmentPathCandidateKeys
                .asSequence()
                .mapNotNull { key -> item[key].primitiveStringOrNull() }
                .map { value -> value.trim().replace('\\', '/') }
                .firstOrNull()
                ?.takeIf { value -> value.isNotEmpty() }
                ?: return@forEach
            val filename = attachmentFilenameCandidateKeys
                .asSequence()
                .mapNotNull { key -> item[key].primitiveStringOrNull() }
                .map { value -> value.trim() }
                .firstOrNull()
                ?.takeIf { value -> value.isNotEmpty() }
                ?: path.substringAfterLast('/').ifEmpty { "attachment.bin" }
            val mimeType = attachmentMimeTypeCandidateKeys
                .asSequence()
                .mapNotNull { key -> item[key].primitiveStringOrNull() }
                .map { value -> value.trim() }
                .firstOrNull()
                ?.takeIf { value -> value.isNotEmpty() }
            attachments += MemoImportAttachment(
                path = path,
                filename = filename,
                mimeType = mimeType,
            )
        }
        return attachments
    }

    private fun JsonElement?.primitiveStringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        val content = primitive.content
        return if (content.equals("null", ignoreCase = true)) null else content
    }

    private fun JsonElement?.primitiveDoubleOrNull(): Double? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.doubleOrNull
    }

    private fun JsonElement?.primitiveBooleanOrNull(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.booleanOrNull
    }

    private fun JsonElement?.primitiveInstantOrNull(): Instant? {
        val primitive = this as? JsonPrimitive ?: return null
        primitive.primitiveStringOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { text ->
                parseInstantFromString(text)?.let { return it }
            }
        primitive.longOrNull
            ?.let(::parseInstantFromEpoch)
            ?.let { return it }
        primitive.doubleOrNull
            ?.toLong()
            ?.let(::parseInstantFromEpoch)
            ?.let { return it }
        return null
    }

    private fun parseInstantFromString(raw: String): Instant? {
        runCatching { Instant.parse(raw) }.getOrNull()?.let { return it }
        return raw.toLongOrNull()?.let(::parseInstantFromEpoch)
    }

    private fun parseInstantFromEpoch(value: Long): Instant {
        return if (kotlin.math.abs(value) >= 1_000_000_000_000L) {
            Instant.ofEpochMilli(value)
        } else {
            Instant.ofEpochSecond(value)
        }
    }

    private val contentCandidateKeys = listOf(
        "content",
        "text",
        "body",
        "memo",
    )
    private const val visibilityCandidateKey = "visibility"
    private const val tagsCandidateKey = "tags"
    private val importIdCandidateKeys = listOf(
        "importId",
        "import_id",
        "sourceId",
        "source_id",
        "id",
    )
    private val createdAtCandidateKeys = listOf(
        "createdAt",
        "createTime",
        "create_time",
        "created",
        "createdTs",
        "created_at",
    )
    private const val pinnedCandidateKey = "pinned"
    private const val archivedCandidateKey = "archived"
    private const val attachmentsCandidateKey = "attachments"
    private const val latitudeCandidateKey = "latitude"
    private const val latitudeShortCandidateKey = "lat"
    private const val longitudeCandidateKey = "longitude"
    private const val longitudeShortCandidateKey = "lng"
    private const val longitudeAltCandidateKey = "lon"
    private val attachmentPathCandidateKeys = listOf("path", "file", "entry")
    private val attachmentFilenameCandidateKeys = listOf("filename", "name")
    private val attachmentMimeTypeCandidateKeys = listOf("mimeType", "type")
}
