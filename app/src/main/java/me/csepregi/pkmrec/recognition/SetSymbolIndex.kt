package me.csepregi.pkmrec.recognition

/** One English set whose symbol is bundled: pokemontcg.io set id (also the image's file name), set code, name. */
data class SetSymbolEntry(val id: String, val code: String?, val name: String)

/**
 * The bundled set symbols (`assets/set_symbols/index.tsv`, downloaded from pokemontcg.io at build time)
 * and how a TCG Collector expansion is found among them: by set code, then by name.
 */
class SetSymbolIndex(val entries: List<SetSymbolEntry>) {

    private val byCode = entries.filter { it.code != null }.groupBy { it.code!!.uppercase() }

    fun find(expansionName: String, expansionCode: String?): SetSymbolEntry? {
        val name = normalize(expansionName)
        val sameCode = expansionCode?.let { byCode[it.uppercase()] }.orEmpty()
        if (sameCode.size == 1) return sameCode.first()
        if (sameCode.size > 1) return byName(sameCode, name) ?: sameCode.first()
        return byName(entries, name)
    }

    /**
     * Same name; else the longest name contained in ours ("Hidden Fates Shiny Vault" → "Shiny Vault",
     * "Base Set" → "Base"); else the shortest name containing ours ("151" → "Scarlet & Violet—151").
     */
    private fun byName(candidates: List<SetSymbolEntry>, name: String): SetSymbolEntry? {
        if (name.length < 3) return null
        val named = candidates.map { it to normalize(it.name) }.filter { (_, other) -> other.length >= 3 }
        return named.firstOrNull { (_, other) -> other == name }?.first
            ?: named.filter { (_, other) -> name.contains(other) }.maxByOrNull { it.second.length }?.first
            ?: named.filter { (_, other) -> other.contains(name) }.minByOrNull { it.second.length }?.first
    }

    companion object {
        /** Lines of `id<TAB>code<TAB>name`; the code may be empty. */
        fun parse(text: String): SetSymbolIndex = SetSymbolIndex(
            text.lineSequence()
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3 || parts[0].isBlank() || parts[2].isBlank()) null
                    else SetSymbolEntry(parts[0], parts[1].ifBlank { null }, parts[2])
                }
                .toList(),
        )

        /** Lower-case letters and digits only; "&" and "and" are dropped. */
        internal fun normalize(name: String): String =
            name.lowercase().replace("&", " ").replace(Regex("\\band\\b"), " ").filter { it.isLetterOrDigit() }
    }
}
