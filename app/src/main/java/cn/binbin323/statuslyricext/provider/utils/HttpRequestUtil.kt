package cn.binbin323.statuslyricext.provider.utils

import android.text.TextUtils
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object HttpRequestUtil {

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getJsonResponse(url: String): JSONObject? = getJsonResponse(url, null)

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getJsonResponse(url: String, referer: String?): JSONObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64; rv:77.0) Gecko/20100101 Firefox/77.0"
        )
        if (!TextUtils.isEmpty(referer)) {
            connection.setRequestProperty("Referer", referer)
        }
        connection.connectTimeout = 1000
        connection.readTimeout = 1000
        connection.connect()
        if (connection.responseCode == 200) {
            val data = readStream(connection.inputStream)
            connection.disconnect()
            return JSONObject(String(data))
        }
        connection.disconnect()
        return null
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readStream(inputStream: InputStream): ByteArray {
        val bout = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            bout.write(buffer, 0, len)
        }
        bout.close()
        inputStream.close()
        return bout.toByteArray()
    }
}
