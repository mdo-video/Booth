package it.mdo.booth.tablet

/** Ultimo fotogramma della diretta (JPEG ~640 px) preparato dalla dashcam per l'app di controllo. */
object LiveHub {
    @Volatile var jpeg: ByteArray? = null
    @Volatile var seq = 0L
    @Volatile var rot = 0
    @Volatile var at = 0L
    @Volatile var lastRequest = 0L

    /** Antifurto: al prossimo fotogramma utile la dashcam salva una foto. */
    @Volatile var photoRequested = false

    /** La dashcam prepara i fotogrammi solo se l'app di controllo li ha chiesti negli ultimi secondi. */
    val wanted: Boolean get() = System.currentTimeMillis() - lastRequest < 4000

    val fresh: Boolean get() = jpeg != null && System.currentTimeMillis() - at < 3000

    fun publish(data: ByteArray, rotation: Int) {
        jpeg = data
        rot = rotation
        at = System.currentTimeMillis()
        seq++
    }
}
