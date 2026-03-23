package site.lcyk.keer.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemoGroupType
import site.lcyk.keer.data.model.isTagVisibleInDrawer
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.navigateToGroupChatPage
import site.lcyk.keer.ui.page.common.navigateToColumnPage
import site.lcyk.keer.ui.page.common.navigateToMemosPage
import site.lcyk.keer.ui.page.common.navigateToTagPage
import site.lcyk.keer.ui.page.common.navigateToTopLevel
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.isValidTagName
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SideDrawer(
    memosNavController: NavHostController,
    drawerState: DrawerState? = null,
    onDrawerItemCloseRequested: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val weekDays = remember {
        val day = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        List(DayOfWeek.entries.size) { index ->
            day.plus(index.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }
    val scope = rememberCoroutineScope()
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val generalSettings by userStateViewModel.generalSettings.collectAsState()
    val currentUser = userStateViewModel.currentUser
    val drawerUiState by memosViewModel.visibleDrawerState.collectAsStateWithLifecycle()
    val joinedGroups = drawerUiState.drawerGroups
    val groupIdAliases = drawerUiState.groupIdAliases
    val hasExplore = currentAccount !is Account.Local
    val navBackStackEntry by memosNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val drawerTitle = remember(currentAccount) {
        currentAccount?.toUser()?.name?.ifBlank { R.string.keer.string } ?: R.string.keer.string
    }
    var exploreExpanded by rememberSaveable { mutableStateOf(false) }
    val expandedTagNodes = remember { mutableStateMapOf<String, Boolean>() }
    var activeTagActionTarget by remember { mutableStateOf<String?>(null) }
    var renameTargetTag by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTargetTag by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAndMemosTargetTag by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAndMemosInput by remember { mutableStateOf("") }
    var tagActionErrorMessage by remember { mutableStateOf<String?>(null) }
    var tagActionInProgress by remember { mutableStateOf(false) }
    val rawTags = drawerUiState.tags
    val availableTags = remember(rawTags, generalSettings) {
        normalizeTagList(
            rawTags
                .filterNot(::isCollaboratorTag)
                .filterNot(::isQuoteTag)
        )
            .filter { tag -> generalSettings.isTagVisibleInDrawer(tag) }
    }
    val tagTree = remember(availableTags) { buildTagTree(availableTags) }
    val currentSelectedTag = remember(navBackStackEntry) {
        navBackStackEntry
            ?.arguments
            ?.getString("tag")
            ?.let(Uri::decode)
            ?.let(::normalizeTagName)
    }
    val currentSelectedGroupId = remember(navBackStackEntry, groupIdAliases) {
        val selected = navBackStackEntry
            ?.arguments
            ?.getString("groupId")
            ?.let(Uri::decode)
        if (selected.isNullOrBlank()) {
            null
        } else {
            groupIdAliases.firstOrNull { it.localId == selected }?.remoteId ?: selected
        }
    }
    val visibleTagEntries = remember(tagTree, expandedTagNodes.toMap()) {
        flattenTagTree(tagTree, expandedTagNodes)
    }
    val hasUnreadGroupMessages = remember(joinedGroups, currentSelectedGroupId) {
        joinedGroups.any { group ->
            group.hasUnreadMessages && currentSelectedGroupId != group.id
        }
    }
    val currentSelectedColumnId = remember(navBackStackEntry) {
        navBackStackEntry
            ?.arguments
            ?.getString("columnId")
            ?.let(Uri::decode)
    }
    val visibleColumns = drawerUiState.visibleColumns
    val statsStartDate = remember(currentUser) {
        currentUser?.startDate?.atZone(OffsetDateTime.now().offset)?.toLocalDate()
    }
    val today = LocalDate.now()
    val statsDays = remember(statsStartDate, today) {
        statsStartDate?.let { ChronoUnit.DAYS.between(it, today) } ?: 0
    }
    val statsText = remember(drawerUiState.matrix, drawerUiState.tags, statsDays) {
        formatDrawerStatsText(
            memoCount = drawerUiState.matrix.sumOf { it.count },
            tagCount = drawerUiState.tags.size,
            days = statsDays,
            memoLabel = R.string.memo.string,
            tagLabel = R.string.tag.string,
            dayLabel = R.string.day.string,
        )
    }

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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = drawerTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                memosNavController.navigateToTopLevel(RouteName.SETTINGS)
                                onDrawerItemCloseRequested?.invoke()
                                drawerState?.close()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = R.string.settings.string,
                            tint = if (
                                isSelected(RouteName.SETTINGS) ||
                                isSelected(RouteName.CONFIG) ||
                                isSelected(RouteName.COLUMN_CONFIG) ||
                                isSelected(RouteName.TAG_CONFIG)
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Text(
                    text = statsText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(start = 112.dp, end = 56.dp)
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(end = 5.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(modifier = Modifier.height(HEATMAP_TOP_LABEL_HEIGHT + HEATMAP_ROW_SPACING))
                    Column(
                        modifier = Modifier.fillMaxHeight(),
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
                }
                Heatmap(timeline = drawerUiState.heatmapTimeline)
            }
        }

        item {
            NavigationDrawerItem(
                label = { Text(R.string.memos.string) },
                icon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                selected = isSelected(RouteName.MEMOS),
                onClick = {
                    scope.launch {
                        memosNavController.navigateToMemosPage()
                        onDrawerItemCloseRequested?.invoke()
                        drawerState?.close()
                    }
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
        visibleColumns.forEach { column ->
            item("drawer_column_${column.id}") {
                NavigationDrawerItem(
                    label = { Text(column.name) },
                    icon = { Icon(Icons.Outlined.Bookmarks, contentDescription = null) },
                    selected = isSelected("${RouteName.COLUMN}/{columnId}") &&
                        currentSelectedColumnId == column.id,
                    onClick = {
                        scope.launch {
                            memosNavController.navigateToColumnPage(column.id)
                            onDrawerItemCloseRequested?.invoke()
                            drawerState?.close()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
        if (hasExplore) {
            item {
                NavigationDrawerItem(
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = R.string.explore.string,
                                modifier = Modifier.weight(1f)
                            )
                            if (hasUnreadGroupMessages) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                            }
                            IconButton(
                                onClick = {
                                    exploreExpanded = !exploreExpanded
                                }
                            ) {
                                Icon(
                                    imageVector = if (exploreExpanded) {
                                        Icons.Filled.ExpandMore
                                    } else {
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                                    },
                                    contentDescription = if (exploreExpanded) {
                                        R.string.collapse.string
                                    } else {
                                        R.string.expand.string
                                    }
                                )
                            }
                        }
                    },
                    icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                    selected = isSelected(RouteName.EXPLORE),
                    onClick = {
                        scope.launch {
                            memosNavController.navigateToTopLevel(RouteName.EXPLORE)
                            onDrawerItemCloseRequested?.invoke()
                            drawerState?.close()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
            if (exploreExpanded) {
                joinedGroups.forEach { group ->
                    item("drawer_group_${group.id}") {
                        NavigationDrawerItem(
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = group.name,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (
                                        group.hasUnreadMessages &&
                                        currentSelectedGroupId != group.id
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.error)
                                        )
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    if (group.type == MemoGroupType.DIRECT) {
                                        Icons.Outlined.Person
                                    } else {
                                        Icons.Outlined.Group
                                    },
                                    contentDescription = null
                                )
                            },
                            selected = isSelected("${RouteName.GROUP_CHAT}?groupId={groupId}") &&
                                    currentSelectedGroupId == group.id,
                            onClick = {
                                scope.launch {
                                    memosNavController.navigateToGroupChatPage(group.id)
                                    onDrawerItemCloseRequested?.invoke()
                                    drawerState?.close()
                                }
                            },
                            modifier = Modifier.padding(start = 30.dp, end = 8.dp)
                        )
                    }
                }
            }
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
                    enabled = true,
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
                                            memosNavController.navigateToTagPage(renamedSelected)
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
                                            memosNavController.navigateToMemosPage()
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
                                            memosNavController.navigateToMemosPage()
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
