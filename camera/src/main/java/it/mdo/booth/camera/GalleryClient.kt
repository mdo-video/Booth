package it.mdo.booth.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryHttpException(val code: Int, msg: String) : Exception("HTTP $code $msg")

/**
 * Galleria web degli ospiti (Cloudflare). Ogni dedica salvata va in <indirizzo>/?e=<evento>.
 * L'evento è il nome del file prima dell'ultimo "_" (come per Drive); la chiave è quella impostata sul Worker.
 */
class GalleryClient(ctx: Context) {
    companion object {
        const val DEFAULT_URL = "https://mdo-booth.donofriomarco-92.workers.dev"
        private const val EXPIRE_DAYS = 90
    }

    private val prefs = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) { prefs.edit().putString("url", v.trim().trimEnd('/')).apply() }

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) { prefs.edit().putString("token", v.trim()).apply() }

    /** Ultimo link di galleria (dell'ultimo evento caricato), da copiare e mandare agli ospiti. */
    var lastLink: String
        get() = prefs.getString("lastLink", "") ?: ""
        set(v) { prefs.edit().putString("lastLink", v).apply() }

    val configured: Boolean get() = baseUrl.startsWith("https://") && token.isNotEmpty()

    private fun open(method: String, path: String): HttpURLConnection {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun finish(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw GalleryHttpException(code, text.take(200))
        return text
    }

    /** Prova indirizzo e chiave: lancia un errore se qualcosa non va. */
    fun ping() {
        finish(open("GET", "/api/ping"))
    }

    private fun putFile(path: String, file: File, mime: String, onProgress: (Int) -> Unit) {
        val len = file.length()
        val c = open("PUT", path)
        c.doOutput = true
        c.setFixedLengthStreamingMode(len)
        c.setRequestProperty("Content-Type", mime)
        file.inputStream().use { inp ->
            c.outputStream.use { out ->
                val buf = ByteArray(256 * 1024)
                var sent = 0L
                var last = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    val pct = if (len > 0) (sent * 100 / len).toInt() else 100
                    if (pct != last) { last = pct; onProgress(pct) }
                }
            }
        }
        finish(c)
    }

    private fun postJson(path: String, json: String) {
        val c = open("POST", path)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        c.outputStream.use { it.write(json.toByteArray()) }
        finish(c)
    }

    /** Nome evento = parte del nome file prima dell'ultimo "_". */
    private fun eventName(file: File): String = file.name.substringBeforeLast('_', "").trim()

    private fun slug(s: String): String {
        val plain = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    private class Meta(val w: Int, val h: Int, val duration: Int)

    private fun meta(f: File, photo: Boolean): Meta {
        return try {
            if (photo) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, o)
                var w = o.outWidth
                var h = o.outHeight
                val ori = ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                if (ori == 6 || ori == 8) { val t = w; w = h; h = t }
                Meta(w, h, 0)
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    Meta(w, h, ((ms + 500) / 1000).toInt())
                } finally {
                    r.release()
                }
            }
        } catch (e: Exception) {
            Meta(0, 0, 0)
        }
    }

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date(ms))
    private fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ms))

    /**
     * Carica la dedica: prima il file e la miniatura, per ultima la voce nell'elenco (così in galleria
     * compare solo quando è completa). Restituisce il link della galleria dell'evento.
     */
    fun upload(file: File, thumb: File?, onProgress: (Int) -> Unit): String {
        val photo = file.name.endsWith(".jpg", true)
        val ext = if (photo) "jpg" else "mp4"
        val ev = eventName(file)
        val code = slug(ev).take(48).ifEmpty { "booth" }

        val stem = file.name.substringBeforeLast('.')
        val id = (slug(stem).ifEmpty { "dedica" } + if (photo) "-f" else "").take(90)
        val mediaName = "$id.$ext"

        putFile("/api/file/$code/media/$mediaName", file, if (photo) "image/jpeg" else "video/mp4", onProgress)
        val hasThumb = thumb != null && thumb.isFile
        if (hasThumb) putFile("/api/file/$code/thumbs/$id.jpg", thumb!!, "image/jpeg") { }

        val m = meta(file, photo)
        val num = stem.substringAfterLast('_', "").takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        val name = (if (photo) "Foto" else "Dedica") + (if (num != null) " $num" else "")
        val w = if (m.w > 0) m.w else if (photo) 1280 else 360
        val h = if (m.h > 0) m.h else if (photo) 720 else 640

        val item = JSONObject()
            .put("id", id)
            .put("type", if (photo) "photo" else "video")
            .put("name", name)
            .put("time", iso(file.lastModified()))
            .put("w", w)
            .put("h", h)
            .put("src", "media/$mediaName")
            .put("duration", m.duration)
        if (hasThumb) item.put("thumb", "thumbs/$id.jpg")
        val event = JSONObject()
            .put("code", code)
            .put("title", ev.ifEmpty { "Booth" })
            .put("subtitle", "Le dediche del booth")
            .put("date", day(file.lastModified()))
            .put("expires", day(file.lastModified() + EXPIRE_DAYS * 86_400_000L))
        postJson("/api/item/$code", JSONObject().put("event", event).put("item", item).toString())

        val link = "$baseUrl/?e=$code"
        lastLink = link
        return link
    }
}
