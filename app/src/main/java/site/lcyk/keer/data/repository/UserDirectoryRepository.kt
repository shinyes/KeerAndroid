package site.lcyk.keer.data.repository

import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.mapSuccess
import com.skydoves.sandwich.suspendOnSuccess
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import site.lcyk.keer.R
import site.lcyk.keer.data.api.KeerV2User
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.CollaboratorProfile
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.suspendOnNotLogin
import site.lcyk.keer.util.resolveAvatarUrl

@Singleton
class UserDirectoryRepository @Inject constructor(
    private val accountService: AccountService,
) {
    private val collaboratorAvatarMutex = Mutex()
    private val collaboratorAvatarInFlightIds = mutableSetOf<String>()
    private val lastCurrentUserLoadAtMillis = AtomicLong(0L)

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _friends = MutableStateFlow<List<User>>(emptyList())
    val friends: StateFlow<List<User>> = _friends.asStateFlow()

    private val _collaboratorProfiles = MutableStateFlow<Map<String, CollaboratorProfile>>(emptyMap())
    val collaboratorProfiles: StateFlow<Map<String, CollaboratorProfile>> = _collaboratorProfiles.asStateFlow()

    suspend fun loadCurrentUser(): ApiResponse<User> = withContext(Dispatchers.IO) {
        val loadedAt = System.currentTimeMillis()
        accountService.getRepository().getCurrentUser().suspendOnSuccess {
            _currentUser.value = data
            lastCurrentUserLoadAtMillis.set(loadedAt)
        }.suspendOnNotLogin {
            _currentUser.value = null
            lastCurrentUserLoadAtMillis.set(loadedAt)
        }
    }

    suspend fun loadCurrentUserIfStale(
        maxAgeMillis: Long = currentUserStaleThresholdMillis
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val current = _currentUser.value
        val lastLoadedAt = lastCurrentUserLoadAtMillis.get()
        val hasFreshCache = current != null && now - lastLoadedAt in 0 until maxAgeMillis
        if (!hasFreshCache) {
            loadCurrentUser()
        }
    }

    suspend fun refreshFriends(): ApiResponse<List<User>> = withContext(Dispatchers.IO) {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return@withContext ApiResponse.Success(emptyList())
        when (val response = remoteRepository.listFriends()) {
            is ApiResponse.Success -> {
                _friends.value = response.data
                updateCollaboratorProfilesFromFriends(response.data)
                response
            }
            else -> response.mapSuccess { emptyList<User>() }
        }
    }

    suspend fun addFriend(userIdentifier: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return@withContext ApiResponse.exception(
                IllegalStateException(R.string.current_account_no_friends.string)
            )
        when (val response = remoteRepository.addFriend(userIdentifier)) {
            is ApiResponse.Success -> {
                val next = (_friends.value + response.data)
                    .distinctBy(User::identifier)
                    .sortedBy { it.name.lowercase() }
                _friends.value = next
                updateCollaboratorProfilesFromFriends(next)
                ApiResponse.Success(Unit)
            }
            else -> response.mapSuccess { Unit }
        }
    }

    suspend fun removeFriend(userIdentifier: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return@withContext ApiResponse.exception(
                IllegalStateException(R.string.current_account_no_friends.string)
            )
        when (val response = remoteRepository.removeFriend(userIdentifier)) {
            is ApiResponse.Success -> {
                val normalized = normalizeCollaboratorUserID(userIdentifier)
                if (normalized != null) {
                    _friends.value = _friends.value.filterNot { friend -> friend.identifier == normalized }
                }
                ApiResponse.Success(Unit)
            }
            else -> response.mapSuccess { Unit }
        }
    }

    suspend fun prefetchCollaboratorAvatars(userIds: List<String>) = withContext(Dispatchers.IO) {
        val account = accountService.currentAccountValue() as? Account.KeerV2 ?: return@withContext
        val normalizedIds = userIds
            .asSequence()
            .mapNotNull(::normalizeCollaboratorUserID)
            .distinct()
            .toList()
        if (normalizedIds.isEmpty()) {
            return@withContext
        }

        val missingIds = collaboratorAvatarMutex.withLock {
            normalizedIds.filterNot { userId ->
                _collaboratorProfiles.value.containsKey(userId) || collaboratorAvatarInFlightIds.contains(userId)
            }.also { pendingIds ->
                collaboratorAvatarInFlightIds.addAll(pendingIds)
            }
        }
        if (missingIds.isEmpty()) {
            return@withContext
        }

        try {
            val api = accountService.createKeerV2Client(account.info.host, account.accountKey()).second
            val currentUserID = account.info.id.toString()
            val remoteIDs = missingIds.filterNot { userId -> userId == currentUserID }
            val remoteUsersByID = hashMapOf<String, KeerV2User>()
            if (remoteIDs.isNotEmpty()) {
                val unresolved = linkedSetOf<String>()
                remoteIDs.chunked(userBatchQueryChunkSize).forEach { chunk ->
                    val batch = api.getUsersBatch(chunk.joinToString(",")).getOrNull()
                    if (batch == null) {
                        unresolved += chunk
                    } else {
                        batch.users.forEach { user ->
                            val userID = normalizeCollaboratorUserID(user.name)
                            if (userID != null) {
                                remoteUsersByID[userID] = user
                            }
                        }
                        chunk.forEach { userID ->
                            if (!remoteUsersByID.containsKey(userID)) {
                                unresolved += userID
                            }
                        }
                    }
                }

                if (unresolved.isNotEmpty()) {
                    unresolved.chunked(userFallbackLookupChunkSize).forEach { chunk ->
                        val fallbackUsers = kotlinx.coroutines.coroutineScope {
                            chunk.map { userId ->
                                async { userId to api.getUser(userId).getOrNull() }
                            }.awaitAll()
                        }
                        fallbackUsers.forEach { (userId, user) ->
                            if (user != null) {
                                remoteUsersByID[userId] = user
                            }
                        }
                    }
                }
            }

            val current = _currentUser.value
            val fetched = missingIds.associateWith { userId ->
                if (userId == currentUserID) {
                    CollaboratorProfile(
                        id = userId,
                        name = current?.name?.takeIf { it.isNotBlank() }
                            ?: account.info.name.ifBlank { userId },
                        avatarUrl = resolveAvatarUrl(
                            account.info.host,
                            current?.avatarUrl.orEmpty().ifBlank { account.info.avatarUrl }
                        )
                    )
                } else {
                    val user = remoteUsersByID[userId]
                    CollaboratorProfile(
                        id = userId,
                        name = user?.username?.takeIf { it.isNotBlank() }
                            ?: userId,
                        avatarUrl = resolveAvatarUrl(account.info.host, user?.avatarUrl.orEmpty())
                    )
                }
            }

            collaboratorAvatarMutex.withLock {
                val merged = _collaboratorProfiles.value.toMutableMap()
                missingIds.forEach { userId ->
                    fetched[userId]?.let { profile ->
                        merged[userId] = profile
                    }
                }
                _collaboratorProfiles.value = merged
            }
        } finally {
            collaboratorAvatarMutex.withLock {
                collaboratorAvatarInFlightIds.removeAll(missingIds.toSet())
            }
        }
    }

    fun reset() {
        _currentUser.value = null
        _friends.value = emptyList()
        _collaboratorProfiles.value = emptyMap()
        lastCurrentUserLoadAtMillis.set(0L)
    }

    private suspend fun AccountService.currentAccountValue(): Account? {
        return currentAccount.first()
    }

    private fun normalizeCollaboratorUserID(raw: String): String? {
        val normalized = raw.trim()
            .substringAfterLast('/')
            .substringBefore('|')
            .trim()
        return normalized.ifEmpty { null }
    }

    private fun updateCollaboratorProfilesFromFriends(friends: List<User>) {
        val merged = _collaboratorProfiles.value.toMutableMap()
        friends.forEach { friend ->
            merged[friend.identifier] = CollaboratorProfile(
                id = friend.identifier,
                name = friend.name,
                avatarUrl = friend.avatarUrl
            )
        }
        _collaboratorProfiles.value = merged
    }

    private companion object {
        private const val userBatchQueryChunkSize = 200
        private const val userFallbackLookupChunkSize = 8
        private const val currentUserStaleThresholdMillis = 30_000L
    }
}
