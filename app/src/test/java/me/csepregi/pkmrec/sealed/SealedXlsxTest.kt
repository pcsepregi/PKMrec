package me.csepregi.pkmrec.sealed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

class SealedXlsxTest {

    private fun entries(bytes: ByteArray): Map<String, String> = ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes().decodeToString() }
    }

    @Test
    fun `the workbook has a row per owned product and a total row`() {
        val items = listOf(
            SealedItem(1, "Pitch Black Elite Trainer Box", "Elite Trainer Box", "Pitch Black", count = 2, purchaseHuf = 20000,
                productUrl = "https://www.cardmarket.com/en/Pokemon/Products/Elite-Trainer-Boxes/Pitch-Black-Elite-Trainer-Box",
                price = SealedPrice(SealedPrice.KIND_PRICE, 60.0, 7, time = 0)),
            SealedItem(2, "Ascended Heroes Mini Tin <Pikachu & Co>", "Tin", "Ascended Heroes", count = 1),
            SealedItem(3, "Sold Booster", "Booster", "", count = 0),
        )
        val files = entries(SealedXlsx.build(items, rate = 400.0, rateDate = "2026-10-23", zone = ZoneOffset.UTC))
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"),
            files.keys,
        )
        val sheet = files.getValue("xl/worksheets/sheet1.xml")
        // Sorted by name: the tin comes first, escaped; the sold booster is left out.
        assertTrue(sheet.indexOf("Ascended Heroes Mini Tin &lt;Pikachu &amp; Co&gt;") in 0 until sheet.indexOf("Pitch Black Elite Trainer Box"))
        assertFalse(sheet.contains("Sold Booster"))
        // ETB: 2 pieces, 20 000 Ft each, 60 € = 24 000 Ft each, value 48 000 Ft, difference +8 000 Ft.
        assertTrue(sheet.contains("""<c r="G3" s="2"><v>40000</v></c>"""))
        assertTrue(sheet.contains("""<c r="H3" s="3"><v>60</v></c>"""))
        assertTrue(sheet.contains("""<c r="J3" s="2"><v>48000</v></c>"""))
        assertTrue(sheet.contains("""<c r="K3" s="2"><v>8000</v></c>"""))
        assertTrue(sheet.contains("HYPERLINK(&quot;https://www.cardmarket.com/en/Pokemon/Products/Elite-Trainer-Boxes/Pitch-Black-Elite-Trainer-Box&quot;"))
        // Total row: 3 pieces, purchases 40 000 Ft, value 48 000 Ft, difference 8 000 Ft.
        assertTrue(sheet.contains("""<c r="E4" s="4"><v>3</v></c>"""))
        assertTrue(sheet.contains("""<c r="J4" s="5"><v>48000</v></c>"""))
        assertTrue(sheet.contains("1 € = 400,00 Ft (EKB, 2026-10-23)"))
        assertTrue(sheet.contains("""<autoFilter ref="A1:N3"/>"""))
    }

    @Test
    fun `pictures go into the first column of their rows`() {
        val items = listOf(
            SealedItem(1, "A Booster", count = 1, imageFile = "1.webp"),
            SealedItem(2, "B Booster", count = 1),
        )
        val jpeg = byteArrayOf(-1, -40, -1, -39)
        val files = entries(SealedXlsx.build(items, rate = null, rateDate = null) { item ->
            if (item.imageFile != null) SealedXlsx.Picture(jpeg, 200, 100) else null
        })
        assertTrue(files.containsKey("xl/media/image1.jpeg"))
        assertFalse(files.containsKey("xl/media/image2.jpeg"))
        assertTrue(files.getValue("[Content_Types].xml").contains("drawing+xml"))
        val sheet = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("""<row r="2" ht="64" customHeight="1">"""))
        assertTrue(sheet.contains("""<row r="3">"""))
        assertTrue(sheet.contains("""<drawing r:id="rId1"/>"""))
        // 200 × 100 scaled to 80 × 40 pixels, anchored at row 2 (index 1).
        val drawing = files.getValue("xl/drawings/drawing1.xml")
        assertTrue(drawing.contains("<xdr:row>1</xdr:row>"))
        assertTrue(drawing.contains("""<xdr:ext cx="762000" cy="381000"/>"""))
    }

    @Test
    fun `without pictures there is no drawing`() {
        val files = entries(SealedXlsx.build(listOf(SealedItem(1, "A", count = 1)), rate = null, rateDate = null))
        assertFalse(files.keys.any { it.startsWith("xl/drawings") || it.startsWith("xl/media") })
        assertFalse(files.getValue("xl/worksheets/sheet1.xml").contains("<drawing"))
    }

    @Test
    fun `columns are lettered like Excel`() {
        assertEquals("A", SealedXlsx.column(0))
        assertEquals("M", SealedXlsx.column(12))
        assertEquals("Z", SealedXlsx.column(25))
        assertEquals("AA", SealedXlsx.column(26))
    }
}
