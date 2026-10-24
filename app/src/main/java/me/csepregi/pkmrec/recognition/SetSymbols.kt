package me.csepregi.pkmrec.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.util.concurrent.ConcurrentHashMap

/**
 * The set symbols bundled in `assets/set_symbols/` (`index.tsv` and one `<id>.png` per set).
 * The images are downloaded by the build (`.github/scripts/fetch-set-symbols.py`); without them
 * the index is empty and set symbols are simply not used. Reads files: call off the main thread.
 */
object SetSymbols {

    private const val DIR = "set_symbols"

    @Volatile private var cached: SetSymbolIndex? = null
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    fun index(context: Context): SetSymbolIndex = cached ?: load(context).also { cached = it }

    private fun load(context: Context): SetSymbolIndex =
        runCatching {
            context.assets.open("$DIR/index.tsv").bufferedReader().use { SetSymbolIndex.parse(it.readText()) }
        }.getOrDefault(SetSymbolIndex(emptyList()))

    /** The image of the set symbol [id] ([SetSymbolEntry.id]), or null when it is not bundled. */
    fun bitmap(context: Context, id: String): Bitmap? =
        bitmaps[id] ?: runCatching {
            context.assets.open("$DIR/$id.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { bitmaps[id] = it }

    /** The symbol as greyscale with transparency, for [SymbolMatcher]. */
    fun grayImage(context: Context, id: String): GrayImage? {
        val bitmap = bitmap(context, id) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val pixels = FloatArray(w * h) { i ->
            val c = argb[i]
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        }
        val alpha = FloatArray(w * h) { i -> Color.alpha(argb[i]) / 255f }
        return GrayImage(w, h, pixels, alpha)
    }
}
