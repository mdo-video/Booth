package it.mdo.booth.tablet

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as gridItems
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Larghezza della colonna "Ultime" e altezza massima di una miniatura verticale. */
private val StripW = 140.dp
private val ThumbMaxH = 160.dp
private val ThumbSide = 6.dp   // respiro laterale per l'ombra delle miniature

private val Fucsia = Color(0xFFFF2BFF)
private val Oro = Color(0xFFFFD54A)

/**
 * "Ultime dediche" a sinistra: ogni miniatura ha la proporzione del suo file
 * (orizzontale = larga, verticale = alta), quindi possono stare insieme.
 * Con il video orizzontale è una colonna sola; con il video verticale l'anteprima è stretta,
 * resta molto spazio a sinistra e le miniature si dispongono su più colonne (`columns`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryStrip(
    client: BoothClient,
    list: List<HistoryItem>,
    selected: String?,
    onSelect: (HistoryItem) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 1,
) {
    if (columns <= 1) {
        val listState = rememberLazyListState()
        LaunchedEffect(list.firstOrNull()?.name) {
            if (list.isNotEmpty()) listState.animateScrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = modifier.width(StripW),
            contentPadding = PaddingValues(horizontal = ThumbSide, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "titolo") { StripHeader(list.size) }
            items(list, key = { it.name }) { item ->
                HistoryThumb(client, item, item.name == selected) { onSelect(item) }
            }
        }
    } else {
        val gridState = rememberLazyStaggeredGridState()
        LaunchedEffect(list.firstOrNull()?.name) {
            if (list.isNotEmpty()) gridState.animateScrollToItem(0)
        }
        Column(modifier.width(StripW * columns)) {
            Box(Modifier.width(StripW).padding(horizontal = ThumbSide, vertical = 4.dp)) { StripHeader(list.size) }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = ThumbSide, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalItemSpacing = 14.dp,
            ) {
                gridItems(list, key = { it.name }) { item ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        HistoryThumb(client, item, item.name == selected) { onSelect(item) }
                    }
                }
            }
        }
    }
}

/** Titolo con una barretta fucsia e il numero di dediche della serata. */
@Composable
private fun StripHeader(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 4.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(Brush.verticalGradient(listOf(Oro, Fucsia))))
        Text(
            Lang.t("ULTIME", "LATEST"),
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun HistoryThumb(client: BoothClient, item: HistoryItem, selected: Boolean, onClick: () -> Unit) {
    var bmp by remember(item.name) { mutableStateOf<Bitmap?>(client.thumbCached(item.name)) }
    LaunchedEffect(item.name) { if (bmp == null) bmp = client.thumb(item.name) }
    // proporzione della miniatura vera; finché non arriva si parte da 16:9
    val ar = bmp?.let { it.width.toFloat() / it.height.toFloat() } ?: (16f / 9f)
    val (w, h) = fitMedia(ar, StripW - ThumbSide * 2, ThumbMaxH)
    val shape = RoundedCornerShape(16.dp)
    val ring: Brush = if (selected) Brush.linearGradient(listOf(Oro, Fucsia)) else SolidColor(Color.White.copy(alpha = 0.55f))
    val number = item.name.substringBeforeLast('.').substringAfterLast('_').let { if (it.isNotEmpty() && it.all(Char::isDigit)) "#$it" else it }
    val clock = remember(item.time) {
        if (item.time > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.time)) else ""
    }
    Box(
        Modifier.size(w, h)
            .shadow(if (selected) 12.dp else 6.dp, shape, ambientColor = if (selected) Fucsia else Color.Black, spotColor = if (selected) Fucsia else Color.Black)
            .clip(shape)
            .background(Color(0xFF2B2F36))
            .border(if (selected) 3.dp else 1.5.dp, ring, shape)
            .clickable(onClick = onClick),
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // velo scuro in basso con numero e ora
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(number, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (clock.isNotEmpty()) Text(clock, color = Color(0xCCFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        // distintivo: ▶ per i video, fotocamera per le foto
        Box(
            Modifier.align(Alignment.TopStart).padding(7.dp).size(24.dp).clip(CircleShape).background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (item.isPhoto) "📷" else "▶", color = Color.White, fontSize = if (item.isPhoto) 11.sp else 10.sp)
        }
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
    // proporzione: quella della miniatura già scaricata, poi quella vera del file
    val startAr = client.thumbCached(name)?.let { it.width.toFloat() / it.height.toFloat() } ?: (16f / 9f)
    var realAr by remember(cacheKey) { mutableStateOf<Float?>(null) }
    val (mw, mh) = fitMedia(realAr ?: startAr, 528.dp, cardMediaMaxHeight())
    Column(
        modifier.width(maxOf(mw, 360.dp) + 32.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF21C1D22)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DownloadedMedia(client, path, cacheKey, Modifier.size(mw, mh).clip(RoundedCornerShape(14.dp)), onAspect = { realAr = it })
        }
        Text(name, color = Color.White, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlineButton(Lang.t("Chiudi", "Close"), Modifier.weight(1f), onClose)
            OutlineButton(Lang.t("Condividi", "Share"), Modifier.weight(1f), onShare)
        }
    }
}
