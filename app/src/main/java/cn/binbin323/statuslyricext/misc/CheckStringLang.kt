package cn.binbin323.statuslyricext.misc

object CheckStringLang {

    private val JAPANESE_BLOCKS = setOf(
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
    )

    /**
     * Returns true if the first character of [text] belongs to a Japanese Unicode block.
     * Mirrors the original Java behaviour (loop exits on the first character).
     */
    @JvmStatic
    fun isJapenese(text: String): Boolean {
        for (c in text) {
            return JAPANESE_BLOCKS.contains(Character.UnicodeBlock.of(c))
        }
        return false
    }
}
