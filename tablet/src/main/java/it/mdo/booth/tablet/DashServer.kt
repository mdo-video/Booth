package it.mdo.booth.tablet

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Server HTTP della dashcam (porta 8090). Tutto tranne /ping richiede il codice a 6 cifre (?k= o intestazione X-Key). */
class DashServer(
    private val key: () -> String,
    private val status: () -> JSONObject,
    private val clips: () -> JSONArray,
    private val clipFile: (String) -> File?,
    private val applyConfig: (JSONObject) -> Unit,
    private val applySettings: (JSONObject) -> Unit,
) {
    private var engine: ApplicationEngine? = null

    private suspend fun ApplicationCall.authed(): Boolean {
        val k = request.queryParameters["k"] ?: request.headers["X-Key"]
        if (k != null && k == key()) return true
        respondText("codice errato", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
        return false
    }

    fun start() {
        engine = embeddedServer(CIO, port = DashcamService.PORT) {
            install(PartialContent)
            install(AutoHeadResponse)
            routing {
                get("/ping") { call.respondText("boothdash") }
                get("/status") {
                    if (!call.authed()) return@get
                    call.respondText(status().toString(), ContentType.Application.Json)
                }
                get("/log") {
                    if (!call.authed()) return@get
                    val n = call.request.queryParameters["n"]?.toIntOrNull() ?: 300
                    call.respondText(EventLog.tailJson(n), ContentType.Application.Json)
                }
                get("/clips") {
                    if (!call.authed()) return@get
                    call.respondText(clips().toString(), ContentType.Application.Json)
                }
                get("/clip/{name}") {
                    if (!call.authed()) return@get
                    val f = clipFile(call.parameters["name"].orEmpty())
                    if (f != null) call.respond(LocalFileContent(f, if (f.extension == "jpg") ContentType.Image.JPEG else ContentType.Video.MP4))
                    else call.respond(HttpStatusCode.NotFound)
                }
                get("/snap") {
                    if (!call.authed()) return@get
                    LiveHub.lastRequest = System.currentTimeMillis()
                    val after = call.request.queryParameters["after"]?.toLongOrNull() ?: -1L
                    var waited = 0
                    while (!(LiveHub.fresh && LiveHub.seq > after) && waited < 1500) {
                        delay(50)
                        waited += 50
                        LiveHub.lastRequest = System.currentTimeMillis()
                    }
                    val data = LiveHub.jpeg
                    if (data == null || !LiveHub.fresh) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.response.header("X-Seq", LiveHub.seq.toString())
                        call.response.header("X-Rot", LiveHub.rot.toString())
                        call.respondBytes(data, ContentType.Image.JPEG)
                    }
                }
                post("/settings") {
                    if (!call.authed()) return@post
                    val body = call.receiveText()
                    val ok = runCatching { applySettings(JSONObject(body)) }.isSuccess
                    call.respondText(if (ok) "OK" else "ERR")
                }
                post("/config") {
                    if (!call.authed()) return@post
                    val body = call.receiveText()
                    val ok = runCatching { applyConfig(JSONObject(body)) }.isSuccess
                    call.respondText(if (ok) "OK" else "ERR")
                }
                post("/update/check") {
                    if (!call.authed()) return@post
                    DashState.updates?.check()
                    call.respondText("OK")
                }
                post("/update/install") {
                    if (!call.authed()) return@post
                    DashState.updates?.installNow()
                    call.respondText("OK")
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        runCatching { engine?.stop(300, 1000) }
    }
}
