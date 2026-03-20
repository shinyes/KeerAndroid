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
import java.io.PushbackReader
import java.io.Reader
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

    suspend fun writeDocument(
        appendable: Appendable,
        exportedAt: String,
        source: MemoTransferSource? = null,
        writeMemos: suspend ((MemoTransferMemo) -> Unit) -> Unit,
    ) {
        appendable.append("{")
        appendable.append("\"format\":")
        appendable.append(json.encodeToString(memoTransferFormatV2))
        appendable.append(",\"exportedAt\":")
        appendable.append(json.encodeToString(exportedAt))
        if (source != null) {
            appendable.append(",\"source\":")
            appendable.append(json.encodeToString(MemoTransferSource.serializer(), source))
        }
        appendable.append(",\"memos\":[")
        var wroteMemo = false
        writeMemos { memo ->
            if (wroteMemo) {
                appendable.append(",")
            }
            appendable.append(json.encodeToString(MemoTransferMemo.serializer(), memo))
            wroteMemo = true
        }
        appendable.append("]}")
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

    suspend fun forEachImportEntry(
        reader: Reader,
        onEntry: suspend (MemoImportEntry) -> Unit,
    ) {
        JsonMemoStreamReader(reader).readEntries { rawElement ->
            val payload = runCatching { json.parseToJsonElement(rawElement) }
                .getOrNull() ?: return@readEntries
            parseMemoEntry(payload)?.let { entry ->
                onEntry(entry)
            }
        }
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

private class JsonMemoStreamReader(reader: Reader) {
    private val source = PushbackReader(reader.buffered(16 * 1024), 8)
    private val targetArrayKeys = setOf("memos", "items", "data")

    suspend fun readEntries(onElement: suspend (String) -> Unit) {
        val first = nextNonWhitespace()
        when (first) {
            '['.code -> readArrayElements(onElement)
            '{'.code -> readObjectForMemoArrays(onElement)
            else -> throw IllegalArgumentException("Unsupported memo import format")
        }
    }

    private suspend fun readObjectForMemoArrays(onElement: suspend (String) -> Unit) {
        var expectKey = true
        while (true) {
            val token = nextNonWhitespace()
            when (token) {
                '}'.code -> return
                '"'.code -> {
                    if (!expectKey) {
                        throw IllegalArgumentException("Invalid JSON object")
                    }
                    val key = readJsonStringContent()
                    expect(':')
                    val valueStart = nextNonWhitespace()
                    if (key in targetArrayKeys && valueStart == '['.code) {
                        readArrayElements(onElement)
                    } else {
                        skipValue(valueStart)
                    }
                    val delimiter = nextNonWhitespace()
                    when (delimiter) {
                        ','.code -> expectKey = true
                        '}'.code -> return
                        else -> throw IllegalArgumentException("Invalid JSON object delimiter")
                    }
                }
                else -> throw IllegalArgumentException("Invalid JSON object")
            }
        }
    }

    private suspend fun readArrayElements(onElement: suspend (String) -> Unit) {
        var expectValue = true
        while (true) {
            val token = nextNonWhitespace()
            when {
                token == ']'.code -> return
                expectValue -> {
                    if (token == '{'.code) {
                        onElement(readRawValueFromStart(token))
                    } else {
                        skipValue(token)
                    }
                    val delimiter = nextNonWhitespace()
                    when (delimiter) {
                        ','.code -> expectValue = true
                        ']'.code -> return
                        else -> throw IllegalArgumentException("Invalid JSON array delimiter")
                    }
                }
                else -> throw IllegalArgumentException("Invalid JSON array")
            }
        }
    }

    private fun skipValue(start: Int) {
        readRawValueFromStart(start)
    }

    private fun readRawValueFromStart(start: Int): String {
        if (start < 0) {
            throw IllegalArgumentException("Unexpected end of JSON")
        }
        val builder = StringBuilder()
        builder.append(start.toChar())
        when (start) {
            '"'.code -> {
                var escaped = false
                while (true) {
                    val next = read()
                    if (next < 0) {
                        throw IllegalArgumentException("Unterminated JSON string")
                    }
                    builder.append(next.toChar())
                    if (escaped) {
                        escaped = false
                        continue
                    }
                    if (next == '\\'.code) {
                        escaped = true
                        continue
                    }
                    if (next == '"'.code) {
                        return builder.toString()
                    }
                }
            }
            '{'.code, '['.code -> {
                val stack = ArrayDeque<Char>()
                stack.addLast(start.toChar())
                var inString = false
                var escaped = false
                while (stack.isNotEmpty()) {
                    val next = read()
                    if (next < 0) {
                        throw IllegalArgumentException("Unterminated JSON value")
                    }
                    val ch = next.toChar()
                    builder.append(ch)
                    if (inString) {
                        if (escaped) {
                            escaped = false
                        } else if (ch == '\\') {
                            escaped = true
                        } else if (ch == '"') {
                            inString = false
                        }
                        continue
                    }
                    when (ch) {
                        '"' -> inString = true
                        '{', '[' -> stack.addLast(ch)
                        '}' -> {
                            if (stack.removeLastOrNull() != '{') {
                                throw IllegalArgumentException("Invalid JSON nesting")
                            }
                        }
                        ']' -> {
                            if (stack.removeLastOrNull() != '[') {
                                throw IllegalArgumentException("Invalid JSON nesting")
                            }
                        }
                    }
                }
                return builder.toString()
            }
            else -> {
                while (true) {
                    val next = read()
                    if (next < 0) {
                        return builder.toString()
                    }
                    val ch = next.toChar()
                    if (ch.isWhitespace() || ch == ',' || ch == ']' || ch == '}') {
                        unread(next)
                        return builder.toString()
                    }
                    builder.append(ch)
                }
            }
        }
    }

    private fun readJsonStringContent(): String {
        val builder = StringBuilder()
        while (true) {
            val next = read()
            if (next < 0) {
                throw IllegalArgumentException("Unterminated JSON key string")
            }
            when (next) {
                '"'.code -> return builder.toString()
                '\\'.code -> {
                    val escaped = read()
                    if (escaped < 0) {
                        throw IllegalArgumentException("Unterminated JSON escape")
                    }
                    when (escaped.toChar()) {
                        '"', '\\', '/' -> builder.append(escaped.toChar())
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            val hex = CharArray(4)
                            repeat(4) { index ->
                                val hexChar = read()
                                if (hexChar < 0) {
                                    throw IllegalArgumentException("Invalid unicode escape")
                                }
                                hex[index] = hexChar.toChar()
                            }
                            builder.append(hex.concatToString().toInt(16).toChar())
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape sequence")
                    }
                }
                else -> builder.append(next.toChar())
            }
        }
    }

    private fun expect(expected: Char) {
        val next = nextNonWhitespace()
        if (next != expected.code) {
            throw IllegalArgumentException("Expected '$expected'")
        }
    }

    private fun nextNonWhitespace(): Int {
        while (true) {
            val next = read()
            if (next < 0) {
                return next
            }
            if (!next.toChar().isWhitespace()) {
                return next
            }
        }
    }

    private fun read(): Int = source.read()

    private fun unread(ch: Int) {
        if (ch >= 0) {
            source.unread(ch)
        }
    }
}
