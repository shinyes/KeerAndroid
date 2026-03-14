package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemosSyncRunner @Inject constructor(
    private val accountService: AccountService,
) {
    suspend fun sync(): ApiResponse<Unit> {
        return accountService.getRepository().sync()
    }
}
