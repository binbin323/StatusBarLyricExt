package cn.zhaiyifan.lyric

import android.text.TextUtils
import android.util.Log
import cn.zhaiyifan.lyric.model.Lyric
import cn.zhaiyifan.lyric.model.Lyric.Sentence
import java.io.*

object LyricUtils {
    private val TAG = LyricUtils::class.java.simpleName

    @JvmStatic
    fun parseLyric(inputStream: InputStream, encoding: String): Lyric {
        val lyric = Lyric()
        try {
            val br = BufferedReader(InputStreamReader(inputStream, encoding))
            var line: String?
            while (br.readLine().also { line = it } != null) {
                parseLine(line!!, lyric)
            }
            lyric.sentenceList.sortWith(Lyric.SentenceComparator())
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return lyric
    }

    @JvmStatic
    fun parseLyric(file: File, encoding: String): Lyric {
        val lyric = Lyric()
        try {
            val br = BufferedReader(InputStreamReader(FileInputStream(file), encoding))
            Log.i(TAG, "parseLyric(${file.path}, $encoding)")
            var line: String?
            while (br.readLine().also { line = it } != null) {
                parseLine(line!!, lyric)
            }
            lyric.sentenceList.sortWith(Lyric.SentenceComparator())
        } catch (e: IOException) {
            e.printStackTrace()
        }
        if (TextUtils.isEmpty(lyric.title) || TextUtils.isEmpty(lyric.artist)) {
            val nameWithExt = file.name
            val fn = if (nameWithExt.length > 4) nameWithExt.dropLast(4) else nameWithExt
            val index = fn.indexOf('-')
            val title: String
            val artist: String?
            if (index > 0) {
                artist = fn.substring(0, index).trim()
                title = fn.substring(index + 1).trim()
            } else {
                title = fn.trim()
                artist = null
            }
            if (TextUtils.isEmpty(lyric.title) && title.isNotEmpty()) {
                lyric.title = title
            } else if (artist != null) {
                lyric.artist = artist
            }
        }
        return lyric
    }

    /** Parse lyric directly from a string, bypassing intermediate file I/O. */
    @JvmStatic
    fun parseLyric(content: String, encoding: String): Lyric {
        return parseLyric(ByteArrayInputStream(content.toByteArray(charset(encoding))), encoding)
    }

    @JvmStatic
    fun saveLyric(lyric: Lyric): String = ""

    @JvmStatic
    fun getSentence(lyric: Lyric, ts: Long): Sentence? = getSentence(lyric, ts, 0)

    @JvmStatic
    fun getSentence(lyric: Lyric, ts: Long, index: Int): Sentence? =
        getSentence(lyric, ts, index, 0)

    @JvmStatic
    fun getSentence(lyric: Lyric, ts: Long, index: Int, offset: Int): Sentence? {
        val found = getSentenceIndex(lyric, ts, index, offset)
        return if (found == -1) null else lyric.sentenceList[found]
    }

    @JvmStatic
    fun getSentenceIndex(lyric: Lyric?, ts: Long, index: Int, offset: Int): Int {
        if (lyric == null || ts < 0 || index < -1) return -1
        val list = lyric.sentenceList
        if (list.isEmpty()) return -1
        var idx = when {
            index >= list.size -> list.size - 1
            index == -1 -> 0
            else -> index
        }
        var found = -2
        if (list[idx].fromTime + offset > ts) {
            for (i in idx downTo 0) {
                if (list[i].fromTime + offset <= ts) {
                    found = i
                    break
                }
            }
            if (found == -2) found = -1
        } else {
            for (i in idx until list.size - 1) {
                if (list[i + 1].fromTime + offset > ts) {
                    found = i
                    break
                }
            }
            if (found == -2) found = list.size - 1
        }
        return found
    }

    private fun parseLine(rawLine: String, lyric: Lyric): Boolean {
        val line = rawLine.trim()
        var openBracketIndex = line.indexOf('[', 0)

        while (openBracketIndex != -1) {
            var closedBracketIndex = line.indexOf(']', openBracketIndex)
            if (closedBracketIndex < 1) return false
            val closedTag = line.substring(openBracketIndex + 1, closedBracketIndex)
            val parts = closedTag.split(":", limit = 2)
            if (parts.size < 2) return false

            when {
                parts[0].equals(Constants.ID_TAG_TITLE, ignoreCase = true) ->
                    lyric.title = parts[1].trim()
                parts[0].equals(Constants.ID_TAG_ARTIST, ignoreCase = true) ->
                    lyric.artist = parts[1].trim()
                parts[0].equals(Constants.ID_TAG_ALBUM, ignoreCase = true) ->
                    lyric.album = parts[1].trim()
                parts[0].equals(Constants.ID_TAG_CREATOR_LRCFILE, ignoreCase = true) ->
                    lyric.by = parts[1].trim()
                parts[0].equals(Constants.ID_TAG_CREATOR_SONGTEXT, ignoreCase = true) ->
                    lyric.author = parts[1].trim()
                parts[0].equals(Constants.ID_TAG_LENGTH, ignoreCase = true) ->
                    lyric.length = parseTime(parts[1].trim(), lyric)
                parts[0].equals(Constants.ID_TAG_OFFSET, ignoreCase = true) ->
                    lyric.offset = parseOffset(parts[1].trim())
                parts[0].firstOrNull()?.isDigit() == true -> {
                    val timestampList = mutableListOf<Long>()
                    val time = parseTime(closedTag, lyric)
                    if (time != -1L) timestampList.add(time)
                    // Handle multiple timestamps: [01:38.33][01:44.01][03:22.05]content
                    while (line.length > closedBracketIndex + 2 && line[closedBracketIndex + 1] == '[') {
                        val nextOpen = closedBracketIndex + 1
                        val nextClose = line.indexOf(']', nextOpen + 1)
                        if (nextClose < 0) break
                        val t = parseTime(line.substring(nextOpen + 1, nextClose), lyric)
                        if (t != -1L) timestampList.add(t)
                        closedBracketIndex = nextClose
                    }
                    val content = line.substring(closedBracketIndex + 1)
                    for (ts in timestampList) {
                        lyric.addSentence(content, ts)
                    }
                }
                else -> return true // unknown tag – skip
            }
            openBracketIndex = line.indexOf('[', closedBracketIndex + 1)
        }
        return true
    }

    private fun parseTime(time: String, lyric: Lyric): Long {
        val ss = time.split("[:.]".toRegex())
        return when (ss.size) {
            2 -> try {
                if (lyric.offset == 0 && ss[0].equals("offset", ignoreCase = true)) {
                    lyric.offset = ss[1].toInt()
                    -1L
                } else {
                    val min = ss[0].toInt()
                    val sec = ss[1].toInt()
                    require(min >= 0 && sec in 0..59)
                    (min * 60 + sec) * 1000L
                }
            } catch (e: Exception) { -1L }
            3 -> try {
                val min = ss[0].toInt()
                val sec = ss[1].toInt()
                val mm = ss[2].toInt()
                require(min >= 0 && sec in 0..59 && mm in 0..999)
                (min * 60 + sec) * 1000L + mm
            } catch (e: Exception) { -1L }
            else -> -1L
        }
    }

    private fun parseOffset(str: String): Int {
        if (str.equals("0", ignoreCase = true)) return 0
        val ss = str.split(":")
        return if (ss.size == 2 && ss[0].equals("offset", ignoreCase = true)) {
            val os = ss[1].toInt()
            Log.i(TAG, "total offset: $os")
            os
        } else {
            Int.MAX_VALUE
        }
    }
}
