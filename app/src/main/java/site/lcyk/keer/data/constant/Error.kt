package site.lcyk.keer.data.constant

import site.lcyk.keer.R
import site.lcyk.keer.ext.string

class KeerException(string: String) : Exception(string) {
    companion object {
        val notLogin = KeerException("NOT_LOGIN")
        val invalidAccessToken = KeerException("INVALID_ACCESS_TOKEN")
        val accessTokenInvalid = KeerException("ACCESS_TOKEN_INVALID")
        val invalidParameter = KeerException("INVALID_PARAMETER")
        val invalidServer = KeerException("INVALID_SERVER")
    }

    override fun getLocalizedMessage(): String? {
        return when (this) {
            invalidAccessToken -> R.string.invalid_access_token.string
            accessTokenInvalid -> R.string.access_token_invalid_relogin.string
            invalidServer -> R.string.invalid_server.string
            else -> {
                super.getLocalizedMessage()
            }
        }
    }
}
