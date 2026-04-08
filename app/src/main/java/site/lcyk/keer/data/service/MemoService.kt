package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.GeoMemoPoint
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.repository.AbstractMemoRepository

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MemoService @Inject constructor(
    private val accountService: AccountService,
    private val syncCoordinator: SyncCoordinator,
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getRepository(): AbstractMemoRepository {
        return accountService.getRepository()
    }

    val syncStatus: Flow<SyncStatus> = syncCoordinator.syncStatus

    val memos = accountService.currentAccount
        .flatMapLatest {
            accountService.getRepository().observeMemos()
        }
        .stateIn(serviceScope, SharingStarted.WhileSubscribed(5_000L), emptyList<MemoEntity>())

    val resources = accountService.currentAccount
        .flatMapLatest {
            accountService.getRepository().observeResources()
        }
        .stateIn(serviceScope, SharingStarted.WhileSubscribed(5_000L), emptyList<ResourceEntity>())

    val geoPoints = accountService.currentAccount
        .flatMapLatest {
            accountService.getRepository().observeMemoGeoPoints()
        }
        .stateIn(serviceScope, SharingStarted.WhileSubscribed(5_000L), emptyList<GeoMemoPoint>())

    val tags = accountService.currentAccount
        .flatMapLatest {
            accountService.getRepository().observeTags()
        }
        .stateIn(serviceScope, SharingStarted.WhileSubscribed(5_000L), emptyList<String>())

    fun observeResource(identifier: String): Flow<ResourceEntity?> {
        val normalizedIdentifier = identifier.trim()
        if (normalizedIdentifier.isEmpty()) {
            return flowOf(null)
        }
        return accountService.currentAccount.flatMapLatest {
            accountService.getRepository().observeResource(normalizedIdentifier)
        }
    }

    fun requestSync(
        trigger: SyncTrigger = SyncTrigger.AUTO,
        force: Boolean = false,
        domains: Set<SyncDomain> = SyncCoordinator.FULL_DOMAINS,
        groupId: String? = null,
        bypassCoalesce: Boolean = false,
    ) {
        syncCoordinator.requestSync(
            trigger = trigger,
            force = force,
            domains = domains,
            groupId = groupId,
            bypassCoalesce = bypassCoalesce,
        )
    }

    suspend fun sync(
        force: Boolean,
        trigger: SyncTrigger = if (force) SyncTrigger.MANUAL else SyncTrigger.AUTO,
        domains: Set<SyncDomain> = SyncCoordinator.FULL_DOMAINS,
        groupId: String? = null,
        bypassCoalesce: Boolean = false,
    ): ApiResponse<Unit> {
        return syncCoordinator.sync(
            force = force,
            trigger = trigger,
            domains = domains,
            groupId = groupId,
            bypassCoalesce = bypassCoalesce,
        )
    }
}
