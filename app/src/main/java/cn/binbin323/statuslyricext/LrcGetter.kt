package cn.binbin323.statuslyricext

import android.content.Context
import android.media.MediaMetadata
import android.text.TextUtils
import android.util.Log
import cn.binbin323.statuslyricext.provider.BinLrcProvider
import cn.binbin323.statuslyricext.provider.ILrcProvider
import cn.binbin323.statuslyricext.provider.KugouProvider
import cn.binbin323.statuslyricext.provider.NeteaseProvider
import cn.binbin323.statuslyricext.provider.QQMusicProvider
import cn.binbin323.statuslyricext.provider.utils.LyricSearchUtil
import cn.zhaiyifan.lyric.LyricUtils
import cn.zhaiyifan.lyric.model.Lyric
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object LrcGetter {

    private const val TAG = "LrcGetter"
    private val HEX = "0123456789ABCDEF".toCharArray()
    private val sNeteaseProvider = NeteaseProvider()
    private val sBinProvider = BinLrcProvider()
    private val sQQMusicProvider = QQMusicProvider()
    private val sKugouProvider = KugouProvider()
    private val sExecutor = Executors.newCachedThreadPool()

    fun getLyric(context: Context, metadata: MediaMetadata): Lyric? {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (TextUtils.isEmpty(title)) return null

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val cacheKey = "$title,$artist,$album,$duration"
        val cacheFile = File(context.cacheDir, sha1Hex(cacheKey) + ".lrc")

        // Try cache first
        if (cacheFile.exists()) {
            val cached = LyricUtils.parseLyric(cacheFile, "UTF-8")
            if (cached.sentenceList.isNotEmpty()) {
                Log.i(TAG, "cache hit (${cached.sentenceList.size} lines): $title")
                return cached
            }
            // Corrupted cache – delete and re-fetch
            cacheFile.delete()
        }

        // Query all four providers in parallel, pick the best match (lowest distance)
        val providers: List<Pair<String, ILrcProvider>> = listOf(
            "netease" to sNeteaseProvider,
            "bin" to sBinProvider,
            "qqmusic" to sQQMusicProvider,
            "kugou" to sKugouProvider
        )
        val futures: List<Pair<String, Future<ILrcProvider.LyricResult?>>> = providers.map { (name, provider) ->
            name to sExecutor.submit<ILrcProvider.LyricResult?> {
                try {
                    provider.getLyric(metadata)
                } catch (e: Exception) {
                    Log.w(TAG, "$name provider failed for: $title", e)
                    null
                }
            }
        }

        val result = futures
            .mapNotNull { (name, future) ->
                try {
                    val res = future.get(5, TimeUnit.SECONDS)
                    if (res != null && LyricSearchUtil.isLyricContent(res.mLyric)) {
                        Log.i(TAG, "$name provider returned result (distance=${res.mDistance}) for: $title")
                        name to res
                    } else {
                        Log.i(TAG, "$name provider returned no valid lyric for: $title")
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "waiting for provider result failed for: $title", e)
                    null
                }
            }
            .minByOrNull { (_, res) -> res.mDistance }
            ?.also { (name, res) ->
                Log.i(TAG, "best provider: $name (distance=${res.mDistance}) for: $title")
            }
            ?.second

        if (result == null || !LyricSearchUtil.isLyricContent(result.mLyric)) {
            Log.i(TAG, "no valid lyric for: $title")
            return null
        }

        // Parse directly from string (no intermediate file I/O path required)
        val lyric = LyricUtils.parseLyric(result.mLyric, "UTF-8")
        if (lyric.sentenceList.isEmpty()) {
            Log.i(TAG, "empty sentence list after parse for: $title")
            return null
        }

        // Persist to cache asynchronously-safe (write may fail silently)
        try {
            FileOutputStream(cacheFile).use { out ->
                out.write(result.mLyric.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to write lyric cache", e)
        }

        Log.i(TAG, "fetched ${lyric.sentenceList.size} lines for: $title")
        return lyric
    }

    private fun sha1Hex(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            buildString(bytes.size * 2) {
                for (b in bytes) {
                    append(HEX[(b.toInt() shr 4) and 0xF])
                    append(HEX[b.toInt() and 0xF])
                }
            }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }
}
