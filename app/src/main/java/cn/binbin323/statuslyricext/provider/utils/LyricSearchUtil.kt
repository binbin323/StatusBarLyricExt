package cn.binbin323.statuslyricext.provider.utils

import android.media.MediaMetadata
import android.text.TextUtils
import com.github.houbb.opencc4j.util.ZhConverterUtil
import cn.binbin323.statuslyricext.misc.CheckStringLang
import java.net.URLEncoder
import java.util.regex.Pattern

object LyricSearchUtil {

    private val LyricContentPattern =
        Pattern.compile("(\\[\\d\\d:\\d\\d\\.\\d{0,3}]|\\[\\d\\d:\\d\\d])[^\\r\\n]")

    @JvmStatic
    fun getSearchKey(metadata: MediaMetadata): String {
        var title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        var album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        var artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

        if (ZhConverterUtil.isTraditional(title) && !CheckStringLang.isJapenese(title))
            title = ZhConverterUtil.toSimple(title)
        if (ZhConverterUtil.isTraditional(artist) && !CheckStringLang.isJapenese(artist))
            artist = ZhConverterUtil.toSimple(artist)
        if (ZhConverterUtil.isTraditional(album) && !CheckStringLang.isJapenese(album))
            album = ZhConverterUtil.toSimple(album)

        val ret = when {
            artist.isNotEmpty() -> "$artist-$title"
            album.isNotEmpty() -> "$album-$title"
            else -> title
        }
        return try {
            URLEncoder.encode(ret, "UTF-8")
        } catch (e: Exception) {
            ret
        }
    }

    @JvmStatic
    fun getMetadataDistance(
        metadata: MediaMetadata,
        title: String,
        artist: String,
        album: String
    ): Long {
        var realTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        var realArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        var realAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        if (ZhConverterUtil.isTraditional(realTitle) && !CheckStringLang.isJapenese(realTitle))
            realTitle = ZhConverterUtil.toSimple(realTitle)
        if (ZhConverterUtil.isTraditional(realArtist) && !CheckStringLang.isJapenese(realArtist))
            realArtist = ZhConverterUtil.toSimple(realArtist)
        if (ZhConverterUtil.isTraditional(realAlbum) && !CheckStringLang.isJapenese(realAlbum))
            realAlbum = ZhConverterUtil.toSimple(realAlbum)

        if (TextUtils.isEmpty(title) ||
            (!realTitle.contains(title) && !title.contains(realTitle))
        ) return 10000L

        var res = levenshtein(title, realTitle) * 100L
        res += levenshtein(artist, realArtist) * 10L
        res += levenshtein(album, realAlbum).toLong()
        return res
    }

    @JvmStatic
    fun isLyricContent(content: String?): Boolean {
        if (TextUtils.isEmpty(content)) return false
        return LyricContentPattern.matcher(content!!).find()
    }

    @JvmStatic
    fun levenshtein(a: CharSequence?, b: CharSequence?): Int {
        if (TextUtils.isEmpty(a)) return if (TextUtils.isEmpty(b)) 0 else b!!.length
        if (TextUtils.isEmpty(b)) return a!!.length
        val lenA = a!!.length
        val lenB = b!!.length
        val dp = Array(lenA + 1) { IntArray(lenB + 1) { lenA + lenB } }
        for (i in 1..lenA) dp[i][0] = i
        for (j in 1..lenB) dp[0][j] = j
        for (i in 1..lenA) {
            for (j in 1..lenB) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j - 1] + cost, dp[i - 1][j] + 1, dp[i][j - 1] + 1)
            }
        }
        return dp[lenA][lenB]
    }
}
