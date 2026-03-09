package cn.binbin323.statuslyricext.provider

import android.media.MediaMetadata
import org.json.JSONArray
import org.json.JSONException
import cn.binbin323.statuslyricext.provider.utils.HttpRequestUtil
import cn.binbin323.statuslyricext.provider.utils.LyricSearchUtil

class NeteaseProvider : ILrcProvider {

    companion object {
        private const val NETEASE_BASE_URL = "http://music.163.com/api/"
        private const val NETEASE_SEARCH_URL_FORMAT =
            "${NETEASE_BASE_URL}search/get?s=%s&type=1&offset=0&limit=5"
        private const val NETEASE_LRC_URL_FORMAT =
            "${NETEASE_BASE_URL}song/lyric?os=pc&id=%d&lv=-1&kv=-1&tv=-1"
    }

    override fun getLyric(data: MediaMetadata): ILrcProvider.LyricResult? {
        val searchUrl = String.format(NETEASE_SEARCH_URL_FORMAT, LyricSearchUtil.getSearchKey(data))
        return try {
            val searchResult = HttpRequestUtil.getJsonResponse(searchUrl) ?: return null
            if (searchResult.getLong("code") != 200L) return null

            val songs = searchResult.getJSONObject("result").getJSONArray("songs")
            val (lrcUrl, distance) = getLrcUrl(songs, data)

            val lrcJson = HttpRequestUtil.getJsonResponse(lrcUrl) ?: return null
            val lyric = lrcJson.getJSONObject("lrc").getString("lyric")
            ILrcProvider.LyricResult(mLyric = lyric, mDistance = distance)
        } catch (e: JSONException) {
            null
        }
    }

    private fun getLrcUrl(jsonArray: JSONArray, metadata: MediaMetadata): Pair<String, Long> {
        var currentId = -1L
        var minDistance = 10000L
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val title = obj.getString("name")
            val album = obj.getJSONObject("album").getString("name")
            val artistsArray = obj.getJSONArray("artists")
            val artist = buildString {
                for (j in 0 until artistsArray.length()) {
                    if (j > 0) append("/")
                    append(artistsArray.getJSONObject(j).getString("name"))
                }
            }
            val dist = LyricSearchUtil.getMetadataDistance(metadata, title, artist, album)
            if (dist < minDistance) {
                minDistance = dist
                currentId = obj.getLong("id")
            }
        }
        return Pair(String.format(NETEASE_LRC_URL_FORMAT, currentId), minDistance)
    }
}
