package cn.binbin323.statuslyricext

import android.content.Context
import android.media.MediaMetadata
import android.text.TextUtils
import android.util.Log
import cn.binbin323.statuslyricext.provider.BinLrcProvider
import cn.binbin323.statuslyricext.provider.NeteaseProvider
import cn.binbin323.statuslyricext.provider.utils.LyricSearchUtil
import cn.zhaiyifan.lyric.LyricUtils
import cn.zhaiyifan.lyric.model.Lyric
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object LrcGetter {

    private const val TAG = "LrcGetter"
    private val HEX = "0123456789ABCDEF".toCharArray()
    private val sNeteaseProvider = NeteaseProvider()
    private val sBinProvider = BinLrcProvider()

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

        // Fetch from provider: Netease first, fall back to Bin
        val result = try {
            val netease = sNeteaseProvider.getLyric(metadata)
            if (netease != null && LyricSearchUtil.isLyricContent(netease.mLyric)) {
                Log.i(TAG, "netease provider success for: $title")
                netease
            } else {
                Log.i(TAG, "netease provider returned nothing, falling back to bin for: $title")
                sBinProvider.getLyric(metadata)
            }
        } catch (e: Exception) {
            Log.w(TAG, "netease provider failed, falling back to bin for: $title", e)
            try {
                sBinProvider.getLyric(metadata)
            } catch (e2: Exception) {
                Log.e(TAG, "bin provider also failed for: $title", e2)
                return null
            }
        }

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
