package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsersSyncRunner @Inject constructor(
    private val accountService: AccountService,
) {
    suspend fun sync(): ApiResponse<Unit> {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return ApiResponse.Success(Unit)
        return remoteRepository.syncKnownUsers()
    }
}
