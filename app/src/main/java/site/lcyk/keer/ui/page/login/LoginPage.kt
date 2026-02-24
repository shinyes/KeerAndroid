package site.lcyk.keer.ui.page.login

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PermIdentity
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.skydoves.sandwich.suspendOnSuccess
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.suspendOnErrorMessage
import site.lcyk.keer.ui.component.Markdown
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.LoginCompatibility
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LoginPage(
    navController: NavHostController
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lifecycleOwner = LocalLifecycleOwner.current
    val userStateViewModel = LocalUserState.current
    val snackbarState = remember { SnackbarHostState() }

    var host by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(userStateViewModel.host))
    }

    var accessToken by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    fun normalizedHost(): String {
        val trimmed = host.text.trim()
        return if (trimmed.contains("//")) trimmed else "https://$trimmed"
    }

    fun login() = coroutineScope.launch {
        if (host.text.isBlank() || accessToken.text.isBlank()) {
            snackbarState.showSnackbar(R.string.fill_login_form.string)
            return@launch
        }

        val sanitizedHost = normalizedHost()
        host = TextFieldValue(sanitizedHost)

        when (val compatibility = userStateViewModel.checkLoginCompatibility(sanitizedHost)) {
            LoginCompatibility.Supported -> Unit
            is LoginCompatibility.Unsupported -> {
                snackbarState.showSnackbar(compatibility.message)
                return@launch
            }
        }

        val resp = userStateViewModel.loginMemosWithAccessToken(
            host = sanitizedHost,
            accessToken = accessToken.text.trim()
        )
        resp.suspendOnSuccess {
            navController.navigate(RouteName.MEMOS) {
                popUpTo(navController.graph.id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
        .suspendOnErrorMessage {
            snackbarState.showSnackbar(it)
        }
    }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            SnackbarHost(hostState = snackbarState)
        },
        topBar = {
            LargeTopAppBar(
                title = { Text(text = if (userStateViewModel.currentUser != null) R.string.add_account.string else R.string.keer.string) },
                navigationIcon = {
                    if (userStateViewModel.currentUser != null) {
                        IconButton(onClick = {
                            navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = R.string.back.string)
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {},
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { login() },
                        containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                        elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                        text = { Text(R.string.add_account.string) },
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Outlined.Login,
                                contentDescription = R.string.add_account.string
                            )
                        }
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxHeight()
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Markdown(
                R.string.input_login_information.string,
                modifier = Modifier.padding(bottom = 20.dp),
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = 30.dp)
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoViewRequester)
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                coroutineScope.launch {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                    value = host,
                    onValueChange = { host = it },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Computer,
                            contentDescription = R.string.address.string
                        )
                    },
                    label = {
                        Text(R.string.host.string)
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    )
                )

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                coroutineScope.launch {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                    value = accessToken,
                    onValueChange = { accessToken = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.PermIdentity,
                            contentDescription = R.string.access_token.string
                        )
                    },
                    label = {
                        Text(R.string.access_token.string)
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { login() })
                )
            }
        }
    }
}
