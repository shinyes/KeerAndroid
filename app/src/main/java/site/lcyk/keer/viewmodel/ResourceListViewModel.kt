package site.lcyk.keer.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.service.MemoService
import javax.inject.Inject

@HiltViewModel
class ResourceListViewModel @Inject constructor(
    private val memoService: MemoService
): ViewModel() {
    private var remoteSnapshot: List<ResourceEntity> = emptyList()
    private var localResources: List<ResourceEntity> = emptyList()

    var resources = mutableStateListOf<ResourceEntity>()
        private set

    init {
        viewModelScope.launch {
            memoService.resources.collectLatest { latestResources ->
                localResources = latestResources
                publishMergedResources()
            }
        }
    }

    fun loadResources() = viewModelScope.launch {
        memoService.getRepository().listResources().suspendOnSuccess {
            remoteSnapshot = data.sortedByDescending { it.date }
            publishMergedResources()
        }
    }

    private fun publishMergedResources() {
        val merged = mergeRemoteAndLocalResources(
            remoteSnapshot = remoteSnapshot,
            localResources = localResources,
        )
        resources.clear()
        resources.addAll(merged)
    }

    private fun mergeRemoteAndLocalResources(
        remoteSnapshot: List<ResourceEntity>,
        localResources: List<ResourceEntity>,
    ): List<ResourceEntity> {
        if (remoteSnapshot.isEmpty()) {
            return localResources.sortedByDescending { it.date }
        }
        val localByIdentifier = localResources.associateBy { it.identifier }
        val localByRemoteId = localResources
            .mapNotNull { resource -> resource.remoteId?.let { remoteId -> remoteId to resource } }
            .toMap()
        val mergedRemote = remoteSnapshot.map { resource ->
            val local = localByIdentifier[resource.identifier]
                ?: resource.remoteId?.let(localByRemoteId::get)
            if (local == null) {
                resource
            } else {
                resource.copy(
                    localUri = local.localUri ?: resource.localUri,
                    thumbnailLocalUri = local.thumbnailLocalUri ?: resource.thumbnailLocalUri,
                    memoId = local.memoId ?: resource.memoId,
                )
            }
        }
        val localDrafts = localResources.filter { it.remoteId == null }
        return (mergedRemote + localDrafts)
            .distinctBy { it.identifier }
            .sortedByDescending { it.date }
    }
}
