package me.csepregi.pkmrec.sealed

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.cardmarket.CardmarketImage
import java.io.File

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

    private fun dir(context: Context) = File(context.filesDir, DIR)
}
