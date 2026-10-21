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
        modifier = modifier.width(120.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x99000000)),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.name }) { item ->
            HistoryThumb(client, item, item.name == selected) { onSelect(item) }
        }
    }
}

@Composable
private fun HistoryThumb(client: BoothClient, item: HistoryItem, selected: Boolean, onClick: () -> Unit) {
    var bmp by remember(item.name) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.name) { bmp = client.thumb(item.name) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
            .background(Color(0xFF2B2F36))
            .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFFFFC107) else Color(0xFF5F6670), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomCenter,
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            (if (item.isPhoto) "📷 " else "") + item.name.substringBeforeLast('.').substringAfterLast('_'),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp).background(Color(0x99000000)).padding(horizontal = 6.dp),
        )
    }
}

/** Anteprima di un video dello storico: guarda, condividi, QR, elimina. */
@Composable
fun HistoryCard(
    client: BoothClient,
    path: String,
    cacheKey: String,
    name: String,
    sharing: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onQr: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember(name) { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
    Column(
        modifier.width(320.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xEE1B1D22)).padding(10.dp)
    ) {
        DownloadedMedia(client, path, cacheKey, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.height(8.dp))
        Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Chiudi", Color(0xFF3A3F47), Modifier.weight(1f), onClose)
            CardButton(if (sharing) "…" else "Condividi", Color(0xFF1565C0), Modifier.weight(1f)) {
                if (!sharing) onShare()
            }
            CardButton("QR", Color(0xFF6A1B9A), Modifier.weight(1f), onQr)
        }
        Spacer(Modifier.height(8.dp))
        CardButton(
            if (confirm) "Tocca ancora per eliminare" else "Elimina",
            if (confirm) Color(0xFFC62828) else Color(0xFF3A3F47),
            Modifier.fillMaxWidth(),
        ) { if (confirm) onDelete() else confirm = true }
    }
}
