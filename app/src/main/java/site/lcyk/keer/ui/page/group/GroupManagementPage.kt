package site.lcyk.keer.ui.page.group

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.ModeEdit
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.GroupManagementViewModel
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupManagementPage(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null,
    viewModel: GroupManagementViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val userStateViewModel = LocalUserState.current
    val currentUser = userStateViewModel.currentUser

    val groups by viewModel.groups.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var createDialogVisible by remember { mutableStateOf(false) }
    var joinDialogVisible by remember { mutableStateOf(false) }
    var editTargetGroup by remember { mutableStateOf<MemoGroup?>(null) }
    var deleteTargetGroup by remember { mutableStateOf<MemoGroup?>(null) }

    LaunchedEffect(currentUser?.identifier) {
        viewModel.refreshGroups()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(R.string.group_management.string) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (drawerState != null) {
                                onMenuButtonOpenRequested?.invoke()
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch { drawerState.open() }
                            } else {
                                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (drawerState != null) Icons.Filled.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (drawerState != null) R.string.menu.string else R.string.back.string
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { joinDialogVisible = true }) {
                        Icon(Icons.Outlined.GroupAdd, contentDescription = R.string.join_group.string)
                    }
                    IconButton(onClick = { createDialogVisible = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = R.string.create_group.string)
                    }
                }
            )
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

            if (!loading && groups.isEmpty()) {
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

            items(groups, key = { it.id }) { group ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            if (group.creatorId == currentUser?.identifier) {
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
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                onClick = {
                                    navController.navigate("${RouteName.GROUP_CHAT}?groupId=${Uri.encode(group.id)}")
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
                                onClick = { deleteTargetGroup = group }
                            ) {
                                Text(
                                    if (group.creatorId == currentUser?.identifier) {
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

    if (joinDialogVisible) {
        JoinGroupDialog(
            onDismiss = { joinDialogVisible = false },
            onConfirm = { groupId ->
                if (groupId.isBlank()) {
                    return@JoinGroupDialog
                }
                scope.launch {
                    if (viewModel.joinGroup(groupId)) {
                        joinDialogVisible = false
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
        AlertDialog(
            onDismissRequest = { deleteTargetGroup = null },
            title = {
                Text(
                    if (target.creatorId == currentUser?.identifier) {
                        R.string.delete_group.string
                    } else {
                        R.string.leave_group.string
                    }
                )
            },
            text = {
                Text(
                    if (target.creatorId == currentUser?.identifier) {
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
private fun JoinGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (groupId: String) -> Unit
) {
    var groupId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(R.string.join_group.string) },
        text = {
            OutlinedTextField(
                value = groupId,
                onValueChange = { groupId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(R.string.group_id.string) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(groupId)
                }
            ) {
                Text(R.string.confirm.string)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(R.string.cancel.string)
            }
        }
    )
}
