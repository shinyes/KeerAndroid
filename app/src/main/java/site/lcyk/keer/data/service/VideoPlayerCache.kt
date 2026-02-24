package site.lcyk.keer.data.service

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object VideoPlayerCache {
    private const val CACHE_DIR_NAME = "video_player_cache"
    private const val MAX_CACHE_SIZE_BYTES: Long = 512L * 1024L * 1024L

    @Volatile
    private var cache: Cache? = null

    fun get(context: Context): Cache {
        return cache ?: synchronized(this) {
            cache ?: createCache(context.applicationContext).also { cache = it }
        }
    }

    private fun createCache(context: Context): Cache {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE_BYTES)
        val databaseProvider = StandaloneDatabaseProvider(context)
        return SimpleCache(cacheDir, evictor, databaseProvider)
    }
}
