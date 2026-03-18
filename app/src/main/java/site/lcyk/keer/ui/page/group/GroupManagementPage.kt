package site.lcyk.keer.ui.page.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.ModeEdit
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoGroupType
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.model.isExploreEntryVisible
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.ui.page.common.navigateToGroupChatPage
import site.lcyk.keer.util.exploreGroupEntryId
import site.lcyk.keer.viewmodel.GroupManagementViewModel
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupManagementPage(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null,
    viewModel: GroupManagementViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val userStateViewModel = LocalUserState.current
    val currentUser = userStateViewModel.currentUser
    val friends by userStateViewModel.friends.collectAsState()
    val generalSettings by userStateViewModel.generalSettings.collectAsState()
    val pendingVisibilityOverrides = remember { mutableStateMapOf<String, Boolean>() }

    val groups by viewModel.groups.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val managedGroups = remember(groups) {
        groups.filter { group -> group.type == MemoGroupType.GROUP }
    }

    var createDialogVisible by remember { mutableStateOf(false) }
    var inviteTargetGroup by remember { mutableStateOf<MemoGroup?>(null) }
    var editTargetGroup by remember { mutableStateOf<MemoGroup?>(null) }
    var deleteTargetGroup by remember { mutableStateOf<MemoGroup?>(null) }

    LaunchedEffect(currentUser?.identifier) {
        userStateViewModel.refreshFriends()
        viewModel.refreshGroups()
    }

    PageScaffold(
        title = R.string.group_management.string,
        drawerState = drawerState,
        onMenuButtonOpenRequested = onMenuButtonOpenRequested,
        onBack = { navController.popBackStackIfLifecycleIsResumed(lifecycleOwner) },
        actions = {
            IconButton(onClick = { createDialogVisible = true }) {
                Icon(Icons.Outlined.Add, contentDescription = R.string.create_group.string)
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (loading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                item {
                    Text(
                        text = errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }

            if (!loading && managedGroups.isEmpty()) {
                item {
                    Text(
                        text = R.string.no_groups_joined.string,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(managedGroups, key = { it.id }) { group ->
                val isCreator = normalizeUserIdentifier(group.creatorId) == currentUser?.identifier
                val exploreEntryId = remember(group.id) { exploreGroupEntryId(group.id) }
                val switchPending = pendingVisibilityOverrides.containsKey(exploreEntryId)
                val visibleInExplore = pendingVisibilityOverrides[exploreEntryId]
                    ?: generalSettings.isExploreEntryVisible(exploreEntryId)
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "#${group.id}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (isCreator) {
                                Text(
                                    text = R.string.group_creator_label.string,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (group.description.isNotBlank()) {
                            Text(
                                text = group.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = navController.context.getString(R.string.group_members_count, group.members.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = R.string.show_in_explore_list.string,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Switch(
                                checked = visibleInExplore,
                                enabled = !switchPending,
                                onCheckedChange = { checked ->
                                    pendingVisibilityOverrides[exploreEntryId] = checked
                                    scope.launch {
                                        val response = userStateViewModel.updateExploreEntryVisibility(
                                            entryId = exploreEntryId,
                                            visibleInExplore = checked,
                                        )
                                        pendingVisibilityOverrides.remove(exploreEntryId)
                                        if (response !is com.skydoves.sandwich.ApiResponse.Success) {
                                            viewModel.refreshGroups()
                                        }
                                    }
                                }
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    navController.navigateToGroupChatPage(group.id)
                                }
                            ) {
                                Icon(Icons.Outlined.Tag, contentDescription = null)
                                Text(
                                    text = R.string.group_chat.string,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }

                            TextButton(
                                onClick = { editTargetGroup = group }
                            ) {
                                Icon(Icons.Outlined.ModeEdit, contentDescription = null)
                                Text(
                                    text = R.string.edit.string,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }

                            TextButton(
                                onClick = { inviteTargetGroup = group }
                            ) {
                                Icon(Icons.Outlined.GroupAdd, contentDescription = null)
                                Text(
                                    text = R.string.invite_friend.string,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }

                            TextButton(
                                onClick = { deleteTargetGroup = group }
                            ) {
                                Text(
                                    if (isCreator) {
                                        R.string.delete_group.string
                                    } else {
                                        R.string.leave_group.string
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (createDialogVisible) {
        GroupEditorDialog(
            title = R.string.create_group,
            confirmText = R.string.create,
            onDismiss = { createDialogVisible = false },
            onConfirm = { name, description ->
                if (name.isBlank()) {
                    return@GroupEditorDialog
                }
                scope.launch {
                    if (viewModel.createGroup(name, description)) {
                        createDialogVisible = false
                    }
                }
            }
        )
    }

    inviteTargetGroup?.let { target ->
        InviteFriendDialog(
            group = target,
            friends = friends,
            onDismiss = { inviteTargetGroup = null },
            onInvite = { friend ->
                scope.launch {
                    if (viewModel.addGroupMember(target.id, friend.identifier)) {
                        inviteTargetGroup = null
                    }
                }
            }
        )
    }

    editTargetGroup?.let { target ->
        GroupEditorDialog(
            title = R.string.edit_group,
            initialName = target.name,
            initialDescription = target.description,
            confirmText = R.string.save,
            onDismiss = { editTargetGroup = null },
            onConfirm = { name, description ->
                if (name.isBlank()) {
                    return@GroupEditorDialog
                }
                scope.launch {
                    if (viewModel.updateGroup(target.id, name, description)) {
                        editTargetGroup = null
                    }
                }
            }
        )
    }

    deleteTargetGroup?.let { target ->
        val isCreator = normalizeUserIdentifier(target.creatorId) == currentUser?.identifier
        AlertDialog(
            onDismissRequest = { deleteTargetGroup = null },
            title = {
                Text(
                    if (isCreator) {
                        R.string.delete_group.string
                    } else {
                        R.string.leave_group.string
                    }
                )
            },
            text = {
                Text(
                    if (isCreator) {
                        R.string.delete_group_confirm.string
                    } else {
                        R.string.leave_group_confirm.string
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            if (viewModel.deleteOrLeaveGroup(target.id)) {
                                deleteTargetGroup = null
                            }
                        }
                    }
                ) {
                    Text(R.string.confirm.string)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetGroup = null }) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }
}

@Composable
private fun GroupEditorDialog(
    title: Int,
    confirmText: Int,
    initialName: String = "",
    initialDescription: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember(title, initialName) { mutableStateOf(initialName) }
    var description by remember(title, initialDescription) { mutableStateOf(initialDescription) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.string) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(R.string.group_name.string) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(R.string.group_description.string) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, description) }) {
                Text(confirmText.string)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(R.string.cancel.string)
            }
        }
    )
}

@Composable
private fun InviteFriendDialog(
    group: MemoGroup,
    friends: List<User>,
    onDismiss: () -> Unit,
    onInvite: (User) -> Unit
) {
    val memberIds = remember(group.members) {
        group.members
            .map { member -> normalizeUserIdentifier(member.userId) }
            .filter { memberId -> memberId.isNotEmpty() }
            .toSet()
    }
    val invitableFriends = remember(friends, memberIds) {
        friends
            .filter { friend -> normalizeUserIdentifier(friend.identifier) !in memberIds }
            .sortedBy { friend -> friend.name.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(R.string.invite_friend.string) },
        text = {
            if (invitableFriends.isEmpty()) {
                Text(
                    text = R.string.no_invitable_friends.string,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(invitableFriends, key = { friend -> friend.identifier }) { friend ->
                        TextButton(
                            onClick = { onInvite(friend) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(friend.name)
                                Text(
                                    text = friend.identifier,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(R.string.cancel.string)
            }
        }
    )
}

private fun normalizeUserIdentifier(raw: String): String {
    return raw.trim().substringAfterLast('/')
}
