package it.mdo.booth.tablet

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Firma breve del nome del file: fa aprire agli ospiti con il QR solo la loro pagina di download. */
object Pairing {
    fun sig(key: String, name: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)
    }
}

/** Link della pagina di download per gli ospiti (firmato se c'è il codice di abbinamento). */
fun guestLink(base: String, name: String, key: String): String =
    if (key.isBlank()) "$base/p/$name" else "$base/p/$name?s=${Pairing.sig(key, name)}"
