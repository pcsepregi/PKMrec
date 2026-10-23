package me.csepregi.pkmrec.recognition

/** One line of recognised text with its vertical position in the (upright) camera image. */
data class OcrLine(val text: String, val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/**
 * What was read from a card.
 *
 * @property setCode printed set code, e.g. "PAL" (Scarlet & Violet era and later); null on older cards
 * @property number card number as printed, e.g. "015", "TG05", "045"
 * @property total set total after the slash, e.g. "128"; null for promos
 * @property regulationMark e.g. 'G'; null if not read
 * @property name card name from the top of the card, if read
 */
data class CardReading(
    val setCode: String?,
    val number: String,
    val total: String?,
    val regulationMark: Char? = null,
    val name: String? = null,
) {
    /** Identity used to decide whether consecutive frames agree (the name is not part of it). */
    val key: String get() = "${setCode.orEmpty()}|$number|${total.orEmpty()}"

    /** Human-readable form, e.g. "PAL 123/193" or "SVP 045". */
    val display: String
        get() = listOfNotNull(setCode, if (total != null) "$number/$total" else number).joinToString(" ")
}

/**
 * Finds the set code, card number and name in OCR output of an English Pokémon card.
 *
 * The bottom strip of a modern English card reads like `G PAL EN 123/193` (regulation mark,
 * set code, language, number/total). Promos read like `SVP EN 045`. Older cards only have
 * `123/193`.
 */
object CardTextParser {

    /** Letter prefixes that really appear in card numbers (Trainer Gallery, Galarian Gallery, ...). */
    private val NUMBER_PREFIXES = setOf("TG", "GG", "SV", "RC", "H")

    /** Promo set codes whose number is printed glued to the code, e.g. "SWSH123". */
    private val GLUED_PROMO = Regex("""^(SWSH|SM|XY|BW|DP|HGSS)(\d{1,3})$""")

    private val SLASH_SPACING = Regex("""\s*/\s*""")
    private val SET_CODE = Regex("""^[A-Z0-9]{2,4}$""")
    private val NOT_SET_CODES = setOf("EN", "HP", "EX", "GX", "VMAX", "VSTAR", "TM")
    private val REGULATION_MARK = Regex("""^[A-K]$""")
    private val NAME_JUNK = Regex("""\s*(HP\s*\d+|\d+\s*HP|\d+)\s*$""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<OcrLine>, imageHeight: Int): CardReading? {
        val candidates = lines.mapNotNull { line -> readNumberLine(line, lines)?.let { line to it } }
        if (candidates.isEmpty()) return null

        // Prefer readings from the lower half of the image (the card's bottom strip), then those with a set code.
        val (_, reading) = candidates.maxBy { (line, r) ->
            (if (line.top > imageHeight / 2) 2 else 0) + (if (r.setCode != null) 1 else 0)
        }
        return reading.copy(name = findName(lines, imageHeight))
    }

    /** Tries to read a number (and set code / regulation mark) from one line. */
    internal fun readNumberLine(line: OcrLine, allLines: List<OcrLine>): CardReading? {
        val tokens = line.text.uppercase().replace(SLASH_SPACING, "/").split(Regex("\\s+")).filter { it.isNotBlank() }

        // 1) number/total, e.g. 015/128, TG05/TG30
        for ((index, token) in tokens.withIndex()) {
            val slash = token.indexOf('/')
            if (slash <= 0 || slash == token.length - 1) continue
            val number = normalizeNumberPart(token.substring(0, slash)) ?: continue
            val total = normalizeNumberPart(token.substring(slash + 1)) ?: continue
            if (total.filter { it.isDigit() }.length < 2) continue
            val before = tokens.subList(0, index)
            val (setCode, mark) = setCodeAndMark(before) ?: setCodeFromNeighbours(line, allLines) ?: (null to null)
            return CardReading(setCode, number, total, mark)
        }

        // 2) promo "SVP EN 045": set code, EN, number
        val enIndex = tokens.indexOf("EN")
        if (enIndex in 1 until tokens.size - 1) {
            val number = normalizeNumberPart(tokens[enIndex + 1])
            val code = tokens[enIndex - 1]
            if (number != null && number.all { it.isDigit() } && isSetCode(code)) {
                val mark = tokens.getOrNull(enIndex - 2)?.takeIf { REGULATION_MARK.matches(it) }?.first()
                return CardReading(code, number, null, mark)
            }
        }

        // 3) glued promo "SWSH123"
        tokens.firstNotNullOfOrNull { GLUED_PROMO.matchEntire(it) }?.let { match ->
            return CardReading(match.groupValues[1], match.groupValues[2], null)
        }
        return null
    }

    /**
     * Normalises a number part read by OCR: fixes O→0 and I/l/|→1 where digits are expected,
     * keeps real prefixes like "TG". Returns null if it does not look like a card number.
     */
    internal fun normalizeNumberPart(raw: String): String? {
        val text = raw.trim().trim('.', ',', ':', ';')
        if (text.isEmpty() || text.length > 5) return null
        val prefix = NUMBER_PREFIXES.filter { text.startsWith(it) && text.length > it.length }.maxByOrNull { it.length }
        val digitsPart = if (prefix != null) text.substring(prefix.length) else text
        val digits = digitsPart.map { c ->
            when (c) {
                'O', 'o', 'Q', 'D' -> '0'
                'I', 'l', '|', 'L' -> '1'
                'S' -> '5'
                'B' -> '8'
                else -> c
            }
        }.joinToString("")
        if (digits.isEmpty() || digits.length > 3 || !digits.all { it.isDigit() }) return null
        return (prefix ?: "") + digits
    }

    private fun isSetCode(token: String) =
        SET_CODE.matches(token) && token.any { it.isLetter() } && token !in NOT_SET_CODES

    /** From the tokens before the number: "[mark] CODE [EN]". */
    private fun setCodeAndMark(before: List<String>): Pair<String, Char?>? {
        val withoutEn = before.dropLastWhile { it == "EN" }
        val code = withoutEn.lastOrNull()?.takeIf { isSetCode(it) } ?: return null
        val mark = withoutEn.getOrNull(withoutEn.size - 2)?.takeIf { REGULATION_MARK.matches(it) }?.first()
        return code to mark
    }

    /** OCR sometimes splits "PAL EN" and "123/193" into separate lines at the same height. */
    private fun setCodeFromNeighbours(line: OcrLine, allLines: List<OcrLine>): Pair<String, Char?>? {
        val tolerance = (line.height * 0.6).toInt().coerceAtLeast(4)
        return allLines.asSequence()
            .filter { it !== line && kotlin.math.abs(it.top - line.top) <= tolerance }
            .mapNotNull { other ->
                val tokens = other.text.uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (tokens.lastOrNull() == "EN" || tokens.contains("EN")) setCodeAndMark(tokens.takeWhile { it != "EN" } + "EN") else null
            }
            .firstOrNull()
    }

    /** The card name is the tallest line with letters in the top part of the image. */
    internal fun findName(lines: List<OcrLine>, imageHeight: Int): String? =
        lines.asSequence()
            .filter { it.top < imageHeight * 0.35 }
            .map { it to it.text.replace(NAME_JUNK, "").trim() }
            .filter { (_, text) -> text.count { it.isLetter() } >= 3 && !text.contains('/') }
            .maxByOrNull { (line, _) -> line.height }
            ?.second
}
