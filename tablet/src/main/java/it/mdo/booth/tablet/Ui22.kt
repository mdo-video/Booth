package it.mdo.booth.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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

/** In alto a destra: telecomando (se associato), Wi-Fi/QR, Impostazioni. */
@Composable
fun TopPills(
    remotePaired: Boolean,
    remoteConnected: Boolean,
    onRemote: () -> Unit,
    onWifi: () -> Unit,
    onSettings: () -> Unit,
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
        Pill(onSettings) {
            Text(Lang.t("Impostazioni", "Settings"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Sull'anteprima: nome dell'effetto appena scelto, puntini e piccola indicazione "scorri". */
@Composable
fun FilterHint(current: String, toast: String?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (toast != null) {
            Text(
                toast, color = Color(0xFF14101A), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFE83A))
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Filters.all.forEach { f ->
                val sel = f.id == current
                Box(
                    Modifier.size(if (sel) 10.dp else 6.dp).clip(CircleShape)
                        .background(if (sel) Color(0xFFFFE83A) else Color(0x88FFFFFF))
                )
            }
        }
        Text(
            "‹   " + Lang.t("Scorri per gli effetti", "Swipe for effects") + "   ›",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000))
                .padding(horizontal = 14.dp, vertical = 5.dp),
        )
    }
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
