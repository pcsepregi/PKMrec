package me.csepregi.pkmrec.recognition

import kotlin.math.abs

/**
 * One line of recognised text with its position in the (upright) camera image.
 * [fromStrip] marks lines read from the enlarged crop of the card's bottom strip.
 */
data class OcrLine(
    val text: String,
    val top: Int,
    val bottom: Int,
    val left: Int = 0,
    val right: Int = 0,
    val fromStrip: Boolean = false,
) {
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

/** A reading and the OCR line its number was read from (the set symbol is printed next to it). */
data class ParsedCard(val reading: CardReading, val numberLine: OcrLine)

/**
 * Finds the set code, card number and name in OCR output of an English Pokémon card.
 *
 * The bottom strip of a modern English card reads like `G PAL EN 123/193` (regulation mark,
 * set code, language, number/total). OCR often glues or splits these ("PALEN", separate
 * text blocks), so the set code is found via the "EN" language marker anywhere near the
 * number. Promos read like `SVP EN 045`. Older cards only have `123/193`.
 */
object CardTextParser {

    /** Letter prefixes that really appear in card numbers (Trainer Gallery, Galarian Gallery, ...). */
    private val NUMBER_PREFIXES = setOf("TG", "GG", "SV", "RC", "H")

    /** Promo set codes whose number is printed glued to the code, e.g. "SWSH123". */
    private val GLUED_PROMO = Regex("""^(SWSH|SM|XY|BW|DP|HGSS)(\d{1,3})$""")

    private val SLASH_SPACING = Regex("""\s*/\s*""")
    private val SEPARATORS = Regex("""[·•‧∙\-–—_.,:;|\[\](){}<>«»"'`]""")
    /**
     * Italic outlined digits (full-art cards) merge "1/" or "/" into a "V": "141/132" → "14V132".
     * Also accepts a backslash or pipe read instead of the slash.
     */
    private val SLASH_AS_V = Regex("""^(\d{1,3})[V\\|](\d{2,3})$""")
    private val GLUED_EN = Regex("""^([A-Z0-9]{2,4})EN$""")
    private val SET_CODE = Regex("""^[A-Z0-9]{2,4}$""")
    private val NOT_SET_CODES = setOf("EN", "HP", "EX", "GX", "VMAX", "VSTAR", "TM", "ILLUS", "THE")
    private val REGULATION_MARK = Regex("""^[A-K]$""")

    private val NAME_TRAILING_JUNK = Regex("""\s*(HP\s*\d+.*|\d+\s*HP.*|\d+\s*)$""", RegexOption.IGNORE_CASE)
    private val NAME_LEADING_JUNK = Regex("""^(BASIC|STAGE\s*[12]?|STAGE[12])\s+""", RegexOption.IGNORE_CASE)
    private val NOT_NAMES = Regex("""^(BASIC|STAGE\s*\d?|EVOLVES\b.*|TRAINER|ITEM|SUPPORTER|STADIUM|POKÉMON TOOL|ENERGY|TERA)$""", RegexOption.IGNORE_CASE)

    /** Lines that only trainer and energy cards print; their names are not Pokémon names. */
    private val NOT_POKEMON_CARD = Regex("""^(TRAINER|ITEM|SUPPORTER|STADIUM|POKÉMON TOOL|(SPECIAL\s+)?ENERGY)\b""", RegexOption.IGNORE_CASE)

    /** A year such as "2017" from the copyright line, not a number with a lost slash. */
    private val YEAR = Regex("""^(19|20)\d\d$""")

    /** Totals of English sets stay well below this; a larger one means the slash was put in the wrong place. */
    private const val MAX_TOTAL = 300

    /**
     * @param names when given, the name of a Pokémon card must contain a Pokémon's name; otherwise
     *   the name is left empty rather than taking an attack or ability name.
     */
    fun parse(lines: List<OcrLine>, imageHeight: Int, names: PokemonNameFinder? = null): CardReading? =
        parseWithLine(lines, imageHeight, names)?.reading

    fun parseWithLine(lines: List<OcrLine>, imageHeight: Int, names: PokemonNameFinder? = null): ParsedCard? {
        val tokenized = lines.map { it to tokenize(it.text) }
        val candidates = tokenized.mapNotNull { (line, tokens) -> readNumber(line, tokens, tokenized)?.let { line to it } }
            // Only when no number with a slash was read, and only in the enlarged bottom strip (elsewhere
            // any 4-digit text would do): a number whose slash OCR dropped ("2573" = 25/73).
            .ifEmpty {
                tokenized.filter { (line, _) -> line.fromStrip }
                    .mapNotNull { (line, tokens) -> readNumberWithoutSlash(tokens)?.let { line to it } }
            }
        if (candidates.isEmpty()) return null

        // Prefer readings from the lower part of the text area (the card's bottom strip), then those with a set code.
        val textTop = lines.minOf { it.top }
        val textBottom = lines.maxOf { it.bottom }
        val middle = (textTop + textBottom) / 2
        val (numberLine, reading) = candidates.maxBy { (line, r) ->
            (if (line.top > middle) 2 else 0) + (if (r.setCode != null) 1 else 0)
        }
        return ParsedCard(reading.copy(name = findName(lines, names)), numberLine)
    }

    /** Uppercases, removes separator noise, joins "123 / 193" and splits glued "PALEN" into "PAL EN". */
    internal fun tokenize(text: String): List<String> =
        text.uppercase()
            .replace(SLASH_SPACING, "/")
            .replace(SEPARATORS, " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { token -> fixSlashReadAsV(token) }
            .flatMap { token ->
                val glued = GLUED_EN.matchEntire(token)
                if (glued != null && isSetCode(glued.groupValues[1])) listOf(glued.groupValues[1], "EN") else listOf(token)
            }

    private fun readNumber(
        line: OcrLine,
        tokens: List<String>,
        allLines: List<Pair<OcrLine, List<String>>>,
    ): CardReading? {
        // 1) number/total, e.g. 015/128, TG05/TG30
        for ((index, token) in tokens.withIndex()) {
            val slash = token.indexOf('/')
            if (slash <= 0 || slash == token.length - 1) continue
            val total = normalizeNumberPart(token.substring(slash + 1)) ?: continue
            if (total.count { it.isDigit() } < 2) continue
            val number = normalizeNumberPart(token.substring(0, slash))
                ?: numberAfterSymbol(token.substring(0, slash))
                ?: continue
            val code = codeBeforeEn(tokens.subList(0, index))
                ?: codeBeforeEn(tokens.subList(index + 1, tokens.size))
                ?: nearestCodeBeforeEn(line, allLines)
            // Cards with a set code (Scarlet & Violet era on) always print zero-padded numbers
            // ("041/132"); "41/132" next to a set code is a partial read of e.g. "141/132".
            if (code != null && isTruncated(number, total)) continue
            return CardReading(code?.first, number, total, code?.second)
        }

        // 2) promo "SVP EN 045": set code, EN, number
        val enIndex = tokens.indexOf("EN")
        if (enIndex >= 1 && enIndex < tokens.size - 1) {
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
     * Normalises a number part read by OCR: fixes O→0 and I/L/|→1 where digits are expected,
     * keeps real prefixes like "TG". Returns null if it does not look like a card number.
     */
    internal fun normalizeNumberPart(raw: String): String? {
        val text = raw.trim().uppercase()
        if (text.isEmpty() || text.length > 5) return null
        val prefix = NUMBER_PREFIXES.filter { text.startsWith(it) && text.length > it.length }.maxByOrNull { it.length }
        val digitsPart = if (prefix != null) text.substring(prefix.length) else text
        val digits = digitsPart.map { c ->
            when (c) {
                'O', 'Q', 'D' -> '0'
                'I', 'L', '|' -> '1'
                'S' -> '5'
                'B' -> '8'
                else -> c
            }
        }.joinToString("")
        if (digits.isEmpty() || digits.length > 3 || !digits.all { it.isDigit() }) return null
        return (prefix ?: "") + digits
    }

    /**
     * The set symbol printed right before the number is often read as one or two characters glued
     * to it: "A25", "1109", "F0109" (regulation mark and symbol). Drops them when the rest is a number.
     */
    internal fun numberAfterSymbol(raw: String): String? {
        for (drop in 1..2) {
            val rest = raw.drop(drop)
            if (rest.none { it.isDigit() }) return null
            val number = normalizeNumberPart(rest) ?: continue
            if (number.all { it.isDigit() }) return number
        }
        return null
    }

    /**
     * A line that is just a 4–6 digit number, e.g. "2573" for 25/73 (OCR lost the slash).
     * Taken only when exactly one split gives a plausible number and total.
     */
    private fun readNumberWithoutSlash(tokens: List<String>): CardReading? {
        val digits = tokens.filter { token -> token.any { it.isDigit() } }
        if (digits.size != 1 || tokens.any { it.length > 2 && it != digits[0] }) return null
        val text = digits[0]
        if (text.length !in 4..6 || !text.all { it.isDigit() } || YEAR.matches(text)) return null
        val splits = (1..3).mapNotNull { at ->
            val number = text.take(at)
            val total = text.drop(at)
            val totalValue = total.toInt()
            val plausible = total.length in 2..3 && !total.startsWith("0") && totalValue <= MAX_TOTAL &&
                number.toInt() in 1..totalValue * 2
            if (plausible) CardReading(null, number, total) else null
        }
        return splits.singleOrNull()
    }

    /** "14V132" → "141/132" (the V swallowed "1/"); "123V193" → "123/193" (the V is just the slash). */
    internal fun fixSlashReadAsV(token: String): String {
        val match = SLASH_AS_V.matchEntire(token) ?: return token
        val (number, total) = match.destructured
        val fixedNumber = if (number.length < total.length) number + "1" else number
        return "$fixedNumber/$total"
    }

    internal fun isTruncated(number: String, total: String): Boolean {
        if (number.any { it.isLetter() } || total.any { it.isLetter() }) return false
        return number.length < total.length
    }

    private fun isSetCode(token: String) =
        SET_CODE.matches(token) && token.any { it.isLetter() } && token !in NOT_SET_CODES

    /** In "[mark] CODE EN" returns CODE (and the mark); the code must be directly before "EN". */
    private fun codeBeforeEn(tokens: List<String>): Pair<String, Char?>? {
        val en = tokens.lastIndexOf("EN")
        if (en < 1) return null
        val code = tokens[en - 1].takeIf { isSetCode(it) } ?: return null
        val mark = tokens.getOrNull(en - 2)?.takeIf { REGULATION_MARK.matches(it) }?.first()
        return code to mark
    }

    /** OCR often puts "G PAL EN" in a separate text block; take the "CODE EN" closest to the number line. */
    private fun nearestCodeBeforeEn(
        line: OcrLine,
        allLines: List<Pair<OcrLine, List<String>>>,
    ): Pair<String, Char?>? {
        val maxDistance = (line.height * 3).coerceAtLeast(30)
        return allLines
            .filter { (other, _) -> other !== line && abs(other.top - line.top) <= maxDistance }
            .mapNotNull { (other, tokens) -> codeBeforeEn(tokens)?.let { abs(other.top - line.top) to it } }
            .minByOrNull { it.first }
            ?.second
    }

    /** The card name is the tallest mostly-letters line in the top part of the card's text area. */
    internal fun findName(lines: List<OcrLine>, names: PokemonNameFinder? = null): String? {
        if (lines.isEmpty()) return null
        val top = lines.minOf { it.top }
        val bottom = lines.maxOf { it.bottom }
        val limit = top + (bottom - top) * 0.25
        val pokemonCard = lines.none { NOT_POKEMON_CARD.containsMatchIn(it.text.trim()) }
        val mustBePokemon = names != null && !names.isEmpty && pokemonCard
        return lines.asSequence()
            .filter { it.top <= limit }
            .map { it to cleanName(it.text) }
            .filter { (_, text) ->
                text.count { it.isLetter() } >= 3 && !text.contains('/') && !NOT_NAMES.matches(text) &&
                    // Names are short; long sentences are ability or attack text (full-art cards).
                    text.length <= 28 && text.split(' ').size <= 4 && !text.startsWith("ability", ignoreCase = true)
            }
            .filter { (_, text) -> !mustBePokemon || names?.containsPokemonName(text) == true }
            .maxByOrNull { (line, _) -> line.height }
            ?.second
    }

    private fun cleanName(text: String): String =
        text.trim()
            .replace(NAME_LEADING_JUNK, "")
            .replace(NAME_TRAILING_JUNK, "")
            .trim()
}
