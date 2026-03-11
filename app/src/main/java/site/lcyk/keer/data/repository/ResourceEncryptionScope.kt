package site.lcyk.keer.data.repository

sealed interface ResourceEncryptionScope {
    data object Account : ResourceEncryptionScope

    data class Collaborators(
        val userIds: List<String>,
    ) : ResourceEncryptionScope

    data class Group(
        val groupId: String,
    ) : ResourceEncryptionScope
}
