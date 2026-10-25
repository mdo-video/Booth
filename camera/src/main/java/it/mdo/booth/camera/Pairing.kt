package it.mdo.booth.camera

import android.content.Context
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Codice di abbinamento a 6 cifre tra tablet e telefono camera. La protezione si accende dalla scheda dell'app. */
object Pairing {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("booth_pair", Context.MODE_PRIVATE)

    fun key(ctx: Context): String {
        val saved = prefs(ctx).getString("key", null)
        if (saved != null && saved.length == 6) return saved
        return regenerate(ctx)
    }

    fun regenerate(ctx: Context): String {
        val k = "%06d".format(java.security.SecureRandom().nextInt(1_000_000))
        prefs(ctx).edit().putString("key", k).apply()
        return k
    }

    fun required(ctx: Context): Boolean = prefs(ctx).getBoolean("required", false)

    fun setRequired(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("required", on).apply()
    }

    /** Firma breve del nome del file, per i link dei QR degli ospiti. */
    fun sig(key: String, name: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)
    }
}
