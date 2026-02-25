package site.lcyk.keer.ui.component

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
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
                    memosNavController = memosNavController,
                    drawerState = drawerState,
                    onDrawerItemCloseRequested = onDrawerItemCloseRequested
                )
            }
        }
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
