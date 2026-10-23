package it.mdo.booth.tablet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Lingua dei testi per gli ospiti: italiano (predefinito) o inglese. */
object Lang {
    var en by mutableStateOf(false)
    fun t(ita: String, eng: String): String = if (en) eng else ita
}
