package site.lcyk.keer.data.security

interface MemoContentCodec {
    fun encode(plainText: String): String
    fun decode(storedText: String): String
    fun isEncoded(storedText: String): Boolean = false
}
