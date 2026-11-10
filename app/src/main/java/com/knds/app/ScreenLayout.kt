package com.knds.app

import android.graphics.Rect

/**
 * Dónde se dibujan las dos pantallas de la DS. Lo usan el renderizado, la carcasa/mando táctil
 * y la animación de arranque, así que todos coinciden al píxel.
 *
 * Con carcasa:
 * - Vertical: consola negra de una pieza que flota centrada sobre el fondo. Arriba la franja
 *   del altavoz, la pantalla superior con su marco, una ranura fina y la pantalla táctil; los
 *   controles van debajo.
 * - Horizontal: las dos pantallas apiladas en el centro dentro de un marco negro; los controles a
 *   los lados.
 * Sin carcasa: pantallas lo más grandes posible (arriba en vertical, centradas en horizontal).
 *
 * La carcasa se dibuja también bajo la cámara (muesca o agujero); las pantallas y los controles
 * la esquivan ([cutout]).
 */
object ScreenLayout {
    /** Zona de la cámara en píxeles de la vista. La rellena [GameWindow]. */
    @Volatile var cutout = Rect()

    /** Horizontal: márgenes del marco único que rodea las dos pantallas. */
    const val LAND_SIDE = 0.06f
    const val LAND_TOP = 0.05f
    const val LAND_GAP = 0.05f
    const val LAND_BOTTOM = 0.10f

    /** Márgenes seguros para una vista w×h; a los lados se igualan para que todo quede centrado. */
    fun safe(w: Int, h: Int): Rect {
        val c = cutout
        val side = maxOf(c.left, c.right)
        return Rect(side, c.top, side, c.bottom)
    }

    /** Geometría completa: pantallas («arriba» y «abajo» de posición) y la bisagra en vertical. */
    class Geometry {
        val upper = Rect()
        val lower = Rect()
        var hingeY = 0f
        var hingeH = 0f
        /** Vertical con carcasa: donde empieza la zona de controles. */
        var controlsTop = 0f
        /** Vertical con carcasa: el cuerpo de la consola y los marcos de las dos pantallas. */
        val device = android.graphics.RectF()
        val upperBezel = android.graphics.RectF()
        val lowerBezel = android.graphics.RectF()
    }

    /**
     * Proporciones de la consola vertical, en fracción del ancho del cuerpo: una pieza negra mate
     * con la franja de altavoz arriba, dos pantallas 4:3 con su marco y una ranura fina entre ellas.
     */
    private const val TOP_STRIP = 0.112f
    private const val BEZEL_SIDE = 0.076f
    private const val SCREEN_W = 0.776f
    private const val BEZEL_TOP = 0.033f
    private const val BEZEL_BOTTOM = 0.04f
    private const val SLIT = 0.034f
    /** Alto mínimo del cuerpo (con la zona de controles) respecto a su ancho. */
    private const val BODY_MIN = 2.17f
    private const val BODY_MAX = 2.4f

    fun compute(w: Int, h: Int, swapped: Boolean, skin: Boolean, top: Rect, bottom: Rect) {
        val g = Geometry()
        layout(w, h, skin, g)
        (if (swapped) bottom else top).set(g.upper)
        (if (swapped) top else bottom).set(g.lower)
    }

    fun layout(w: Int, h: Int, skin: Boolean, g: Geometry) {
        if (w <= 0 || h <= 0) { g.upper.setEmpty(); g.lower.setEmpty(); return }
        val portrait = h >= w
        val safe = safe(w, h)
        val ox = safe.left; val oy = safe.top
        val sw = (w - safe.left - safe.right).toFloat()
        val sh = (h - safe.top - safe.bottom).toFloat()
        g.hingeH = 0f; g.hingeY = 0f; g.controlsTop = h.toFloat()
        g.device.set(0f, 0f, w.toFloat(), h.toFloat())
        g.upperBezel.setEmpty(); g.lowerBezel.setEmpty()
        if (!skin) {
            var scale = if (portrait) minOf(sw / 256f, sh * 0.66f / 384f) else minOf(sh / 384f, sw * 0.5f / 256f)
            if (scale >= 2f) scale = kotlin.math.floor(scale * 4f) / 4f
            val ww = (256 * scale).toInt(); val hh = (192 * scale).toInt()
            val left = ox + ((sw - ww) / 2).toInt()
            val y0 = oy + if (portrait) 0 else ((sh - hh * 2) / 2).toInt()
            g.upper.set(left, y0, left + ww, y0 + hh)
            g.lower.set(left, y0 + hh, left + ww, y0 + hh * 2)
            g.controlsTop = g.lower.bottom.toFloat()
            return
        }
        if (portrait) {
            // El cuerpo flota centrado sobre el fondo; si sobra alto, crece la zona de controles.
            val u = minOf(sw * 0.76f, sh * 0.95f / BODY_MIN)
            val hd = minOf(sh * 0.95f, u * BODY_MAX)
            val dl = ox + (sw - u) / 2f
            val dt = oy + (sh - hd) / 2f
            g.device.set(dl, dt, dl + u, dt + hd)
            val ww = (u * SCREEN_W).toInt(); val hh = ww * 3 / 4
            val left = (dl + (u - ww) / 2f).toInt()
            val bezelL = dl + u * BEZEL_SIDE; val bezelR = dl + u * (1f - BEZEL_SIDE)
            val upperBezelTop = dt + u * TOP_STRIP
            val upperTop = (upperBezelTop + u * BEZEL_TOP).toInt()
            g.upper.set(left, upperTop, left + ww, upperTop + hh)
            val upperBezelBottom = g.upper.bottom + u * BEZEL_BOTTOM
            g.upperBezel.set(bezelL, upperBezelTop, bezelR, upperBezelBottom)
            val lowerBezelTop = upperBezelBottom + u * SLIT
            g.hingeY = (upperBezelBottom + lowerBezelTop) / 2f
            g.hingeH = u * SLIT
            val lowerTop = (lowerBezelTop + u * BEZEL_BOTTOM).toInt()
            g.lower.set(left, lowerTop, left + ww, lowerTop + hh)
            g.lowerBezel.set(bezelL, lowerBezelTop, bezelR, g.lower.bottom + u * BEZEL_BOTTOM)
            g.controlsTop = g.lowerBezel.bottom
        } else {
            val tall = 192f * (2f + LAND_TOP + LAND_GAP + LAND_BOTTOM)
            val s = minOf(sh * 0.97f / tall, (sw - sh * 0.80f) / (256f * (1f + 2 * LAND_SIDE)))
                .coerceAtLeast(sh * 0.5f / tall)
            val ww = (256 * s).toInt(); val hh = (192 * s).toInt()
            val left = ox + ((sw - ww) / 2).toInt()
            val top = oy + ((sh - tall * s) / 2 + LAND_TOP * hh).toInt()
            g.upper.set(left, top, left + ww, top + hh)
            val lowerTop = top + hh + (LAND_GAP * hh).toInt()
            g.lower.set(left, lowerTop, left + ww, lowerTop + hh)
        }
    }
}
