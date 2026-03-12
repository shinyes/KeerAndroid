package site.lcyk.keer.ui.page.login

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PermIdentity
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.skydoves.sandwich.suspendOnSuccess
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.suspendOnErrorMessage
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.LoginCompatibility
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LoginPage(
    navController: NavHostController
) {
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val userStateViewModel = LocalUserState.current
    val snackbarState = remember { SnackbarHostState() }
    val hostRequester = remember { BringIntoViewRequester() }
    val usernameRequester = remember { BringIntoViewRequester() }
    val displayNameRequester = remember { BringIntoViewRequester() }
    val passwordRequester = remember { BringIntoViewRequester() }
    val confirmPasswordRequester = remember { BringIntoViewRequester() }

    var host by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(userStateViewModel.host))
    }

    var username by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    var password by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    var displayName by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    var confirmPassword by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    var registerMode by rememberSaveable {
        mutableStateOf(false)
    }

    fun normalizedHost(): String {
        val trimmed = host.text.trim()
        return if (trimmed.contains("//")) trimmed else "https://$trimmed"
    }

    fun submit() = coroutineScope.launch {
        if (host.text.isBlank() || username.text.isBlank() || password.text.isBlank()) {
            snackbarState.showSnackbar(R.string.fill_login_form.string)
            return@launch
        }
        if (registerMode && confirmPassword.text != password.text) {
            snackbarState.showSnackbar(R.string.passwords_do_not_match.string)
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

        val resp = if (registerMode) {
            userStateViewModel.registerMemosAccount(
                host = sanitizedHost,
                username = username.text.trim(),
                password = password.text,
                displayName = displayName.text.trim()
            )
        } else {
            userStateViewModel.loginMemosWithPassword(
                host = sanitizedHost,
                username = username.text.trim(),
                password = password.text
            )
        }
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

    fun fieldModifier(requester: BringIntoViewRequester): Modifier {
        return Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .onFocusEvent { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        requester.bringIntoView()
                    }
                }
            }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarState)
        },
        topBar = {
            TopAppBar(
                title = { Text(text = if (userStateViewModel.currentUser != null) R.string.add_account.string else R.string.keer.string) },
                navigationIcon = {
                    if (userStateViewModel.currentUser != null) {
                        IconButton(onClick = {
                            navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = R.string.back.string)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 30.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                modifier = fieldModifier(hostRequester),
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
                modifier = fieldModifier(usernameRequester),
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.PermIdentity,
                        contentDescription = R.string.username.string
                    )
                },
                label = {
                    Text(R.string.username.string)
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                )
            )

            if (registerMode) {
                OutlinedTextField(
                    modifier = fieldModifier(displayNameRequester),
                    value = displayName,
                    onValueChange = { displayName = it },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Badge,
                            contentDescription = R.string.display_name.string
                        )
                    },
                    label = {
                        Text(R.string.display_name.string)
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    )
                )
            }

            OutlinedTextField(
                modifier = fieldModifier(passwordRequester),
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = R.string.password.string
                    )
                },
                label = {
                    Text(R.string.password.string)
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = if (registerMode) ImeAction.Next else ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { submit() })
            )

            if (registerMode) {
                OutlinedTextField(
                    modifier = fieldModifier(confirmPasswordRequester),
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = R.string.confirm_password.string
                        )
                    },
                    label = {
                        Text(R.string.confirm_password.string)
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() })
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            BottomAppBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                tonalElevation = 0.dp,
                actions = {
                    TextButton(
                        onClick = {
                            registerMode = !registerMode
                            confirmPassword = TextFieldValue()
                        }
                    ) {
                        Text(
                            if (registerMode) {
                                R.string.switch_to_sign_in.string
                            } else {
                                R.string.switch_to_register.string
                            }
                        )
                    }
                },
            )
            ExtendedFloatingActionButton(
                modifier = Modifier.align(Alignment.End),
                onClick = { submit() },
                text = { Text(if (registerMode) R.string.register.string else R.string.sign_in.string) },
                icon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.Login,
                        contentDescription = if (registerMode) R.string.register.string else R.string.sign_in.string
                    )
                }
            )
        }
    }
}
