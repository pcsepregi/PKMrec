package me.csepregi.pkmrec.tcg

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** The logged-in TCG Collector user, as found in `appState.userDto`. */
data class TcgUser(
    val id: Int,
    val displayName: String,
    val isPremium: Boolean,
) {
    companion object {
        /** Returns the user from a page's appState, or null when the page was served to a logged-out visitor. */
        fun fromAppState(appState: JsonObject): TcgUser? {
            val dto = appState["userDto"] as? JsonObject ?: return null
            val id = (dto["id"] as? JsonPrimitive)?.intOrNull ?: return null
            val name = (dto["displayName"] as? JsonPrimitive)?.content.orEmpty()
            val premium = (dto["isPremiumEnabled"] as? JsonPrimitive)?.booleanOrNull ?: false
            return TcgUser(id, name, premium)
        }
    }
}
