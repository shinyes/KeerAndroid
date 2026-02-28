package site.lcyk.keer.data.security

interface MemoContentCodec {
    fun encode(plainText: String): String
    fun decode(storedText: String): String
}

object PlaintextMemoContentCodec : MemoContentCodec {
    override fun encode(plainText: String): String = plainText

    override fun decode(storedText: String): String = storedText
}
