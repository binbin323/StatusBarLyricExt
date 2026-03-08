package cn.binbin323.statuslyricext.provider

import android.media.MediaMetadata

interface ILrcProvider {
    @Throws(Exception::class)
    fun getLyric(data: MediaMetadata): LyricResult?

    data class LyricResult(
        var mLyric: String = "",
        var mDistance: Long = 0L
    )
}
