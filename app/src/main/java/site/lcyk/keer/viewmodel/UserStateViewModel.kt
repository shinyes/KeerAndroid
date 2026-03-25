package site.lcyk.keer.viewmodel

import android.net.Uri
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.GroupIdAlias
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.StorageCleanupSummary
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.model.UserGeneralSettings
import site.lcyk.keer.data.model.withExploreEntryVisibility
import site.lcyk.keer.data.repository.JoinedGroupRepository
import site.lcyk.keer.data.repository.UserDirectoryRepository
import site.lcyk.keer.data.service.AccountLocalSettingsStore
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.MemoExportResult
import site.lcyk.keer.data.service.MemoImportResult
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.data.service.MemoTransferOperation
import site.lcyk.keer.data.service.MemoTransferStage
import site.lcyk.keer.data.service.MemoTransferService
import site.lcyk.keer.data.service.SyncTrigger
import site.lcyk.keer.data.repository.UserGeneralSettingsRepository
import site.lcyk.keer.ext.string
import okhttp3.OkHttpClient
import javax.inject.Inject

data class MemoTransferTaskState(
    val running: Boolean = false,
    val operation: MemoTransferOperation? = null,
    val stage: MemoTransferStage? = null,
    val completed: Int? = null,
    val total: Int? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class UserStateViewModel @Inject constructor(
    private val accountService: AccountService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
    private val memoService: MemoService,
    private val memoTransferService: MemoTransferService,
    private val userGeneralSettingsRepository: UserGeneralSettingsRepository,
    private val userDirectoryRepository: UserDirectoryRepository,
    private val joinedGroupRepository: JoinedGroupRepository,
) : ViewModel() {

    var currentUser: User? by mutableStateOf(null)
        private set

    var host: String = ""
        private set
    val okHttpClient: OkHttpClient get() = accountService.httpClient
    val collaboratorProfiles = userDirectoryRepository.collaboratorProfiles
    val friends = userDirectoryRepository.friends
    val accounts = accountService.accounts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val currentAccount = accountService.currentAccount.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val generalSettings = userGeneralSettingsRepository.observeCurrentGeneralSettings()
        .stateIn(viewModelScope, SharingStarted.Lazily, UserGeneralSettings())
    val currentAvatarUri = accountLocalSettingsStore.observeCurrentAvatarUri()
        .stateIn(viewModelScope, SharingStarted.Lazily, "")
    val joinedGroups: StateFlow<List<MemoGroup>> = joinedGroupRepository.observeJoinedGroups()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val groupIdAliases: StateFlow<List<GroupIdAlias>> = joinedGroupRepository.observeGroupIdAliases()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val memoTransferMutex = Mutex()
    private val _memoTransferTaskState = MutableStateFlow(MemoTransferTaskState())
    val memoTransferTaskState: StateFlow<MemoTransferTaskState> = _memoTransferTaskState.asStateFlow()

    init {
        viewModelScope.launch {
            userDirectoryRepository.currentUser.collectLatest { user ->
                currentUser = user
            }
        }
        viewModelScope.launch {
            accountService.currentAccount.collectLatest {
                host = when(it) {
                    is Account.KeerV2 -> it.info.host
                    else -> ""
                }
                userDirectoryRepository.reset()
                if (it != null) {
                    userGeneralSettingsRepository.refreshCurrentGeneralSettings(
                        reason = "account_changed",
                    )
                }
            }
        }
    }

    suspend fun loadCurrentUser(): ApiResponse<User> = withContext(Dispatchers.IO) {
        val response = userDirectoryRepository.loadCurrentUser()
        if (response is ApiResponse.Success) {
            memoService.requestSync(
                trigger = SyncTrigger.AUTO,
                force = false,
                domains = setOf(SyncDomain.PROFILE)
            )
        }
        response
    }

    suspend fun loadCurrentUserIfStale(
        maxAgeMillis: Long = 30_000L
    ) = withContext(Dispatchers.IO) {
        userDirectoryRepository.loadCurrentUserIfStale(maxAgeMillis)
    }

    suspend fun hasAnyAccount(): Boolean = withContext(Dispatchers.IO) {
        accountService.accounts.first().isNotEmpty()
    }

    suspend fun loginMemosWithPassword(
        host: String,
        username: String,
        password: String
    ): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        try {
            when (val response = accountService.signInKeerV2WithPassword(host, username, password)) {
                is ApiResponse.Success -> completeAuthenticatedSession()
                is ApiResponse.Failure.Error -> response
                is ApiResponse.Failure.Exception -> response
            }
        } catch (e: Throwable) {
            ApiResponse.exception(e)
        }
    }

    suspend fun registerMemosAccount(
        host: String,
        username: String,
        password: String,
    ): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        try {
            when (val response = accountService.registerKeerV2WithPassword(host, username, password)) {
                is ApiResponse.Success -> completeAuthenticatedSession()
                is ApiResponse.Failure.Error -> response
                is ApiResponse.Failure.Exception -> response
            }
        } catch (e: Throwable) {
            ApiResponse.exception(e)
        }
    }

    suspend fun logout(accountKey: String) = withContext(Dispatchers.IO) {
        if (currentAccount.first()?.accountKey() == accountKey) {
            currentUser = null
        }
        accountService.removeAccount(accountKey)
    }

    suspend fun switchAccount(accountKey: String) = withContext(Dispatchers.IO) {
        accountService.switchAccount(accountKey)
        userGeneralSettingsRepository.refreshCurrentGeneralSettings(
            forceNetwork = true,
            reason = "switch_account",
        )
        loadCurrentUser()
    }

    suspend fun uploadCurrentUserAvatar(uri: Uri): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        val response = accountService.uploadCurrentUserAvatar(uri)
        if (response is ApiResponse.Success) {
            memoService.requestSync(
                trigger = SyncTrigger.MUTATION,
                force = false,
                domains = setOf(SyncDomain.PROFILE)
            )
        }
        loadCurrentUser()
        response
    }

    suspend fun refreshFriends(): ApiResponse<List<User>> = withContext(Dispatchers.IO) {
        userDirectoryRepository.refreshFriends()
    }

    suspend fun addFriend(userIdentifier: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        userDirectoryRepository.addFriend(userIdentifier)
    }

    suspend fun removeFriend(userIdentifier: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        userDirectoryRepository.removeFriend(userIdentifier)
    }

    suspend fun changePassword(
        currentPassword: String,
        newPassword: String
    ): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return@withContext ApiResponse.exception(IllegalStateException(R.string.current_account_no_password_change.string))
        remoteRepository.changePassword(currentPassword, newPassword)
    }

    suspend fun refreshGeneralSettings(): ApiResponse<UserGeneralSettings> = withContext(Dispatchers.IO) {
        userGeneralSettingsRepository.refreshCurrentGeneralSettings(
            forceNetwork = true,
            reason = "manual_refresh",
        )
    }

    suspend fun updateMemoEditGesture(
        gesture: site.lcyk.keer.data.model.MemoEditGesture
    ): ApiResponse<UserGeneralSettings> = withContext(Dispatchers.IO) {
        userGeneralSettingsRepository.updateMemoEditGesture(gesture)
    }

    suspend fun updateMemoColumns(
        columns: List<site.lcyk.keer.data.model.MemoColumnConfig>
    ): ApiResponse<UserGeneralSettings> = withContext(Dispatchers.IO) {
        userGeneralSettingsRepository.updateMemoColumns(columns)
    }

    suspend fun updateExploreEntryVisibility(
        entryId: String,
        visibleInExplore: Boolean,
    ): ApiResponse<UserGeneralSettings> = withContext(Dispatchers.IO) {
        val current = generalSettings.value
        userGeneralSettingsRepository.updateCurrentUserGeneralSettings(
            current.withExploreEntryVisibility(
                entryId = entryId,
                visibleInExplore = visibleInExplore,
            )
        )
    }

    suspend fun updateTagDrawerVisibility(
        tag: String,
        visibleInDrawer: Boolean,
    ): ApiResponse<UserGeneralSettings> = withContext(Dispatchers.IO) {
        userGeneralSettingsRepository.updateTagDrawerVisibility(
            tag = tag,
            visibleInDrawer = visibleInDrawer,
        )
    }

    suspend fun cleanupOrphanFiles(): ApiResponse<StorageCleanupSummary> = withContext(Dispatchers.IO) {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return@withContext ApiResponse.exception(IllegalStateException(R.string.current_account_no_admin_ops.string))
        remoteRepository.cleanupOrphanFiles()
    }

    suspend fun exportPersonalMemos(destinationUri: Uri): Result<MemoExportResult> = withContext(Dispatchers.IO) {
        memoTransferMutex.withLock {
            _memoTransferTaskState.value = MemoTransferTaskState(
                running = true,
                operation = MemoTransferOperation.EXPORT,
                stage = MemoTransferStage.PREPARING,
            )
            try {
                memoTransferService.exportPersonalMemos(destinationUri) { progress ->
                    _memoTransferTaskState.value = MemoTransferTaskState(
                        running = true,
                        operation = progress.operation,
                        stage = progress.stage,
                        completed = progress.completed,
                        total = progress.total,
                    )
                }
            } finally {
                _memoTransferTaskState.value = MemoTransferTaskState()
            }
        }
    }

    suspend fun importPersonalMemos(sourceUri: Uri): Result<MemoImportResult> = withContext(Dispatchers.IO) {
        memoTransferMutex.withLock {
            _memoTransferTaskState.value = MemoTransferTaskState(
                running = true,
                operation = MemoTransferOperation.IMPORT,
                stage = MemoTransferStage.PREPARING,
            )
            try {
                val result = memoTransferService.importPersonalMemos(sourceUri) { progress ->
                    _memoTransferTaskState.value = MemoTransferTaskState(
                        running = true,
                        operation = progress.operation,
                        stage = progress.stage,
                        completed = progress.completed,
                        total = progress.total,
                    )
                }
                val summary = result.getOrNull() ?: return@withContext result
                if (summary.imported > 0) {
                    memoService.requestSync(
                        trigger = SyncTrigger.MUTATION,
                        force = true,
                        domains = setOf(SyncDomain.MEMOS)
                    )
                }
                result
            } finally {
                _memoTransferTaskState.value = MemoTransferTaskState()
            }
        }
    }

    fun observeAccountAvatarUri(accountKey: String) =
        accountLocalSettingsStore.observeUserAvatarUri(accountKey)

    suspend fun openDirectChat(userIdentifier: String): ApiResponse<MemoGroup> = withContext(Dispatchers.IO) {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return@withContext ApiResponse.exception(IllegalStateException(R.string.current_account_no_direct_chats.string))
        when (val response = remoteRepository.createDirectGroup(userIdentifier)) {
            is ApiResponse.Success -> {
                joinedGroupRepository.upsertGroup(response.data)
                response
            }
            else -> response
        }
    }

    suspend fun prefetchCollaboratorAvatars(userIds: List<String>) = withContext(Dispatchers.IO) {
        userDirectoryRepository.prefetchCollaboratorAvatars(userIds)
    }

    private suspend fun completeAuthenticatedSession(): ApiResponse<Unit> {
        userGeneralSettingsRepository.refreshCurrentGeneralSettings(
            forceNetwork = true,
            reason = "authenticated_session",
        )
        return when (val response = loadCurrentUser()) {
            is ApiResponse.Success -> {
                memoService.requestSync(
                    trigger = SyncTrigger.AUTH_BOOTSTRAP,
                    force = false,
                    domains = site.lcyk.keer.data.service.SyncCoordinator.FULL_DOMAINS
                )
                ApiResponse.Success(Unit)
            }
            is ApiResponse.Failure.Error -> response
            is ApiResponse.Failure.Exception -> response
        }
    }

}

val LocalUserState =
    compositionLocalOf<UserStateViewModel> { error(R.string.user_state_not_found.string) }
