package me.csepregi.pkmrec.recognition

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A small greyscale image (0 = black, 1 = white) with optional opacity, row by row.
 * Plain Kotlin, so set symbol matching can be unit tested without Android.
 */
class GrayImage(
    val width: Int,
    val height: Int,
    val pixels: FloatArray,
    val alpha: FloatArray? = null,
) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "bad image size" }
        require(alpha == null || alpha.size == pixels.size) { "bad alpha size" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    /** The [w] × [h] part starting at ([x], [y]), clipped to the image. */
    fun cropped(x: Int, y: Int, w: Int, h: Int): GrayImage {
        val left = x.coerceIn(0, width - 1)
        val top = y.coerceIn(0, height - 1)
        val cw = w.coerceAtMost(width - left).coerceAtLeast(1)
        val ch = h.coerceAtMost(height - top).coerceAtLeast(1)
        val p = FloatArray(cw * ch) { i -> pixels[(top + i / cw) * width + left + i % cw] }
        val a = alpha?.let { al -> FloatArray(cw * ch) { i -> al[(top + i / cw) * width + left + i % cw] } }
        return GrayImage(cw, ch, p, a)
    }

    /** The image drawn over a plain [background] (0..1), without opacity. */
    fun over(background: Float): GrayImage {
        val a = alpha ?: return this
        return GrayImage(width, height, FloatArray(pixels.size) { i -> a[i] * pixels[i] + (1 - a[i]) * background })
    }

    /** Resized by averaging the source pixels each target pixel covers (box filter). */
    fun scaled(newWidth: Int, newHeight: Int): GrayImage {
        require(newWidth > 0 && newHeight > 0)
        val sx = width.toFloat() / newWidth
        val sy = height.toFloat() / newHeight
        val out = FloatArray(newWidth * newHeight)
        val outAlpha = alpha?.let { FloatArray(newWidth * newHeight) }
        for (ty in 0 until newHeight) {
            val y0 = ty * sy
            val y1 = (ty + 1) * sy
            for (tx in 0 until newWidth) {
                val x0 = tx * sx
                val x1 = (tx + 1) * sx
                var sum = 0f
                var sumAlpha = 0f
                var weight = 0f
                for (y in floor(y0).toInt() until min(height, ceil(y1).toInt())) {
                    val wy = min(y + 1f, y1) - max(y.toFloat(), y0)
                    if (wy <= 0f) continue
                    for (x in floor(x0).toInt() until min(width, ceil(x1).toInt())) {
                        val wx = min(x + 1f, x1) - max(x.toFloat(), x0)
                        if (wx <= 0f) continue
                        val w = wx * wy
                        val i = y * width + x
                        sum += pixels[i] * w
                        if (alpha != null) sumAlpha += alpha[i] * w
                        weight += w
                    }
                }
                val o = ty * newWidth + tx
                out[o] = if (weight > 0f) sum / weight else 0f
                if (outAlpha != null) outAlpha[o] = if (weight > 0f) sumAlpha / weight else 0f
            }
        }
        return GrayImage(newWidth, newHeight, out, outAlpha)
    }

    /** Scaled to [newHeight], keeping the aspect ratio. */
    fun scaledToHeight(newHeight: Int): GrayImage =
        scaled(max(1, Math.round(width.toFloat() * newHeight / height)), newHeight)

    /** The part inside the opaque pixels' bounding box (transparent margins of a symbol image removed). */
    fun trimmed(threshold: Float = 0.1f): GrayImage {
        val a = alpha ?: return this
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (a[y * width + x] > threshold) {
                left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
            }
        }
        if (right < 0) return this
        val w = right - left + 1
        val h = bottom - top + 1
        val p = FloatArray(w * h) { i -> pixels[(top + i / w) * width + left + i % w] }
        val al = FloatArray(w * h) { i -> a[(top + i / w) * width + left + i % w] }
        return GrayImage(w, h, p, al)
    }
}
