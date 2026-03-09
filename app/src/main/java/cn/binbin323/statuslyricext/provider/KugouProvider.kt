package cn.binbin323.statuslyricext.provider

import android.media.MediaMetadata
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import cn.binbin323.statuslyricext.provider.utils.HttpRequestUtil
import cn.binbin323.statuslyricext.provider.utils.LyricSearchUtil

class KugouProvider : ILrcProvider {

    companion object {
        private const val KUGOU_BASE_URL = "http://lyrics.kugou.com/"
        private const val KUGOU_SEARCH_URL_FORMAT =
            "${KUGOU_BASE_URL}search?ver=1&man=yes&client=pc&keyword=%s&duration=%d"
        private const val KUGOU_LRC_URL_FORMAT =
            "${KUGOU_BASE_URL}download?ver=1&client=pc&id=%d&accesskey=%s&fmt=lrc&charset=utf8"
    }

    override fun getLyric(data: MediaMetadata): ILrcProvider.LyricResult? {
        val searchUrl = String.format(
            KUGOU_SEARCH_URL_FORMAT,
            LyricSearchUtil.getSearchKey(data),
            data.getLong(MediaMetadata.METADATA_KEY_DURATION)
        )
        return try {
            val searchResult = HttpRequestUtil.getJsonResponse(searchUrl) ?: return null
            if (searchResult.getLong("status") != 200L) return null

            val array = searchResult.getJSONArray("candidates")
            val (lrcUrl, distance) = getLrcUrl(array, data)

            val lrcJson = HttpRequestUtil.getJsonResponse(lrcUrl) ?: return null
            val lyric = String(Base64.decode(lrcJson.getString("content").toByteArray(), Base64.DEFAULT))
            ILrcProvider.LyricResult(mLyric = lyric, mDistance = distance)
        } catch (e: JSONException) {
            null
        }
    }

    private fun getLrcUrl(jsonArray: JSONArray, metadata: MediaMetadata): Pair<String, Long> {
        var currentAccessKey = ""
        var currentId = -1L
        var minDistance = 10000L
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val soundName = obj.getString("soundname")
            val artist = obj.getString("singer")
            val dist = LyricSearchUtil.getMetadataDistance(metadata, soundName, artist, "")
            if (dist < minDistance) {
                minDistance = dist
                currentId = obj.getLong("id")
                currentAccessKey = obj.getString("accesskey")
            }
        }
        return Pair(String.format(KUGOU_LRC_URL_FORMAT, currentId, currentAccessKey), minDistance)
    }
}
