package it.mdo.booth.control

import org.json.JSONObject

/** Effetti colore disponibili (stesso id usato dal telefono camera e dal tablet). */
object Filters {
    val all = listOf(
        "none" to "Normale", "cinema" to "Cinema", "gold" to "Dorato", "romantic" to "Romantico",
        "noir" to "Noir", "vintage" to "Vintage", "neon" to "Neon",
    )

    /** L'elenco completo (con quelli personalizzati) lo manda il telefono; se manca si usa quello base. */
    fun fromInfo(info: JSONObject?): List<Pair<String, String>> {
        val a = info?.optJSONArray("filters") ?: return all
        val out = List(a.length()) { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("name") } }
        return out.ifEmpty { all }
    }
}
