package cn.binbin323.statuslyricext.provider

import android.media.MediaMetadata
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import cn.binbin323.statuslyricext.provider.utils.LyricSearchUtil
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class BinLrcProvider : ILrcProvider {

    companion object {
        private const val TAG = "BinLrcProvider"
        // Use LAN IP on real devices, or 10.0.2.2 when testing with Android emulator.
        private const val BIN_LRC_SERVICE_URL = "https://sms-api.dhao.cc/api/lyric"
    }

    override fun getLyric(data: MediaMetadata): ILrcProvider.LyricResult? {
        val title = data.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isEmpty()) return null

        val artist = data.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()

        val url = Uri.parse(BIN_LRC_SERVICE_URL)
            .buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("artist", artist)
            .build()
            .toString()

        val body = getTextResponse(url) ?: return null
        if (body.isEmpty()) return null

        val result = parseLyricResult(data, body, title, artist, "")
        if (result == null || !LyricSearchUtil.isLyricContent(result.mLyric)) return null
        return result
    }

    private fun parseLyricResult(
        metadata: MediaMetadata,
        body: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String
    ): ILrcProvider.LyricResult? {
        return try {
            val root = JSONObject(body)
            val lyric = pickLyric(root)
            if (lyric.isEmpty()) return null

            val result = ILrcProvider.LyricResult(mLyric = lyric)
            val distance = root.optLong("distance", -1L)
            if (distance >= 0) {
                result.mDistance = distance
                return result
            }
            val t = pickText(root, "title", fallbackTitle)
            val a = pickText(root, "artist", fallbackArtist)
            val al = pickText(root, "album", fallbackAlbum)
            result.mDistance = LyricSearchUtil.getMetadataDistance(metadata, t, a, al)
            result
        } catch (e: JSONException) {
            // Treat plain-text LRC body as a direct lyric
            ILrcProvider.LyricResult(mLyric = body, mDistance = 0L)
        }
    }

    private fun pickLyric(root: JSONObject): String {
        val direct = root.optString("lyric", "")
        if (direct.isNotEmpty()) return direct

        val lrc = root.optString("lrc", "")
        if (lrc.isNotEmpty()) return lrc

        root.optJSONObject("data")?.let { data ->
            val dataLyric = data.optString("lyric", "")
            if (dataLyric.isNotEmpty()) return dataLyric
            val dataLrc = data.optString("lrc", "")
            if (dataLrc.isNotEmpty()) return dataLrc
        }

        root.optJSONObject("lrc")?.let { lrcObj ->
            val nested = lrcObj.optString("lyric", "")
            if (nested.isNotEmpty()) return nested
        }

        return ""
    }

    private fun pickText(root: JSONObject, key: String, fallback: String): String {
        val value = root.optString(key, "")
        if (value.isNotEmpty()) return value
        root.optJSONObject("data")?.let { data ->
            val v = data.optString(key, "")
            if (v.isNotEmpty()) return v
        }
        return fallback
    }

    private fun getTextResponse(urlString: String): String? {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
            connection.setRequestProperty("User-Agent", "StatusBarLyricExt/1.0")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "request failed, code=$code, url=$urlString")
                return null
            }
            input = connection.inputStream
            String(readStream(input), StandardCharsets.UTF_8)
        } finally {
            input?.close()
            connection?.disconnect()
        }
    }

    private fun readStream(inputStream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            out.write(buffer, 0, len)
        }
        return out.toByteArray()
    }
}
