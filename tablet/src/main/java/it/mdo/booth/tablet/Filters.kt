package it.mdo.booth.tablet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import org.json.JSONArray
import org.json.JSONObject

/**
 * Effetti colore (matrici 3x3 sui colori RGB), uguali a quelli dell'app Camera.
 * In più: effetti personalizzati scritti a mano nelle impostazioni (testo JSON) e mandati al telefono.
 */
object Filters {
    class F(val id: String, val name: String, val nameEn: String, val m: FloatArray?)

    private fun sat(s: Float): FloatArray {
        val lr = 0.3086f; val lg = 0.6094f; val lb = 0.0820f
        return floatArrayOf(
            (1 - s) * lr + s, (1 - s) * lg, (1 - s) * lb,
            (1 - s) * lr, (1 - s) * lg + s, (1 - s) * lb,
            (1 - s) * lr, (1 - s) * lg, (1 - s) * lb + s,
        )
    }
    private val SEPIA = floatArrayOf(0.393f, 0.769f, 0.189f, 0.349f, 0.686f, 0.168f, 0.272f, 0.534f, 0.131f)
    private val IDENT = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private fun mix(a: FloatArray, b: FloatArray, w: Float) = FloatArray(9) { a[it] * w + b[it] * (1 - w) }
    /** Saturazione s, poi un guadagno per ogni canale (rosso, verde, blu). */
    private fun tone(s: Float, r: Float = 1f, g: Float = 1f, b: Float = 1f): FloatArray {
        val m = sat(s)
        val gain = floatArrayOf(r, g, b)
        return FloatArray(9) { m[it] * gain[it / 3] }
    }

    val builtin = listOf(
        F("none", "Normale", "Normal", null),
        F("bn", "B&N", "B&W", floatArrayOf(0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f)),
        F("sepia", "Seppia", "Sepia", SEPIA),
        F("vintage", "Vintage", "Vintage", mix(SEPIA, IDENT, 0.55f)),
        F("warm", "Caldo", "Warm", floatArrayOf(1.10f, 0f, 0f, 0f, 1.02f, 0f, 0f, 0f, 0.85f)),
        F("cool", "Freddo", "Cool", floatArrayOf(0.88f, 0f, 0f, 0f, 1.0f, 0f, 0f, 0f, 1.12f)),
        F("vivid", "Vivido", "Vivid", sat(1.6f)),
        // eleganti
        F("cinema", "Cinema", "Cinema", tone(1.15f, 1.06f, 0.98f, 0.93f)),
        F("gold", "Dorato", "Golden", tone(1.10f, 1.12f, 1.04f, 0.78f)),
        F("romantic", "Romantico", "Romantic", tone(1.05f, 1.10f, 0.95f, 1.02f)),
        F("pastel", "Pastello", "Pastel", tone(0.70f, 1.06f, 1.06f, 1.06f)),
        F("noir", "Noir", "Noir", tone(0f, 0.85f, 0.85f, 0.85f)),
        F("faded", "Sbiadito", "Faded", tone(0.55f, 1.02f, 1.0f, 0.98f)),
        // simpatici
        F("neon", "Neon", "Neon", tone(2.2f, 1.05f, 0.95f, 1.20f)),
        F("cartoon", "Cartoon", "Cartoon", tone(2.0f, 1.10f, 1.10f, 1.10f)),
        F("eighties", "Anni '80", "80s", tone(1.7f, 1.15f, 0.85f, 1.25f)),
        F("dream", "Sogno", "Dream", tone(0.80f, 1.08f, 0.98f, 1.10f)),
        F("night", "Notte", "Night", tone(0.50f, 0.55f, 0.70f, 1.0f)),
        F("popart", "Pop Art", "Pop Art", tone(2.8f)),
        F("sun", "Sole", "Sunny", tone(1.25f, 1.15f, 1.06f, 0.72f)),
        F("icecream", "Gelato", "Ice cream", tone(0.90f, 1.0f, 1.10f, 1.08f)),
        F("sunset", "Tramonto", "Sunset", tone(1.4f, 1.20f, 0.90f, 0.85f)),
        F("ocean", "Oceano", "Ocean", tone(1.2f, 0.80f, 1.0f, 1.20f)),
        F("matrix", "Matrix", "Matrix", floatArrayOf(0.03f, 0.06f, 0.01f, 0.33f, 0.66f, 0.11f, 0.03f, 0.06f, 0.01f)),
        F("alien", "Alieno", "Alien", floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)),
    )

    private var customList by mutableStateOf<List<F>>(emptyList())
    val custom: List<F> get() = customList
    val all: List<F> get() = builtin + customList

    /** Scelta fatta dalle impostazioni (id separati da virgola, nell'ordine in cui compaiono). Vuota = scelta consigliata. */
    private var shownIds by mutableStateOf<List<String>>(emptyList())
    fun setShown(csv: String) {
        shownIds = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Effetti mostrati agli ospiti: massimo 7 (Normale + i migliori). Quelli personalizzati prendono i posti in fondo. */
    private val PICK = listOf("none", "cinema", "gold", "romantic", "noir", "vintage", "neon")
    val shown: List<F>
        get() {
            if (shownIds.isNotEmpty()) {
                val chosen = shownIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
                val withNone = if (chosen.any { it.id == "none" }) chosen else listOf(builtin[0]) + chosen
                return withNone.take(7)
            }
            val c = customList.take(7)
            val base = PICK.mapNotNull { id -> builtin.firstOrNull { it.id == id } }
            return (base.take(7 - c.size) + c).take(7)
        }

    /** Legge il testo JSON degli effetti personalizzati. Restituisce null se il testo non è valido. */
    fun parseOrNull(json: String): List<F>? {
        val text = json.trim()
        if (text.isEmpty()) return emptyList()
        return runCatching {
            val arr = if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
            val out = ArrayList<F>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val raw = o.optString("id", "").ifBlank { "f${i + 1}" }
                val id = customId(raw)
                val nome = o.optString("nome", o.optString("name", raw)).take(18)
                val name = o.optString("name", nome).take(18)
                val mat = o.optJSONArray("matrice")
                val m = if (mat != null && mat.length() == 9) {
                    FloatArray(9) { mat.getDouble(it).toFloat().coerceIn(-3f, 3f) }
                } else {
                    val lum = o.optDouble("luminosita", 1.0).toFloat().coerceIn(0f, 3f)
                    tone(
                        o.optDouble("saturazione", 1.0).toFloat().coerceIn(0f, 4f),
                        o.optDouble("rosso", 1.0).toFloat().coerceIn(0f, 3f) * lum,
                        o.optDouble("verde", 1.0).toFloat().coerceIn(0f, 3f) * lum,
                        o.optDouble("blu", 1.0).toFloat().coerceIn(0f, 3f) * lum,
                    )
                }
                if (out.none { it.id == id }) out.add(F(id, nome, name, m))
            }
            out
        }.getOrNull()
    }

    /** Identificativo interno di un effetto personalizzato. */
    fun customId(raw: String): String = "u_" + raw.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(24)

    /** Gli effetti personalizzati come oggetti JSON (lista vuota se il testo è vuoto o non valido). */
    fun customObjects(json: String): List<JSONObject> = runCatching {
        val t = json.trim()
        if (t.isEmpty()) emptyList() else {
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
            List(arr.length()) { arr.getJSONObject(it) }
        }
    }.getOrDefault(emptyList())

    fun toJson(list: List<JSONObject>): String = if (list.isEmpty()) "" else JSONArray(list).toString()

    /** Matrice colori di un effetto fatto con i cursori (1 = nessuna modifica). */
    fun customMatrix(sat: Float, r: Float, g: Float, b: Float, lum: Float): FloatArray = tone(sat, r * lum, g * lum, b * lum)

    /** Imposta gli effetti personalizzati; se il testo non è valido restano quelli di prima. */
    fun setCustom(json: String) {
        parseOrNull(json)?.let { customList = it }
    }

    /** Esempio mostrato nelle impostazioni (tutti i valori sono opzionali, 1 = nessuna modifica). */
    val EXAMPLE = """[
  {"id": "mio1", "nome": "Mio effetto", "name": "My effect",
   "saturazione": 1.3, "rosso": 1.1, "verde": 1.0, "blu": 0.9, "luminosita": 1.0}
]"""

    fun get(id: String): F = all.firstOrNull { it.id == id } ?: all[0]
    fun has(id: String): Boolean = all.any { it.id == id }

    fun label(f: F): String = Lang.t(f.name, f.nameEn)

    fun colorFilter(id: String): ColorFilter? = colorFilterOf(get(id).m)

    fun colorFilterOf(m: FloatArray?): ColorFilter? {
        if (m == null) return null
        return ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    m[0], m[1], m[2], 0f, 0f,
                    m[3], m[4], m[5], 0f, 0f,
                    m[6], m[7], m[8], 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
}
