package site.lcyk.keer.data.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.data.local.KeerDatabase
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.LocalAccount
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.repository.AbstractMemoRepository
import site.lcyk.keer.data.repository.KeerV2Repository
import site.lcyk.keer.data.repository.LocalDatabaseRepository
import site.lcyk.keer.data.repository.RemoteRepository
import site.lcyk.keer.data.repository.SyncingRepository
import site.lcyk.keer.data.security.AccountKeyManager
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import site.lcyk.keer.data.security.EncryptedMemoContentCodec
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class RepositorySession(
    val repository: AbstractMemoRepository,
    val remoteRepository: RemoteRepository?,
    val httpClient: OkHttpClient,
)

@Singleton
class RepositoryFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val database: KeerDatabase,
    private val fileStorage: FileStorage,
    private val attachmentEncryptionManager: AttachmentEncryptionManager,
    private val accountKeyManager: AccountKeyManager,
    private val authSessionManager: AuthSessionManager,
) {
    fun createSession(
        account: Account?,
        readMemoSyncCursor: suspend (String) -> String?,
        writeMemoSyncCursor: suspend (String, String) -> Unit,
        readUserSyncAnchor: suspend (String) -> Instant?,
        writeUserSyncAnchor: suspend (String, Instant) -> Unit,
        readSyncedUserIDs: suspend (String) -> List<String>,
        writeSyncedUserIDs: suspend (String, List<String>) -> Unit,
        onUserSynced: suspend (String, User) -> Unit,
    ): RepositorySession {
        return when (account) {
            null -> {
                RepositorySession(
                    repository = LocalDatabaseRepository(
                        database.memoDao(),
                        fileStorage,
                        Account.Local(LocalAccount()),
                    ),
                    remoteRepository = null,
                    httpClient = okHttpClient,
                )
            }

            is Account.Local -> {
                RepositorySession(
                    repository = LocalDatabaseRepository(
                        database.memoDao(),
                        fileStorage,
                        account,
                    ),
                    remoteRepository = null,
                    httpClient = okHttpClient,
                )
            }

            is Account.KeerV2 -> {
                val accountKey = account.accountKey()
                val clientBundle = authSessionManager.createKeerV2Client(
                    host = account.info.host,
                    accountKey = accountKey,
                )
                val remoteRepository = KeerV2Repository(
                    memosApi = clientBundle.api,
                    account = account,
                    okHttpClient = clientBundle.httpClient,
                    appContext = context.applicationContext,
                    readUserSyncAnchor = {
                        readUserSyncAnchor(accountKey)
                    },
                    writeUserSyncAnchor = { anchor ->
                        writeUserSyncAnchor(accountKey, anchor)
                    },
                    readSyncedUserIDs = {
                        readSyncedUserIDs(accountKey)
                    },
                    writeSyncedUserIDs = { userIDs ->
                        writeSyncedUserIDs(accountKey, userIDs)
                    },
                    attachmentEncryptionManager = attachmentEncryptionManager,
                    accountKeyManager = accountKeyManager,
                    memoContentCodec = EncryptedMemoContentCodec(
                        account = account,
                        accountKeyManager = accountKeyManager,
                    ),
                )
                RepositorySession(
                    repository = SyncingRepository(
                        database = database,
                        memoDao = database.memoDao(),
                        fileStorage = fileStorage,
                        remoteRepository = remoteRepository,
                        account = account,
                        readMemoSyncCursor = {
                            readMemoSyncCursor(accountKey)
                        },
                        writeMemoSyncCursor = { cursor ->
                            writeMemoSyncCursor(accountKey, cursor)
                        },
                        onUserSynced = { user ->
                            onUserSynced(accountKey, user)
                        },
                    ),
                    remoteRepository = remoteRepository,
                    httpClient = clientBundle.httpClient,
                )
            }
        }
    }
}
