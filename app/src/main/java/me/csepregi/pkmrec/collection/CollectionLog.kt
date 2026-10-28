package me.csepregi.pkmrec.collection

import android.content.Context
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.diagnostics.ErrorLog
import me.csepregi.pkmrec.tcg.TcgCard
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Every change the app made to the TCG Collector collection (add, undo, remove) and to the
 * Pokedex list (list-add, list-remove: before/after = 0 or 1),
 * kept only on the phone, newest [MAX_ENTRIES]. One tab-separated line per change.
 */
object CollectionLog {

    const val MAX_ENTRIES = 500

    private const val FILE_NAME = "collection-log.tsv"
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    data class Entry(
        val time: String,
        /** "add", "undo", "remove", "list-add" or "list-remove". */
        val action: String,
        val cardId: Int,
        val name: String,
        val expansion: String,
        val number: String,
        val variant: String,
        val before: Int,
        val after: Int,
    ) {
        fun toLine(): String = listOf(time, action, cardId, name, expansion, number, variant, before, after)
            .joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }

        companion object {
            fun now(action: String, card: TcgCard, variant: String, before: Int, after: Int) = Entry(
                LocalDateTime.now().format(TIME), action, card.id, card.name, card.expansionName, card.number, variant, before, after,
            )

            /** Null for a line that cannot be read (e.g. cut off). */
            fun parse(line: String): Entry? {
                val f = line.split('\t')
                if (f.size != 9) return null
                return Entry(
                    f[0], f[1], f[2].toIntOrNull() ?: return null, f[3], f[4], f[5], f[6],
                    f[7].toIntOrNull() ?: return null, f[8].toIntOrNull() ?: return null,
                )
            }
        }
    }

    /** Appends an entry, dropping the oldest above [MAX_ENTRIES]. Never throws. */
    fun log(context: Context, entry: Entry) {
        synchronized(this) {
            runCatching {
                val target = file(context)
                target.appendText(entry.toLine() + "\n")
                val lines = target.readLines().filter { it.isNotBlank() }
                if (lines.size > MAX_ENTRIES) target.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n", postfix = "\n"))
            }.onFailure { ErrorLog.log("collection log", it) }
        }
    }

    /** Newest first. */
    fun entries(context: Context): List<Entry> = synchronized(this) {
        runCatching { file(context).takeIf { it.exists() }?.readLines() }.getOrNull().orEmpty()
            .mapNotNull { Entry.parse(it) }
            .reversed()
    }

    /** Readable text of all entries, oldest first, ready to paste. */
    fun report(context: Context): String = buildString {
        appendLine("PKMrec ${BuildConfig.VERSION_NAME} collection log")
        val all = entries(context).reversed()
        if (all.isEmpty()) appendLine("(empty)")
        all.forEach { appendLine(format(it)) }
    }

    /** e.g. "2026-10-24 19:50:12  add  Pikachu · Scarlet & Violet 151 · 025/165 · Normal  0 → 1" */
    fun format(e: Entry): String =
        "${e.time}  ${e.action}  ${e.name} · ${e.expansion} · ${e.number} · ${e.variant}  ${e.before} → ${e.after}"

    fun clear(context: Context) {
        synchronized(this) { runCatching { file(context).delete() } }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
