package it.mdo.booth.camera

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
        Entry(51, "1.46", "Codice di abbinamento e servizio attivo", listOf(
            "Nuova scheda Codice di abbinamento: con la protezione accesa il telefono risponde solo al tablet che ha il codice",
            "Servizio in primo piano con notifica fissa: Android non chiude più l'app durante l'evento",
            "Alla prima apertura chiede di escludere l'app dal risparmio batteria",
        )),
        Entry(50, "1.45", "App Camera nello stile di Controllo", listOf(
            "Camera: nuova schermata scura a schede, uguale all'app Controllo, sviluppata in orizzontale su due colonne (in verticale diventa una colonna)",
            "Camera: stato grande (Pronto, In registrazione, In attesa del tablet), pallino del collegamento al tablet e tempo di registrazione con barra",
            "Camera: qualità, orientamento e microfono sempre visibili; tasti Controlla, Novità e Schermo nero",
        )),
        Entry(49, "1.44", "Menu Dediche in ordine", listOf(
            "Controllo, scheda Dediche: i tasti sotto le miniature vanno a capo in righe ordinate e \"Elimina\" non si schiaccia più in verticale",
            "Controllo, scheda Dediche: anche i tasti in alto (Aggiorna, Nuove, Seleziona tutto, Nessuna, IP) si vedono tutti, senza scorrere",
        )),
        Entry(48, "1.43", "Dirette, impostazioni a schede e slider nuovo", listOf(
            "Tablet: la scritta \"Scorri per gli effetti\" resta sempre visibile",
            "Tablet: slider della durata più moderno, senza sfondo; valori sopra la barra e \"Durata massima\" sotto",
            "Tablet: impostazioni divise per categorie (Telefono camera, Aspetto, Ospiti, Accessori, Dashcam, Sistema), come nell'app Controllo",
            "Controllo: nuova \"Diretta booth\" a bassa risoluzione (max 640x480, 15 fotogrammi al secondo); la vecchia Diretta ora si chiama \"Diretta dashcam\"",
            "Controllo: schede riordinate: Stato, Dediche, Diretta booth, Diretta dashcam, Video, Log, Impost.",
        )),
        Entry(47, "1.42", "Slider durata più piccolo", listOf(
            "Lo slider della durata è più compatto e non copre più il tasto torcia/flash",
        )),
        Entry(46, "1.41", "Durata scelta dall'ospite", listOf(
            "Sul tablet, in basso a destra, l'ospite sceglie la durata del video con uno slider: 30, 60, 90 o 120 secondi",
        )),
        Entry(45, "1.40", "Volume e durata", listOf(
            "Barra del volume più a sinistra, a metà tra l'anteprima e il bordo, e il doppio più spessa",
            "Scala in dB reali: in basso -60 dB, in alto 0 dB; tacche a -18 dB (giallo) e -6 dB (rosso)",
            "Microfono USB di nuovo rilevato: la barra lo segue anche se lo colleghi dopo l'avvio",
            "Durata massima con uno slider: 30, 60, 90 o 120 secondi",
        )),
        Entry(44, "1.39", "Volume microfono", listOf(
            "La barra del volume funziona anche prima di premere REC",
            "Barra più stabile: il rumore di fondo del silenzio non la muove più",
        )),
        Entry(43, "1.38", "Eventi, volume e dediche", listOf(
            "Barra del volume del microfono sul tablet mentre registri un video",
            "Pellicola del tablet: solo l'evento in corso; prima del collegamento niente tasto REC",
            "Impostazioni tablet: Elimina tutte (evento in corso) con conferma",
            "Controllo: tocco = anteprima, tocco lungo = selezione, Seleziona tutto, Elimina, Nuovo evento con data",
        )),
        Entry(42, "1.37", "Pagina QR", listOf(
            "Sotto il tasto Scarica c'è un suggerimento per condividere il file",
        )),
        Entry(41, "1.36", "Telecomando e pagina QR", listOf(
            "Il telecomando durante il conto alla rovescia non fa nulla, come il tocco sul tablet",
            "Il QR apre una pagina con anteprima e tasto Scarica per foto e video",
        )),
        Entry(40, "1.35", "Effetti e eliminazione dediche", listOf(
            "Effetti ridotti a 7 (Normale, Cinema, Dorato, Romantico, Noir, Vintage, Neon)",
            "Si possono eliminare le dediche dall'app Controllo",
        )),
        Entry(36, "1.31", "Nuovi effetti", listOf(
            "Nuovi effetti colore su foto e video, più quelli personalizzati mandati dal tablet",
        )),
        Entry(34, "1.29", "Nuove icone", listOf(
            "Icone rinnovate: stesso logo MD, colore diverso per ogni app (Booth rosso, Camera blu, Controllo verde)",
        )),
        Entry(27, "1.22", "Effetti foto e video", listOf(
            "Effetti colore stile Instagram applicati a foto e video, anche senza cornice",
        )),
        Entry(26, "1.21", "Notifiche di aggiornamento ad app chiusa", listOf(
            "Il controllo delle nuove versioni ora funziona anche quando l'app è chiusa (ogni 3 ore circa, anche dopo il riavvio)",
            "Se non arriva nulla, nelle impostazioni Android dell'app togli le restrizioni batteria (su Xiaomi anche \"Avvio automatico\")",
        )),
        Entry(24, "1.19", "Booth a prova di ospite", listOf(
            "Tablet: Condividi apre solo il QR, impostazioni dietro codice, tasti per gli ospiti ridotti",
            "Tablet: notifica dashcam che dice sempre la verità",
            "Controllo: nuovo interruttore Blocco FISSO nelle impostazioni del telefono camera",
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
