package it.mdo.booth.control

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Gesti del telecomando a un tasto (gli stessi del tablet: RemoteGesture). */
private val REMOTE_GESTURES = listOf(
    "SINGLE" to "1 clic", "DOUBLE" to "2 clic", "TRIPLE" to "3 clic", "QUAD" to "4 clic", "LONG" to "Pressione lunga",
)

private val REMOTE_ACTIONS = mapOf(
    "SHOOT" to "Scatta / registra", "MODE" to "Foto ↔ Video", "LIGHT" to "Torcia / flash",
    "DURATION" to "Durata video", "VIDEO" to "Solo video", "PHOTO" to "Solo foto",
    "TORCH" to "Torcia", "FLASH" to "Flash foto", "NONE" to "Niente",
)

private val REMOTE_DEFAULTS = mapOf("SINGLE" to "SHOOT", "DOUBLE" to "MODE", "LONG" to "LIGHT", "TRIPLE" to "NONE", "QUAD" to "NONE")

// stessi tempi del telecomando sul tablet (RemoteGestureDetector)
private const val LONG_MS = 700L
private const val GAP_MS = 300L

/**
 * Replica del telecomando Bluetooth a un tasto: il tasto bianco riconosce 1, 2, 3, 4 clic e la pressione lunga
 * con gli stessi tempi del tablet e manda il gesto al tablet, che lo esegue come se arrivasse dal telecomando vero.
 */
@Composable
fun RemoteTab(api: Api) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var clicks by remember { mutableIntStateOf(0) }       // clic contati in attesa di altri
    var last by remember { mutableStateOf("") }           // ultimo gesto inviato (es. "DOUBLE")
    var result by remember { mutableStateOf("") }         // esito dell'ultimo invio
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "remote-press")

    val remoteMap = api.status?.optJSONObject("tablet")?.optJSONObject("remote")
    fun actionName(g: String): String {
        val id = remoteMap?.optString(g)?.takeIf { it.isNotEmpty() } ?: REMOTE_DEFAULTS[g] ?: "NONE"
        return REMOTE_ACTIONS[id] ?: id
    }

    fun send(g: String) {
        last = g
        result = "Invio…"
        scope.launch {
            val ok = api.remote(g)
            result = if (ok) "Inviato al tablet" else "Il tablet non risponde"
            delay(2500)
            if (last == g) { last = ""; result = "" }
        }
    }

    // stessa logica di RemoteGestureDetector: clic ravvicinati si sommano, tenuto premuto = lunga
    var finishJob by remember { mutableStateOf<Job?>(null) }
    fun finish() {
        finishJob?.cancel()
        val g = when (clicks) { 1 -> "SINGLE"; 2 -> "DOUBLE"; 3 -> "TRIPLE"; else -> "QUAD" }
        clicks = 0
        send(g)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- il telecomando ----
        Box(Modifier.padding(top = 8.dp).size(width = 200.dp, height = 285.dp), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.fillMaxSize()) {
                // corpo nero con riflesso
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color(0xFF3A3A3F), Color(0xFF0E0E10), Color(0xFF1C1C20))),
                    cornerRadius = CornerRadius(size.width * 0.42f, size.width * 0.42f),
                    size = size,
                )
                drawRoundRect(
                    color = Color(0x33FFFFFF), topLeft = Offset(size.width * 0.04f, size.width * 0.04f),
                    size = Size(size.width * 0.92f, size.height - size.width * 0.08f),
                    cornerRadius = CornerRadius(size.width * 0.38f, size.width * 0.38f),
                    style = Stroke(width = 2.dp.toPx()),
                )
                // spia in alto a sinistra
                drawCircle(
                    color = if (pressed || last.isNotEmpty()) Color(0xFF66BB6A) else Color(0xFF55555C),
                    radius = 4.dp.toPx(), center = Offset(size.width * 0.22f, size.height * 0.16f),
                )
                // anello per il cinturino in basso
                drawCircle(
                    color = Color(0xFF050506), radius = 9.dp.toPx(), center = Offset(size.width * 0.5f, size.height * 0.9f),
                )
            }
            // tasto bianco con macchina fotografica: è l'unico tasto del telecomando
            Box(
                Modifier.padding(top = 78.dp).size(104.dp).scale(pressScale)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (pressed) Color(0xFFD8D8DC) else Color.White)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            pressed = true
                            finishJob?.cancel()
                            var longFired = false
                            val longJob = scope.launch {
                                delay(LONG_MS)
                                longFired = true
                                clicks = 0
                                send("LONG")
                            }
                            tryAwaitRelease()
                            pressed = false
                            longJob.cancel()
                            if (longFired) return@detectTapGestures
                            clicks++
                            if (clicks >= 4) finish() else finishJob = scope.launch { delay(GAP_MS); finish() }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Canvas(Modifier.size(width = 50.dp, height = 38.dp)) {
                        val w = size.width
                        val h = size.height
                        val black = Color(0xFF1B1B1F)
                        drawRoundRect(black, topLeft = Offset(w * 0.30f, 0f), size = Size(w * 0.36f, h * 0.22f), cornerRadius = CornerRadius(3.dp.toPx()))
                        drawRoundRect(black, topLeft = Offset(0f, h * 0.14f), size = Size(w, h * 0.86f), cornerRadius = CornerRadius(7.dp.toPx()))
                        drawCircle(Color.White, radius = h * 0.30f, center = Offset(w * 0.5f, h * 0.58f))
                        drawCircle(black, radius = h * 0.21f, center = Offset(w * 0.5f, h * 0.58f))
                    }
                    Text("CAMERA", color = Color(0xFF1B1B1F), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // ---- cosa sta succedendo ----
        Text(
            when {
                pressed -> "Tieni premuto: pressione lunga"
                clicks > 0 -> "$clicks clic… (continua o attendi)"
                last.isNotEmpty() -> (REMOTE_GESTURES.firstOrNull { it.first == last }?.second ?: last) + " · " + result
                else -> "Premi il tasto bianco come sul telecomando"
            },
            color = if (result == "Il tablet non risponde") Color(0xFFFF8A80) else Color(0xFFB0B5C0),
            fontSize = 15.sp,
        )

        // ---- che cosa fa ogni gesto (dalle impostazioni del tablet) ----
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1E25)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("COSA FA OGNI GESTO", color = Color(0xFF9AA0AC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            REMOTE_GESTURES.forEach { (g, label) ->
                val on = last == g
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (on) Color(0xFF3D5AFE) else Color.Transparent).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(actionName(g), color = if (on) Color.White else Color(0xFFB0B5C0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                "Le combinazioni si cambiano in Impost. → TELECOMANDO. Dopo lo scatto: 1 clic Salva, 2 clic Rifai, 3 clic Condividi. " +
                    "In registrazione qualsiasi clic = STOP. Se sul tablet c'è la schermata iniziale, il primo clic la chiude.",
                color = Color.Gray, fontSize = 12.sp,
            )
        }
    }
}
