package com.knds.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import android.view.SurfaceHolder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Hilo de emulación: ejecuta frames de melonDS, pinta las dos pantallas y envía el audio.
 * Con sonido, la escritura bloqueante en AudioTrack marca el ritmo; sin sonido o en
 * avance rápido, el ritmo se controla con el reloj.
 */
class Emulator(val nds: Nds) : Thread("KNDS-emu") {

    @Volatile var keys = 0
    @Volatile var hwKeys = 0
    @Volatile var touching = false
    @Volatile var touchX = 0
    @Volatile var touchY = 0
    @Volatile var fastForward = false
    @Volatile var soundOn = true
    @Volatile var swapped = false
    /** Con carcasa: las pantallas se colocan dentro de la consola dibujada. */
    @Volatile var skin = true
    /** Fotogramas por paso en avance rápido (0 = sin límite, unos 8). */
    @Volatile var ffSpeed = 3
    /** Suavizado bilineal de la imagen (si no, píxeles nítidos). */
    @Volatile var smooth = false
        set(v) { field = v; paint.isFilterBitmap = v }

    @Volatile private var running = true
    @Volatile private var paused = false
    private val lock = Object()
    private val commands = ConcurrentLinkedQueue<(Nds) -> Unit>()

    private var holder: SurfaceHolder? = null
    private var surfaceW = 0
    private var surfaceH = 0

    private val bitmap = Bitmap.createBitmap(Nds.WIDTH, Nds.HEIGHT * 2, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val srcTop = Rect(0, 0, Nds.WIDTH, Nds.HEIGHT)
    private val srcBottom = Rect(0, Nds.HEIGHT, Nds.WIDTH, Nds.HEIGHT * 2)
    private val dstTop = Rect()
    private val dstBottom = Rect()
    private val audio = ShortArray(4096 * 2)

    fun setSurface(h: SurfaceHolder?, w: Int, hgt: Int) {
        synchronized(lock) { holder = h; surfaceW = w; surfaceH = hgt }
    }

    /** Ejecuta [cmd] en el hilo de emulación entre dos frames. */
    fun post(cmd: (Nds) -> Unit) {
        commands.add(cmd)
        synchronized(lock) { lock.notifyAll() }
    }

    fun pauseEmu() { paused = true }

    fun resumeEmu() {
        paused = false
        synchronized(lock) { lock.notifyAll() }
    }

    fun shutdown() {
        running = false
        resumeEmu()
    }

    override fun run() {
        val track = createAudio()
        var nextFrame = System.nanoTime()
        var frames = 0L
        try {
            while (running) {
                while (true) commands.poll()?.invoke(nds) ?: break
                if (paused) {
                    track?.pause()
                    nds.flushSave()
                    synchronized(lock) { while (paused && running && commands.isEmpty()) lock.wait(250) }
                    nextFrame = System.nanoTime()
                    continue
                }
                nds.setInput(keys or hwKeys, touching, touchX, touchY)
                val ff = fastForward
                val steps = if (ff) (if (ffSpeed <= 0) 8 else ffSpeed) else 1
                for (i in 0 until steps) {
                    if (!nds.runFrame()) { running = false; break }
                    if (ff || !soundOn) nds.readAudio(audio) // se descarta, pero no se acumula
                }
                draw()
                if (++frames % 120 == 0L) nds.flushSave()

                if (soundOn && !ff && track != null) {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                    val n = nds.readAudio(audio)
                    if (n > 0) track.write(audio, 0, n * 2)
                    nextFrame = System.nanoTime()
                } else {
                    track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) { it.pause(); it.flush() } }
                    nextFrame += FRAME_NS
                    val wait = nextFrame - System.nanoTime()
                    if (wait > 0) sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    else if (wait < -FRAME_NS * 4) nextFrame = System.nanoTime()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            track?.release()
            nds.close()
        }
    }

    private fun draw() {
        synchronized(lock) {
            val h = holder ?: return
            if (surfaceW <= 0 || surfaceH <= 0) return
            bitmap.setPixels(nds.screens, 0, Nds.WIDTH, 0, 0, Nds.WIDTH, Nds.HEIGHT * 2)
            val canvas = try { h.lockHardwareCanvas() } catch (e: Exception) { null } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                ScreenLayout.compute(surfaceW, surfaceH, swapped, skin, dstTop, dstBottom)
                canvas.drawBitmap(bitmap, srcTop, dstTop, paint)
                canvas.drawBitmap(bitmap, srcBottom, dstBottom, paint)
            } finally {
                h.unlockCanvasAndPost(canvas)
            }
        }
    }

    /**
     * Copia de las dos pantallas (arriba y abajo, 256×384) para la miniatura de un estado.
     * Se llama desde el hilo de emulación (dentro de [post]).
     */
    fun snapshot(): Bitmap {
        val b = Bitmap.createBitmap(Nds.WIDTH, Nds.HEIGHT * 2, Bitmap.Config.ARGB_8888)
        b.setPixels(nds.screens, 0, Nds.WIDTH, 0, 0, Nds.WIDTH, Nds.HEIGHT * 2)
        return b
    }

    private fun createAudio(): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(Nds.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val perFrame = Nds.SAMPLE_RATE / 60 * 4 // bytes por frame (estéreo, 16 bits)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Nds.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, perFrame * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    } catch (e: Exception) {
        Log.e("KNDS", "sin audio", e)
        null
    }

    companion object {
        private const val FRAME_NS = 16_715_000L // la DS va a ~59,83 fps
    }
}
