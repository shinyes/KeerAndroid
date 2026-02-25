package site.lcyk.keer.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.util.isValidTagName
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.net.URLEncoder
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SideDrawer(
    memosNavController: NavHostController,
    drawerState: DrawerState? = null,
    onDrawerItemCloseRequested: (() -> Unit)? = null
) {
    val weekDays = remember {
        val day = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        List(DayOfWeek.entries.size) { index ->
            day.plus(index.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }
    var showHeatMap by remember {
        mutableStateOf(false)
    }
    val scope = rememberCoroutineScope()
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val hasExplore = currentAccount !is Account.Local
    val rootNavController = LocalRootNavController.current
    val navBackStackEntry by memosNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val hapticFeedback = LocalHapticFeedback.current
    val expandedTagNodes = remember { mutableStateMapOf<String, Boolean>() }
    var activeTagActionTarget by remember { mutableStateOf<String?>(null) }
    var renameTargetTag by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTargetTag by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAndMemosTargetTag by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAndMemosInput by remember { mutableStateOf("") }
    var tagActionErrorMessage by remember { mutableStateOf<String?>(null) }
    var tagActionInProgress by remember { mutableStateOf(false) }
    val rawTags = memosViewModel.tags.toList()
    val availableTags = remember(rawTags) { normalizeTagList(rawTags) }
    val tagTree = remember(availableTags) { buildTagTree(availableTags) }
    val currentSelectedTag = remember(navBackStackEntry) {
        navBackStackEntry
            ?.arguments
            ?.getString("tag")
            ?.let(Uri::decode)
            ?.let(::normalizeTagName)
    }
    val visibleTagEntries = flattenTagTree(tagTree, expandedTagNodes)

    fun isSelected(route: String): Boolean {
        return currentDestination?.hierarchy?.any { it.route == route } == true
    }

    fun isTagSelected(tag: String): Boolean {
        if (!isSelected("${RouteName.TAG}/{tag}")) return false
        return currentSelectedTag == normalizeTagName(tag)
    }

    fun matchesTagOrDescendant(candidate: String, root: String): Boolean {
        return candidate == root || candidate.startsWith("$root/")
    }

    LazyColumn {
        item {
            Stats()
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(end = 5.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(weekDays[0],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                    Text(weekDays[3],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                    Text(weekDays[6],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                if (showHeatMap) {
                    Heatmap()
                }
            }
        }

        item {
            Text(
                R.string.keer.string,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(20.dp)
            )
        }
        item {
            NavigationDrawerItem(
                label = { Text(R.string.memos.string) },
                icon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                selected = isSelected(RouteName.MEMOS),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        memosNavController.navigate(RouteName.MEMOS) {
                            launchSingleTop = true
                            restoreState = true
                        }
                        onDrawerItemCloseRequested?.invoke()
                        drawerState?.close()
                    }
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
        if (hasExplore) {
            item {
                NavigationDrawerItem(
                    label = { Text(R.string.explore.string) },
                    icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                    selected = isSelected(RouteName.EXPLORE),
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            memosNavController.navigate(RouteName.EXPLORE) {
                                launchSingleTop = true
                                restoreState = true
                            }
                            onDrawerItemCloseRequested?.invoke()
                            drawerState?.close()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
        item {
            NavigationDrawerItem(
                label = { Text(R.string.resources.string) },
                icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                selected = false,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        onDrawerItemCloseRequested?.invoke()
                        drawerState?.close()
                        rootNavController.navigate(RouteName.RESOURCE)
                    }
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
        item {
            NavigationDrawerItem(
                label = { Text(R.string.archived.string) },
                icon = { Icon(Icons.Outlined.Inventory2, contentDescription = null) },
                selected = isSelected(RouteName.ARCHIVED),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        memosNavController.navigate(RouteName.ARCHIVED) {
                            launchSingleTop = true
                            restoreState = true
                        }
                        onDrawerItemCloseRequested?.invoke()
                        drawerState?.close()
                    }
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
        item {
            NavigationDrawerItem(
                label = { Text(R.string.settings.string) },
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                selected = false,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        onDrawerItemCloseRequested?.invoke()
                        drawerState?.close()
                        rootNavController.navigate(RouteName.SETTINGS)
                    }
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }

        item {
            Text(
                R.string.tags.string,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(20.dp)
            )
        }

        visibleTagEntries.forEach { entry ->
            item("tag_${entry.fullPath}") {
                TagDrawerItem(
                    tag = entry.fullPath,
                    displayName = entry.displayName,
                    selected = isTagSelected(entry.fullPath),
                    enabled = entry.selectable,
                    depth = entry.depth,
                    expandable = entry.expandable,
                    expanded = entry.expanded,
                    onToggleExpand = if (entry.expandable) {
                        {
                            expandedTagNodes[entry.fullPath] = !entry.expanded
                        }
                    } else {
                        null
                    },
                    onLongPress = if (entry.selectable) {
                        { activeTagActionTarget = entry.fullPath }
                    } else {
                        null
                    },
                    memosNavController = memosNavController,
                    drawerState = drawerState,
                    onDrawerItemCloseRequested = onDrawerItemCloseRequested
                )
            }
        }
    }

    activeTagActionTarget?.let { targetTag ->
        val tagMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                if (!tagActionInProgress) {
                    activeTagActionTarget = null
                }
            },
            sheetState = tagMenuSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = R.string.tag_actions.string,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TagActionMenuItem(
                    title = stringResource(R.string.rename_tag),
                    enabled = !tagActionInProgress,
                    onClick = {
                        renameTargetTag = targetTag
                        renameValue = targetTag
                        activeTagActionTarget = null
                    }
                )
                TagActionMenuItem(
                    title = stringResource(R.string.delete_tag),
                    enabled = !tagActionInProgress,
                    destructive = true,
                    onClick = {
                        deleteTargetTag = targetTag
                        activeTagActionTarget = null
                    }
                )
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = { activeTagActionTarget = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(R.string.cancel.string)
                }
            }
        }
    }

    renameTargetTag?.let { sourceTag ->
        AlertDialog(
            onDismissRequest = {
                if (!tagActionInProgress) {
                    renameTargetTag = null
                }
            },
            title = { Text(R.string.rename_tag.string) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(R.string.new_tag_name.string) },
                        singleLine = true,
                        enabled = !tagActionInProgress
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = {
                        val normalizedSourceTag = normalizeTagName(sourceTag)
                        val normalizedNewTag = normalizeTagName(renameValue)
                        if (normalizedSourceTag.isEmpty() || normalizedNewTag.isEmpty() || !isValidTagName(normalizedNewTag)) {
                            tagActionErrorMessage = R.string.invalid_tag_name.string
                            return@TextButton
                        }
                        scope.launch {
                            tagActionInProgress = true
                            val response = memosViewModel.renameTag(normalizedSourceTag, normalizedNewTag)
                            tagActionInProgress = false
                            if (response is com.skydoves.sandwich.ApiResponse.Success) {
                                currentSelectedTag
                                    ?.takeIf { matchesTagOrDescendant(it, normalizedSourceTag) }
                                    ?.let { selected ->
                                        val renamedSelected = renameTagWithPrefix(
                                            tag = selected,
                                            oldPrefix = normalizedSourceTag,
                                            newPrefix = normalizedNewTag
                                        )
                                        memosNavController.navigate(
                                            "${RouteName.TAG}/${URLEncoder.encode(renamedSelected, "UTF-8")}"
                                        ) {
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                renameTargetTag = null
                                renameValue = ""
                            } else {
                                tagActionErrorMessage = response.getErrorMessage()
                            }
                        }
                    }
                ) {
                    Text(R.string.confirm.string)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = { renameTargetTag = null }
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }

    deleteTargetTag?.let { targetTag ->
        AlertDialog(
            onDismissRequest = {
                if (!tagActionInProgress) {
                    deleteTargetTag = null
                }
            },
            title = { Text(R.string.delete_tag.string) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TagActionMenuItem(
                        title = stringResource(R.string.delete_tag_and_memos),
                        enabled = !tagActionInProgress,
                        destructive = true,
                        onClick = {
                            val normalizedTag = normalizeTagName(targetTag)
                            if (normalizedTag.isEmpty()) {
                                tagActionErrorMessage = R.string.invalid_tag_name.string
                                return@TagActionMenuItem
                            }
                            confirmDeleteAndMemosTargetTag = normalizedTag
                            confirmDeleteAndMemosInput = ""
                            deleteTargetTag = null
                        }
                    )
                    TagActionMenuItem(
                        title = stringResource(R.string.delete_tag_only),
                        enabled = !tagActionInProgress,
                        destructive = false,
                        onClick = {
                            val normalizedTag = normalizeTagName(targetTag)
                            if (normalizedTag.isEmpty()) {
                                tagActionErrorMessage = R.string.invalid_tag_name.string
                                return@TagActionMenuItem
                            }
                            scope.launch {
                                tagActionInProgress = true
                                val response = memosViewModel.deleteTag(normalizedTag, deleteAssociatedMemos = false)
                                tagActionInProgress = false
                                if (response is com.skydoves.sandwich.ApiResponse.Success) {
                                    currentSelectedTag
                                        ?.takeIf { matchesTagOrDescendant(it, normalizedTag) }
                                        ?.let {
                                            memosNavController.navigate(RouteName.MEMOS) {
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    deleteTargetTag = null
                                } else {
                                    tagActionErrorMessage = response.getErrorMessage()
                                }
                            }
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = { deleteTargetTag = null }
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }

    confirmDeleteAndMemosTargetTag?.let { targetTag ->
        val canConfirmDelete = normalizeTagName(confirmDeleteAndMemosInput) == targetTag
        AlertDialog(
            onDismissRequest = {
                if (!tagActionInProgress) {
                    confirmDeleteAndMemosTargetTag = null
                    confirmDeleteAndMemosInput = ""
                }
            },
            title = { Text(R.string.delete_tag_and_memos.string) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.delete_tag_and_memos_confirm_hint, targetTag),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedTextField(
                        value = confirmDeleteAndMemosInput,
                        onValueChange = { confirmDeleteAndMemosInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(R.string.delete_tag_and_memos_confirm_label.string) },
                        singleLine = true,
                        enabled = !tagActionInProgress
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !tagActionInProgress && canConfirmDelete,
                    onClick = {
                        scope.launch {
                            tagActionInProgress = true
                            val response = memosViewModel.deleteTag(targetTag, deleteAssociatedMemos = true)
                            tagActionInProgress = false
                            if (response is com.skydoves.sandwich.ApiResponse.Success) {
                                currentSelectedTag
                                    ?.takeIf { matchesTagOrDescendant(it, targetTag) }
                                    ?.let {
                                        memosNavController.navigate(RouteName.MEMOS) {
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                confirmDeleteAndMemosTargetTag = null
                                confirmDeleteAndMemosInput = ""
                            } else {
                                tagActionErrorMessage = response.getErrorMessage()
                            }
                        }
                    }
                ) {
                    Text(R.string.confirm.string)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = {
                        confirmDeleteAndMemosTargetTag = null
                        confirmDeleteAndMemosInput = ""
                    }
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }

    tagActionErrorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { tagActionErrorMessage = null },
            title = { Text(R.string.tag_action_failed.string) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { tagActionErrorMessage = null }) {
                    Text(R.string.confirm.string)
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        memosViewModel.loadTags()
        delay(0)
        showHeatMap = true
    }

    LaunchedEffect(currentSelectedTag, tagTree) {
        currentSelectedTag?.let { selectedTag ->
            ancestorPaths(selectedTag).forEach { ancestor ->
                expandedTagNodes[ancestor] = true
            }
        }
    }
}

private val TagActionItemShape = RoundedCornerShape(12.dp)
private const val TagActionItemContainerAlpha = 0.45f

@Composable
private fun TagActionMenuItem(
    title: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (destructive) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TagActionItemContainerAlpha)
    }
    val contentColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TagActionItemShape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                contentColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private data class TagTreeNode(
    val segment: String,
    val fullPath: String,
    var isRealTag: Boolean = false,
    val children: LinkedHashMap<String, TagTreeNode> = linkedMapOf()
)

private data class FlatTagEntry(
    val fullPath: String,
    val displayName: String,
    val depth: Int,
    val selectable: Boolean,
    val expandable: Boolean,
    val expanded: Boolean
)

private fun buildTagTree(tags: List<String>): List<TagTreeNode> {
    val roots = linkedMapOf<String, TagTreeNode>()

    tags.forEach { rawTag ->
        val normalizedTag = normalizeTagName(rawTag)
        if (normalizedTag.isEmpty()) {
            return@forEach
        }
        val segments = normalizedTag
            .split("/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return@forEach
        }

        var currentMap = roots
        var currentPath = ""
        var lastNode: TagTreeNode? = null

        segments.forEach { segment ->
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            val node = currentMap.getOrPut(segment) {
                TagTreeNode(
                    segment = segment,
                    fullPath = currentPath
                )
            }
            currentMap = node.children
            lastNode = node
        }
        lastNode?.isRealTag = true
    }

    return roots.values.toList()
}

private fun flattenTagTree(
    roots: List<TagTreeNode>,
    expandedState: Map<String, Boolean>
): List<FlatTagEntry> {
    val result = mutableListOf<FlatTagEntry>()

    fun visit(node: TagTreeNode, depth: Int) {
        val hasChildren = node.children.isNotEmpty()
        val expanded = expandedState[node.fullPath] ?: true

        result += FlatTagEntry(
            fullPath = node.fullPath,
            displayName = node.segment,
            depth = depth,
            selectable = node.isRealTag,
            expandable = hasChildren,
            expanded = expanded
        )

        if (hasChildren && expanded) {
            node.children.values.forEach { child ->
                visit(child, depth + 1)
            }
        }
    }

    roots.forEach { root ->
        visit(root, 0)
    }
    return result
}

private fun ancestorPaths(tag: String): List<String> {
    val normalizedTag = normalizeTagName(tag)
    if (normalizedTag.isEmpty()) {
        return emptyList()
    }

    val segments = normalizedTag.split("/").filter { it.isNotEmpty() }
    val paths = mutableListOf<String>()
    var currentPath = ""
    segments.dropLast(1).forEach { segment ->
        currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
        paths += currentPath
    }
    return paths
}

private fun renameTagWithPrefix(tag: String, oldPrefix: String, newPrefix: String): String {
    return when {
        tag == oldPrefix -> newPrefix
        tag.startsWith("$oldPrefix/") -> "$newPrefix/${tag.removePrefix("$oldPrefix/")}"
        else -> tag
    }
}
