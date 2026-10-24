package me.csepregi.pkmrec.sealed

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong

/**
 * The Sealed catalog as an Excel workbook (.xlsx), for reading only: one row per owned product
 * with purchase price, Cardmarket price and value, and a total row. Written by hand (an .xlsx is
 * a zip of a few XML files), so no spreadsheet library is needed.
 */
object SealedXlsx {

    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private val HEADERS = listOf(
        "Név", "Kiadás", "Típus", "Darab",
        "Beszerzési ár/db (Ft)", "Beszerzés összesen (Ft)",
        "Cardmarket ár/db (€)", "Cardmarket ár/db (Ft)", "Érték összesen (Ft)", "Különbség (Ft)",
        "Ár", "Ár dátuma", "Cardmarket",
    )
    private val WIDTHS = listOf(48, 22, 18, 8, 14, 14, 14, 14, 14, 14, 16, 12, 14)

    private const val STYLE_HEADER = 1
    private const val STYLE_FORINT = 2
    private const val STYLE_EURO = 3
    private const val STYLE_TOTAL = 4
    private const val STYLE_TOTAL_FORINT = 5

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private sealed interface Cell {
        data class Text(val value: String, val style: Int = 0) : Cell
        data class Number(val value: Double, val style: Int = 0) : Cell
        data class Link(val url: String, val label: String) : Cell
        data object Empty : Cell
    }

    /** [rate]: forints for one euro (null before the first ECB download), [rateDate]: its ECB date. */
    fun build(items: List<SealedItem>, rate: Double?, rateDate: String?, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val owned = items.filter { it.count > 0 }.sortedBy { it.name.lowercase() }
        val rows = mutableListOf<List<Cell>>()
        rows += HEADERS.map { Cell.Text(it, STYLE_HEADER) }
        for (item in owned) rows += row(item, rate, zone)

        val summary = SealedInventory.summary(owned, rate)
        rows += listOf(
            Cell.Text("Összesen", STYLE_TOTAL), Cell.Empty, Cell.Empty,
            Cell.Number(summary.pieces.toDouble(), STYLE_TOTAL),
            Cell.Empty,
            Cell.Number(summary.purchaseHuf.toDouble(), STYLE_TOTAL_FORINT),
            Cell.Empty, Cell.Empty,
            rate?.let { Cell.Number((summary.valueEur * it).roundToLong().toDouble(), STYLE_TOTAL_FORINT) } ?: Cell.Empty,
            summary.differenceHuf?.let { Cell.Number(it.toDouble(), STYLE_TOTAL_FORINT) } ?: Cell.Empty,
        )
        rows += emptyList<Cell>()
        rows += listOf(
            Cell.Text(
                rate?.let { "Árfolyam: 1 € = " + String.format(Locale.ROOT, "%.2f", it).replace('.', ',') + " Ft (EKB" + (rateDate?.let { d -> ", $d" } ?: "") + ")" }
                    ?: "Árfolyam: még nincs letöltve, a forint oszlopok üresek.",
            ),
        )
        rows += listOf(Cell.Text("A különbség csak azokat a termékeket számolja, amelyeknek beszerzési ára és Cardmarket ára is van."))

        return zip(
            "[Content_Types].xml" to CONTENT_TYPES,
            "_rels/.rels" to ROOT_RELS,
            "xl/workbook.xml" to WORKBOOK,
            "xl/_rels/workbook.xml.rels" to WORKBOOK_RELS,
            "xl/styles.xml" to STYLES,
            "xl/worksheets/sheet1.xml" to sheet(rows, dataRows = owned.size),
        )
    }

    private fun row(item: SealedItem, rate: Double?, zone: ZoneId): List<Cell> {
        val eur = item.price?.value
        val purchase = item.purchaseHuf
        val unitHuf = if (eur != null && rate != null) (eur * rate).roundToLong() else null
        fun forints(value: Long?) = value?.let { Cell.Number(it.toDouble(), STYLE_FORINT) } ?: Cell.Empty
        return listOf(
            Cell.Text(item.name),
            Cell.Text(item.set),
            Cell.Text(item.type),
            Cell.Number(item.count.toDouble()),
            forints(purchase),
            forints(purchase?.let { it * item.count }),
            eur?.let { Cell.Number(it, STYLE_EURO) } ?: Cell.Empty,
            forints(unitHuf),
            forints(unitHuf?.let { it * item.count }),
            forints(if (unitHuf != null && purchase != null) (unitHuf - purchase) * item.count else null),
            Cell.Text(
                when (item.price?.kind) {
                    null -> "nincs lekérve"
                    SealedPrice.KIND_PRICE -> "átlag"
                    SealedPrice.KIND_FEW -> "kevés ajánlat"
                    else -> "nincs ajánlat"
                },
            ),
            item.price?.let { Cell.Text(Instant.ofEpochMilli(it.time).atZone(zone).format(DATE)) } ?: Cell.Empty,
            item.productUrl?.let { Cell.Link(it, "Megnyitás") } ?: Cell.Empty,
        )
    }

    private fun sheet(rows: List<List<Cell>>, dataRows: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<cols>")
        WIDTHS.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        append("</cols><sheetData>")
        rows.forEachIndexed { r, cells ->
            append("""<row r="${r + 1}">""")
            cells.forEachIndexed { c, cell ->
                val ref = column(c) + (r + 1)
                when (cell) {
                    is Cell.Text -> append("""<c r="$ref" t="inlineStr"${style(cell.style)}><is><t xml:space="preserve">${escape(cell.value)}</t></is></c>""")
                    is Cell.Number -> append("""<c r="$ref"${style(cell.style)}><v>${number(cell.value)}</v></c>""")
                    is Cell.Link -> {
                        val formula = "HYPERLINK(\"${cell.url.replace("\"", "\"\"")}\",\"${cell.label}\")"
                        append("""<c r="$ref" t="str"><f>${escape(formula)}</f><v>${escape(cell.label)}</v></c>""")
                    }
                    Cell.Empty -> Unit
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        if (dataRows > 0) append("""<autoFilter ref="A1:${column(HEADERS.size - 1)}${dataRows + 1}"/>""")
        append("</worksheet>")
    }

    private fun style(id: Int) = if (id == 0) "" else """ s="$id""""

    private fun number(value: Double): String =
        if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    /** 0 → A, 25 → Z, 26 → AA. */
    internal fun column(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + n % 26)
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    /** XML text: escaped, without the control characters XML does not allow. */
    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Sealed" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    /** Styles: 0 plain, 1 bold header, 2 forints, 3 euros, 4 bold, 5 bold forints. */
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0 &quot;Ft&quot;"/><numFmt numFmtId="165" formatCode="#,##0.00 &quot;€&quot;"/></numFmts>
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="6">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
