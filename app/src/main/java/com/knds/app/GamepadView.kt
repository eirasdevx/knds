package com.knds.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Consola dibujada y mando táctil.
 *
 * - Vertical: una consola negra mate de una pieza que flota sobre la carátula del juego
 *   desenfocada. Arriba la franja del altavoz y la cámara; luego la pantalla superior, una ranura
 *   fina (el micrófono: mantener para soplar) y la pantalla táctil. Debajo, L/R en las esquinas,
 *   cruceta, A/B/X/Y en rombo, START y SELECT abajo a los lados y la tecla cuadrada del centro,
 *   que abre el menú. Junto a ella, en tono discreto: guardar, cargar, intercambiar pantallas y
 *   avance rápido.
 * - Horizontal: pantallas apiladas en un marco negro en el centro y controles a los lados.
 *
 * Los toques sobre la pantalla táctil (que no caigan en un control) se envían como lápiz.
 * Sin carcasa, los controles se dibujan translúcidos sobre fondo negro.
 */
class GamepadView(context: Context) : View(context) {
    /** keys (máscara de [Keys]), tocando la pantalla, x, y (coordenadas de la DS 0..255, 0..191) */
    var onInput: (Int, Boolean, Int, Int) -> Unit = { _, _, _, _ -> }
    var onMic: (Boolean) -> Unit = {}
    var onAction: (Int) -> Unit = {}

    var buttonsVisible = true
        set(v) { field = v; if (!v) { pressed = 0; emit() }; invalidate() }
    var swapped = false
        set(v) { field = v; relayout() }
    var skin = true
        set(v) { field = v; relayout() }
    var slot = 1
        set(v) { field = v; invalidate() }
    var fastForward = false
        set(v) { field = v; invalidate() }
    /** Fondo detrás de la consola vertical (la carátula ya desenfocada); null = degradado. */
    var backdrop: Bitmap? = null
        set(v) { field = v; invalidate() }

    companion object {
        const val ACT_MENU = 0
        const val ACT_SAVE = 1
        const val ACT_LOAD = 2
        const val ACT_SLOT = 3
        const val ACT_FF = 4
        const val ACT_SWAP = 5
        /** Micrófono: se mantiene pulsado (soplar). */
        const val ACT_MIC = 6
        private const val ACCENT = 0xFFD2BBFF.toInt()

        /** Desenfoque barato: se reduce mucho y se vuelve a ampliar con suavizado en dos pasos. */
        fun softBlur(src: Bitmap): Bitmap {
            val ratio = src.height.toFloat() / src.width
            fun scaled(b: Bitmap, w: Int) = Bitmap.createScaledBitmap(b, w, maxOf(1, (w * ratio).toInt()), true)
            return scaled(scaled(scaled(src, 14), 56), 168)
        }
    }

    private enum class Shape { ROUND, PILL, DOT }
    private class Btn(val mask: Int, val label: String, val rect: RectF, val shape: Shape)
    private class Tool(val action: Int, val rect: RectF)

    private val buttons = mutableListOf<Btn>()
    private val tools = mutableListOf<Tool>()
    private val capsules = mutableListOf<RectF>()
    private val geo = ScreenLayout.Geometry()
    private val touchScreen = Rect()
    private var padX = 0f
    private var padY = 0f
    private var padR = 0f
    private var k = 0f
    private var micRect = RectF()

    private var pressed = 0
    private var touching = false
    private var touchX = 0
    private var touchY = 0
    private var toolDown = -1
    private var micHeld = false
    private var portrait = true
    /** Vertical con carcasa: la consola negra flotante. */
    private val floating get() = skin && portrait

    fun relayout() { if (width > 0) onSizeChanged(width, height, width, height); invalidate() }

    // ------------------------------------------------------------------ geometría

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buttons.clear(); tools.clear(); capsules.clear()
        if (w == 0 || h == 0) return
        portrait = h >= w
        ScreenLayout.layout(w, h, skin, geo)
        touchScreen.set(if (swapped) geo.upper else geo.lower)
        when {
            floating -> layoutConsole()
            portrait -> layoutPortrait(w, h)
            else -> layoutLandscape(w, h)
        }
        setupPaints(w.toFloat(), h.toFloat())
    }

    private fun circle(mask: Int, label: String, cx: Float, cy: Float, r: Float) {
        buttons += Btn(mask, label, RectF(cx - r, cy - r, cx + r, cy + r), Shape.ROUND)
    }

    /** Rombo A/B/X/Y como en la consola: X arriba, A derecha, B abajo, Y izquierda. */
    private fun diamond(cx: Float, cy: Float, r: Float, d: Float = r * 1.42f) {
        circle(Keys.X, "X", cx, cy - d, r)
        circle(Keys.A, "A", cx + d, cy, r)
        circle(Keys.B, "B", cx, cy + d, r)
        circle(Keys.Y, "Y", cx - d, cy, r)
    }

    /** START/SELECT: botón redondo pequeño con el rótulo a su derecha. */
    private fun dot(mask: Int, label: String, left: Float, cy: Float, size: Float) {
        buttons += Btn(mask, label, RectF(left, cy - size / 2, left + size * 3.6f, cy + size / 2), Shape.DOT)
    }

    /**
     * Barra de accesos en cápsula: botones redondos de diámetro [size] centrados en (cx, cy). El
     * menú, si está, va en el centro como un círculo blanco.
     */
    private fun capsule(cx: Float, cy: Float, size: Float, actions: IntArray) {
        val step = size * 1.22f
        val pad = size * 0.16f
        val total = step * (actions.size - 1) + size
        val left = cx - total / 2
        capsules += RectF(left - pad, cy - size / 2 - pad, left + total + pad, cy + size / 2 + pad)
        actions.forEachIndexed { i, a ->
            val x = left + size / 2 + i * step
            tools += Tool(a, RectF(x - size / 2, cy - size / 2, x + size / 2, cy + size / 2))
        }
    }

    /** Vertical con carcasa: proporciones tomadas del cuerpo de la consola (ancho [u]). */
    private fun layoutConsole() {
        val d = geo.device
        val u = d.width()
        k = u
        val c = geo.controlsTop
        val hc = d.bottom - c
        // L y R pegados a los cantos, justo bajo la pantalla táctil
        val shTop = c + u * 0.03f; val shBottom = c + u * 0.105f
        buttons += Btn(Keys.L, "L", RectF(d.left + u * 0.026f, shTop, d.left + u * 0.148f, shBottom), Shape.PILL)
        buttons += Btn(Keys.R, "R", RectF(d.right - u * 0.148f, shTop, d.right - u * 0.026f, shBottom), Shape.PILL)
        // cruceta y rombo a la misma altura
        padR = u * 0.172f
        padX = d.left + u * 0.25f
        padY = maxOf(c + hc * 0.47f, shBottom + u * 0.035f + padR)
        diamond(d.left + u * 0.732f, padY, u * 0.062f, u * 0.125f)
        // fila inferior: START · accesos · tecla del menú · accesos · SELECT
        val rowY = maxOf(c + hc * 0.79f, padY + padR + u * 0.075f).coerceAtMost(d.bottom - u * 0.07f)
        val dotSize = u * 0.052f
        dot(Keys.START, "START", d.left + u * 0.05f, rowY, dotSize)
        dot(Keys.SELECT, "SELECT", d.left + u * 0.778f, rowY, dotSize)
        // cápsula de accesos entre START y SELECT, con el menú destacado en el centro
        capsule(d.centerX(), rowY, u * 0.074f, intArrayOf(ACT_LOAD, ACT_SAVE, ACT_MENU, ACT_SWAP, ACT_FF))
        // la ranura entre las pantallas es el micrófono
        micRect.set(d.centerX() - u * 0.04f, geo.hingeY - u * 0.005f, d.centerX() + u * 0.04f, geo.hingeY + u * 0.005f)
        tools += Tool(ACT_MIC, micRect)
    }

    /** Vertical sin carcasa: pantallas arriba, controles translúcidos y barra de accesos al pie. */
    private fun layoutPortrait(w: Int, h: Int) {
        val safe = ScreenLayout.safe(w, h)
        val wf = w.toFloat(); val hf = h.toFloat()
        val left = safe.left.toFloat(); val right = wf - safe.right
        val sw = right - left
        val gap = minOf(sw, hf) * 0.025f
        val density = resources.displayMetrics.density
        val bottom = hf - safe.bottom - gap * 1.2f
        val toolH = minOf(44 * density, (hf - safe.bottom) * 0.05f, sw / 8.5f)
        capsule(left + sw / 2, bottom - toolH / 2, toolH,
            intArrayOf(ACT_LOAD, ACT_SAVE, ACT_SLOT, ACT_MENU, ACT_SWAP, ACT_FF))
        val top = geo.lower.bottom.toFloat() + gap * 0.5f
        val ch = (bottom - toolH - gap) - top
        k = minOf(sw, ch * 2.4f)
        val cx = left + sw / 2
        val shW = k * 0.25f; val shH = minOf(k * 0.085f, ch * 0.16f)
        buttons += Btn(Keys.L, "L", RectF(left + gap * 1.4f, top, left + gap * 1.4f + shW, top + shH), Shape.PILL)
        buttons += Btn(Keys.R, "R", RectF(right - gap * 1.4f - shW, top, right - gap * 1.4f, top + shH), Shape.PILL)
        val midY = top + shH + (ch - shH) * 0.5f
        padX = left + sw * 0.23f; padY = midY
        padR = minOf(sw * 0.17f, (ch - shH) * 0.42f)
        val r = minOf(sw * 0.066f, (ch - shH) * 0.15f)
        diamond(right - sw * 0.23f, midY, r)
        dot(Keys.START, "START", cx - k * 0.1f, midY - k * 0.06f, k * 0.055f)
        dot(Keys.SELECT, "SELECT", cx - k * 0.1f, midY + k * 0.06f, k * 0.055f)
        micRect.set(cx - k * 0.09f, top, cx + k * 0.09f, top + shH)
        tools += Tool(ACT_MIC, micRect)
    }

    private fun layoutLandscape(w: Int, h: Int) {
        val safe = ScreenLayout.safe(w, h)
        val wf = w.toFloat(); val hf = h.toFloat()
        val top = safe.top.toFloat(); val sh = hf - safe.top - safe.bottom
        val bezelL = geo.upper.left - geo.upper.width() * (if (skin) ScreenLayout.LAND_SIDE else 0f)
        val bezelR = geo.upper.right + geo.upper.width() * (if (skin) ScreenLayout.LAND_SIDE else 0f)
        val sideL = safe.left.toFloat(); val sideR = wf - safe.right
        val sideW = minOf(bezelL - sideL, sideR - bezelR)
        k = minOf(sh, sideW / 0.42f)
        val lx = (sideL + bezelL) / 2; val rx = (bezelR + sideR) / 2
        fun y(f: Float) = top + sh * f
        val shW = k * 0.27f; val shH = k * 0.09f
        buttons += Btn(Keys.L, "L", RectF(lx - shW / 2, y(0.05f), lx + shW / 2, y(0.05f) + shH), Shape.PILL)
        buttons += Btn(Keys.R, "R", RectF(rx - shW / 2, y(0.05f), rx + shW / 2, y(0.05f) + shH), Shape.PILL)
        padX = lx; padY = y(0.44f); padR = k * 0.165f
        diamond(rx, y(0.42f), k * 0.068f)
        dot(Keys.START, "START", rx - k * 0.12f, y(0.70f), k * 0.06f)
        dot(Keys.SELECT, "SELECT", rx - k * 0.12f, y(0.79f), k * 0.06f)
        micRect.set(lx - k * 0.09f, y(0.70f) - k * 0.035f, lx + k * 0.09f, y(0.70f) + k * 0.035f)
        tools += Tool(ACT_MIC, micRect)
        val toolH = minOf(k * 0.1f, 44 * resources.displayMetrics.density, sideW / 4.4f)
        capsule(lx, y(0.89f), toolH, intArrayOf(ACT_LOAD, ACT_MENU, ACT_SAVE))
        capsule(rx, y(0.91f), toolH, intArrayOf(ACT_SLOT, ACT_SWAP, ACT_FF))
    }

    // ------------------------------------------------------------------ pinturas

    private var shellColor = 0
    private var buttonColor = 0
    private var unit = 0f
    private val shellRect = RectF()
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bodyShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sideKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lcdEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF050506.toInt() }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val slitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gradientBackdrop = Paint()
    private val veil = Paint().apply { color = 0x26000000 }
    private val keyShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val keyFace = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val keyPressed = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val shellLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT; typeface = Typeface.create("sans-serif", Typeface.NORMAL); letterSpacing = 0.06f
    }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
    }
    private val micHole = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEB141417.toInt() }
    private val capEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x24FFFFFF }
    private val capShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val capMenu = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPress = Paint(Paint.ANTI_ALIAS_FLAG)
    private val toolPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val toolOn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    // estilo translúcido (sin carcasa)
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val ghostOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x90FFFFFF.toInt() }
    private val ghostText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD0FFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val shape = Path()
    private val drawRect = RectF()
    private var toolIcon = 0
    private var mutedIcon = 0

    private fun shade(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255), (Color.blue(c) * f).toInt().coerceIn(0, 255))
    private fun lighten(c: Int, t: Float) = Color.rgb((Color.red(c) + (255 - Color.red(c)) * t).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * t).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * t).toInt())
    private fun luminance(c: Int) = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private fun setupPaints(w: Float, h: Float) {
        shellColor = ConsoleStyle.shell(context)
        buttonColor = ConsoleStyle.buttons(context)
        val density = resources.displayMetrics.density
        val dark = luminance(shellColor) < 120
        if (floating) {
            val d = geo.device
            unit = d.width() * 0.025f
            shellRect.set(d)
            shellPaint.shader = LinearGradient(0f, d.top, 0f, d.bottom,
                lighten(shellColor, 0.06f), shade(shellColor, 0.88f), Shader.TileMode.CLAMP)
            rimPaint.color = if (dark) alpha(lighten(shellColor, 0.22f), 0x80) else alpha(shade(shellColor, 0.6f), 0xA0)
            rimPaint.strokeWidth = maxOf(1f, density * 1.2f)
            bodyShadow.color = 0x59000000
            sideKeyPaint.color = shade(shellColor, 0.82f)
            bezelPaint.color = if (dark) shade(shellColor, 0.62f) else 0xFF1A1B1F.toInt()
            bezelEdge.color = if (dark) alpha(lighten(shellColor, 0.12f), 0x90) else 0x40000000
            bezelEdge.strokeWidth = maxOf(1f, density)
            holePaint.color = if (dark) shade(shellColor, 0.38f) else shade(shellColor, 0.5f)
            holeRing.color = alpha(lighten(shellColor, 0.2f), 0x70); holeRing.strokeWidth = maxOf(1f, density * 0.8f)
            slitPaint.color = if (dark) shade(shellColor, 0.4f) else shade(shellColor, 0.55f)
            gradientBackdrop.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(0xFFB9A3D9.toInt(), 0xFFE7A882.toInt(), 0xFF7BB8C8.toInt(), 0xFF96C98E.toInt()),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        } else {
            unit = minOf(w, h) * 0.025f
            shellRect.set(unit * 0.5f, unit * 0.5f, w - unit * 0.5f, h - unit * 0.5f)
            shellPaint.shader = LinearGradient(0f, 0f, 0f, h, lighten(shellColor, 0.08f), shade(shellColor, 0.86f), Shader.TileMode.CLAMP)
            rimPaint.color = shade(shellColor, 0.42f); rimPaint.strokeWidth = unit * 0.9f
            bezelPaint.color = 0xFF111114.toInt()
            bezelEdge.color = 0x30FFFFFF; bezelEdge.strokeWidth = maxOf(1f, density)
        }
        lcdEdge.strokeWidth = maxOf(1.5f, density)
        capEdge.strokeWidth = maxOf(1f, density * 0.8f)
        // botones planos: cara del color de los botones, canto fino y rótulo claro (u oscuro)
        val lightKeys = luminance(buttonColor) > 140
        keyEdge.color = if (lightKeys) alpha(shade(buttonColor, 0.7f), 0xC0) else alpha(lighten(buttonColor, 0.14f), 0xC0)
        keyEdge.strokeWidth = maxOf(1f, density * 1.1f)
        keyPressed.color = if (lightKeys) 0x22000000 else 0x1FFFFFFF
        keyLabel.color = if (lightKeys) 0xFF2A2B30.toInt() else 0xFFEDEDF1.toInt()
        arrowStroke.color = keyLabel.color
        shellLabel.color = if (luminance(shellColor) > 150) shade(shellColor, 0.35f) else lighten(shellColor, 0.72f)
        brandPaint.color = if (luminance(shellColor) > 150) shade(shellColor, 0.6f) else lighten(shellColor, 0.55f)
        toolIcon = if (skin && luminance(shellColor) > 160) 0xFF3C3F46.toInt() else 0xFFE4E4EA.toInt()
        mutedIcon = if (luminance(shellColor) > 150) shade(shellColor, 0.55f) else lighten(shellColor, 0.32f)
        toolPaint.color = if (skin) 0x30000000 else 0x30FFFFFF
        toolOn.color = if (skin) 0x60000000 else 0x60FFFFFF
    }

    // ------------------------------------------------------------------ dibujo

    override fun onDraw(canvas: Canvas) {
        if (floating) drawConsole(canvas) else if (skin) drawLandscapeShell(canvas)
        drawCapsules(canvas)
        for (t in tools) drawTool(canvas, t)
        if (!buttonsVisible) return
        drawDpad(canvas)
        for (b in buttons) drawButton(canvas, b)
    }

    private fun clipScreens(canvas: Canvas) {
        for (r in arrayOf(geo.upper, geo.lower)) {
            if (android.os.Build.VERSION.SDK_INT >= 26) canvas.clipOutRect(r)
            else @Suppress("DEPRECATION") canvas.clipRect(RectF(r), android.graphics.Region.Op.DIFFERENCE)
        }
    }

    /** Carátula desenfocada a pantalla completa (recortada para llenar), o un degradado suave. */
    private fun drawBackdrop(canvas: Canvas) {
        val b = backdrop
        if (b == null) { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientBackdrop); return }
        val scale = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val bw = b.width * scale; val bh = b.height * scale
        drawRect.set((width - bw) / 2, (height - bh) / 2, (width + bw) / 2, (height + bh) / 2)
        canvas.drawBitmap(b, null, drawRect, backdropPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
    }

    /** La consola vertical: fondo, botones laterales, sombra, cuerpo, altavoz, marcos y ranura. */
    private fun drawConsole(canvas: Canvas) {
        val save = canvas.save()
        clipScreens(canvas)
        drawBackdrop(canvas)
        val d = geo.device
        val u = d.width(); val hd = d.height()
        val radius = u * 0.105f
        // botones de volumen y encendido asomando por los cantos
        val keyW = u * 0.018f
        fun sideKey(left: Boolean, from: Float, to: Float) {
            val x = if (left) d.left - keyW * 0.55f else d.right - keyW * 0.45f
            drawRect.set(x, d.top + hd * from, x + keyW, d.top + hd * to)
            canvas.drawRoundRect(drawRect, keyW / 2, keyW / 2, sideKeyPaint)
        }
        sideKey(true, 0.145f, 0.285f); sideKey(true, 0.30f, 0.42f); sideKey(false, 0.28f, 0.36f)
        // sombra desplazada abajo a la izquierda, como si flotara sobre el fondo
        drawRect.set(d); drawRect.offset(-u * 0.028f, u * 0.032f)
        canvas.drawRoundRect(drawRect, radius, radius, bodyShadow)
        canvas.drawRoundRect(d, radius, radius, shellPaint)
        drawRect.set(d); drawRect.inset(rimPaint.strokeWidth / 2, rimPaint.strokeWidth / 2)
        canvas.drawRoundRect(drawRect, radius, radius, rimPaint)
        // franja superior: cámara, sensores y rejilla del altavoz
        val stripY = d.top + u * 0.066f
        for ((x, r) in arrayOf(0.15f to 0.012f, 0.217f to 0.021f, 0.293f to 0.015f, 0.343f to 0.011f,
            0.69f to 0.021f, 0.77f to 0.012f)) {
            canvas.drawCircle(d.left + u * x, stripY, u * r, holePaint)
            if (r > 0.018f) canvas.drawCircle(d.left + u * x, stripY, u * r, holeRing)
        }
        drawRect.set(d.left + u * 0.42f, stripY - u * 0.008f, d.left + u * 0.58f, stripY + u * 0.008f)
        canvas.drawRoundRect(drawRect, u * 0.008f, u * 0.008f, holePaint)
        // marcos de las pantallas
        val br = u * 0.02f
        for (b in arrayOf(geo.upperBezel, geo.lowerBezel)) {
            canvas.drawRoundRect(b, br, br, bezelPaint)
            canvas.drawRoundRect(b, br, br, bezelEdge)
        }
        // altavoces a los lados de la pantalla superior
        val sy = geo.upperBezel.centerY()
        for (x in floatArrayOf(d.left + u * 0.038f, d.right - u * 0.038f))
            for (i in -1..1) canvas.drawCircle(x, sy + i * u * 0.034f, u * 0.0085f, holePaint)
        for (r in arrayOf(geo.upper, geo.lower)) {
            drawRect.set(r); drawRect.inset(-lcdEdge.strokeWidth / 2, -lcdEdge.strokeWidth / 2)
            canvas.drawRect(drawRect, lcdEdge)
        }
        canvas.restoreToCount(save)
    }

    /** Horizontal con carcasa: cuerpo a toda la pantalla y marco negro alrededor de las pantallas. */
    private fun drawLandscapeShell(canvas: Canvas) {
        val save = canvas.save()
        clipScreens(canvas)
        val radius = unit * 3.4f
        canvas.drawRoundRect(shellRect, radius, radius, shellPaint)
        canvas.drawRoundRect(shellRect, radius, radius, rimPaint)
        val up = geo.upper; val lo = geo.lower
        val ws = up.width().toFloat(); val hs = up.height().toFloat()
        drawRect.set(up.left - ws * ScreenLayout.LAND_SIDE, up.top - hs * ScreenLayout.LAND_TOP,
            up.right + ws * ScreenLayout.LAND_SIDE, lo.bottom + hs * ScreenLayout.LAND_BOTTOM)
        canvas.drawRoundRect(drawRect, hs * 0.08f, hs * 0.08f, bezelPaint)
        canvas.drawRoundRect(drawRect, hs * 0.08f, hs * 0.08f, bezelEdge)
        val strip = hs * ScreenLayout.LAND_BOTTOM
        if (strip >= unit) {
            brandPaint.textSize = strip * 0.5f
            canvas.drawText("KNDS", up.centerX().toFloat(), lo.bottom + strip * 0.55f + brandPaint.textSize * 0.36f, brandPaint)
        }
        for (r in arrayOf(up, lo)) {
            drawRect.set(r); drawRect.inset(-lcdEdge.strokeWidth / 2, -lcdEdge.strokeWidth / 2)
            canvas.drawRect(drawRect, lcdEdge)
        }
        canvas.restoreToCount(save)
    }

    /** Tecla plana: sombra corta debajo, cara del color de los botones y canto fino; al pulsar se hunde. */
    private inline fun key(canvas: Canvas, down: Boolean, face: Int, corner: Float, content: () -> Unit) {
        val depth = maxOf(1.5f, unit * 0.22f)
        val effect = if (corner > 0f) CornerPathEffect(corner) else null
        keyShadow.pathEffect = effect; keyFace.pathEffect = effect; keyEdge.pathEffect = effect; keyPressed.pathEffect = effect
        if (!down) { canvas.save(); canvas.translate(0f, depth); canvas.drawPath(shape, keyShadow); canvas.restore() }
        canvas.save()
        if (down) canvas.translate(0f, depth * 0.6f)
        keyFace.color = face
        canvas.drawPath(shape, keyFace)
        if (down) canvas.drawPath(shape, keyPressed)
        canvas.drawPath(shape, keyEdge)
        keyShadow.pathEffect = null; keyFace.pathEffect = null; keyEdge.pathEffect = null; keyPressed.pathEffect = null
        content()
        canvas.restore()
    }

    private fun drawDpad(canvas: Canvas) {
        val arm = padR * 0.36f
        if (!skin) {
            val cr = arm * 0.35f
            for ((mask, r) in dpadArms(arm)) canvas.drawRoundRect(r, cr, cr, if ((pressed and mask) != 0) ghostOn else ghostFill)
            return
        }
        shape.reset()
        shape.moveTo(padX - arm, padY - padR); shape.lineTo(padX + arm, padY - padR)
        shape.lineTo(padX + arm, padY - arm); shape.lineTo(padX + padR, padY - arm)
        shape.lineTo(padX + padR, padY + arm); shape.lineTo(padX + arm, padY + arm)
        shape.lineTo(padX + arm, padY + padR); shape.lineTo(padX - arm, padY + padR)
        shape.lineTo(padX - arm, padY + arm); shape.lineTo(padX - padR, padY + arm)
        shape.lineTo(padX - padR, padY - arm); shape.lineTo(padX - arm, padY - arm)
        shape.close()
        // la cruceta es algo más oscura que los botones, como en la carcasa de referencia
        val face = if (luminance(buttonColor) > 140) shade(buttonColor, 0.9f) else shade(buttonColor, 0.72f)
        key(canvas, false, face, arm * 0.3f) {
            val cr = arm * 0.3f
            for ((mask, r) in dpadArms(arm)) if ((pressed and mask) != 0) canvas.drawRoundRect(r, cr, cr, keyPressed)
            // rayas finas en cada brazo: | arriba y abajo, — a los lados
            arrowStroke.strokeWidth = maxOf(2f, padR * 0.045f)
            val a = padR * 0.82f; val b = padR * 0.52f
            canvas.drawLine(padX, padY - a, padX, padY - b, arrowStroke)
            canvas.drawLine(padX, padY + b, padX, padY + a, arrowStroke)
            canvas.drawLine(padX - a, padY, padX - b, padY, arrowStroke)
            canvas.drawLine(padX + b, padY, padX + a, padY, arrowStroke)
        }
    }

    private fun dpadArms(arm: Float) = arrayOf(
        Keys.UP to RectF(padX - arm, padY - padR, padX + arm, padY - arm * 0.3f),
        Keys.DOWN to RectF(padX - arm, padY + arm * 0.3f, padX + arm, padY + padR),
        Keys.LEFT to RectF(padX - padR, padY - arm, padX - arm * 0.3f, padY + arm),
        Keys.RIGHT to RectF(padX + arm * 0.3f, padY - arm, padX + padR, padY + arm),
    )

    private fun triangle(canvas: Canvas, cx: Float, cy: Float, s: Float, dir: Int, p: Paint) {
        val path = Path()
        when (dir) {
            0 -> { path.moveTo(cx, cy - s * 0.6f); path.lineTo(cx + s * 0.6f, cy + s * 0.4f); path.lineTo(cx - s * 0.6f, cy + s * 0.4f) }
            2 -> { path.moveTo(cx, cy + s * 0.6f); path.lineTo(cx + s * 0.6f, cy - s * 0.4f); path.lineTo(cx - s * 0.6f, cy - s * 0.4f) }
            1 -> { path.moveTo(cx + s * 0.6f, cy); path.lineTo(cx - s * 0.4f, cy - s * 0.6f); path.lineTo(cx - s * 0.4f, cy + s * 0.6f) }
            else -> { path.moveTo(cx - s * 0.6f, cy); path.lineTo(cx + s * 0.4f, cy - s * 0.6f); path.lineTo(cx + s * 0.4f, cy + s * 0.6f) }
        }
        path.close()
        canvas.drawPath(path, p)
    }

    private fun drawButton(canvas: Canvas, b: Btn) {
        val on = (pressed and b.mask) != 0
        val r = b.rect
        if (!skin) {
            ghostText.textSize = r.height() * (if (b.shape == Shape.ROUND) 0.42f else 0.4f)
            when (b.shape) {
                Shape.ROUND -> canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, if (on) ghostOn else ghostFill)
                Shape.DOT -> {
                    val dotR = r.height() * 0.36f
                    canvas.drawCircle(r.left + dotR, r.centerY(), dotR, if (on) ghostOn else ghostFill)
                    ghostText.textSize = r.height() * 0.42f
                    ghostText.textAlign = Paint.Align.LEFT
                    canvas.drawText(b.label, r.left + dotR * 2.6f, r.centerY() + ghostText.textSize * 0.36f, ghostText)
                    ghostText.textAlign = Paint.Align.CENTER
                    return
                }
                else -> canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, if (on) ghostOn else ghostFill)
            }
            canvas.drawText(b.label, r.centerX(), r.centerY() + ghostText.textSize * 0.36f, ghostText)
            return
        }
        when (b.shape) {
            Shape.DOT -> {
                val dotR = r.height() * 0.3f
                val dotX = r.left + dotR * 1.2f
                shape.reset(); shape.addCircle(dotX, r.centerY(), dotR, Path.Direction.CW)
                key(canvas, on, shade(buttonColor, 0.85f), 0f) {}
                shellLabel.textSize = r.height() * 0.3f
                canvas.drawText(b.label, dotX + dotR * 1.9f, r.centerY() + shellLabel.textSize * 0.36f, shellLabel)
            }
            Shape.ROUND -> {
                shape.reset(); shape.addCircle(r.centerX(), r.centerY(), r.width() / 2, Path.Direction.CW)
                key(canvas, on, buttonColor, 0f) {
                    keyLabel.textSize = r.width() * 0.5f
                    canvas.drawText(b.label, r.centerX(), r.centerY() + keyLabel.textSize * 0.36f, keyLabel)
                }
            }
            Shape.PILL -> {
                val corner = r.height() * (if (floating) 0.3f else 0.5f)
                shape.reset(); shape.addRoundRect(r, corner, corner, Path.Direction.CW)
                key(canvas, on, buttonColor, 0f) {
                    keyLabel.textSize = r.height() * 0.6f
                    canvas.drawText(b.label, r.centerX(), r.centerY() + keyLabel.textSize * 0.36f, keyLabel)
                }
            }
        }
    }

    private fun drawTool(canvas: Canvas, t: Tool) {
        val r = t.rect
        if (t.action == ACT_MIC) {
            if (floating) {
                // ranura entre las pantallas: se ilumina mientras se sopla
                val rr = r.height() / 2
                slitPaint.alpha = 255
                canvas.drawRoundRect(r, rr, rr, if (micHeld) iconFill.apply { color = ACCENT } else slitPaint)
                return
            }
            // rejilla del micrófono: píldora oscura con agujeros y el rótulo MIC
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, if (micHeld) toolOn else toolPaint)
            val n = 5; val step = r.width() * 0.5f / n
            micHole.color = if (micHeld) ACCENT else if (skin) shade(shellColor, 0.3f) else 0x90FFFFFF.toInt()
            for (i in 0 until n) canvas.drawCircle(r.left + r.width() * 0.12f + i * step + step / 2, r.centerY(), r.height() * 0.1f, micHole)
            labelPaint.color = if (micHeld) ACCENT else toolIcon
            labelPaint.textSize = r.height() * 0.42f
            canvas.drawText("MIC", r.left + r.width() * 0.8f, r.centerY() + labelPaint.textSize * 0.36f, labelPaint)
            return
        }
        // botón redondo dentro de la cápsula; el menú es un círculo blanco con el icono oscuro
        val down = toolDown == t.action
        val cx = r.centerX(); val cy = r.centerY(); val rad = r.width() / 2
        val col = when (t.action) {
            ACT_MENU -> {
                capMenu.color = if (down) 0xFFCFCFD6.toInt() else 0xFFF4F4F7.toInt()
                canvas.drawCircle(cx, cy, rad, capMenu)
                0xFF17171A.toInt()
            }
            else -> {
                val on = t.action == ACT_FF && fastForward
                if (down || on) { capPress.color = if (on) 0x40D2BBFF else 0x30FFFFFF; canvas.drawCircle(cx, cy, rad, capPress) }
                if (on) ACCENT else 0xFFF2F2F5.toInt()
            }
        }
        drawIcon(canvas, t.action, cx, cy, rad * 0.95f, col)
    }

    /** Fondo de las cápsulas: píldora oscura translúcida con un canto claro muy fino. */
    private fun drawCapsules(canvas: Canvas) {
        for (c in capsules) {
            val rr = c.height() / 2
            drawRect.set(c); drawRect.offset(0f, c.height() * 0.06f)
            canvas.drawRoundRect(drawRect, rr, rr, capShadow)
            canvas.drawRoundRect(c, rr, rr, capFill)
            canvas.drawRoundRect(c, rr, rr, capEdge)
        }
    }

    private fun drawIcon(canvas: Canvas, action: Int, cx: Float, cy: Float, s: Float, col: Int) {
        iconPaint.color = col; iconFill.color = col
        iconPaint.strokeWidth = s * 0.13f
        when (action) {
            ACT_MENU -> for (i in -1..1) {
                val y = cy + i * s * 0.32f
                canvas.drawLine(cx - s * 0.45f, y, cx + s * 0.45f, y, iconPaint)
            }
            ACT_SAVE, ACT_LOAD -> {
                val tw = s * 0.42f
                val p = Path()
                p.moveTo(cx - tw, cy + s * 0.05f); p.lineTo(cx - tw, cy + s * 0.45f)
                p.lineTo(cx + tw, cy + s * 0.45f); p.lineTo(cx + tw, cy + s * 0.05f)
                canvas.drawPath(p, iconPaint)
                canvas.drawLine(cx, cy - s * 0.5f, cx, cy + s * 0.2f, iconPaint)
                if (action == ACT_SAVE) {
                    canvas.drawLine(cx - s * 0.22f, cy - s * 0.02f, cx, cy + s * 0.2f, iconPaint)
                    canvas.drawLine(cx + s * 0.22f, cy - s * 0.02f, cx, cy + s * 0.2f, iconPaint)
                } else {
                    canvas.drawLine(cx - s * 0.22f, cy - s * 0.28f, cx, cy - s * 0.5f, iconPaint)
                    canvas.drawLine(cx + s * 0.22f, cy - s * 0.28f, cx, cy - s * 0.5f, iconPaint)
                }
            }
            ACT_SLOT -> {
                labelPaint.color = col
                labelPaint.textSize = s * 0.75f
                canvas.drawText(slot.toString(), cx, cy + s * 0.12f, labelPaint)
                labelPaint.textSize = s * 0.32f
                labelPaint.color = (col and 0x00FFFFFF) or 0xB0000000.toInt()
                canvas.drawText("RANURA", cx, cy + s * 0.62f, labelPaint)
            }
            ACT_SWAP -> {
                // flechas que se cruzan: intercambiar pantallas
                canvas.drawLine(cx - s * 0.2f, cy - s * 0.45f, cx - s * 0.2f, cy + s * 0.45f, iconPaint)
                canvas.drawLine(cx - s * 0.2f, cy + s * 0.45f, cx - s * 0.38f, cy + s * 0.25f, iconPaint)
                canvas.drawLine(cx - s * 0.2f, cy + s * 0.45f, cx - s * 0.02f, cy + s * 0.25f, iconPaint)
                canvas.drawLine(cx + s * 0.2f, cy + s * 0.45f, cx + s * 0.2f, cy - s * 0.45f, iconPaint)
                canvas.drawLine(cx + s * 0.2f, cy - s * 0.45f, cx + s * 0.02f, cy - s * 0.25f, iconPaint)
                canvas.drawLine(cx + s * 0.2f, cy - s * 0.45f, cx + s * 0.38f, cy - s * 0.25f, iconPaint)
            }
            ACT_FF -> {
                triangle(canvas, cx - s * 0.25f, cy, s * 0.55f, 1, iconFill)
                triangle(canvas, cx + s * 0.2f, cy, s * 0.55f, 1, iconFill)
            }
        }
    }

    // ------------------------------------------------------------------ toques

    private fun hit(x: Float, y: Float): Int {
        var mask = 0
        val dx = x - padX; val dy = y - padY
        val d = hypot(dx, dy)
        if (d < padR * 1.3f && d > padR * 0.15f) {
            if (abs(dx) > d * 0.38f) mask = mask or (if (dx < 0) Keys.LEFT else Keys.RIGHT)
            if (abs(dy) > d * 0.38f) mask = mask or (if (dy < 0) Keys.UP else Keys.DOWN)
            return mask
        }
        for (b in buttons) {
            val slop = b.rect.height() * (if (b.shape == Shape.ROUND) 0.18f else 0.35f)
            if (x >= b.rect.left - slop && x <= b.rect.right + slop && y >= b.rect.top - slop && y <= b.rect.bottom + slop)
                mask = mask or b.mask
        }
        return mask
    }

    private fun toolAt(x: Float, y: Float): Int {
        val minSlop = 10 * resources.displayMetrics.density
        for (t in tools) {
            val sl = if (t.action == ACT_MIC) maxOf(t.rect.height() * 0.5f, minSlop * 1.6f)
                else t.rect.height() * 0.11f  // justo hasta el botón vecino de la cápsula
            if (x >= t.rect.left - sl && x <= t.rect.right + sl && y >= t.rect.top - sl && y <= t.rect.bottom + sl) return t.action
        }
        return -1
    }

    private fun emit() = onInput(pressed, touching, touchX, touchY)

    fun releaseInputs() {
        pressed = 0; touching = false; toolDown = -1
        if (micHeld) { micHeld = false; onMic(false) }
        emit(); invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        var mask = 0
        var tool = -1
        var touch = false
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                val x = e.getX(i); val y = e.getY(i)
                // la pantalla táctil manda: la ranura del micrófono no debe robarle toques
                if (touchScreen.contains(x.toInt(), y.toInt())) {
                    touch = true
                    touchX = ((x - touchScreen.left) * 256f / touchScreen.width()).toInt().coerceIn(0, 255)
                    touchY = ((y - touchScreen.top) * 192f / touchScreen.height()).toInt().coerceIn(0, 191)
                    continue
                }
                val t = toolAt(x, y)
                if (t >= 0) { tool = t; continue }
                val m = if (buttonsVisible) hit(x, y) else 0
                if (m != 0) mask = mask or m
            }
        }
        // El micrófono funciona mientras se mantiene; el resto de accesos, al soltar.
        val mic = tool == ACT_MIC
        if (mic != micHeld) { micHeld = mic; onMic(mic); if (mic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        val t = if (mic) -1 else tool
        if (t != toolDown) {
            val released = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
            if (toolDown >= 0 && released) onAction(toolDown)
            if (t >= 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toolDown = t
        }
        if ((mask and pressed.inv()) != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (mask != pressed || touch != touching || touch) {
            pressed = mask; touching = touch
            emit()
        }
        invalidate()
        return true
    }
}
