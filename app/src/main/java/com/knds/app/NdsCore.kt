package com.knds.app

/**
 * Funciones nativas del núcleo (melonDS, en libknds.so). Kotlin puro: no depende de Android,
 * así que también se puede usar desde la JVM del PC para pruebas.
 */
object NdsCore {
    init {
        System.loadLibrary("knds")
    }

    @JvmStatic external fun nativeSetBaseDir(dir: String)
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeCreate(
        romPath: String, savePath: String, bios9: ByteArray?, bios7: ByteArray?, firmware: ByteArray?,
        sampleRate: Int, jit: Boolean, language: Int, nickname: String, threaded3D: Boolean,
    ): Long
    @JvmStatic external fun nativeDestroy(h: Long)
    @JvmStatic external fun nativeReset(h: Long)
    @JvmStatic external fun nativeRunFrame(h: Long): Int
    @JvmStatic external fun nativeGetScreens(h: Long, out: IntArray): Boolean
    @JvmStatic external fun nativeSetInput(h: Long, keys: Int, touch: Boolean, x: Int, y: Int)
    @JvmStatic external fun nativeSetMicBlow(h: Long, on: Boolean)
    @JvmStatic external fun nativeSetLidClosed(h: Long, closed: Boolean)
    @JvmStatic external fun nativeReadAudio(h: Long, out: ShortArray): Int
    @JvmStatic external fun nativeFlushSave(h: Long): Boolean
    @JvmStatic external fun nativeSaveState(h: Long): ByteArray?
    @JvmStatic external fun nativeLoadState(h: Long, data: ByteArray): Boolean
    @JvmStatic external fun nativeSetMicLive(h: Long, on: Boolean)
    @JvmStatic external fun nativePushMic(h: Long, samples: ShortArray, count: Int)
    @JvmStatic external fun nativeSetCheats(h: Long, codes: Array<String>): Boolean
}

/** Botones de la DS (1 = pulsado) para [Nds.setInput]. */
object Keys {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
    const val R = 1 shl 8
    const val L = 1 shl 9
    const val X = 1 shl 10
    const val Y = 1 shl 11
}

/** Una consola DS en marcha. No es seguro usarla desde varios hilos a la vez. */
class Nds private constructor(@Volatile private var handle: Long) : AutoCloseable {
    companion object {
        const val WIDTH = 256
        const val HEIGHT = 192
        const val SAMPLE_RATE = 48000

        /**
         * Crea la consola y arranca el juego. Sin BIOS/firmware se usan las libres de melonDS
         * con arranque directo. Lanza [IllegalStateException] si la ROM no es válida.
         */
        fun create(
            romPath: String, savePath: String, bios9: ByteArray? = null, bios7: ByteArray? = null,
            firmware: ByteArray? = null, jit: Boolean = true, language: Int = 5, nickname: String = "KNDS",
            threaded3D: Boolean = true,
        ): Nds {
            val h = NdsCore.nativeCreate(romPath, savePath, bios9, bios7, firmware, SAMPLE_RATE, jit, language, nickname, threaded3D)
            check(h != 0L) { NdsCore.nativeLastError() }
            return Nds(h)
        }
    }

    /** Las dos pantallas en ARGB: arriba (256x192) y abajo (256x192), una detrás de otra. */
    val screens = IntArray(WIDTH * HEIGHT * 2)

    fun runFrame(): Boolean {
        val r = NdsCore.nativeRunFrame(handle)
        NdsCore.nativeGetScreens(handle, screens)
        return r >= 0
    }

    fun setInput(keys: Int, touching: Boolean, x: Int, y: Int) = NdsCore.nativeSetInput(handle, keys, touching, x, y)
    fun setMicBlow(on: Boolean) = NdsCore.nativeSetMicBlow(handle, on)
    fun setLidClosed(closed: Boolean) = NdsCore.nativeSetLidClosed(handle, closed)

    /** Lee el audio pendiente (estéreo intercalado, 48 kHz). Devuelve el número de muestras estéreo. */
    fun readAudio(out: ShortArray): Int = NdsCore.nativeReadAudio(handle, out)

    /** Escribe la partida en disco si ha cambiado. */
    fun flushSave(): Boolean = NdsCore.nativeFlushSave(handle)
    fun saveState(): ByteArray? = NdsCore.nativeSaveState(handle)
    fun loadState(data: ByteArray): Boolean = NdsCore.nativeLoadState(handle, data)
    fun reset() = NdsCore.nativeReset(handle)

    /** Activa el micrófono real; las muestras llegan con [pushMic] (se puede llamar desde otro hilo). */
    fun setMicLive(on: Boolean) { if (handle != 0L) NdsCore.nativeSetMicLive(handle, on) }
    fun pushMic(samples: ShortArray, count: Int) { val h = handle; if (h != 0L) NdsCore.nativePushMic(h, samples, count) }

    /** Trucos Action Replay activos (cada uno, pares de palabras hex). false si alguno no es válido. */
    fun setCheats(codes: List<String>): Boolean = NdsCore.nativeSetCheats(handle, codes.toTypedArray())

    override fun close() {
        if (handle != 0L) {
            NdsCore.nativeFlushSave(handle)
            NdsCore.nativeDestroy(handle)
            handle = 0L
        }
    }
}
