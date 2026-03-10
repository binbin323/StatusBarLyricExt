package cn.binbin323.statuslyricext.misc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import cn.binbin323.statuslyricext.R
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val UPDATE_URL = "https://ota.dhao.cc/slc.json"

    fun check(activity: Activity) {
        val currentVersionCode = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode.toInt()
        } catch (_: Exception) {
            return
        }

        val ref = WeakReference(activity)
        Thread {
            try {
                val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(response)
                val latestVersionCode = json.getInt("versionCode")
                val latestVersionName = json.optString("versionName", "")
                val downloadUrl = json.optString("downloadUrl", "")
                val changelog = json.optString("changelog", "")

                if (latestVersionCode > currentVersionCode) {
                    Handler(Looper.getMainLooper()).post {
                        val act = ref.get()
                        if (act != null && !act.isFinishing) {
                            showUpdateDialog(act, latestVersionName, downloadUrl, changelog)
                        }
                    }
                }
            } catch (_: Exception) {
                // 网络错误静默忽略
            }
        }.start()
    }

    private fun showUpdateDialog(
        activity: Activity,
        versionName: String,
        downloadUrl: String,
        changelog: String
    ) {
        val message = buildString {
            if (versionName.isNotEmpty()) {
                append(activity.getString(R.string.update_new_version, versionName))
                append("\n\n")
            }
            if (changelog.isNotEmpty()) {
                append(changelog)
            }
        }.ifEmpty { activity.getString(R.string.update_available_message) }

        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ ->
                if (downloadUrl.isNotEmpty()) {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                }
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }
}
