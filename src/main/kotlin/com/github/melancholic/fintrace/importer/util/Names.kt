package com.github.melancholic.fintrace.importer.util

private const val MAX_NAME_LENGTH = 30
private val ALLOWED_PUNCTUATION = " -_[]()',’&#@№".codePoints().toArray().toSet()
private val WHITESPACE = Regex("\\s+")

fun sanitizeName(name: String): String? {
    val codePoints = name.codePoints().toArray()
        .map { if (isAllowed(it)) it else ' '.code }
        .let { String(it.toIntArray(), 0, it.size) }
        .replace(WHITESPACE, " ")
        .codePoints().toArray()
        .dropWhile { !isLetterOrNumber(it) }

    val result = StringBuilder()
    for (cp in codePoints) {
        if (result.length + Character.charCount(cp) > MAX_NAME_LENGTH) break
        result.appendCodePoint(cp)
    }
    return result.toString().trimEnd().ifEmpty { null }
}

private fun isAllowed(cp: Int) = isLetterOrNumber(cp) || isMark(cp) || cp in ALLOWED_PUNCTUATION

private fun isLetterOrNumber(cp: Int) = Character.isLetter(cp) || Character.getType(cp).toByte() in NUMBERS

private fun isMark(cp: Int) = Character.getType(cp).toByte() in MARKS

private val NUMBERS = setOf(Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER)
private val MARKS = setOf(Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK)
