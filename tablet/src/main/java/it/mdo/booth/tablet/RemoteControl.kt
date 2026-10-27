package it.mdo.booth.tablet

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Gesti riconoscibili con un telecomando a un solo tasto. */
enum class RemoteGesture(val label: String) {
    SINGLE("singola"), DOUBLE("doppia"), TRIPLE("tripla"), QUAD("quadrupla"), LONG("lunga")
}

/** I gesti riconosciuti arrivano qui; li raccoglie BoothScreen. */
object RemoteBus {
    val gestures = MutableSharedFlow<RemoteGesture>(extraBufferCapacity = 8)
}

/** Diagnostica e associazione, mostrate in Impostazioni → TELECOMANDO. */
object RemoteLog {
    val learning = MutableStateFlow(false)
    val lines = MutableStateFlow<List<String>>(emptyList())
    fun add(s: String) { lines.value = (listOf(s) + lines.value).take(10) }
}

/**
 * Trasforma gli eventi tasto (giù/su) in gesti.
 * Pressione lunga: tasto tenuto oltre LONG_MS. Click multipli: separati da meno di GAP_MS.
 * Nota: molti scatti selfie mandano giù+su insieme anche se si tiene premuto: la lunga allora non è rilevabile
 * e restano utili singola/doppia/tripla/quadrupla.
 */
class RemoteGestureDetector(private val emit: (RemoteGesture) -> Unit) {
    private val h = Handler(Looper.getMainLooper())
    private var clicks = 0
    private var isDown = false
    private var longFired = false
    private val longRun = Runnable { fireLong() }
    private val finishRun = Runnable { finish() }

    fun onKey(e: KeyEvent) {
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return // tasto tenuto: la lunga è già gestita dal timer
                isDown = true
                longFired = false
                h.removeCallbacks(finishRun)
                h.removeCallbacks(longRun)
                h.postDelayed(longRun, LONG_MS)
            }
            KeyEvent.ACTION_UP -> {
                if (!isDown) return
                isDown = false
                h.removeCallbacks(longRun)
                if (longFired) { longFired = false; return }
                clicks++
                if (clicks >= 4) finish() else h.postDelayed(finishRun, GAP_MS)
            }
        }
    }

    private fun fireLong() {
        longFired = true
        clicks = 0
        h.removeCallbacks(finishRun)
        emit(RemoteGesture.LONG)
    }

    private fun finish() {
        h.removeCallbacks(finishRun)
        val g = when (clicks) {
            1 -> RemoteGesture.SINGLE
            2 -> RemoteGesture.DOUBLE
            3 -> RemoteGesture.TRIPLE
            else -> RemoteGesture.QUAD
        }
        clicks = 0
        emit(g)
    }

    companion object {
        const val LONG_MS = 700L
        const val GAP_MS = 300L
    }
}

/** Il telecomando associato è collegato in questo momento? (lo aggiorna MainActivity quando cambiano i dispositivi di input) */
object RemoteState {
    val connected = MutableStateFlow(false)

    fun refresh(local: LocalSettings) {
        val d = local.remoteDevice
        connected.value = d.isNotEmpty() &&
            InputDevice.getDeviceIds().any { id -> InputDevice.getDevice(id)?.descriptor == d }
    }
}

/** Punto d'ingresso: MainActivity.dispatchKeyEvent passa qui ogni tasto. */
object RemoteInput {
    private val detector = RemoteGestureDetector { g ->
        RemoteLog.add("→ ${g.label}")
        RemoteBus.gestures.tryEmit(g)
    }

    /** true = evento consumato (non arriva al sistema: niente cambio volume). */
    fun handle(e: KeyEvent, local: LocalSettings): Boolean {
        val dev = e.device ?: return false
        if (dev.isVirtual) return false

        // associazione: il prossimo tasto premuto sul telecomando lo "impara"
        if (RemoteLog.learning.value) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                local.remoteDevice = dev.descriptor
                local.remoteName = dev.name
                local.save()
                RemoteLog.learning.value = false
                RemoteLog.add("Associato: ${dev.name}")
                RemoteState.refresh(local)
            }
            return true
        }

        if (local.remoteDevice.isEmpty() || dev.descriptor != local.remoteDevice) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            RemoteLog.add("tasto ${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")}")
        }
        detector.onKey(e)
        return true
    }
}

/** Azioni che si possono assegnare a una combinazione di tasti del telecomando. */
object RemoteActions {
    /** id, nome breve (per i tasti di scelta) */
    val all = listOf(
        "SHOOT" to "Scatta / registra",
        "MODE" to "Foto ↔ Video",
        "LIGHT" to "Torcia / flash",
        "DURATION" to "Durata video",
        "VIDEO" to "Solo video",
        "PHOTO" to "Solo foto",
        "TORCH" to "Torcia",
        "FLASH" to "Flash foto",
        "NONE" to "Niente",
    )
    val gestures = listOf(
        RemoteGesture.SINGLE, RemoteGesture.DOUBLE, RemoteGesture.TRIPLE, RemoteGesture.QUAD, RemoteGesture.LONG,
    )
    val defaults = mapOf(
        "SINGLE" to "SHOOT", "DOUBLE" to "MODE", "LONG" to "LIGHT", "TRIPLE" to "NONE", "QUAD" to "NONE",
    )
    val durations = listOf(30, 60, 90, 120)

    fun gestureLabel(g: RemoteGesture) = when (g) {
        RemoteGesture.SINGLE -> "1 clic"
        RemoteGesture.DOUBLE -> "2 clic"
        RemoteGesture.TRIPLE -> "3 clic"
        RemoteGesture.QUAD -> "4 clic"
        RemoteGesture.LONG -> "Pressione lunga"
    }

    /** Testo per la legenda, secondo la modalità attuale (null = nessuna azione). */
    fun legend(action: String, isPhoto: Boolean, canPhoto: Boolean): String? = when (action) {
        "SHOOT" -> if (isPhoto) "Scatta foto" else "Registra video"
        "MODE" -> if (!canPhoto) null else if (isPhoto) "Passa a VIDEO" else "Passa a FOTO"
        "LIGHT" -> if (isPhoto) "Flash foto" else "Torcia"
        "DURATION" -> "Durata video"
        "VIDEO" -> "Registra video"
        "PHOTO" -> "Scatta foto"
        "TORCH" -> "Torcia"
        "FLASH" -> "Flash foto"
        else -> null
    }
}
