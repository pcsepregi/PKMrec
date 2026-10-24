package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SymbolMatcherTest {

    /** A black shape on a transparent 64 × 64 image, like the bundled symbols. */
    private fun symbol(inside: (Float, Float) -> Boolean): GrayImage {
        val size = 64
        val alpha = FloatArray(size * size) { i ->
            val x = (i % size + 0.5f) / size * 2 - 1
            val y = (i / size + 0.5f) / size * 2 - 1
            if (inside(x, y)) 1f else 0f
        }
        return GrayImage(size, size, FloatArray(size * size), alpha)
    }

    private val circle = symbol { x, y -> x * x + y * y <= 0.8f }
    private val plus = symbol { x, y -> abs(x) < 0.3f || abs(y) < 0.3f }
    private val triangle = symbol { x, y -> y > -0.8f && abs(x) < (y + 0.8f) / 1.6f * 0.9f }

    /** A camera crop: light card, the shape drawn [symbolSize] pixels tall at ([left], [top]), some noise and a "number". */
    private fun crop(shape: GrayImage, symbolSize: Int, left: Int, top: Int, inverted: Boolean = false): GrayImage {
        val w = 100
        val h = 40
        val random = Random(7)
        val drawn = shape.scaled(symbolSize, symbolSize)
        val pixels = FloatArray(w * h) { 0.9f + random.nextFloat() * 0.08f }
        for (y in 0 until symbolSize) for (x in 0 until symbolSize) {
            val a = drawn.alpha!![y * symbolSize + x]
            val i = (top + y) * w + left + x
            pixels[i] = pixels[i] * (1 - a) + 0.1f * a
        }
        // Digits to the right of the symbol: vertical strokes.
        for (y in 10 until 30) for (x in 60 until 95) if ((x / 4) % 2 == 0) pixels[y * w + x] = 0.15f
        if (inverted) for (i in pixels.indices) pixels[i] = 1 - pixels[i]
        return GrayImage(w, h, pixels)
    }

    private val symbols = mapOf("circle" to circle, "plus" to plus, "triangle" to triangle)

    @Test
    fun `finds the printed symbol among the candidates`() {
        for ((name, shape) in symbols) {
            val ranked = SymbolMatcher.rank(crop(shape, 24, 20, 8), symbols)
            assertEquals(name, ranked.first().first)
            assertEquals(name, SymbolMatcher.decide(ranked))
        }
    }

    @Test
    fun `works on light symbols on a dark card and at other sizes`() {
        assertEquals("plus", SymbolMatcher.decide(SymbolMatcher.rank(crop(plus, 24, 20, 8, inverted = true), symbols)))
        assertEquals("triangle", SymbolMatcher.decide(SymbolMatcher.rank(crop(triangle, 18, 30, 12), symbols)))
    }

    @Test
    fun `no decision without a symbol or between equal scores`() {
        val blank = GrayImage(100, 40, FloatArray(4000) { 0.9f })
        assertNull(SymbolMatcher.decide(SymbolMatcher.rank(blank, symbols)))
        assertNull(SymbolMatcher.decide(listOf("a" to 0.9f, "b" to 0.85f)))
        assertNull(SymbolMatcher.decide(listOf("a" to 0.3f)))
        assertEquals("a", SymbolMatcher.decide(listOf("a" to 0.8f)))
    }

    @Test
    fun `image helpers keep sizes and averages`() {
        val img = GrayImage(4, 2, floatArrayOf(0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        val half = img.scaled(2, 1)
        assertEquals(0.75f, half[0, 0], 1e-4f)
        assertEquals(2, img.cropped(1, 0, 2, 5).height)
        val trimmed = circle.trimmed()
        assertTrue(trimmed.width < 64 && trimmed.height < 64)
    }
}
