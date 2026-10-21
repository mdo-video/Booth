package it.mdo.booth.tablet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Registro eventi del booth: una riga JSON per evento (orario, tipo, dettaglio). */
object EventLog {
    private var file: File? = null
    private var lastPhone: Boolean? = null
    private var lastStateKey = ""

    @Synchronized
    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "events.jsonl")
    }

    @Synchronized
    fun add(type: String, detail: String = "") {
        val f = file ?: return
        try {
            val line = JSONObject().put("t", System.currentTimeMillis()).put("e", type).put("d", detail).toString()
            f.appendText(line + "\n")
            if (f.length() > 600_000) {
                val keep = f.readLines().takeLast(3000)
                f.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    /** Il telefono si è collegato o scollegato (la prima emissione "non connesso" non conta). */
    fun phone(connected: Boolean) {
        val last = lastPhone
        lastPhone = connected
        if (last == null && !connected) return
        if (last != connected) add(if (connected) "PHONE_ON" else "PHONE_OFF")
    }

    /** Traduce gli stati del telefono in eventi (anteprima pronta, dedica salvata). */
    fun stateChange(state: String) {
        val parts = state.split(":")
        val key = when (parts[0]) {
            "TAKE" -> "TAKE:" + parts.getOrNull(1)
            "TAKE_SAVED" -> "SAVED:" + parts.getOrNull(1)
            else -> ""
        }
        if (key.isEmpty() || key == lastStateKey) return
        lastStateKey = key
        if (parts[0] == "TAKE") add("DEDICA_PRONTA", parts.getOrNull(1).orEmpty())
        else add("DEDICA_SALVATA", parts.getOrNull(2) ?: parts.getOrNull(1).orEmpty())
    }

    @Synchronized
    private fun lines(): List<String> = try { file?.takeIf { it.exists() }?.readLines().orEmpty() } catch (_: Exception) { emptyList() }

    fun tailJson(n: Int): String {
        val arr = JSONArray()
        lines().takeLast(n.coerceIn(1, 3000)).forEach { l -> runCatching { arr.put(JSONObject(l)) } }
        return arr.toString()
    }

    /** Conteggio per tipo degli eventi da [since] in poi. */
    fun counts(since: Long): JSONObject {
        val out = JSONObject()
        lines().forEach { l ->
            runCatching {
                val j = JSONObject(l)
                if (j.getLong("t") >= since) {
                    val e = j.getString("e")
                    out.put(e, out.optInt(e, 0) + 1)
                }
            }
        }
        return out
    }
}
