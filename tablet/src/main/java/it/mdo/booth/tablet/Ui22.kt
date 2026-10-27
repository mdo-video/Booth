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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
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

@Composable
private fun RoundBtn(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF291A37))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** In alto a destra: Wi-Fi/QR e pallino del telecomando (verde = collegato, rosso = non collegato). Toccando il pallino si apre la legenda. */
@Composable
fun TopPills(
    remotePaired: Boolean,
    remoteConnected: Boolean,
    onRemote: () -> Unit,
    onWifi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundBtn(onWifi) {
            Icon(painterResource(R.drawable.ic_wifi), null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        if (remotePaired) {
            RoundBtn(onRemote) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(if (remoteConnected) Color(0xFF43A047) else Color(0xFFE53935)))
            }
        }
    }
}

/**
 * Sul bordo superiore dell'anteprima. Con il bordo largo: la scritta "scorri" sta sopra il bordo bianco del fotogramma e i
 * puntini sotto, alla stessa distanza, così il bordo è esattamente nel mezzo. Senza spazio sopra (bordo stretto o assente)
 * scritta e puntini restano dentro il fotogramma, in alto.
 * [onEdge] = il punto (0,0) di questo componente è sul bordo superiore del fotogramma e sopra c'è posto per la scritta.
 */
@Composable
fun FilterHint(current: String, onEdge: Boolean = false, modifier: Modifier = Modifier) {
    val ids = Filters.shown.map { it.id }
    val idx = ids.indexOf(current).coerceAtLeast(0)
    val label = Lang.t("‹ Scorri per gli effetti ›", "‹ Swipe for effects ›")
    val labelMod = Modifier.clip(RoundedCornerShape(50))
        .background(if (onEdge) Color(0xF2000000) else Color(0xB3000000))
        .padding(horizontal = 14.dp, vertical = 6.dp)
    val dots: @Composable (Modifier) -> Unit = { m ->
        Row(m.height(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in ids.indices) {
                val sel = i == idx
                Box(
                    Modifier.size(if (sel) 12.dp else 8.dp).clip(CircleShape)
                        .background(if (sel) Color(0xFFFFE83A) else Color(0xB3FFFFFF))
                )
            }
        }
    }
    if (onEdge) {
        // ancora alta 0 sul bordo: la scritta cresce verso l'alto, i puntini verso il basso
        Box(modifier.fillMaxWidth().height(0.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .offset(y = (-HINT_GAP).dp).then(labelMod),
            )
            dots(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset(y = (HINT_BORDER + HINT_GAP).dp))
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.offset(y = 8.dp).then(labelMod),
            )
            dots(Modifier.offset(y = 14.dp))
        }
    }
}

/** distanza (dp) tra scritta e bordo e tra bordo e puntini; spessore indicativo del bordo bianco */
private const val HINT_GAP = 10
private const val HINT_BORDER = 4.5

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

/** Tasto luce accanto a REC: in modalità VIDEO è la torcia, in modalità FOTO è il flash. Stessa icona (fulmine) in entrambi i casi. Giallo = acceso, scuro = spento. */
@Composable
fun LightButton(photo: Boolean, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(72.dp).clip(CircleShape)
            .background(if (on) Color(0xFFFFD54A) else Color(0xFF291A37))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_bolt), null,
            tint = if (on) Color(0xFF2B1B00) else Color.White,
            modifier = Modifier.size(32.dp),
        )
    }
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
