package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.repository.SyncPullDomain
import site.lcyk.keer.data.repository.SyncPullResult
import site.lcyk.keer.data.repository.SyncStreamMode
import site.lcyk.keer.data.repository.UserGeneralSettingsRepository

@Singleton
class PullSyncEngine @Inject constructor(
    private val accountService: AccountService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
    private val userGeneralSettingsRepository: UserGeneralSettingsRepository,
    private val groupsSyncRunner: GroupsSyncRunner,
) {
    suspend fun runUnifiedTailSession(): ApiResponse<Unit> {
        return runStreamSession(
            // 全域统一由 mapStreamDomains 推导，避免与 FULL_STREAM_DOMAINS 重复维护。
            domains = mapStreamDomains(SyncDomain.entries.toSet()),
            groupScopes = emptyList(),
            mode = SyncStreamMode.TAIL,
            limit = TAIL_STREAM_PAGE_SIZE,
            reason = "tail",
        )
    }

    suspend fun run(
        domains: Set<SyncDomain>,
        groupId: String?,
        trigger: SyncTrigger = SyncTrigger.AUTO,
    ): ApiResponse<Unit> {
        return withContext(Dispatchers.IO) {
            if (domains.isEmpty()) {
                return@withContext ApiResponse.Success(Unit)
            }

            if (SyncDomain.PROFILE in domains) {
                val avatarResult = accountService.syncPendingAvatarIfNeeded()
                if (avatarResult !is ApiResponse.Success) {
                    return@withContext avatarResult
                }
            }

            val streamDomains = mapStreamDomains(domains)
            if (streamDomains.isEmpty()) {
                return@withContext ApiResponse.Success(Unit)
            }

            val normalizedGroupId = groupId
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.removePrefix("groups/")
            val groupScopes = if (SyncDomain.GROUPS in domains && !normalizedGroupId.isNullOrBlank()) {
                listOf("groups/$normalizedGroupId")
            } else {
                emptyList()
            }
            return@withContext runStreamSession(
                domains = streamDomains,
                groupScopes = groupScopes,
                mode = SyncStreamMode.BOOTSTRAP,
                limit = MANUAL_STREAM_PAGE_SIZE,
                reason = "manual:$trigger",
            )
        }
    }

    private suspend fun runStreamSession(
        domains: Set<SyncPullDomain>,
        groupScopes: List<String>,
        mode: SyncStreamMode,
        limit: Int,
        reason: String,
    ): ApiResponse<Unit> {
        return withContext(Dispatchers.IO) {
            if (domains.isEmpty()) {
                return@withContext ApiResponse.Success(Unit)
            }

        val remoteRepository = accountService.getRemoteRepository()
                ?: return@withContext ApiResponse.Success(Unit)
        val account = accountService.currentAccount.first()
                ?: return@withContext ApiResponse.Success(Unit)
        val accountKey = account.accountKey().trim()
        if (accountKey.isBlank()) {
                return@withContext ApiResponse.Success(Unit)
        }
            val memoRepository = accountService.getRepository()

            var currentCursor = resolveInitialStreamCursor(accountKey)
            // 初次全量（游标为 0）时服务端默认反向拉取（新→旧）：每批返回的是"已处理下界"，
            // 不能作为增量游标持久化；只有结束时 bootstrap_end 携带的增量游标（反向起点）才写入。
            val descending = currentCursor == "0"
            val streamResult = remoteRepository.streamSyncBootstrap(
                resumeCursor = currentCursor,
                domains = domains,
                groupScopes = groupScopes,
                mode = mode,
                limit = limit,
            ) { chunk ->
                when (
                    val applyResult = applyStreamChunk(
                        accountKey = accountKey,
                        reason = reason,
                        chunk = chunk,
                    )
                ) {
                    is ApiResponse.Success -> Unit
                    is ApiResponse.Failure.Error -> return@streamSyncBootstrap applyResult
                    is ApiResponse.Failure.Exception -> return@streamSyncBootstrap applyResult
                }

                val nextCursor = chunk.nextCursor.trim()
                if (!descending && nextCursor.isNotEmpty() && nextCursor != currentCursor) {
                    currentCursor = nextCursor
                    persistStreamCursor(accountKey, currentCursor)
                }
                ApiResponse.Success(Unit)
            }
            when (streamResult) {
                is ApiResponse.Success -> {
                    val finalCursor = streamResult.data.trim()
                    if (finalCursor.isNotEmpty() && finalCursor != currentCursor) {
                        persistStreamCursor(accountKey, finalCursor)
                    }
                    ApiResponse.Success(Unit)
                }
                is ApiResponse.Failure.Error -> streamResult
                is ApiResponse.Failure.Exception -> streamResult
            }
        }
    }

    private suspend fun applyStreamChunk(
        accountKey: String,
        reason: String,
        chunk: SyncPullResult,
    ): ApiResponse<Unit> {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return ApiResponse.Success(Unit)
        val memoRepository = accountService.getRepository()

        chunk.patches.settings.generalSettings?.let { settings ->
            when (
                val settingsApply = userGeneralSettingsRepository.applySyncedGeneralSettings(
                    settings = settings,
                    reason = "stream_$reason",
                )
            ) {
                is ApiResponse.Success -> Unit
                is ApiResponse.Failure.Error -> return settingsApply
                is ApiResponse.Failure.Exception -> return settingsApply
            }
        }
        when (
            val securityApply = remoteRepository.applySecuritySyncPatch(
                settingsPatch = chunk.patches.settings,
                groupKeysPatch = chunk.patches.groupKeys,
            )
        ) {
            is ApiResponse.Success -> Unit
            is ApiResponse.Failure.Error -> return securityApply
            is ApiResponse.Failure.Exception -> return securityApply
        }

        if (
            chunk.patches.groups.upserts.isNotEmpty() ||
            chunk.patches.groups.deletes.isNotEmpty() ||
            chunk.patches.groupMessages.groups.isNotEmpty()
        ) {
            groupsSyncRunner.applyStreamChunk(accountKey, chunk)
        }

        when (val memoApply = memoRepository.applyMemoStreamPatch(chunk.patches.memos, chunk.nextCursor)) {
            is ApiResponse.Success -> Unit
            is ApiResponse.Failure.Error -> return memoApply
            is ApiResponse.Failure.Exception -> return memoApply
        }
        when (val attachmentApply = memoRepository.applyAttachmentStreamPatch(chunk.patches.attachments)) {
            is ApiResponse.Success -> Unit
            is ApiResponse.Failure.Error -> return attachmentApply
            is ApiResponse.Failure.Exception -> return attachmentApply
        }
        return ApiResponse.Success(Unit)
    }

    private fun mapStreamDomains(domains: Set<SyncDomain>): Set<SyncPullDomain> {
        val streamDomains = linkedSetOf<SyncPullDomain>()
        if (SyncDomain.MEMOS in domains) {
            streamDomains += SyncPullDomain.MEMOS
            streamDomains += SyncPullDomain.ATTACHMENTS
        }
        if (SyncDomain.USERS in domains) {
            streamDomains += SyncPullDomain.USERS
            streamDomains += SyncPullDomain.FRIENDSHIPS
        }
        if (SyncDomain.PROFILE in domains) {
            streamDomains += SyncPullDomain.SETTINGS
            streamDomains += SyncPullDomain.SETTINGS_ENCRYPTION
        }
        if (SyncDomain.GROUPS in domains) {
            streamDomains += SyncPullDomain.GROUPS
            streamDomains += SyncPullDomain.GROUP_MESSAGES
            streamDomains += SyncPullDomain.GROUP_KEYS
            streamDomains += SyncPullDomain.ATTACHMENTS
            streamDomains += SyncPullDomain.USERS
        }
        return streamDomains
    }

    private suspend fun resolveInitialStreamCursor(accountKey: String): String {
        val streamCursor = accountLocalSettingsStore.readStreamSyncCursor(accountKey)
            ?.trim()
            .orEmpty()
        if (streamCursor.isNotEmpty()) {
            return streamCursor
        }
        val fallbackCursor = listOf(
            accountLocalSettingsStore.readMemoSyncCursor(accountKey),
            accountLocalSettingsStore.readGroupSyncCursor(accountKey),
            accountLocalSettingsStore.readProfileSyncCursor(accountKey),
        ).mapNotNull { cursor ->
            cursor
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) }
                ?.toLongOrNull()
        }.maxOrNull()
        return fallbackCursor?.toString() ?: "0"
    }

    private suspend fun persistStreamCursor(accountKey: String, cursor: String) {
        accountLocalSettingsStore.writeStreamSyncCursor(accountKey, cursor)
        accountLocalSettingsStore.writeProfileSyncCursor(accountKey, cursor)
    }

    private companion object {
        private const val MANUAL_STREAM_PAGE_SIZE = 180
        private const val TAIL_STREAM_PAGE_SIZE = 240
    }
}
