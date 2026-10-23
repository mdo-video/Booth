package it.mdo.booth.tablet

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/** Effetti colore stile Instagram (matrici 3x3 sui colori RGB). */
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

    val all = listOf(
        F("none", "Normale", "Normal", null),
        F("bn", "B&N", "B&W", floatArrayOf(0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f)),
        F("sepia", "Seppia", "Sepia", SEPIA),
        F("vintage", "Vintage", "Vintage", mix(SEPIA, IDENT, 0.55f)),
        F("warm", "Caldo", "Warm", floatArrayOf(1.10f, 0f, 0f, 0f, 1.02f, 0f, 0f, 0f, 0.85f)),
        F("cool", "Freddo", "Cool", floatArrayOf(0.88f, 0f, 0f, 0f, 1.0f, 0f, 0f, 0f, 1.12f)),
        F("vivid", "Vivido", "Vivid", sat(1.6f)),
    )
    fun get(id: String): F = all.firstOrNull { it.id == id } ?: all[0]
    fun has(id: String): Boolean = all.any { it.id == id }

    fun label(f: F): String = Lang.t(f.name, f.nameEn)

    fun colorFilter(id: String): ColorFilter? {
        val m = get(id).m ?: return null
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
