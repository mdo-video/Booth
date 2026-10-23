package it.mdo.booth.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Effetto appena scelto con lo scorrimento: vale subito sull'anteprima, prima che il telefono confermi. */
object FilterState {
    var override by mutableStateOf<String?>(null)
}

/** Margini dell'anteprima: il bordo 2 è il layout con pellicola a sinistra, tasti a destra e comandi sotto. */
fun previewPadding(mode: Int): PaddingValues = when (mode) {
    0 -> PaddingValues(0.dp)
    1 -> PaddingValues(24.dp)
    else -> PaddingValues(start = 168.dp, end = 168.dp, top = 104.dp, bottom = 176.dp)
}

@Composable
private fun Pill(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF2A1B38)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** In alto a destra: telecomando (se associato) e Wi-Fi/QR. Le impostazioni sono nascoste (pressione lunga sul bordo). */
@Composable
fun TopPills(
    remotePaired: Boolean,
    remoteConnected: Boolean,
    onRemote: () -> Unit,
    onWifi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (remotePaired) {
            Pill(onRemote) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (remoteConnected) Color(0xFF43A047) else Color(0xFFE53935)))
                Text(
                    if (remoteConnected) Lang.t("Telecomando collegato", "Remote connected")
                    else Lang.t("Telecomando non collegato", "Remote not connected"),
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Pill(onWifi) {
            Icon(painterResource(R.drawable.ic_wifi), null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** Sul lato sinistro dell'anteprima: puntini verticali dell'effetto attivo e indicazione "scorri". */
@Composable
fun FilterHint(current: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Lang.t("Effetti", "Effects"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text("▲", color = Color.White, fontSize = 12.sp)
        Filters.all.forEach { f ->
            val sel = f.id == current
            Box(
                Modifier.size(if (sel) 10.dp else 6.dp).clip(CircleShape)
                    .background(if (sel) Color(0xFFFFE83A) else Color(0x88FFFFFF))
            )
        }
        Text("▼", color = Color.White, fontSize = 12.sp)
        Text(Lang.t("scorri", "swipe"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Nome dell'effetto appena scelto, al centro dell'anteprima per un attimo. */
@Composable
fun FilterToast(name: String, modifier: Modifier = Modifier) {
    Text(
        name, color = Color(0xFF14101A), fontSize = 24.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFE83A))
            .padding(horizontal = 22.dp, vertical = 8.dp),
    )
}

/** Interruttore VIDEO / FOTO accanto al tasto REC. */
@Composable
fun ModeToggle(photo: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color(0xFF2A1B38)).padding(6.dp)) {
        Seg("VIDEO", !photo) { onChange(false) }
        Seg(Lang.t("FOTO", "PHOTO"), photo) { onChange(true) }
    }
}

@Composable
private fun Seg(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color(0xFF14101A) else Color.White,
        fontSize = 16.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 14.dp),
    )
}

/** Zone invisibili sul bordo fuori dall'anteprima: tenendo premuto si aprono le impostazioni (PIN). */
@Composable
fun SettingsHotZones(pad: PaddingValues, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val top = pad.calculateTopPadding()
    val bottom = pad.calculateBottomPadding()
    val start = pad.calculateLeftPadding(LayoutDirection.Ltr)
    val end = pad.calculateRightPadding(LayoutDirection.Ltr)
    val action by rememberUpdatedState(onLongPress)
    val zone = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { action() }) }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top).then(zone))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom).then(zone))
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(start).then(zone))
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(end).then(zone))
    }
}
