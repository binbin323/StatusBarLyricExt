package cn.zhaiyifan.lyric.model

class Lyric {
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var by: String? = null
    var author: String? = null
    var offset: Int = 0
    var length: Long = 0
    val sentenceList: MutableList<Sentence> = ArrayList(100)

    fun addSentence(content: String, time: Long) {
        sentenceList.add(Sentence(content, time))
    }

    override fun toString(): String = buildString {
        appendLine("Title: $title")
        appendLine("Artist: $artist")
        appendLine("Album: $album")
        appendLine("By: $by")
        appendLine("Author: $author")
        appendLine("Length: $length")
        appendLine("Offset: $offset")
        sentenceList.forEach { appendLine(it.toString()) }
    }

    class SentenceComparator : Comparator<Sentence> {
        override fun compare(s1: Sentence, s2: Sentence): Int =
            (s1.fromTime - s2.fromTime).toInt()
    }

    class Sentence(var content: String, var fromTime: Long) {
        override fun toString() = "$fromTime: $content"
    }
}
