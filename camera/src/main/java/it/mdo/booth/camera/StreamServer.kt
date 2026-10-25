package it.mdo.booth.camera

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/**
 * WebSocket /ws
 *  tablet -> telefono : START | STOP | SAVE | DISCARD | INFO? | SET:{json}
 *  telefono -> tablet : STATE:... | EVENT:... | INFO:{json} + frame JPEG binari
 * HTTP GET /take/<file> : il video appena registrato (con Range, per l'anteprima)
 * HTTP POST /frame      : il tablet invia il PNG della cornice
 */
class StreamServer(
    private val takeFile: (String) -> File?,
    private val onCommand: (String) -> Unit,
    private val historyFile: (String) -> File?,
    private val historyList: () -> String,
    private val thumbFile: (String) -> File?,
    private val onFrame: (ByteArray) -> Boolean = { false },
) {
    companion object {
        const val PORT = 8080
    }

    private val sessions = Collections.synchronizedSet(mutableSetOf<DefaultWebSocketServerSession>())
    @Volatile private var lastState = "READY"
    @Volatile private var lastInfo = ""
    private var engine: ApplicationEngine? = null
    private val textScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher() + SupervisorJob())

    fun hasClients(): Boolean = sessions.isNotEmpty()

    fun start() {
        engine = embeddedServer(CIO, port = PORT) {
            install(WebSockets)
            install(PartialContent)
            install(AutoHeadResponse)
            routing {
                get("/take/{name}") {
                    val f = takeFile(call.parameters["name"].orEmpty())
                    if (f != null && f.exists()) {
                        call.respond(LocalFileContent(f, ContentType.Video.MP4))
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
                post("/frame") {
                    val bytes = call.receive<ByteArray>()
                    if (bytes.isNotEmpty() && onFrame(bytes)) {
                        call.respondText("OK")
                    } else {
                        call.respond(HttpStatusCode.BadRequest)
                    }
                }
                post("/del/{name}") {
                    val n = call.parameters["name"].orEmpty()
                    if (n.endsWith(".mp4") || n.endsWith(".jpg")) {
                        onCommand("DEL:" + n)
                        call.respondText("OK")
                    } else {
                        call.respond(HttpStatusCode.BadRequest)
                    }
                }
                get("/list") {
                    call.respondText(historyList(), ContentType.Application.Json)
                }
                get("/t/{name}") {
                    val f = thumbFile(call.parameters["name"].orEmpty())
                    if (f != null) call.respond(LocalFileContent(f, ContentType.Image.JPEG))
                    else call.respond(HttpStatusCode.NotFound)
                }
                get("/h/{name}") {
                    val f = historyFile(call.parameters["name"].orEmpty())
                    if (f != null) {
                        val type = if (f.name.endsWith(".jpg")) ContentType.Image.JPEG else ContentType.Video.MP4
                        if (call.request.queryParameters["dl"] == "1") {
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, f.name).toString(),
                            )
                        }
                        call.respond(LocalFileContent(f, type))
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
                get("/p/{name}") {
                    // pagina del QR: anteprima della foto o del video con il tasto Scarica
                    val n = call.parameters["name"].orEmpty()
                    val f = historyFile(n)
                    if (f == null || !f.exists()) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        val photo = f.name.endsWith(".jpg")
                        val media = if (photo) "<img src=\"/h/${f.name}\" alt=\"\">"
                        else "<video src=\"/h/${f.name}\" controls playsinline preload=\"metadata\"></video>"
                        val label = if (photo) "Scarica la foto" else "Scarica il video"
                        val html = "<!DOCTYPE html><html lang=\"it\"><head><meta charset=\"utf-8\">" +
                            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                            "<title>La tua dedica</title><style>" +
                            "body{margin:0;background:#14101A;color:#fff;font-family:sans-serif;text-align:center}" +
                            "img,video{width:100%;max-height:75vh;object-fit:contain;background:#000;display:block}" +
                            "a.b{display:inline-block;margin:20px 0 28px;padding:16px 34px;border-radius:40px;" +
                            "background:#FF2D55;color:#fff;font-size:20px;font-weight:bold;text-decoration:none}" +
                            "p.n{margin:0 24px 28px;color:#B8B0C4;font-size:15px}" +
                            "</style></head><body>$media" +
                            "<a class=\"b\" href=\"/h/${f.name}?dl=1\" download=\"${f.name}\">$label</a>" +
                            "<p class=\"n\">Dopo il download trovi il file nel telefono, aprilo e condividilo come vuoi.</p>" +
                            "</body></html>"
                        call.respondText(html, ContentType.Text.Html)
                    }
                }
                webSocket("/ws") {
                    sessions.add(this)
                    outgoing.send(Frame.Text("STATE:$lastState"))
                    if (lastInfo.isNotEmpty()) outgoing.send(Frame.Text("INFO:$lastInfo"))
                    try {
                        for (f in incoming) {
                            if (f is Frame.Text) onCommand(f.readText())
                        }
                    } finally {
                        sessions.remove(this)
                    }
                }
            }
        }.start(wait = false)
    }

    fun sendFrame(jpeg: ByteArray) {
        synchronized(sessions) { sessions.forEach { it.outgoing.trySend(Frame.Binary(true, jpeg)) } }
    }

    fun sendState(state: String) {
        lastState = state
        broadcastText("STATE:$state")
    }

    fun sendEvent(ev: String) {
        broadcastText("EVENT:$ev")
    }

    /** Livello del microfono 0..100 mentre registra (la barra sul tablet). */
    fun sendLevel(v: Int) {
        broadcastText("LEVEL:$v")
    }

    fun sendInfo(json: String) {
        lastInfo = json
        broadcastText("INFO:$json")
    }

    private fun broadcastText(text: String) {
        val copy = synchronized(sessions) { sessions.toList() }
        copy.forEach { s -> textScope.launch { runCatching { s.outgoing.send(Frame.Text(text)) } } }
    }

    fun stop() {
        textScope.cancel()
        engine?.stop(300, 1000)
    }
}

/** Annuncia il telefono in rete locale (mDNS) così il tablet lo trova senza IP. */
class NsdAdvertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "BoothCamera"
            serviceType = "_boothcam._tcp."
            setPort(port)
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        runCatching { nsd.unregisterService(listener) }
    }
}
