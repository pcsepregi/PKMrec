package me.csepregi.pkmrec.recognition

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A line of text read from a photo, in photo pixels: its centre, its size along ([width]) and
 * across ([height]) the text, and the text's slope ([angle], radians, 0 = level).
 * Cards on a table lie at any angle, so a level bounding box is not enough.
 */
data class PhotoLine(
    val text: String,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val angle: Float = 0f,
) {
    /** The line as seen from a frame turned by [frameAngle] around ([ox], [oy]): level lines for a card at that angle. */
    fun inFrame(ox: Float, oy: Float, frameAngle: Float): OcrLine {
        val c = cos(-frameAngle)
        val s = sin(-frameAngle)
        val x = ox + (cx - ox) * c - (cy - oy) * s
        val y = oy + (cx - ox) * s + (cy - oy) * c
        return OcrLine(
            text,
            (y - height / 2).roundToInt(),
            (y + height / 2).roundToInt(),
            (x - width / 2).roundToInt(),
            (x + width / 2).roundToInt(),
        )
    }

    companion object {
        /** From ML Kit's corner points (top left, top right, bottom right, bottom left, as the text reads). */
        fun fromCorners(text: String, corners: List<Pair<Float, Float>>): PhotoLine? {
            if (corners.size != 4) return null
            val (tl, tr, br, bl) = corners
            val width = (dist(tl, tr) + dist(bl, br)) / 2
            val height = (dist(tl, bl) + dist(tr, br)) / 2
            if (width <= 0f || height <= 0f) return null
            val angle = atan2((tr.second - tl.second) + (br.second - bl.second), (tr.first - tl.first) + (br.first - bl.first))
            return PhotoLine(text, corners.sumOf { it.first.toDouble() }.toFloat() / 4, corners.sumOf { it.second.toDouble() }.toFloat() / 4, width, height, angle)
        }

        private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
    }
}


/**
 * Finds the cards in the text lines of a photo of several cards laid out on a table (Tömeges eladás).
 *
 * Every card number (`123/193`) marks a card. The card is estimated around its number from the
 * number's text size and slope, number at the bottom left as on cards from Sun & Moon on, and the
 * card's name and set code are looked for in that area. A card whose number could not be read is
 * found by its name. Plain Kotlin, so it can be tested with real OCR output.
 */
object MultiCardLayout {

    /** Card height in number-line heights, and the card's width to height ratio. */
    private const val CARD_HEIGHTS = 50
    private const val CARD_ASPECT = 63f / 88f

    /** Pieces are a quarter of the photo's long side and overlap by a quarter. */
    private const val PIECES_PER_SIDE = 4
    private const val MIN_PIECE = 640

    /** Lines of one card are about as level as its number; other cards on the table lie at other angles. */
    private const val MAX_ANGLE_DIFFERENCE = (20 * PI / 180).toFloat()

    /** Card-type labels printed just above a trainer card's name. */
    private val TRAINER_LABELS = listOf("supporter", "item", "pokemontool", "stadium")

    /** A name line is clearly taller than the card's other text. */
    private const val NAME_HEIGHT_FACTOR = 1.3f

    /** A number whose slash OCR lost, zero-padded as on Scarlet & Violet cards: "224182" = 224/182. */
    private val SIX_DIGITS = Regex("""^(\d{3})(\d{3})$""")

    /**
     * One card found on a photo, by its number or, when no number was read, by its name
     * ([CardReading.isNameOnly]). [line] is the number line, or the name line.
     */
    data class Found(val reading: CardReading, val line: PhotoLine)

    /** A part of the photo, in photo pixels (right and bottom exclusive). */
    internal data class Piece(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Overlapping pieces covering a [width] x [height] photo. */
    internal fun pieces(width: Int, height: Int): List<Piece> {
        val size = max(MIN_PIECE, max(width, height) / PIECES_PER_SIDE)
        val step = size * 3 / 4
        fun starts(length: Int): List<Int> {
            if (length <= size) return listOf(0)
            val count = (length - size + step - 1) / step + 1
            return (0 until count).map { i -> min(i * step, length - size) }
        }
        return starts(height).flatMap { y -> starts(width).map { x -> Piece(x, y, min(x + size, width), min(y + size, height)) } }
    }

    /**
     * The same text read in several pieces (or in a piece and the whole photo) is kept once: a line
     * lying inside a longer line of about the same size and slope is dropped.
     */
    internal fun distinctLines(lines: List<PhotoLine>): List<PhotoLine> {
        val kept = mutableListOf<PhotoLine>()
        lines.sortedByDescending { it.text.length }.forEach { line ->
            val duplicate = kept.any { k ->
                val sizeRatio = max(k.height, line.height) / min(k.height, line.height)
                if (sizeRatio > 1.6f || angleDifference(k.angle, line.angle) > MAX_ANGLE_DIFFERENCE) return@any false
                val inK = line.inFrame(k.cx, k.cy, k.angle)
                val cx = (inK.left + inK.right) / 2f
                val cy = (inK.top + inK.bottom) / 2f
                abs(cx - k.cx) <= k.width / 2 + line.height / 2 && abs(cy - k.cy) <= k.height / 2
            }
            if (!duplicate) kept += line
        }
        return kept
    }

    /**
     * The cards in a photo's lines: every card number marks a card; a card name outside every
     * numbered card (its number unreadable) marks a card found by name.
     */
    fun cards(lines: List<PhotoLine>, names: PokemonNameFinder?): List<Found> {
        val anchors = lines.mapNotNull { line -> numberOf(line)?.let { Found(it, line) } }
        val usual = if (lines.isEmpty()) 0f else lines.map { it.height }.sorted()[lines.size / 2]
        val claimed = mutableSetOf<PhotoLine>()
        val numbered = distinct(anchors).map { anchor ->
            val number = anchor.line
            // Every line seen level with the card, as if the card lay straight.
            val numberLine = number.inFrame(number.cx, number.cy, number.angle)
            val framed = lines.filter { it !== number && angleDifference(it.angle, number.angle) <= MAX_ANGLE_DIFFERENCE }
                .map { it to it.inFrame(number.cx, number.cy, number.angle) } + (number to numberLine)
            val inArea = cardArea(numberLine, framed.map { it.second }).toSet()
            val areaLines = framed.filter { it.second in inArea }
            claimed += areaLines.map { it.first }
            val area = inArea.toList()
            val parsed = CardTextParser.parseWithLine(area, numberLine.bottom, names)
            val reading = if (parsed != null && parsed.numberLine == numberLine) parsed.reading else anchor.reading
            // The card's title: a name line nearest to where the top of the card is expected.
            val titleY = numberLine.bottom - (CARD_HEIGHTS - 3) * numberLine.height
            val title = areaLines.mapNotNull { (line, level) -> nameOf(line, lines, names, usual)?.let { it to level } }
                .minByOrNull { (_, level) -> abs((level.top + level.bottom) / 2 - titleY) }?.first
            Found(reading.copy(name = title ?: reading.name ?: CardTextParser.findName(area, names)), number)
        }
        val named = nameCards(lines.filter { it !in claimed }, lines, names, usual)
        return (numbered + named).sortedWith(compareBy<Found>({ rowOf(it.line) }, { it.line.cx }))
    }

    /** The card number on one line; numbers whose total was cut off ("110/01") are not trusted. */
    private fun numberOf(line: PhotoLine): CardReading? {
        val level = line.inFrame(line.cx, line.cy, line.angle)
        val reading = CardTextParser.parseWithLine(listOf(level), level.bottom, null)?.reading
        if (reading != null) {
            val total = reading.total ?: return reading.takeIf { isPromoCode(it.setCode) }
            // "110/01": total cut off. "10/085": a padded total belongs to a padded number, so the number was cut off.
            val cut = total.length < reading.number.length ||
                (total.length == 3 && total.startsWith("0") && reading.number.all { it.isDigit() } && reading.number.length < 3)
            return if (cut) null else reading
        }
        val tokens = line.text.trim().split(Regex("\\s+"))
        val digits = tokens.singleOrNull { SIX_DIGITS.matches(it) } ?: return null
        if (tokens.any { it != digits && it.length > 3 }) return null
        val (number, total) = SIX_DIGITS.matchEntire(digits)!!.destructured
        return if (total.toInt() in 10..300) CardReading(null, number, total) else null
    }

    /**
     * Cards found by their name: a Pokémon's name in a line clearly taller than the photo's
     * usual text, or a line right under a trainer label ("Supporter", "Item", "Pokémon Tool").
     * The same name read twice close together is one card.
     */
    private fun nameCards(candidates: List<PhotoLine>, all: List<PhotoLine>, names: PokemonNameFinder?, usual: Float): List<Found> {
        val found = candidates.mapNotNull { line ->
            nameOf(line, all, names, usual)?.let { Found(CardReading(null, "", null, name = it), line) }
        }
        val kept = mutableListOf<Found>()
        found.sortedByDescending { it.line.height }.forEach { f ->
            val near = kept.any { k -> hypot(f.line.cx - k.line.cx, f.line.cy - k.line.cy) < 5 * max(f.line.height, k.line.height) }
            if (!near) kept += f
        }
        return kept
    }

    /** Promo codes print no total ("SVP EN 045"); other code-only readings are noise on a table photo. */
    private fun isPromoCode(code: String?): Boolean =
        code != null && (code.endsWith("P") || code in setOf("SWSH", "SM", "XY", "BW", "DP", "HGSS"))

    /**
     * The card name [line] shows, or null: a Pokémon's name in a line clearly taller than the
     * photo's [usual] text, or the line right under a trainer label.
     */
    private fun nameOf(line: PhotoLine, all: List<PhotoLine>, names: PokemonNameFinder?, usual: Float): String? {
        val text = line.text.trim()
        if (text.length > 28 || text.split(Regex("\\s+")).size > 4 || text.count { it.isLetter() } < 4) return null
        if (text.startsWith("evolves", ignoreCase = true) || isTrainerLabel(text) || text.contains("trainer", ignoreCase = true)) return null
        val pokemon = if (line.height >= usual * NAME_HEIGHT_FACTOR || startsWithStage(text)) names?.find(text) else null
        return pokemon ?: text.takeIf { hasTrainerLabelAbove(line, all) }?.let { cleanTrainerName(it) }?.takeIf { it.length >= 4 }
    }

    /** "BASIC" or "STAGE" glued to a Pokémon's name, however OCR spells it: "CASIGNZekrom", "eASIE Delibird". */
    private fun startsWithStage(text: String): Boolean {
        val head = text.lowercase().filter { it in 'a'..'z' }.take(5)
        return head.length == 5 && (levenshtein(head, "basic") <= 2 || levenshtein(head, "stage") <= 2)
    }

    /** A trainer label read just above [line] on the same card, left-aligned with it. */
    private fun hasTrainerLabelAbove(line: PhotoLine, all: List<PhotoLine>): Boolean {
        val self = line.inFrame(line.cx, line.cy, line.angle)
        return all.any { other ->
            if (other === line || angleDifference(other.angle, line.angle) > MAX_ANGLE_DIFFERENCE || !isTrainerLabel(other.text)) return@any false
            val o = other.inFrame(line.cx, line.cy, line.angle)
            val ocx = (o.left + o.right) / 2
            val ocy = (o.top + o.bottom) / 2
            ocy < self.top && ocy > self.top - 3 * self.height && ocx >= self.left - self.height && ocx <= self.right
        }
    }

    internal fun isTrainerLabel(text: String): Boolean {
        val t = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).filter { it in 'a'..'z' }
        if (t.length < 4) return false
        return TRAINER_LABELS.any { label ->
            val head = t.take(label.length + 1)
            levenshtein(head, label) <= max(1, label.length / 3) || levenshtein(t.take(label.length), label) <= max(1, label.length / 3) ||
                // The start cut off by a neighbouring card: "orter", "Dorter".
                (t.length >= 5 && t.length < label.length && levenshtein(t, label.takeLast(t.length)) <= 1)
        }
    }

    /** "Team Rocket's Ariana" as printed; OCR noise around it (digits, stray symbols) dropped. */
    private fun cleanTrainerName(text: String): String =
        text.replace(Regex("""[^\p{L}' .-]"""), "").replace(Regex("\\s+"), " ").trim()

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    /** One anchor per number: number lines close together (split or read twice) are one card. */
    private fun distinct(anchors: List<Found>): List<Found> {
        val kept = mutableListOf<Found>()
        anchors.sortedWith(compareByDescending<Found> { it.reading.setCode != null }.thenByDescending { it.line.text.length }).forEach { a ->
            val near = kept.any { k ->
                val h = max(a.line.height, k.line.height).coerceAtLeast(1f)
                hypot(a.line.cx - k.line.cx, a.line.cy - k.line.cy) < 6 * h
            }
            if (!near) kept += a
        }
        return kept
    }

    /** The lines inside the card estimated above and to the right of its number line (all level). */
    internal fun cardArea(numberLine: OcrLine, lines: List<OcrLine>): List<OcrLine> {
        val h = numberLine.height.coerceAtLeast(1)
        val height = CARD_HEIGHTS * h
        val width = (height * CARD_ASPECT).toInt()
        val left = numberLine.left - width / 10
        val right = numberLine.left + width
        val top = numberLine.bottom - height
        val bottom = numberLine.bottom + h
        return lines.filter { line ->
            val cx = (line.left + line.right) / 2
            val cy = (line.top + line.bottom) / 2
            cx in left..right && cy in top..bottom
        }
    }

    private fun angleDifference(a: Float, b: Float): Float {
        var d = abs(a - b) % (2 * PI).toFloat()
        if (d > PI) d = (2 * PI).toFloat() - d
        return d
    }

    /** Rows of half a card height: number lines at about the same height share one. */
    private fun rowOf(line: PhotoLine): Int = (line.cy / (line.height.coerceAtLeast(1f) * CARD_HEIGHTS / 2)).toInt()
}
