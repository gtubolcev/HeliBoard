// SPDX-License-Identifier: GPL-3.0-only
// Ported from Phone Whisper's TextEditing.kt (smart spacing, capitalization, punctuation).
package helium314.keyboard.latin.phonewhisper

/** Pure text helpers for inserting dictated text. No Android dependencies. */
object VoiceText {

    /** A change relative to the cursor: delete [deleteBefore] chars, then insert [insert]. */
    data class Edit(val deleteBefore: Int, val insert: String)

    /** Dictated text with spacing added; the phrase itself starts at [phraseOffset]. */
    data class Dictation(val insert: String, val phraseOffset: Int, val phrase: String)

    private const val TRAILING_PUNCT = ".,?!…;:"
    private const val SENTENCE_END = ".?!…"
    private const val NO_SPACE_AFTER = "([{«„“‘\"'/@#"
    const val SMART_MARKS = ".,?!"

    fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()

    private fun isHorizontalSpace(c: Char) = c == ' ' || c == '\t' || c == ' '

    fun isSentenceStart(before: CharSequence): Boolean {
        var i = before.length
        while (i > 0 && isHorizontalSpace(before[i - 1])) i--
        if (i == 0) return true
        val c = before[i - 1]
        return c == '\n' || c in SENTENCE_END
    }

    /**
     * Capitalize at a sentence start, otherwise lower-case the first letter Whisper capitalizes
     * for every segment (keeps acronyms and "I"); add separating spaces next to words.
     */
    fun prepareDictation(raw: String, before: CharSequence, after: CharSequence): Dictation {
        var body = raw.trim()
        if (body.isEmpty()) return Dictation("", 0, "")
        body = if (isSentenceStart(before)) capitalizeFirst(body) else decapitalizeFirst(body)
        val last = before.lastOrNull()
        val first = body.first()
        val lead = if (last != null && !last.isWhitespace() && last !in NO_SPACE_AFTER &&
            first !in TRAILING_PUNCT) " " else ""
        val next = after.firstOrNull()
        val trail = if (next != null && isWordChar(next)) " " else ""
        return Dictation(lead + body + trail, lead.length, body)
    }

    /**
     * Smart punctuation: replace the mark the recognizer put at the end of the phrase, drop
     * spaces before the mark and add one after it. With [append] marks stack ("?!", "...").
     */
    fun punctuate(before: CharSequence, mark: Char, after: CharSequence, append: Boolean): Edit {
        var i = before.length
        while (i > 0 && isHorizontalSpace(before[i - 1])) i--
        if (!append) {
            var j = i
            while (j > 0 && before[j - 1] in TRAILING_PUNCT) j--
            if (j > 0 && !before[j - 1].isWhitespace()) i = j
        }
        val nextIsSpace = after.firstOrNull()?.isWhitespace() == true
        return Edit(before.length - i, "$mark" + if (nextIsSpace) "" else " ")
    }

    fun capitalizeFirst(text: String): String {
        val idx = text.indexOfFirst { it.isLetter() }
        if (idx < 0 || text[idx].isUpperCase()) return text
        return text.substring(0, idx) + text[idx].titlecaseChar() + text.substring(idx + 1)
    }

    fun decapitalizeFirst(text: String): String {
        val idx = text.indexOfFirst { it.isLetter() }
        if (idx < 0 || !text[idx].isUpperCase()) return text
        var end = idx
        while (end < text.length && isWordChar(text[end])) end++
        val word = text.substring(idx, end)
        if (word == "I") return text
        if (word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() }) return text
        return text.substring(0, idx) + text[idx].lowercaseChar() + text.substring(idx + 1)
    }
}
