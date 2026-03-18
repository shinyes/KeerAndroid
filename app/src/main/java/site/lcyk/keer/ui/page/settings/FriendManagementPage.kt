package site.lcyk.keer.ui.page.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.User
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.data.model.MemoGroupType
import site.lcyk.keer.data.model.isExploreEntryVisible
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.ui.page.common.navigateToGroupChatPage
import site.lcyk.keer.util.exploreDirectEntryId
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FriendManagementPage(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val friends by userStateViewModel.friends.collectAsState()
    val generalSettings by userStateViewModel.generalSettings.collectAsState()
    val joinedGroups by userStateViewModel.joinedGroups.collectAsState()
    val currentUserIdentifier = normalizeCollaboratorId(userStateViewModel.currentUser?.identifier.orEmpty())
    val pendingVisibilityOverrides = remember { mutableStateMapOf<String, Boolean>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<User?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentAccount?.accountKey()) {
        errorMessage = null
        if (currentAccount is Account.KeerV2) {
            val response = userStateViewModel.refreshFriends()
            if (response !is ApiResponse.Success) {
                errorMessage = response.getErrorMessage()
            }
        }
    }

    PageScaffold(
        title = R.string.friends.string,
        drawerState = drawerState,
        onMenuButtonOpenRequested = onMenuButtonOpenRequested,
        onBack = { navController.popBackStackIfLifecycleIsResumed(lifecycleOwner) },
        actions = {
            if (currentAccount is Account.KeerV2) {
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = R.string.add_friend.string)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (currentAccount !is Account.KeerV2) {
                item {
                    Text(
                        text = R.string.friends_remote_only.string,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
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

                if (friends.isEmpty()) {
                    item {
                        Text(
                            text = R.string.no_friends_yet.string,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(friends, key = { friend -> friend.identifier }) { friend ->
                    val exploreEntryId = remember(friend.identifier) {
                        exploreDirectEntryId(friend.identifier)
                    }
                    val switchPending = pendingVisibilityOverrides.containsKey(exploreEntryId)
                    val visibleInExplore = pendingVisibilityOverrides[exploreEntryId]
                        ?: generalSettings.isExploreEntryVisible(exploreEntryId)
                    val hasLinkedDirectChat = remember(joinedGroups, friend.identifier, currentUserIdentifier) {
                        joinedGroups.any { group ->
                            group.type == MemoGroupType.DIRECT &&
                                group.members.any { member ->
                                    val normalizedMemberId = normalizeCollaboratorId(member.userId)
                                    normalizedMemberId.isNotEmpty() &&
                                        normalizedMemberId != currentUserIdentifier &&
                                        normalizedMemberId == normalizeCollaboratorId(friend.identifier)
                                }
                        }
                    }
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = friend.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = friend.identifier,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = visibleInExplore,
                                    enabled = currentAccount is Account.KeerV2 && !switchPending,
                                    onCheckedChange = { checked ->
                                        pendingVisibilityOverrides[exploreEntryId] = checked
                                        scope.launch {
                                            when (val response = userStateViewModel.updateExploreEntryVisibility(exploreEntryId, checked)) {
                                                is ApiResponse.Success -> {
                                                    errorMessage = null
                                                    pendingVisibilityOverrides.remove(exploreEntryId)
                                                }
                                                else -> {
                                                    pendingVisibilityOverrides.remove(exploreEntryId)
                                                    errorMessage = response.getErrorMessage()
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            if (!hasLinkedDirectChat) {
                                Text(
                                    text = R.string.show_in_explore_list_hint_private.string,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            when (val response = userStateViewModel.openDirectChat(friend.identifier)) {
                                                is ApiResponse.Success -> {
                                                    errorMessage = null
                                                    navController.navigateToGroupChatPage(response.data.id)
                                                }
                                                else -> {
                                                    errorMessage = response.getErrorMessage()
                                                }
                                            }
                                        }
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 10.dp,
                                        vertical = 2.dp
                                    )
                                ) {
                                    Text(R.string.private_chat.string)
                                }
                                TextButton(
                                    onClick = { deleteTarget = friend },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 10.dp,
                                        vertical = 2.dp
                                    )
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = null)
                                    Text(
                                        text = R.string.remove_friend.string,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddFriendDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { userIdentifier ->
                scope.launch {
                    val response = userStateViewModel.addFriend(userIdentifier)
                    if (response is ApiResponse.Success) {
                        errorMessage = null
                        showAddDialog = false
                    } else {
                        errorMessage = response.getErrorMessage()
                    }
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(R.string.remove_friend.string) },
            text = { Text(target.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val identifier = target.identifier
                        deleteTarget = null
                        scope.launch {
                            val response = userStateViewModel.removeFriend(identifier)
                            if (response !is ApiResponse.Success) {
                                errorMessage = response.getErrorMessage()
                            } else {
                                errorMessage = null
                            }
                        }
                    }
                ) {
                    Text(R.string.confirm.string)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }
}

@Composable
private fun AddFriendDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var userIdentifier by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(R.string.add_friend.string) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = userIdentifier,
                    onValueChange = {
                        userIdentifier = it
                        validationMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(R.string.friend_user_identifier.string) },
                    singleLine = true
                )
                validationMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = userIdentifier.trim()
                    if (normalized.isEmpty()) {
                        validationMessage = R.string.friend_user_identifier_required.string
                        return@TextButton
                    }
                    onConfirm(normalized)
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
