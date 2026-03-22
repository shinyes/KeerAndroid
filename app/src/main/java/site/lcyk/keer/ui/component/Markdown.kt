package site.lcyk.keer.ui.component

import android.net.Uri
import androidx.collection.LruCache
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCheckBox
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownTokenTypes
import site.lcyk.keer.util.findCustomTagMatches
import site.lcyk.keer.util.getCustomTagName
import site.lcyk.keer.util.isCustomTagSupportedNode
import com.mikepenz.markdown.m3.Markdown as M3Markdown

@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    imageBaseUrl: String? = null,
    checkboxChange: ((checked: Boolean, startOffset: Int, endOffset: Int) -> Unit)? = null,
    selectable: Boolean = false,
    onTagClick: ((tag: String) -> Unit)? = null,
) {
    fun withOptionalTextAlign(style: TextStyle): TextStyle {
        return if (textAlign == null) style else style.copy(textAlign = textAlign)
    }

    val currentCheckboxChange = rememberUpdatedState(checkboxChange)
    val currentOnTagClick = rememberUpdatedState(onTagClick)

    val typography = MaterialTheme.typography
    val bodyTextStyle = remember(typography, textAlign) {
        withOptionalTextAlign(typography.bodyLarge)
    }
    val h1TextStyle = remember(typography, textAlign) {
        withOptionalTextAlign(typography.headlineLarge)
    }
    val h2TextStyle = remember(typography, textAlign) {
        withOptionalTextAlign(typography.headlineMedium)
    }
    val h3TextStyle = remember(typography, textAlign) {
        withOptionalTextAlign(typography.headlineSmall)
    }
    val h4TextStyle = remember(typography, textAlign) {
        withOptionalTextAlign(typography.titleLarge)
    }
    val h5TextStyle = remember(typography, textAlign) {
        withOptionalTextAlign(typography.titleMedium)
    }
    val h6TextStyle = remember(typography, textAlign) {
        withOptionalTextAlign(typography.titleSmall)
    }
    val uriHandler = LocalUriHandler.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val tagLinkStyle = remember(primaryColor) {
        TextLinkStyles(
            style = SpanStyle(
                color = primaryColor,
                textDecoration = TextDecoration.Underline,
            )
        )
    }
    val tagLinkListener = remember(uriHandler) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            if (url.startsWith(TAG_LINK_PREFIX)) {
                currentOnTagClick.value?.invoke(Uri.decode(url.removePrefix(TAG_LINK_PREFIX)))
                return@LinkInteractionListener
            }
            uriHandler.openUri(url)
        }
    }
    val imageTransformer = remember(imageBaseUrl) {
        object : ImageTransformer {
            @Composable
            override fun transform(link: String): ImageData {
                return Coil3ImageTransformerImpl.transform(resolveMarkdownImageLink(link, imageBaseUrl))
            }

            @Composable
            override fun intrinsicSize(painter: Painter): Size {
                return Coil3ImageTransformerImpl.intrinsicSize(painter)
            }
        }
    }
    val annotator = remember(tagLinkStyle, tagLinkListener) {
        markdownAnnotator(
            config = markdownAnnotatorConfig(eolAsNewLine = true),
            annotate = { content, child ->
                if (child.type != MarkdownTokenTypes.TEXT) {
                    return@markdownAnnotator false
                }
                if (!isCustomTagSupportedNode(child)) {
                    return@markdownAnnotator false
                }
                val source = child.getUnescapedTextInNode(content)
                val tags = rememberMarkdownTagSegments(source)
                if (tags.isEmpty()) {
                    return@markdownAnnotator false
                }

                var cursor = 0
                tags.forEach { tag ->
                    val start = tag.start
                    val endInclusive = tag.endInclusive
                    if (start > cursor) {
                        append(source.substring(cursor, start))
                    }
                    withLink(
                        LinkAnnotation.Url(
                            url = TAG_LINK_PREFIX + Uri.encode(tag.rawTagName),
                            styles = tagLinkStyle,
                            linkInteractionListener = tagLinkListener
                        )
                    ) {
                        append(tag.fullText)
                    }
                    cursor = endInclusive + 1
                }
                if (cursor < source.length) {
                    append(source.substring(cursor))
                }
                true
            }
        )
    }
    val markdownComponents = remember {
        markdownComponents(
            codeFence = highlightedCodeFence,
            codeBlock = highlightedCodeBlock,
            image = {
                // Intentionally disabled: inline markdown images (e.g. ![alt](url)) are not needed.
            },
            checkbox = {
                val node = it.node
                MarkdownCheckBox(
                    content = it.content,
                    node = it.node,
                    style = it.typography.text,
                    checkedIndicator = { checked, modifier ->
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = if (currentCheckboxChange.value != null) {
                                    {
                                        currentCheckboxChange.value?.invoke(
                                            !checked,
                                            node.startOffset,
                                            node.endOffset
                                        )
                                    }
                                } else {
                                    null
                                },
                                modifier = modifier.semantics {
                                    role = Role.Checkbox
                                    stateDescription = if (checked) "Checked" else "Unchecked"
                                },
                            )
                        }
                    }
                )
            }
        )
    }
    val markdownState = rememberMarkdownState(
        content = text,
        retainState = true
    )

    val markdownContent: @Composable () -> Unit = {
        M3Markdown(
            markdownState = markdownState,
            modifier = modifier,
            imageTransformer = imageTransformer,
            typography = markdownTypography(
                h1 = h1TextStyle,
                h2 = h2TextStyle,
                h3 = h3TextStyle,
                h4 = h4TextStyle,
                h5 = h5TextStyle,
                h6 = h6TextStyle,
                text = bodyTextStyle,
                paragraph = bodyTextStyle,
                ordered = bodyTextStyle,
                bullet = bodyTextStyle,
                list = bodyTextStyle
            ),
            annotator = annotator,
            components = markdownComponents
        )
    }

    if (selectable) {
        SelectionContainer {
            markdownContent()
        }
    } else {
        markdownContent()
    }
}

private fun rememberMarkdownTagSegments(source: String): List<MarkdownTagSegment> {
    MarkdownTagSegmentCache.get(source)?.let { return it }
    val resolved = findCustomTagMatches(source).map { match ->
        MarkdownTagSegment(
            start = match.range.first,
            endInclusive = match.range.last,
            fullText = match.value,
            rawTagName = getCustomTagName(match),
        )
    }.toList()
    MarkdownTagSegmentCache.put(source, resolved)
    return resolved
}

internal fun clearMarkdownTagSegmentCacheForTest() {
    MarkdownTagSegmentCache.clear()
}

internal fun markdownTagSegmentCacheSizeForTest(): Int {
    return MarkdownTagSegmentCache.size()
}

internal fun resolveMarkdownTagSegmentsForTest(source: String): List<String> {
    return rememberMarkdownTagSegments(source).map { segment -> segment.fullText }
}

private fun resolveMarkdownImageLink(link: String, imageBaseUrl: String?): String {
    val uri = link.toUri()
    if (uri.scheme != null || imageBaseUrl.isNullOrBlank()) {
        return link
    }
    return imageBaseUrl.toUri().buildUpon().path(link).build().toString()
}

private const val TAG_LINK_PREFIX = "Keer://tag/"
private const val MARKDOWN_TAG_SEGMENT_CACHE_LIMIT = 2_048

private data class MarkdownTagSegment(
    val start: Int,
    val endInclusive: Int,
    val fullText: String,
    val rawTagName: String,
)

private object MarkdownTagSegmentCache {
    private val cache = object : LruCache<String, List<MarkdownTagSegment>>(MARKDOWN_TAG_SEGMENT_CACHE_LIMIT) {}

    fun get(source: String): List<MarkdownTagSegment>? {
        return synchronized(cache) {
            cache.get(source)
        }
    }

    fun put(source: String, segments: List<MarkdownTagSegment>) {
        synchronized(cache) {
            cache.put(source, segments)
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.evictAll()
        }
    }

    fun size(): Int {
        return synchronized(cache) {
            cache.size()
        }
    }
}
