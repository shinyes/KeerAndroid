package site.lcyk.keer.data.service

enum class SyncTrigger {
    AUTO,
    APP_START,
    APP_FOREGROUND,
    AUTH_BOOTSTRAP,
    MUTATION,
    MANUAL,
}

fun SyncTrigger.isForegroundTrigger(): Boolean {
    return this == SyncTrigger.APP_START || this == SyncTrigger.APP_FOREGROUND
}

fun SyncTrigger.priority(): Int {
    return when (this) {
        SyncTrigger.MANUAL -> 6
        SyncTrigger.AUTH_BOOTSTRAP -> 5
        SyncTrigger.APP_START -> 4
        SyncTrigger.APP_FOREGROUND -> 3
        SyncTrigger.MUTATION -> 2
        SyncTrigger.AUTO -> 1
    }
}
