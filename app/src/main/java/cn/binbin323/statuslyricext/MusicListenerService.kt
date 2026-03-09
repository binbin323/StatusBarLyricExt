package cn.binbin323.statuslyricext

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import cn.binbin323.statuslyricext.misc.Constants
import cn.zhaiyifan.lyric.LyricUtils
import cn.zhaiyifan.lyric.model.Lyric
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MusicListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MusicListenerService"
        private const val NOTIFICATION_ID_LRC = 1
        private const val POLL_INTERVAL_MS = 50L

        // Avium ROM 歌词广播接口
        private const val AVIUM_ACTION_SHOW_CHIP = "org.avium.systemui.chips.action.SHOW_CHIP"
        private const val AVIUM_EXTRA_TYPE = "type"
        private const val AVIUM_EXTRA_TEXT = "text"
        private const val AVIUM_CHIP_TYPE_MUSIC = 1
        private const val AVIUM_STATUS_BAR_LYRIC_KEY = "status_bar_show_lyric"
    }

    private val mMainHandler = Handler(Looper.getMainLooper())
    // Executor recreated lazily if shut down (handles onListenerDisconnected → onListenerConnected cycle)
    private var mFetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var mPendingFetch: Future<*>? = null

    private var mMediaSessionManager: MediaSessionManager? = null
    private var mMediaController: MediaController? = null
    private var mNotificationManager: NotificationManager? = null

    private val mIgnoredPackageList = mutableListOf<String>()
    private lateinit var mSharedPreferences: SharedPreferences

    private var mLyric: Lyric? = null
    private var mLastDisplayedFromTime = -1L
    private var mCurrentSentenceIndex = 0
    private var mIsPlaying = false
    // Tracks the title for which a fetch was most recently started; used for stale-result detection.
    private var mFetchingTitle: String? = null
    // Current song title shown in the notification content area.
    private var mCurrentSongTitle: String = ""
    // Whether the current ROM is Avium (detected once at connect time).
    private var mIsAviumRom: Boolean = false

    // ── Tick runnable ─────────────────────────────────────────────────────────

    private val mTickRunnable = object : Runnable {
        override fun run() {
            if (!mIsPlaying || mMediaController == null) return
            val ps = mMediaController?.playbackState
            if (ps == null || ps.state != PlaybackState.STATE_PLAYING) {
                onPlaybackStopped()
                return
            }
            mLyric?.let { displayLyricAt(ps.position) }
            mMainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ── Lyric fetch ───────────────────────────────────────────────────────────

    private fun fetchLyric(metadata: MediaMetadata) {
        mPendingFetch?.cancel(true)
        mPendingFetch = null
        mLyric = null
        mCurrentSentenceIndex = 0
        mLastDisplayedFromTime = -1L

        val fetchTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (fetchTitle.isNullOrEmpty()) {
            Log.i(TAG, "fetchLyric: no title in metadata, skipping")
            return
        }
        // Record now so onLyricFetched can detect stale results without querying the controller.
        mFetchingTitle = fetchTitle
        mCurrentSongTitle = fetchTitle
        Log.i(TAG, "fetchLyric: $fetchTitle")

        // Recreate executor if a previous onListenerDisconnected shut it down.
        if (mFetchExecutor.isShutdown) {
            mFetchExecutor = Executors.newSingleThreadExecutor()
        }

        mPendingFetch = mFetchExecutor.submit {
            val result = LrcGetter.getLyric(applicationContext, metadata)
            if (Thread.currentThread().isInterrupted) return@submit
            mMainHandler.post { onLyricFetched(fetchTitle, result) }
        }
    }

    private fun onLyricFetched(fetchTitle: String, lyric: Lyric?) {
        // Compare against the title we last requested, NOT against the live controller metadata.
        // Querying mMediaController?.metadata at callback time is unreliable – the controller can
        // be null or metadata can temporarily be null during transitions, causing valid results to
        // be silently discarded.
        if (fetchTitle != mFetchingTitle) {
            Log.i(TAG, "onLyricFetched: stale, discarding [$fetchTitle] (current: $mFetchingTitle)")
            return
        }
        if (lyric == null || lyric.sentenceList.isEmpty()) {
            Log.i(TAG, "onLyricFetched: no lyric for $fetchTitle")
            return
        }
        Log.i(TAG, "onLyricFetched: ${lyric.sentenceList.size} lines loaded for $fetchTitle")
        mLyric = lyric
        mCurrentSentenceIndex = 0
        mLastDisplayedFromTime = -1L
    }

    // ── Playback state helpers ────────────────────────────────────────────────

    private fun onPlaybackStarted() {
        Log.i(TAG, "onPlaybackStarted")
        mIsPlaying = true
        mCurrentSentenceIndex = 0
        mMainHandler.removeCallbacks(mTickRunnable)
        mMainHandler.post(mTickRunnable)
    }

    private fun onPlaybackStopped() {
        Log.i(TAG, "onPlaybackStopped")
        mIsPlaying = false
        mMainHandler.removeCallbacks(mTickRunnable)
        if (mIsAviumRom) {
            sendBroadcast(Intent(AVIUM_ACTION_SHOW_CHIP).apply {
                putExtra(AVIUM_EXTRA_TYPE, AVIUM_CHIP_TYPE_MUSIC)
                putExtra(AVIUM_EXTRA_TEXT, "")
            })
        }
        mNotificationManager?.cancel(NOTIFICATION_ID_LRC)
    }

    // ── Lyric display ─────────────────────────────────────────────────────────

    private fun displayLyricAt(positionMs: Long) {
        val lyric = mLyric ?: return
        if (lyric.sentenceList.isEmpty()) return

        val index = LyricUtils.getSentenceIndex(lyric, positionMs, mCurrentSentenceIndex, lyric.offset)
        if (index < 0) return

        mCurrentSentenceIndex = index
        val sentence = lyric.sentenceList[index]
        if (sentence.fromTime == mLastDisplayedFromTime) return
        mLastDisplayedFromTime = sentence.fromTime

        if (TextUtils.isEmpty(sentence.content)) return

        Log.i(TAG, "Lyric → ${sentence.content}")
        postLyricNotification(sentence.content)
    }

    private fun postLyricNotification(text: String) {
        if (mIsAviumRom) {
            val lyricEnabled = android.provider.Settings.Secure.getInt(
                contentResolver, AVIUM_STATUS_BAR_LYRIC_KEY, 0
            ) == 1
            if (lyricEnabled) {
                sendBroadcast(Intent(AVIUM_ACTION_SHOW_CHIP).apply {
                    putExtra(AVIUM_EXTRA_TYPE, AVIUM_CHIP_TYPE_MUSIC)
                    putExtra(AVIUM_EXTRA_TEXT, text)
                })
            }
            return
        }

        val nm = mNotificationManager ?: return
        val songTitle = mCurrentSongTitle.ifEmpty { text }

        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_LRC)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(songTitle)
            .setContentText(songTitle)
            .setTicker(text)
            .setShowWhen(false)
            .setOngoing(true)
            .build()

        notification.extras.putInt("ticker_icon", R.drawable.ic_music)
        notification.extras.putBoolean("ticker_icon_switch", false)
        notification.flags = notification.flags or
                Constants.FLAG_ALWAYS_SHOW_TICKER or
                Constants.FLAG_ONLY_UPDATE_TICKER
        nm.notify(NOTIFICATION_ID_LRC, notification)
    }

    private fun detectAviumRom(): Boolean = try {
        packageManager.getPackageInfo("org.avium.alivenotifscore", 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    // ── MediaController callbacks ─────────────────────────────────────────────

    private val mMediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.i(TAG, "onPlaybackStateChanged: ${state?.state ?: "null"}")
            if (state == null) return
            if (state.state == PlaybackState.STATE_PLAYING) onPlaybackStarted()
            else onPlaybackStopped()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.i(TAG, "onMetadataChanged: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "null"}")
            if (metadata == null) return
            // Cancel the old lyric notification immediately so stale lyrics don't linger
            // while the new song's lyrics are being fetched.
            mNotificationManager?.cancel(NOTIFICATION_ID_LRC)
            fetchLyric(metadata)
        }

        override fun onSessionDestroyed() {
            Log.i(TAG, "onSessionDestroyed")
            onPlaybackStopped()
            super.onSessionDestroyed()
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    private val mSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            Log.i(TAG, "onActiveSessionsChanged: ${controllers?.size ?: "null"}")
            mMediaController?.unregisterCallback(mMediaCallback)
            mMediaController = null

if (controllers.isNullOrEmpty()) {
            onPlaybackStopped()
            return@OnActiveSessionsChangedListener
        }

        var best: MediaController? = null
        for (c in controllers) {
            if (mIgnoredPackageList.contains(c.packageName)) continue
            if (getControllerState(c) == PlaybackState.STATE_PLAYING) { best = c; break }
            if (best == null) best = c
        }
        if (best == null) {
            onPlaybackStopped()
            return@OnActiveSessionsChangedListener
        }

            Log.i(TAG, "binding to: ${best.packageName}")
            mMediaController = best
            mMediaController!!.registerCallback(mMediaCallback)
            mMediaController!!.metadata?.let { mMediaCallback.onMetadataChanged(it) }
            mMediaController!!.playbackState?.let { mMediaCallback.onPlaybackStateChanged(it) }
        }

    private fun getControllerState(c: MediaController): Int =
        c.playbackState?.state ?: PlaybackState.STATE_NONE

    // ── Broadcast receiver ────────────────────────────────────────────────────

    private val mIgnoredPackageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Constants.BROADCAST_IGNORED_APP_CHANGED == intent.action) {
                updateIgnoredPackageList()
                unbindSession()
                bindSession()
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "onListenerConnected")
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mIsAviumRom = detectAviumRom()
        Log.i(TAG, "isAviumRom=$mIsAviumRom")
        ensureNotificationChannel()
        mMediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        LocalBroadcastManager.getInstance(this).registerReceiver(
            mIgnoredPackageReceiver,
            IntentFilter(Constants.BROADCAST_IGNORED_APP_CHANGED)
        )
        updateIgnoredPackageList()
        bindSession()
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "onListenerDisconnected")
        onPlaybackStopped()
        unbindSession()
        mPendingFetch?.cancel(true)
        mPendingFetch = null
        // Do NOT shutdownNow() – if the service reconnects in the same process lifetime,
        // the executor must still be usable. fetchLyric() recreates it lazily when needed.
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mIgnoredPackageReceiver)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    // ── Session bind / unbind ─────────────────────────────────────────────────

    private fun bindSession() {
        val msm = mMediaSessionManager ?: return
        val listener = ComponentName(this, MusicListenerService::class.java)
        msm.addOnActiveSessionsChangedListener(mSessionsListener, listener)
        mSessionsListener.onActiveSessionsChanged(msm.getActiveSessions(listener))
    }

    private fun unbindSession() {
        mMediaSessionManager?.removeOnActiveSessionsChangedListener(mSessionsListener)
        mMediaController?.unregisterCallback(mMediaCallback)
        mMediaController = null
    }

    private fun updateIgnoredPackageList() {
        mIgnoredPackageList.clear()
        val value = mSharedPreferences.getString(Constants.PREFERENCE_KEY_IGNORED_PACKAGES, "") ?: ""
        value.split(";").forEach { s ->
            val trimmed = s.trim()
            if (trimmed.isNotEmpty()) mIgnoredPackageList.add(trimmed)
        }
    }

    private fun ensureNotificationChannel() {
        val nm = mNotificationManager ?: return
        if (nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_LRC) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_LRC,
                    "LRC",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
    }
}
