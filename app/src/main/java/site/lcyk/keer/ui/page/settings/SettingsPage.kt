package site.lcyk.keer.ui.page.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.MemosIcon
import site.lcyk.keer.ui.component.rememberAuthorizedImageLoader
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.ui.page.common.navigateToAccountPage
import site.lcyk.keer.ui.page.common.navigateToAddAccountPage
import site.lcyk.keer.ui.page.common.navigateToColumnConfigPage
import site.lcyk.keer.ui.page.common.navigateToDebugLogsPage
import site.lcyk.keer.ui.page.common.navigateSingleTop
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.util.resolveAvatarUrl
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    val userStateViewModel = LocalUserState.current
    val rootNavController = LocalRootNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val accounts by userStateViewModel.accounts.collectAsState()
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val generalSettings by userStateViewModel.generalSettings.collectAsState()
    val currentAvatarUri by userStateViewModel.currentAvatarUri.collectAsState()
    val currentAccountKey = currentAccount?.accountKey()
    val currentUser = userStateViewModel.currentUser
    var showEditGestureDialog by remember { mutableStateOf(false) }
    val accountAvatarUrl = when (val account = currentAccount) {
        is Account.KeerV2 -> resolveAvatarUrl(account.info.host, account.info.avatarUrl)
        else -> null
    }
    val displayAvatarModel = if (currentAvatarUri.isNotBlank()) currentAvatarUri else accountAvatarUrl
    val imageLoader = rememberAuthorizedImageLoader()
    val currentEditGesture = generalSettings.memoEditGesture
    val currentColumnsCount = generalSettings.memoColumns.size
    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        scope.launch {
            userStateViewModel.uploadCurrentUserAvatar(uri)
        }
    }
    val exportPersonalMemosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            userStateViewModel.exportPersonalMemos(uri)
                .onSuccess { summary ->
                    val message = if (summary.failedCount > 0) {
                        resources.getString(
                            R.string.personal_memos_export_result_with_failed,
                            summary.exportedCount,
                            summary.exportedAttachmentCount,
                            summary.failedCount,
                        )
                    } else {
                        resources.getString(
                            R.string.personal_memos_export_success,
                            summary.exportedCount,
                            summary.exportedAttachmentCount,
                        )
                    }
                    Toast.makeText(
                        context,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { throwable ->
                    Toast.makeText(
                        context,
                        throwable.localizedMessage ?: R.string.personal_memos_export_failed.string,
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
    val importPersonalMemosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        scope.launch {
            userStateViewModel.importPersonalMemos(uri)
                .onSuccess { summary ->
                    Toast.makeText(
                        context,
                        resources.getString(
                            R.string.personal_memos_import_result,
                            summary.imported,
                            summary.importedAttachmentCount,
                            summary.failed,
                            summary.skipped,
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { throwable ->
                    Toast.makeText(
                        context,
                        throwable.localizedMessage ?: R.string.personal_memos_import_failed.string,
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showPersonalMemosTransferDialog by remember { mutableStateOf(false) }
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmNewPassword by rememberSaveable { mutableStateOf("") }
    var passwordChangeError by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordChangeLoading by remember { mutableStateOf(false) }
    var showCleanupOrphansDialog by remember { mutableStateOf(false) }
    var cleanupOrphansLoading by remember { mutableStateOf(false) }
    val currentPasswordRequiredMessage = stringResource(R.string.current_password_required)
    val newPasswordRequiredMessage = stringResource(R.string.new_password_required)
    val passwordsDoNotMatchMessage = stringResource(R.string.passwords_do_not_match)
    val showAdminSection = currentAccount is Account.KeerV2 && (currentUser?.isAdmin == true)

    LaunchedEffect(currentAccount?.accountKey()) {
        userStateViewModel.loadCurrentUser()
    }

    PageScaffold(
        title = R.string.settings.string,
        drawerState = drawerState,
        onMenuButtonOpenRequested = onMenuButtonOpenRequested,
        onBack = if (drawerState == null) {
            { navController.popBackStackIfLifecycleIsResumed(lifecycleOwner) }
        } else {
            null
        }
    ) { innerPadding ->
        LazyColumn(contentPadding = innerPadding) {
            item {
                Text(
                    R.string.more.string,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp, 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (currentAccount is Account.KeerV2) {
                item {
                    SettingItem(
                        icon = Icons.Outlined.ImportExport,
                        text = R.string.personal_memos_transfer.string
                    ) {
                        showPersonalMemosTransferDialog = true
                    }
                }
            }

            item {
                SettingItem(
                    icon = Icons.Outlined.AccountCircle,
                    text = R.string.set_avatar.string,
                    trailingIcon = {
                        if (displayAvatarModel.isNullOrBlank()) {
                            Icon(
                                imageVector = Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            AsyncImage(
                                model = displayAvatarModel,
                                imageLoader = imageLoader,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                ) {
                    avatarPickerLauncher.launch(arrayOf("image/*"))
                }
            }

            item {
                SettingItem(
                    icon = Icons.Outlined.PersonAdd,
                    text = R.string.friends.string
                ) {
                    navController.navigateSingleTop(RouteName.FRIENDS)
                }
            }

            item {
                SettingItem(
                    icon = Icons.Outlined.Bookmarks,
                    text = R.string.column_config.string,
                    trailingIcon = {
                        if (currentColumnsCount > 0) {
                            Text(
                                text = currentColumnsCount.toString(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                ) {
                    navController.navigateToColumnConfigPage()
                }
            }

            item {
                SettingItem(
                    icon = Icons.Outlined.Group,
                    text = R.string.group_management.string
                ) {
                    navController.navigateSingleTop(RouteName.GROUP_MANAGEMENT)
                }
            }

            item {
                SettingItem(
                    icon = Icons.Outlined.PhotoLibrary,
                    text = R.string.resources.string
                ) {
                    navController.navigateSingleTop(RouteName.RESOURCE)
                }
            }

            item {
                SettingItem(
                    icon = Icons.Outlined.Inventory2,
                    text = R.string.archived.string
                ) {
                    navController.navigateSingleTop(RouteName.ARCHIVED)
                }
            }

            if (currentAccount is Account.KeerV2) {
                item {
                    SettingItem(
                        icon = Icons.Outlined.Lock,
                        text = R.string.change_password.string
                    ) {
                        passwordChangeError = null
                        showChangePasswordDialog = true
                    }
                }
            }

            item {
                SettingItem(
                    icon = Icons.Outlined.Edit,
                    text = R.string.edit_gesture.string,
                    trailingIcon = {
                        Text(
                            text = currentEditGesture.titleResource.string,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                ) {
                    showEditGestureDialog = true
                }
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            item {
                Text(
                    R.string.settings.string,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp, 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            accounts.forEach { account ->
                when (account) {
                    is Account.KeerV2 -> item {
                        AccountSettingItem(
                            icon = MemosIcon,
                            text = account.info.name,
                            accountKey = account.accountKey(),
                            currentAccountKey = currentAccountKey,
                            onClick = {
                                rootNavController.navigateToAccountPage(account.accountKey())
                            }
                        )
                    }
                    is Account.Local -> Unit
                }
            }

            item {
                SettingItem(icon = Icons.Outlined.PersonAdd, text = R.string.add_account.string) {
                    rootNavController.navigateToAddAccountPage()
                }
            }

            item {
                SettingItem(icon = Icons.Outlined.BugReport, text = R.string.debug_logs.string) {
                    rootNavController.navigateToDebugLogsPage()
                }
            }

            if (showAdminSection) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }

                item {
                    Text(
                        R.string.admin.string,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp, 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                item {
                    SettingItem(
                        icon = Icons.Outlined.Inventory2,
                        text = R.string.cleanup_orphan_files.string
                    ) {
                        showCleanupOrphansDialog = true
                    }
                }
            }

        }
    }

    if (showPersonalMemosTransferDialog) {
        AlertDialog(
            onDismissRequest = { showPersonalMemosTransferDialog = false },
            title = { Text(R.string.personal_memos_transfer_menu_title.string) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showPersonalMemosTransferDialog = false
                            exportPersonalMemosLauncher.launch("keer-personal-memos-${System.currentTimeMillis()}.zip")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = R.string.export_personal_memos.string,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(
                        onClick = {
                            showPersonalMemosTransferDialog = false
                            importPersonalMemosLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/json",
                                    "text/plain",
                                    "*/*"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = R.string.import_personal_memos.string,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPersonalMemosTransferDialog = false }) {
                    Text(R.string.close.string)
                }
            }
        )
    }

    if (showEditGestureDialog) {
        AlertDialog(
            onDismissRequest = { showEditGestureDialog = false },
            title = { Text(R.string.edit_gesture.string) },
            text = {
                LazyColumn {
                    items(MemoEditGesture.entries.size) { index ->
                        val gesture = MemoEditGesture.entries[index]
                        TextButton(
                            onClick = {
                                showEditGestureDialog = false
                                scope.launch {
                                    userStateViewModel.updateMemoEditGesture(gesture)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = gesture.titleResource.string,
                                color = if (gesture == currentEditGesture) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEditGestureDialog = false }) {
                    Text(R.string.close.string)
                }
            }
        )
    }

    if (showCleanupOrphansDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!cleanupOrphansLoading) {
                    showCleanupOrphansDialog = false
                }
            },
            title = { Text(R.string.cleanup_orphan_files.string) },
            text = { Text(R.string.cleanup_orphan_files_confirm.string) },
            confirmButton = {
                TextButton(
                    enabled = !cleanupOrphansLoading,
                    onClick = {
                        scope.launch {
                            cleanupOrphansLoading = true
                            when (val response = userStateViewModel.cleanupOrphanFiles()) {
                                is com.skydoves.sandwich.ApiResponse.Success -> {
                                    Toast.makeText(
                                        context,
                                        resources.getString(
                                            R.string.cleanup_orphan_files_result,
                                            response.data.scannedKeys,
                                            response.data.deletedKeys,
                                            response.data.failedKeys,
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    showCleanupOrphansDialog = false
                                }
                                else -> {
                                    Toast.makeText(
                                        context,
                                        response.getErrorMessage(),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            cleanupOrphansLoading = false
                        }
                    }
                ) {
                    Text(R.string.confirm.string)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !cleanupOrphansLoading,
                    onClick = { showCleanupOrphansDialog = false }
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }

    if (showChangePasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!passwordChangeLoading) {
                    showChangePasswordDialog = false
                    currentPassword = ""
                    newPassword = ""
                    confirmNewPassword = ""
                    passwordChangeError = null
                }
            },
            title = { Text(R.string.change_password.string) },
            text = {
                Column {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = {
                            currentPassword = it
                            passwordChangeError = null
                        },
                        label = { Text(R.string.current_password.string) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            passwordChangeError = null
                        },
                        label = { Text(R.string.new_password.string) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = confirmNewPassword,
                        onValueChange = {
                            confirmNewPassword = it
                            passwordChangeError = null
                        },
                        label = { Text(R.string.confirm_password.string) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    passwordChangeError?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            currentPassword.isBlank() -> {
                                passwordChangeError = currentPasswordRequiredMessage
                            }
                            newPassword.isBlank() -> {
                                passwordChangeError = newPasswordRequiredMessage
                            }
                            newPassword != confirmNewPassword -> {
                                passwordChangeError = passwordsDoNotMatchMessage
                            }
                            else -> {
                                passwordChangeLoading = true
                                scope.launch {
                                    val response = userStateViewModel.changePassword(
                                        currentPassword = currentPassword,
                                        newPassword = newPassword
                                    )
                                    passwordChangeLoading = false
                                    if (response is com.skydoves.sandwich.ApiResponse.Success) {
                                        showChangePasswordDialog = false
                                        currentPassword = ""
                                        newPassword = ""
                                        confirmNewPassword = ""
                                        passwordChangeError = null
                                        Toast.makeText(
                                            context,
                                            R.string.password_change_success.string,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        passwordChangeError = response.getErrorMessage()
                                    }
                                }
                            }
                        }
                    },
                    enabled = !passwordChangeLoading
                ) {
                    Text(
                        if (passwordChangeLoading) {
                            R.string.loading.string
                        } else {
                            R.string.confirm.string
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showChangePasswordDialog = false
                        currentPassword = ""
                        newPassword = ""
                        confirmNewPassword = ""
                        passwordChangeError = null
                    },
                    enabled = !passwordChangeLoading
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }
}

@Composable
private fun AccountSettingItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    accountKey: String,
    currentAccountKey: String?,
    onClick: () -> Unit
) {
    SettingItem(
        icon = icon,
        text = text,
        trailingIcon = {
            if (currentAccountKey == accountKey) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = R.string.selected.string,
                    modifier = Modifier.padding(start = 16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = onClick
    )
}

private val MemoEditGesture.titleResource: Int
    get() = when (this) {
        MemoEditGesture.NONE -> R.string.edit_gesture_none
        MemoEditGesture.SINGLE -> R.string.edit_gesture_single
        MemoEditGesture.DOUBLE -> R.string.edit_gesture_double
        MemoEditGesture.LONG -> R.string.edit_gesture_long
    }
