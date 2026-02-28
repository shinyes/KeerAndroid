package site.lcyk.keer.ui.page.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarSettingsPage(
    navController: NavHostController
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val settings by context.settingsDataStore.data.collectAsState(initial = Settings())
    val localAvatarUri = settings.usersList
        .firstOrNull { user -> user.accountKey == settings.currentUser }
        ?.settings
        ?.avatarUri
        .orEmpty()
    val accountAvatarUrl = when (val account = currentAccount) {
        is site.lcyk.keer.data.model.Account.KeerV2 -> resolveAvatarUrl(account.info.host, account.info.avatarUrl)
        else -> null
    }
    val displayAvatarModel = if (localAvatarUri.isNotBlank()) localAvatarUri else accountAvatarUrl
    val imageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { userStateViewModel.okHttpClient }))
        }
        .build()

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

    LaunchedEffect(currentAccount?.accountKey()) {
        userStateViewModel.loadCurrentUser()
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(R.string.settings.string) },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = R.string.back.string)
                    }
                },
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                Text(
                    text = R.string.settings.string,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp, 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            item {
                SettingItem(
                    icon = Icons.Outlined.AccountCircle,
                    text = R.string.avatar.string,
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
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                            )
                        }
                    },
                    onClick = {
                        avatarPickerLauncher.launch(arrayOf("image/*"))
                    }
                )
            }
            item {
                SettingItem(
                    icon = Icons.Outlined.Image,
                    text = R.string.set_avatar.string,
                    onClick = {
                        avatarPickerLauncher.launch(arrayOf("image/*"))
                    }
                )
            }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = R.string.avatar_hint.string,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            item {
                SettingItem(
                    icon = Icons.Outlined.Group,
                    text = R.string.group_management.string,
                    onClick = {
                        navController.navigate(RouteName.GROUP_MANAGEMENT)
                    }
                )
            }
        }
    }
}

private fun resolveAvatarUrl(host: String, avatarUrl: String): String? {
    if (avatarUrl.isBlank()) {
        return null
    }
    if (avatarUrl.toHttpUrlOrNull() != null || "://" in avatarUrl) {
        return avatarUrl
    }
    val baseUrl = host.toHttpUrlOrNull() ?: return avatarUrl
    return runCatching {
        baseUrl.toUrl().toURI().resolve(avatarUrl).toString()
    }.getOrDefault(avatarUrl)
}
