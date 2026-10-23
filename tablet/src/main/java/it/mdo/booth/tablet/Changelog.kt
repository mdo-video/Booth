package it.mdo.booth.tablet

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Novità di ogni versione. Si mostrano dopo un aggiornamento e dalle impostazioni (voce "Novità"). */
object Changelog {
    private class Entry(val code: Int, val name: String, val title: String, val lines: List<String>)

    // dal più recente al più vecchio; code = appVersionCode
    private val entries = listOf(
        Entry(33, "1.28", "Schermata più pulita", listOf(
            "Nascosti per gli ospiti batteria, spazio e stato del telecomando: restano Wi-Fi/QR, Torcia, Flash, Video/Foto e REC",
        )),
        Entry(32, "1.27", "Solo i tasti per gli ospiti", listOf(
            "Tolti i tasti Cornice e Blocca dalla schermata principale: restano Torcia e Flash. Cornice e blocco si cambiano da impostazioni o da Controllo",
        )),
        Entry(31, "1.26", "Impostazioni nascoste", listOf(
            "Il pulsante Impostazioni non si vede più: si aprono tenendo premuto sul bordo fuori dall'anteprima (poi il PIN)",
        )),
        Entry(30, "1.25", "Effetti a scorrimento verticale", listOf(
            "Gli effetti si cambiano scorrendo il dito in su e in giù sull'anteprima: l'indicazione sta sul lato sinistro, senza coprire la cornice",
        )),
        Entry(28, "1.23", "Nuovo layout e effetti a scorrimento", listOf(
            "Schermata principale rifatta: pellicola a sinistra, titolo e pulsanti in alto, tasti Torcia, Flash, Cornice e Blocca a destra, interruttore Video/Foto accanto a REC",
            "Gli effetti si cambiano scorrendo il dito sull'anteprima, come le storie di Instagram, con una piccola indicazione",
            "Impostazioni: ora è un pulsante in alto a destra (chiede il PIN)",
        )),
        Entry(27, "1.22", "Effetti, inglese e ritorno automatico", listOf(
            "Effetti stile Instagram su foto e video (B&N, Seppia, Vintage, Caldo, Freddo, Vivido): gli ospiti li scelgono dalla fila sotto l'anteprima e li vedono subito",
            "Testi per gli ospiti anche in inglese: impostazioni, Lingua per gli ospiti",
            "Ritorno automatico: il QR si chiude dopo 45 secondi e un video non salvato si salva da solo dopo 60 secondi",
            "Il tasto QR in alto a destra ora ha l'icona del Wi-Fi",
        )),
        Entry(26, "1.21", "Notifiche di aggiornamento ad app chiusa", listOf(
            "Il controllo delle nuove versioni ora funziona anche quando l'app è chiusa (ogni 3 ore circa, anche dopo il riavvio)",
            "Se non arriva nulla, nelle impostazioni Android dell'app togli le restrizioni batteria (su Xiaomi anche \"Avvio automatico\")",
        )),
        Entry(25, "1.20", "Sblocco remoto", listOf(
            "Dall'app Controllo: Impostazioni, Sblocca il tablet. Sul tablet si aprono le impostazioni senza digitare il codice",
            "Lo sblocco remoto si vede nel registro eventi",
            "PIN a scelta: la prima volta che tocchi l'ingranaggio il tablet ti fa scegliere un PIN da 4 a 6 cifre (o nessun PIN). Si cambia o si toglie dalle impostazioni, voce PIN DEL TABLET",
        )),
        Entry(24, "1.19", "Booth a prova di ospite", listOf(
            "Condividi apre solo il QR (salva il video se serve): non si esce più dal kiosk",
            "Dopo il video: Rifai, Salva, Condividi. Dopo il salvataggio: Nuova, Condividi",
            "Nello storico: Chiudi e Condividi. Eliminare i video si può solo dall'app Controllo",
            "Ingranaggio nascosto: impostazioni con pressione lunga in alto a destra e codice a 6 cifre",
            "Tolti CORNICE e AUTO/FISSO dal tablet (restano nelle impostazioni e in Controllo)",
            "Notifica dashcam sincera: dice \"Registrazione in corso\" solo se registra davvero, altrimenti \"Dashcam ferma\" con il motivo; con la dashcam spenta non c'è notifica",
        )),
        Entry(23, "1.18", "Nuovo look e aggiornamenti pronti", listOf(
            "Aggiornamenti scaricati da soli in background: arriva la notifica \"Aggiornamento pronto\" e basta toccare Installa",
            "Scheda del telecomando ridisegnata, con stato collegato e azioni ben leggibili",
            "Novità di ogni versione dentro l'app (questa schermata)",
        )),
        Entry(22, "1.17", "Notifica di aggiornamento", listOf(
            "Notifica quando esce una nuova versione, in tutte e tre le app",
            "Controllo automatico ogni 6 ore",
        )),
        Entry(21, "1.16", "Legenda del telecomando", listOf(
            "La legenda non copre più i tasti torcia, flash e cornice",
            "Testo completo, senza tagli",
        )),
        Entry(20, "1.15", "Interfaccia identica al mockup", listOf(
            "Font Poppins, icone nuove, miniature dello storico 16:9 con titolo ULTIMI",
            "Tasti scuri viola semitrasparenti; bordo Fucsia predefinito (tablet)",
        )),
        Entry(19, "1.14", "Interfaccia tablet ridisegnata", listOf(
            "Con il bordo Largo tutto sta fuori dall'immagine: REC e FOTO in basso, tasti grandi a destra, storico a sinistra",
            "Batteria e spazio più grandi, rossi quando stanno finendo",
        )),
        Entry(18, "1.13", "Bordo colorato attorno all'anteprima", listOf(
            "Impostazioni › Bordo anteprima: Pieno schermo, Sottile o Largo",
            "Sette colori predefiniti o codice #RRGGBB",
        )),
        Entry(17, "1.12", "Tab Dediche in Booth Controllo", listOf(
            "Miniature di video e foto salvati sul telefono camera",
            "Scarica in Galleria le dediche scelte",
        )),
        Entry(16, "1.11", "Telecomando Bluetooth a un tasto", listOf(
            "1 pressione REC, 2 FOTO, 3 flash, lunga o 4 torcia",
            "Con l'anteprima: 1 Salva, 2 Rifai",
            "Icona del telecomando con anello verde/rosso e legenda dei gesti",
        )),
        Entry(15, "1.10", "Schermo nero sul telefono camera", listOf(
            "Dopo 60 secondi resta solo un puntino di funzionamento, a luminosità minima",
            "Doppio tocco per uscire",
        )),
        Entry(14, "1.9", "Sicurezza con l'accelerometro", listOf(
            "Il tablet rileva cadute, urti e spostamenti e protegge i video per 24 ore",
            "Il telefono camera avvisa il tablet se viene spostato o cade",
        )),
        Entry(13, "1.8", "Diretta e controllo remoto", listOf(
            "Tab Diretta: la fotocamera frontale del tablet in tempo reale",
            "Le impostazioni del tablet si cambiano dall'app Controllo",
        )),
        Entry(12, "1.7", "Dashcam, controllo remoto, log, aggiornamenti", listOf(
            "Dashcam sul tablet a segmenti, con sovrascrittura dei più vecchi",
            "App Booth Controllo con stato, log eventi e video",
            "Aggiornamenti dalle release GitHub",
        )),
        Entry(11, "1.6", "Cornice e flash", listOf(
            "Cornice PNG su foto e video",
            "Flash nelle foto e tasti rapidi flash e cornice sul tablet",
        )),
    )

    private fun dp(a: Activity, v: Int): Int = (v * a.resources.displayMetrics.density).toInt()

    /** dopo un aggiornamento mostra le novità dell'ultima versione, una volta sola */
    fun showIfNew(a: Activity, installedCode: Int) {
        val prefs = a.getSharedPreferences("changelog", Context.MODE_PRIVATE)
        if (prefs.getInt("seen", 0) >= installedCode) return
        prefs.edit().putInt("seen", installedCode).apply()
        val latest = entries.firstOrNull { it.code <= installedCode } ?: return
        show(a, listOf(latest))
    }

    /** storico completo */
    fun show(a: Activity) = show(a, entries)

    private fun show(a: Activity, list: List<Entry>) {
        try {
            val d = Dialog(a)
            d.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val root = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(a, 26), dp(a, 24), dp(a, 26), dp(a, 20))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1D1026"))
                    cornerRadius = dp(a, 28).toFloat()
                    setStroke(dp(a, 2), Color.parseColor("#5A2F73"))
                }
            }
            root.addView(TextView(a).apply {
                text = "NOVITÀ"
                setTextColor(Color.parseColor("#FF2BFF"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
            })
            val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            for (e in list) {
                body.addView(TextView(a).apply {
                    text = "Versione ${e.name} · ${e.title}"
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(a, 14), 0, dp(a, 4))
                })
                for (l in e.lines) {
                    body.addView(TextView(a).apply {
                        text = "•  $l"
                        setTextColor(Color.parseColor("#E8D6F5"))
                        textSize = 15f
                        setLineSpacing(0f, 1.15f)
                        setPadding(0, dp(a, 2), 0, dp(a, 2))
                    })
                }
            }
            val sv = ScrollView(a).apply { addView(body) }
            val maxH = (a.resources.displayMetrics.heightPixels * 0.55f).toInt()
            root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            sv.post {
                if (sv.height > maxH) {
                    val lp = sv.layoutParams
                    lp.height = maxH
                    sv.layoutParams = lp
                }
            }
            val btn = Button(a).apply {
                text = "Chiudi"
                isAllCaps = false
                setTextColor(Color.parseColor("#14101A"))
                typeface = Typeface.DEFAULT_BOLD
                textSize = 16f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFE83A"))
                    cornerRadius = dp(a, 26).toFloat()
                }
                setOnClickListener { d.dismiss() }
            }
            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 52))
            blp.topMargin = dp(a, 16)
            root.addView(btn, blp)
            d.setContentView(root)
            d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            d.show()
            val w = minOf((a.resources.displayMetrics.widthPixels * 0.9f).toInt(), dp(a, 560))
            d.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            d.window?.setGravity(Gravity.CENTER)
        } catch (e: Exception) {
        }
    }
}
