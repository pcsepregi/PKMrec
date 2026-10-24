package me.csepregi.pkmrec.sealed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.cardmarket.CardmarketImage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Product pictures downloaded from Cardmarket, kept in the app's files (they are not in the export). */
object SealedImages {

    private const val DIR = "sealed_images"

    /** The item's picture, or null when it was never downloaded or is missing (e.g. after an import). */
    fun file(context: Context, item: SealedItem): File? =
        item.imageFile?.let { File(dir(context), it) }?.takeIf { it.isFile && it.length() > 0 }

    /** Writes the picture and returns its file name. */
    suspend fun save(context: Context, id: Int, image: CardmarketImage): String = withContext(Dispatchers.IO) {
        val dir = dir(context).apply { mkdirs() }
        // A new name each time, so the picture on screen is not taken from the old one's cache.
        val name = "$id-${System.currentTimeMillis()}.${image.extension}"
        File(dir, name).writeBytes(image.bytes)
        dir.listFiles { f -> f.name.startsWith("$id-") && f.name != name }?.forEach { it.delete() }
        name
    }

    /**
     * The item's picture as a white-backed JPEG for the Excel export (Excel cannot show every
     * WebP), at most [size] pixels on its longest side; null without a picture. Reads a file.
     */
    fun forSheet(context: Context, item: SealedItem, size: Int): SealedXlsx.Picture? {
        val file = file(context, item) ?: return null
        val source = BitmapFactory.decodeFile(file.path) ?: return null
        val scale = minOf(1.0, size.toDouble() / maxOf(source.width, source.height))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        source.recycle()
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return SealedXlsx.Picture(out.toByteArray(), width, height)
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)
}
