package it.mdo.booth.tablet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

private const val MAX_SHOWN = 7

private fun saveShown(local: LocalSettings, ids: List<String>) {
    local.shownFx = ids.joinToString(",")
    Filters.setShown(local.shownFx)
    local.save()
}

private fun addShown(local: LocalSettings, id: String) {
    val cur = Filters.shown.map { it.id }
    if (id !in cur && cur.size < MAX_SHOWN) saveShown(local, cur + id)
}

private fun round2(x: Float): Double = (x * 100).roundToInt() / 100.0

/** Scelta degli effetti da mostrare agli ospiti, con anteprima dal vivo, e editor a cursori per crearne di nuovi. */
@Composable
fun EffectsSettings(client: BoothClient, local: LocalSettings) {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val customs = Filters.customObjects(local.customFilters)

    // un fotogramma dal vivo, aggiornato ogni tanto, per le anteprime della griglia
    var snap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            client.frame.value?.let { snap = it.asImageBitmap() }
            delay(800)
        }
    }

    if (creating || editing != null) {
        FxEditor(
            client = client,
            initial = editing,
            onCancel = { creating = false; editing = null },
            onSave = { obj, isNew ->
                val list = customs.toMutableList()
                val id = obj.optString("id")
                val idx = list.indexOfFirst { it.optString("id") == id }
                if (idx >= 0) list[idx] = obj else list.add(obj)
                local.customFilters = Filters.toJson(list)
                Filters.setCustom(local.customFilters)
                if (isNew && local.shownFx.isNotEmpty()) addShown(local, Filters.customId(id))
                local.save()
                creating = false
                editing = null
            },
        )
    } else {
        val shownIds = Filters.shown.map { it.id }
        Label("Scegli gli effetti per gli ospiti")
        Text(
            "${shownIds.size} di $MAX_SHOWN scelti. Tocca un effetto per mostrarlo o nasconderlo (Normale resta sempre).",
            color = Color(0xFFB8AEC4), fontSize = 14.sp,
        )
        if (snap == null) {
            Text("Collega il telefono camera per vedere le anteprime dal vivo.", color = Color(0xFFB8AEC4), fontSize = 14.sp)
        }
        if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF8A80), fontSize = 14.sp)
        Filters.all.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { f ->
                    FxTile(f, snap, shownIds.indexOf(f.id) + 1, Modifier.weight(1f)) {
                        msg = ""
                        if (f.id != "none") {
                            val cur = Filters.shown.map { it.id }
                            if (f.id in cur) {
                                saveShown(local, cur - f.id)
                            } else if (cur.size < MAX_SHOWN) {
                                saveShown(local, cur + f.id)
                            } else {
                                msg = "Sono già $MAX_SHOWN: toglierne uno prima di aggiungerne un altro"
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Scelta consigliata", false) {
                msg = ""
                local.shownFx = ""
                Filters.setShown("")
                local.save()
            }
        }

        Label("I miei effetti")
        if (customs.isEmpty()) {
            Text("Nessun effetto personalizzato. Creane uno con i cursori.", color = Color(0xFFB8AEC4), fontSize = 14.sp)
        }
        customs.forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    o.optString("nome", o.optString("id")), color = Color.White, fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!o.has("matrice")) Chip("Modifica", false) { editing = o }
                Chip("Elimina", false) {
                    local.customFilters = Filters.toJson(customs.filter { it !== o })
                    Filters.setCustom(local.customFilters)
                    local.save()
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Nuovo effetto", true) { creating = true }
        }
    }
}

@Composable
private fun FxTile(f: Filters.F, snap: ImageBitmap?, order: Int, modifier: Modifier, onClick: () -> Unit) {
    val sel = order > 0
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape).background(Color(0xFF2B2F36))
            .then(if (sel) Modifier.border(3.dp, Color(0xFFFFE83A), shape) else Modifier)
            .clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
            if (snap != null) {
                Image(
                    bitmap = snap, contentDescription = null, contentScale = ContentScale.Crop,
                    colorFilter = Filters.colorFilterOf(f.m), modifier = Modifier.fillMaxSize(),
                )
            }
            if (sel) {
                Text(
                    "$order", color = Color(0xFF14101A), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(50))
                        .background(Color(0xFFFFE83A)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Text(f.name, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun FxEditor(client: BoothClient, initial: JSONObject?, onCancel: () -> Unit, onSave: (JSONObject, Boolean) -> Unit) {
    var name by remember { mutableStateOf(initial?.optString("nome", "") ?: "") }
    var sat by remember { mutableFloatStateOf((initial?.optDouble("saturazione", 1.0) ?: 1.0).toFloat()) }
    var red by remember { mutableFloatStateOf((initial?.optDouble("rosso", 1.0) ?: 1.0).toFloat()) }
    var green by remember { mutableFloatStateOf((initial?.optDouble("verde", 1.0) ?: 1.0).toFloat()) }
    var blue by remember { mutableFloatStateOf((initial?.optDouble("blu", 1.0) ?: 1.0).toFloat()) }
    var lum by remember { mutableFloatStateOf((initial?.optDouble("luminosita", 1.0) ?: 1.0).toFloat()) }
    val frame by client.frame.collectAsState()

    Label(if (initial == null) "Nuovo effetto" else "Modifica effetto")
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        val fr = frame
        if (fr != null) {
            Image(
                bitmap = fr.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                colorFilter = Filters.colorFilterOf(Filters.customMatrix(sat, red, green, blue, lum)),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "Collega il telefono camera per vedere l'anteprima", color = Color.Gray, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
    OutlinedTextField(
        value = name, onValueChange = { name = it.take(18) }, singleLine = true,
        label = { Text("Nome dell'effetto") }, modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
    )
    FxSlider("Colori vivi", sat, 0f..3f) { sat = it }
    FxSlider("Rosso", red, 0.5f..1.5f) { red = it }
    FxSlider("Verde", green, 0.5f..1.5f) { green = it }
    FxSlider("Blu", blue, 0.5f..1.5f) { blue = it }
    FxSlider("Luminosità", lum, 0.5f..1.5f) { lum = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Salva", true) {
            val o = JSONObject()
            val idRaw = initial?.optString("id").orEmpty().ifBlank { "fx" + (System.currentTimeMillis() / 1000) }
            val nm = name.trim().ifEmpty { "Effetto" }
            o.put("id", idRaw)
            o.put("nome", nm)
            o.put("name", nm)
            o.put("saturazione", round2(sat))
            o.put("rosso", round2(red))
            o.put("verde", round2(green))
            o.put("blu", round2(blue))
            o.put("luminosita", round2(lum))
            onSave(o, initial == null)
        }
        Chip("Azzera cursori", false) { sat = 1f; red = 1f; green = 1f; blue = 1f; lum = 1f }
        Chip("Annulla", false) { onCancel() }
    }
}

@Composable
private fun FxSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.width(120.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text("%.2f".format(value), color = Color(0xFFB8AEC4), fontSize = 14.sp, modifier = Modifier.width(52.dp))
    }
}
