package it.mdo.booth.tablet

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Font dell'interfaccia (Poppins, incluso nell'app). */
val Poppins = FontFamily(
    Font(R.font.poppins_medium, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

/** Fondo dei tasti scuri semitrasparenti (viola profondo). */
val UiDark = androidx.compose.ui.graphics.Color(0x80280028)
