package cn.binbin323.statuslyricext.provider

import android.media.MediaMetadata
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import cn.binbin323.statuslyricext.provider.utils.HttpRequestUtil
import cn.binbin323.statuslyricext.provider.utils.LyricSearchUtil

class KugouProvider : ILrcProvider {

    companion object {
        private const val TAG = "KugouProvider"
        private const val KUGOU_LYRIC_BASE_URL = "http://lyrics.kugou.com/"
        private const val KUGOU_SEARCH_URL_FORMAT =
            "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=%s"
        private const val KUGOU_LYRIC_SEARCH_URL_FORMAT =
            "${KUGOU_LYRIC_BASE_URL}search?ver=1&man=yes&client=pc&keyword=%s&hash=%s"
        private const val KUGOU_LRC_URL_FORMAT =
            "${KUGOU_LYRIC_BASE_URL}download?ver=1&client=pc&id=%s&accesskey=%s&fmt=lrc&charset=utf8"
    }

    override fun getLyric(data: MediaMetadata): ILrcProvider.LyricResult? {
        val searchKey = LyricSearchUtil.getSearchKey(data)
        val searchUrl = String.format(KUGOU_SEARCH_URL_FORMAT, searchKey)
        Log.i(TAG, "searchUrl: $searchUrl")
        return try {
            val searchResult = HttpRequestUtil.getJsonResponse(searchUrl) ?: run {
                Log.w(TAG, "searchResult is null")
                return null
            }
            val searchStatus = searchResult.getInt("status")
            Log.i(TAG, "searchResult status: $searchStatus")
            if (searchStatus != 1) return null

            val infoArray = searchResult.getJSONObject("data").getJSONArray("info")
            Log.i(TAG, "infoArray length: ${infoArray.length()}")
            val (hash, distance) = getBestHash(infoArray, data)
            Log.i(TAG, "bestHash: $hash, distance: $distance")
            if (hash.isEmpty()) return null

            val lyricSearchUrl = String.format(KUGOU_LYRIC_SEARCH_URL_FORMAT, searchKey, hash)
            Log.i(TAG, "lyricSearchUrl: $lyricSearchUrl")
            val lyricSearchResult = HttpRequestUtil.getJsonResponse(lyricSearchUrl) ?: run {
                Log.w(TAG, "lyricSearchResult is null")
                return null
            }
            val lyricSearchStatus = lyricSearchResult.getLong("status")
            Log.i(TAG, "lyricSearchResult status: $lyricSearchStatus")
            if (lyricSearchStatus != 200L) return null

            val candidates = lyricSearchResult.getJSONArray("candidates")
            Log.i(TAG, "candidates length: ${candidates.length()}")
            if (candidates.length() == 0) return null
            val (id, accessKey) = getBestCandidate(candidates, data)
            Log.i(TAG, "bestCandidate id: $id, accessKey: $accessKey")
            if (id.isEmpty()) return null

            val lrcUrl = String.format(KUGOU_LRC_URL_FORMAT, id, accessKey)
            Log.i(TAG, "lrcUrl: $lrcUrl")
            val lrcJson = HttpRequestUtil.getJsonResponse(lrcUrl) ?: run {
                Log.w(TAG, "lrcJson is null")
                return null
            }
            val lyric = String(Base64.decode(lrcJson.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
            Log.i(TAG, "lyric length: ${lyric.length}")
            ILrcProvider.LyricResult(mLyric = lyric, mDistance = distance)
        } catch (e: JSONException) {
            Log.e(TAG, "JSON parse error", e)
            null
        }
    }

    private fun getBestCandidate(jsonArray: JSONArray, metadata: MediaMetadata): Pair<String, String> {
        var bestId = ""
        var bestAccessKey = ""
        var minDistance = 10000L
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val soundName = obj.optString("song", "")
            val artist = obj.optString("singer", "")
            val id = obj.optString("id", "")
            val accessKey = obj.optString("accesskey", "")
            if (id.isEmpty() || accessKey.isEmpty()) continue
            val dist = LyricSearchUtil.getMetadataDistance(metadata, soundName, artist, "")
            if (dist < minDistance) {
                minDistance = dist
                bestId = id
                bestAccessKey = accessKey
            }
        }
        return Pair(bestId, bestAccessKey)
    }

    private fun getBestHash(jsonArray: JSONArray, metadata: MediaMetadata): Pair<String, Long> {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        var bestHash = ""
        var minDistance = 10000L
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val songName = obj.optString("songname", "")
            val artist = obj.optString("singername", "")
            val hash = obj.optString("hash", "")
            // 完全匹配优先返回
            if (songName.equals(title, ignoreCase = true) && hash.isNotEmpty()) {
                return Pair(hash, 0L)
            }
            val dist = LyricSearchUtil.getMetadataDistance(metadata, songName, artist, "")
            if (dist < minDistance) {
                minDistance = dist
                bestHash = hash
            }
        }
        return Pair(bestHash, minDistance)
    }
}
