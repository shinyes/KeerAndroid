package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import javax.inject.Inject
import javax.inject.Singleton
import site.lcyk.keer.data.model.SyncDomain

@Singleton
class PullSyncEngine @Inject constructor(
    private val profileSyncRunner: ProfileSyncRunner,
    private val usersSyncRunner: UsersSyncRunner,
    private val groupsSyncRunner: GroupsSyncRunner,
    private val memosSyncRunner: MemosSyncRunner,
) {

    private val executionOrder = listOf(
        SyncDomain.PROFILE,
        SyncDomain.USERS,
        SyncDomain.GROUPS,
        SyncDomain.MEMOS,
    )

    suspend fun run(
        domains: Set<SyncDomain>,
        groupId: String?,
    ): ApiResponse<Unit> {
        for (domain in executionOrder) {
            if (domain !in domains) {
                continue
            }
            val result = when (domain) {
                SyncDomain.PROFILE -> profileSyncRunner.sync()
                SyncDomain.USERS -> usersSyncRunner.sync()
                SyncDomain.GROUPS -> groupsSyncRunner.sync(groupId)
                SyncDomain.MEMOS -> memosSyncRunner.sync()
            }
            if (result !is ApiResponse.Success) {
                return result
            }
        }
        return ApiResponse.Success(Unit)
    }
}

