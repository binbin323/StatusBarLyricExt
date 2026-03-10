package cn.binbin323.statuslyricext

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import cn.binbin323.statuslyricext.misc.Constants
import cn.binbin323.statuslyricext.misc.UpdateChecker
import java.lang.reflect.Field

class SettingsActivity : FragmentActivity() {

    companion object {
        private val URL_MAP = mapOf(
            "app" to "https://github.com/binbin323/StatusBarLyricExt",
            "lyricview" to "https://github.com/markzhai/LyricView",
            "lyric_adapt_request" to "https://sms.dhao.cc"
        )
        private const val REQUEST_CODE_MEDIA_PERMISSION = 1001

        fun isNotificationListenerEnabled(context: Context?): Boolean {
            context ?: return false
            val pkgName = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Constants.SETTINGS_ENABLED_NOTIFICATION_LISTENERS
            )
            if (!TextUtils.isEmpty(flat)) {
                for (name in flat.split(":")) {
                    val cn = ComponentName.unflattenFromString(name) ?: continue
                    if (TextUtils.equals(pkgName, cn.packageName)) return true
                }
            }
            return false
        }

        private fun getAppVersionName(context: Context): String? = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.collapsing_toolbar_base_layout)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.content_frame, SettingsFragment())
                .commit()
        }

        val collapsingToolbar = findViewById<Toolbar>(R.id.action_bar)
        setActionBar(collapsingToolbar)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_LRC, "LRC", NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }

        requestMediaPermissionIfNeeded()
        UpdateChecker.check(this)
    }

    private fun requestMediaPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_MEDIA_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val imagesGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        val notifGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (audioGranted && imagesGranted && notifGranted) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            REQUEST_CODE_MEDIA_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_MEDIA_PERMISSION) return

        val granted = grantResults.isNotEmpty() && grantResults.all {
            it == PackageManager.PERMISSION_GRANTED
        }
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            permissions.any { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        ) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: ActivityNotFoundException) {}
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        Preference.OnPreferenceClickListener {

        private var mEnabledPreference: SwitchPreference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            mEnabledPreference = findPreference(Constants.PREFERENCE_KEY_ENABLED)

            // Check ROM support for FLAG_ALWAYS_SHOW_TICKER
            val tickerFlagSupported = try {
                android.app.Notification::class.java
                    .getDeclaredField("FLAG_ALWAYS_SHOW_TICKER").getInt(null)
                true
            } catch (e: Exception) {
                false
            }
            val aviumSupported = try {
                requireContext().packageManager.getPackageInfo("org.avium.alivenotifscore", 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            val supported = tickerFlagSupported || aviumSupported
            mEnabledPreference?.let { pref ->
                if (!supported) {
                    pref.isEnabled = false
                    pref.setTitle(R.string.unsupport_rom_title)
                    pref.setSummary(R.string.unsupport_rom_summary)
                }
                pref.isChecked = isNotificationListenerEnabled(context)
                pref.onPreferenceClickListener = this
            }

            findPreference<Preference>("app")?.summary = getAppVersionName(requireContext())

            findPreference<PreferenceCategory>(Constants.PREFERENCE_KEY_ABOUT)?.let { cat ->
                for (i in 0 until cat.preferenceCount) {
                    cat.getPreference(i).onPreferenceClickListener = this
                }
            }
        }

        override fun onResume() {
            super.onResume()
            mEnabledPreference?.isChecked = isNotificationListenerEnabled(context)
        }

        override fun onPreferenceClick(preference: Preference): Boolean {
            if (preference == mEnabledPreference) {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } else {
                val url = URL_MAP[preference.key] ?: return false
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            return true
        }
    }
}
