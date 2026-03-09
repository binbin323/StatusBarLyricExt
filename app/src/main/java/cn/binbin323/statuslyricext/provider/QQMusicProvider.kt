package cn.binbin323.statuslyricext.provider

import android.media.MediaMetadata
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import cn.binbin323.statuslyricext.provider.utils.HttpRequestUtil
import cn.binbin323.statuslyricext.provider.utils.LyricSearchUtil

class QQMusicProvider : ILrcProvider {

    companion object {
        private const val QM_BASE_URL = "https://c.y.qq.com/"
        private const val QM_REFERER = "https://y.qq.com/portal/player.html"
        private const val QM_SEARCH_URL_FORMAT =
            "${QM_BASE_URL}soso/fcgi-bin/music_search_new_platform?w=%s&format=json"
        private const val QM_LRC_URL_FORMAT =
            "${QM_BASE_URL}lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=%s&format=json&nobase64=1"
    }

    override fun getLyric(data: MediaMetadata): ILrcProvider.LyricResult? {
        val searchUrl = String.format(QM_SEARCH_URL_FORMAT, LyricSearchUtil.getSearchKey(data))
        return try {
            val searchResult = HttpRequestUtil.getJsonResponse(searchUrl, QM_REFERER) ?: return null
            if (searchResult.getLong("code") != 0L) return null

            val array = searchResult.getJSONObject("data").getJSONObject("song").getJSONArray("list")
            val (lrcUrl, distance) = getLrcUrl(array, data)

            val lrcJson = HttpRequestUtil.getJsonResponse(lrcUrl, QM_REFERER) ?: return null
            val lyric = String(Base64.decode(lrcJson.getString("lyric").toByteArray(), Base64.DEFAULT))
            ILrcProvider.LyricResult(mLyric = lyric, mDistance = distance)
        } catch (e: JSONException) {
            null
        }
    }

    private fun getLrcUrl(jsonArray: JSONArray, metadata: MediaMetadata): Pair<String, Long> {
        var currentMID = ""
        var minDistance = 10000L
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val songName = obj.getString("songname")
            val albumName = obj.getString("albumname")
            val singers = obj.getJSONArray("singer")
            val artist = buildString {
                for (j in 0 until singers.length()) {
                    if (j > 0) append("/")
                    append(singers.getJSONObject(j).getString("name"))
                }
            }
            val dist = LyricSearchUtil.getMetadataDistance(metadata, songName, artist, albumName)
            if (dist < minDistance) {
                minDistance = dist
                currentMID = obj.getString("songmid")
            }
        }
        return Pair(String.format(QM_LRC_URL_FORMAT, currentMID), minDistance)
    }
}
