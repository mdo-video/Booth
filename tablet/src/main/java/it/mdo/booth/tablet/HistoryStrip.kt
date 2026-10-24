package it.mdo.booth.tablet

import kotlinx.coroutines.delay
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pellicola verticale a sinistra: miniature dei video salvati, dal più recente. */
@Composable
fun HistoryStrip(
    client: BoothClient,
    list: List<HistoryItem>,
    selected: String?,
    onSelect: (HistoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(list.firstOrNull()?.name) {
        if (list.isNotEmpty()) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.width(128.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "titolo") {
            Text(Lang.t("ULTIMI", "LATEST"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        }
        items(list, key = { it.name }) { item ->
            HistoryThumb(client, item, item.name == selected) { onSelect(item) }
        }
    }
}

@Composable
private fun HistoryThumb(client: BoothClient, item: HistoryItem, selected: Boolean, onClick: () -> Unit) {
    var bmp by remember(item.name) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.name) { bmp = client.thumb(item.name) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
            .background(Color(0xFF2B2F36))
            .border(if (selected) 3.dp else 2.dp, if (selected) Color(0xFFFFD54A) else Color.White.copy(alpha = 0.85f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomCenter,
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            (if (item.isPhoto) "📷 " else "") + item.name.substringBeforeLast('.').substringAfterLast('_'),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

/** Anteprima di un video dello storico: guarda, poi Chiudi o Condividi (QR). Gli ospiti non possono eliminare. */
@Composable
fun HistoryCard(
    client: BoothClient,
    path: String,
    cacheKey: String,
    name: String,
    onClose: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(560.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF21C1D22)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DownloadedMedia(client, path, cacheKey, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)))
        Text(name, color = Color.White, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlineButton(Lang.t("Chiudi", "Close"), Modifier.weight(1f), onClose)
            OutlineButton(Lang.t("Condividi", "Share"), Modifier.weight(1f), onShare)
        }
    }
}
