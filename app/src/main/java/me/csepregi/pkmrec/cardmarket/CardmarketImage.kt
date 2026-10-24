package me.csepregi.pkmrec.cardmarket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/** Longest side of a downloaded product picture, in pixels. */
const val CARDMARKET_IMAGE_SIZE = 600

/** A picture read back from the WebView: its bytes and file extension ("webp" or "png"). */
class CardmarketImage(val bytes: ByteArray, val extension: String) {

    /** What [CARDMARKET_IMAGE_JS] saw. */
    sealed interface Read {
        data class Loaded(val image: CardmarketImage) : Read
        data object Loading : Read
        data object Challenge : Read
        data class Failed(val reason: String) : Read
    }

    companion object {
        private val DATA_URL = Regex("""^data:image/(webp|png|jpeg);base64,(.+)$""")

        fun fromScriptResult(result: String?): Read = runCatching {
            val inner = (Json.parseToJsonElement(result ?: "null") as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return Read.Failed("no script result")
            val o = Json.parseToJsonElement(inner).jsonObject
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            when {
                str("challenge") == "true" -> Read.Challenge
                str("loading") == "true" -> Read.Loading
                str("data") != null -> {
                    val match = DATA_URL.find(str("data")!!) ?: return Read.Failed("not a picture")
                    val bytes = Base64.getDecoder().decode(match.groupValues[2])
                    if (bytes.isEmpty()) Read.Failed("empty picture")
                    else Read.Loaded(CardmarketImage(bytes, match.groupValues[1].replace("jpeg", "jpg")))
                }
                else -> Read.Failed(str("error") ?: "no picture")
            }
        }.getOrElse { Read.Failed("unreadable script result: ${it.message}") }
    }
}

/**
 * Run on a picture opened in the WebView: draws it on a canvas (the picture is the page itself,
 * so the canvas may be read) and returns it as a data URL, scaled to [CARDMARKET_IMAGE_SIZE].
 */
const val CARDMARKET_IMAGE_JS = """
(function () {
  var title = document.title || '';
  if (/just a moment|attention required|verify you are human/i.test(title) || document.querySelector('#challenge-form, #challenge-running, .cf-turnstile, #cf-challenge-running')) {
    return JSON.stringify({ challenge: 'true' });
  }
  var img = document.images.length === 1 ? document.images[0] : null;
  if (!img) return JSON.stringify({ error: 'no picture on the page (' + (document.contentType || '?') + ', ' + title.substring(0, 80) + ')' });
  if (!img.complete) return JSON.stringify({ loading: 'true' });
  if (!img.naturalWidth) return JSON.stringify({ error: 'the picture did not load' });
  var scale = Math.min(1, $CARDMARKET_IMAGE_SIZE / Math.max(img.naturalWidth, img.naturalHeight));
  var c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(img.naturalWidth * scale));
  c.height = Math.max(1, Math.round(img.naturalHeight * scale));
  try {
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
    return JSON.stringify({ data: c.toDataURL('image/webp', 0.85) });
  } catch (e) {
    return JSON.stringify({ error: 'canvas: ' + e });
  }
})();
"""
