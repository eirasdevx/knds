package com.knds.app

import android.annotation.SuppressLint
import android.content.Context
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
 * Con carcasa sigue el estilo de la carcasa clásica de KGBA (cuerpo con volumen, canto oscuro y
 * brillo, marco con reflejo y logotipo, LED POWER y botones en relieve con rótulos grabados) con
 * los elementos de la DS:
 * - Vertical: consola plegable. Tapa con la pantalla superior y los altavoces a los lados,
 *   bisagra con el micrófono en el centro (mantener para soplar) y base con la pantalla táctil.
 *   Debajo, L/R en las esquinas, cruceta, A/B/X/Y en rombo y la fila START · accesos · SELECT.
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
    /** Vertical con carcasa: la consola plegable. */
    private val floating get() = skin && portrait && !split
    /** Pantalla completa: pantallas mitad y mitad, nada dibujado encima. */
    private val split get() = ScreenLayout.split && portrait
    /** En el pantalla completa, mantener pulsada la pantalla superior abre el menú. */
    private val openMenu = Runnable {
        menuPending = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onAction(ACT_MENU)
    }
    private var menuPending = false
    private var menuDownX = 0f
    private var menuDownY = 0f
    private var ledX = 0f
    private var ledY = 0f
    private var ledR = 0f

    fun relayout() { if (width > 0) onSizeChanged(width, height, width, height); invalidate() }

    // ------------------------------------------------------------------ geometría

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buttons.clear(); tools.clear(); capsules.clear()
        ledR = 0f
        if (w == 0 || h == 0) return
        portrait = h >= w
        ScreenLayout.layout(w, h, skin, geo)
        touchScreen.set(if (swapped) geo.upper else geo.lower)
        when {
            split -> layoutSplit(w, h)
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
        // LED de encendido bajo R
        ledX = d.right - u * 0.06f; ledY = shBottom + u * 0.045f; ledR = u * 0.012f
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
        // el micrófono va en el centro de la bisagra, como en la DS
        micRect.set(d.centerX() - u * 0.045f, geo.hingeY - geo.hingeH * 0.3f, d.centerX() + u * 0.045f, geo.hingeY + geo.hingeH * 0.3f)
        tools += Tool(ACT_MIC, micRect)
    }

    /**
     * Pantalla completa: solo la barra de accesos, centrada al pie de la pantalla superior (que no
     * es táctil), justo encima de la división.
     */
    private fun layoutSplit(w: Int, h: Int) {
        val safe = ScreenLayout.safe(w, h)
        val sw = w - safe.left - safe.right
        val toolH = minOf(44 * resources.displayMetrics.density, h * 0.05f, sw / 8.5f)
        val cy = geo.upper.bottom - toolH * 0.5f - toolH * 0.35f
        capsule(safe.left + sw / 2f, cy, toolH, intArrayOf(ACT_LOAD, ACT_SAVE, ACT_SLOT, ACT_MENU, ACT_SWAP, ACT_FF))
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
        if (skin) { ledX = rx + k * 0.12f; ledY = y(0.05f) + shH + k * 0.065f; ledR = k * 0.014f }
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
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bezelShine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x10FFFFFF }
    private val lcdEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF2E2E34.toInt() }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holeLight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hingePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5BE35B.toInt() }
    private val ledGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dropShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x45000000 }
    private val keyFace = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pressShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2A000000 }
    private val engrave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val engraveLight = Paint(engrave)
    private val shellLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val shellLabelShadow = Paint(shellLabel)
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
        color = 0xFFD2D2DA.toInt()
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
        val darkShell = luminance(shellColor) < 70
        // carcasa a toda la vista con volumen: degradado, canto grueso oscuro y brillo interior
        unit = if (floating) geo.device.width() * 0.025f else minOf(w, h) * 0.025f
        val base = minOf(w, h) * 0.025f
        shellRect.set(base * 0.5f, base * 0.5f, w - base * 0.5f, h - base * 0.5f)
        shellPaint.shader = LinearGradient(0f, 0f, 0f, h, lighten(shellColor, 0.12f), shade(shellColor, 0.84f), Shader.TileMode.CLAMP)
        rimPaint.color = if (darkShell) lighten(shellColor, 0.1f) else shade(shellColor, 0.42f)
        rimPaint.strokeWidth = base * 0.9f
        glossPaint.strokeWidth = base * 0.3f
        glossPaint.shader = LinearGradient(0f, shellRect.top, 0f, shellRect.top + h * 0.45f,
            0x70FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        // marco oscuro de las pantallas (sobre una carcasa negra, algo más claro para que se vea)
        val bezelTop = if (darkShell) lighten(shellColor, 0.07f) else 0xFF1E1D23.toInt()
        val bezelBottom = if (darkShell) shade(shellColor, 0.55f) else 0xFF0B0B0E.toInt()
        bezelPaint.shader = LinearGradient(0f, 0f, 0f, h, bezelTop, bezelBottom, Shader.TileMode.CLAMP)
        bezelEdge.color = if (darkShell) 0x30FFFFFF else 0x40000000
        bezelEdge.strokeWidth = maxOf(1f, density)
        lcdEdge.strokeWidth = maxOf(2f, unit * 0.3f)
        // agujeros del altavoz y del micrófono hundidos en la carcasa
        holePaint.color = shade(shellColor, if (darkShell) 0.35f else 0.42f)
        holeLight.color = alpha(lighten(shellColor, 0.3f), 0x70)
        // bisagra: cilindro con degradado y juntas oscuras
        if (floating) {
            hingePaint.shader = LinearGradient(0f, geo.hingeY - geo.hingeH / 2, 0f, geo.hingeY + geo.hingeH / 2,
                intArrayOf(shade(shellColor, 0.55f), lighten(shellColor, 0.22f), shellColor, shade(shellColor, 0.5f)),
                floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)
            seamPaint.color = if (darkShell) 0xFF050506.toInt() else shade(shellColor, 0.4f)
            seamPaint.strokeWidth = unit * 0.35f
        }
        ledGlow.shader = if (ledR > 0f) android.graphics.RadialGradient(ledX, ledY, ledR * 3.4f,
            0x8855FF55.toInt(), 0x0055FF55, Shader.TileMode.CLAMP) else null
        // rótulos sobre la carcasa (START, SELECT, POWER, MIC)
        shellLabel.color = if (luminance(shellColor) > 150) shade(shellColor, 0.35f) else lighten(shellColor, 0.55f)
        shellLabelShadow.color = alpha(shade(shellColor, 0.45f), 0xA0)
        brandPaint.color = if (luminance(shellColor) > 170) shade(shellColor, 0.2f) else 0xFFD2D2DA.toInt()
        // botones en relieve; rótulos y flechas grabados (claros si los botones son oscuros)
        val darkKeys = luminance(buttonColor) < 100
        keyEdge.strokeWidth = maxOf(1.5f, unit * 0.16f)
        arrowStroke.color = if (darkKeys) lighten(buttonColor, 0.4f) else shade(buttonColor, 0.62f)
        arrowStroke.strokeWidth = maxOf(1.5f, padR * 0.035f)
        engrave.color = if (darkKeys) lighten(buttonColor, 0.7f) else shade(buttonColor, 0.58f)
        engraveLight.color = if (darkKeys) 0xB0000000.toInt() else alpha(lighten(buttonColor, 0.5f), 0xB0)
        capEdge.strokeWidth = maxOf(1f, density * 0.8f)
        toolIcon = if (skin && luminance(shellColor) > 160) 0xFF3C3F46.toInt() else 0xFFE4E4EA.toInt()
        toolPaint.color = if (skin) 0x30000000 else 0x30FFFFFF
        toolOn.color = if (skin) 0x60000000 else 0x60FFFFFF
    }

    // ------------------------------------------------------------------ dibujo

    override fun onDraw(canvas: Canvas) {
        if (split) {
            // casi transparentes para no tapar el juego; más visibles mientras se pulsa uno
            val alpha = if (toolDown >= 0) 200 else 64
            val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
            drawCapsules(canvas); for (t in tools) drawTool(canvas, t)
            canvas.restoreToCount(save)
            return
        }
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

    /** Cuerpo de la carcasa: degradado, canto oscuro y brillo en el interior del canto. */
    private fun drawShellBody(canvas: Canvas) {
        val radius = shellRect.width().coerceAtMost(shellRect.height()) * 0.085f
        canvas.drawRoundRect(shellRect, radius, radius, shellPaint)
        canvas.drawRoundRect(shellRect, radius, radius, rimPaint)
        val inset = rimPaint.strokeWidth * 0.67f
        drawRect.set(shellRect.left + inset, shellRect.top + inset, shellRect.right - inset, shellRect.bottom - inset)
        canvas.drawRoundRect(drawRect, radius * 0.8f, radius * 0.8f, glossPaint)
    }

    /** Marco con sombra, reflejo diagonal arriba a la derecha y canto fino. */
    private fun drawBezel(canvas: Canvas, r: RectF, rTop: Float, rBottom: Float) {
        shape.reset()
        shape.addRoundRect(r, floatArrayOf(rTop, rTop, rTop, rTop, rBottom, rBottom, rBottom, rBottom), Path.Direction.CW)
        canvas.save(); canvas.translate(0f, unit * 0.2f); canvas.drawPath(shape, dropShadow); canvas.restore()
        canvas.drawPath(shape, bezelPaint)
        canvas.drawPath(shape, bezelEdge)
        canvas.save()
        canvas.clipPath(shape)
        val shine = Path()
        shine.moveTo(r.left + r.width() * 0.52f, r.top); shine.lineTo(r.right, r.top)
        shine.lineTo(r.right, r.top + r.height() * 0.42f); shine.close()
        canvas.drawPath(shine, bezelShine)
        canvas.restore()
    }

    private fun drawLcdEdges(canvas: Canvas) {
        for (r in arrayOf(geo.upper, geo.lower)) {
            drawRect.set(r); drawRect.inset(-lcdEdge.strokeWidth / 2, -lcdEdge.strokeWidth / 2)
            canvas.drawRect(drawRect, lcdEdge)
        }
    }

    private fun drawBrand(canvas: Canvas, cx: Float, top: Float, strip: Float) {
        if (strip < unit) return
        brandPaint.textSize = strip * 0.46f
        canvas.drawText("KNDS", cx, top + strip * 0.52f + brandPaint.textSize * 0.36f, brandPaint)
    }

    /** LED verde de encendido con su halo y el rótulo POWER a la izquierda. */
    private fun drawLed(canvas: Canvas) {
        if (ledR <= 0f) return
        canvas.drawCircle(ledX, ledY, ledR * 3.4f, ledGlow)
        canvas.drawCircle(ledX, ledY, ledR, ledPaint)
        val size = ledR * 2.4f
        shellLabel.textSize = size; shellLabelShadow.textSize = size
        shellLabel.textAlign = Paint.Align.RIGHT; shellLabelShadow.textAlign = Paint.Align.RIGHT
        val tx = ledX - ledR * 2.4f; val ty = ledY + size * 0.36f
        canvas.drawText("POWER", tx, ty + maxOf(1f, size * 0.06f), shellLabelShadow)
        canvas.drawText("POWER", tx, ty, shellLabel)
        shellLabel.textAlign = Paint.Align.LEFT; shellLabelShadow.textAlign = Paint.Align.LEFT
    }

    /** Agujero hundido en la carcasa: sombra oscura y un filo claro debajo. */
    private fun hole(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y + r * 0.35f, r, holeLight)
        canvas.drawCircle(x, y, r, holePaint)
    }

    /**
     * La consola plegable vertical: cuerpo, tapa con la pantalla superior, los altavoces a sus
     * lados y el logotipo; la bisagra con el micrófono; la base con la pantalla táctil y el LED.
     */
    private fun drawConsole(canvas: Canvas) {
        val save = canvas.save()
        clipScreens(canvas)
        drawShellBody(canvas)
        val d = geo.device
        val u = d.width()
        // bisagra a lo ancho: junta entre tapa y base, y los dos cilindros en los extremos
        val hy = geo.hingeY; val hh = geo.hingeH
        canvas.drawLine(shellRect.left + rimPaint.strokeWidth, hy, shellRect.right - rimPaint.strokeWidth, hy, seamPaint)
        val barrelW = u * 0.26f
        for (x0 in floatArrayOf(shellRect.left + rimPaint.strokeWidth * 0.6f, shellRect.right - rimPaint.strokeWidth * 0.6f - barrelW)) {
            drawRect.set(x0, hy - hh / 2, x0 + barrelW, hy + hh / 2)
            canvas.save(); canvas.translate(0f, unit * 0.25f)
            canvas.drawRoundRect(drawRect, hh / 2, hh / 2, dropShadow); canvas.restore()
            canvas.drawRoundRect(drawRect, hh / 2, hh / 2, hingePaint)
            canvas.drawRoundRect(drawRect, hh / 2, hh / 2, seamPaint)
        }
        // marcos: el superior con el logotipo en su franja inferior; el táctil, fino
        val ub = geo.upperBezel; val lb = geo.lowerBezel
        drawBezel(canvas, ub, ub.height() * 0.05f, ub.height() * 0.11f)
        drawBezel(canvas, lb, lb.height() * 0.04f, lb.height() * 0.04f)
        drawLcdEdges(canvas)
        drawBrand(canvas, ub.centerX(), geo.upper.bottom.toFloat(), ub.bottom - geo.upper.bottom)
        // altavoces de la DS a los lados de la pantalla superior
        val gx = (ub.left - d.left) / 2
        val gy = geo.upper.centerY().toFloat()
        val hr = u * 0.0085f; val step = u * 0.026f
        for (cx in floatArrayOf(d.left + gx, d.right - gx))
            for (row in -2..2) for (col in -1..1)
                if (abs(row) + abs(col) < 3) hole(canvas, cx + col * step * 0.62f, gy + row * step, hr)
        drawLed(canvas)
        canvas.restoreToCount(save)
    }

    /** Horizontal con carcasa: cuerpo a toda la pantalla y marco negro alrededor de las pantallas. */
    private fun drawLandscapeShell(canvas: Canvas) {
        val save = canvas.save()
        clipScreens(canvas)
        drawShellBody(canvas)
        val up = geo.upper; val lo = geo.lower
        val ws = up.width().toFloat(); val hs = up.height().toFloat()
        drawRect.set(up.left - ws * ScreenLayout.LAND_SIDE, up.top - hs * ScreenLayout.LAND_TOP,
            up.right + ws * ScreenLayout.LAND_SIDE, lo.bottom + hs * ScreenLayout.LAND_BOTTOM)
        drawBezel(canvas, RectF(drawRect), hs * 0.06f, hs * 0.12f)
        // la franja entre las dos pantallas imita la bisagra de la consola
        val gapTop = up.bottom.toFloat(); val gapBottom = lo.top.toFloat()
        if (gapBottom - gapTop > 2f) {
            seamPaint.color = 0xFF050506.toInt(); seamPaint.strokeWidth = maxOf(1f, (gapBottom - gapTop) * 0.12f)
            val my = (gapTop + gapBottom) / 2
            canvas.drawLine(up.left.toFloat(), my, up.right.toFloat(), my, seamPaint)
        }
        drawLcdEdges(canvas)
        drawBrand(canvas, up.centerX().toFloat(), lo.bottom.toFloat(), hs * ScreenLayout.LAND_BOTTOM)
        drawLed(canvas)
        canvas.restoreToCount(save)
    }

    /** Pieza en relieve: sombra, cara con degradado y canto oscuro; al pulsar se hunde. */
    private inline fun key(canvas: Canvas, down: Boolean, face: Int, corner: Float, bounds: RectF, content: () -> Unit) {
        val depth = maxOf(1.5f, unit * 0.28f)
        val effect = if (corner > 0f) CornerPathEffect(corner) else null
        dropShadow.pathEffect = effect; keyFace.pathEffect = effect; keyEdge.pathEffect = effect
        if (!down) { canvas.save(); canvas.translate(0f, depth); canvas.drawPath(shape, dropShadow); canvas.restore() }
        canvas.save()
        if (down) canvas.translate(0f, depth * 0.6f)
        keyFace.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom,
            if (down) shade(face, 0.84f) else lighten(face, 0.16f), if (down) shade(face, 0.76f) else shade(face, 0.86f),
            Shader.TileMode.CLAMP)
        canvas.drawPath(shape, keyFace)
        keyEdge.color = shade(face, 0.55f)
        canvas.drawPath(shape, keyEdge)
        dropShadow.pathEffect = null; keyFace.pathEffect = null; keyEdge.pathEffect = null
        content()
        canvas.restore()
    }

    /** Texto grabado: filo claro (u oscuro) debajo y el rótulo encima. */
    private fun engraved(canvas: Canvas, label: String, cx: Float, cy: Float, size: Float) {
        engrave.textSize = size; engraveLight.textSize = size
        val y = cy + size * 0.36f
        canvas.drawText(label, cx, y + maxOf(1f, size * 0.05f), engraveLight)
        canvas.drawText(label, cx, y, engrave)
    }

    private fun drawDpad(canvas: Canvas) {
        val arm = padR * 0.34f
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
        drawRect.set(padX - padR, padY - padR, padX + padR, padY + padR)
        key(canvas, false, buttonColor, arm * 0.35f, RectF(drawRect)) {
            val cr = arm * 0.35f
            for ((mask, r) in dpadArms(arm)) if ((pressed and mask) != 0) canvas.drawRoundRect(r, cr, cr, pressShade)
            // flechas huecas y hundimiento circular en el centro
            arrowStroke.strokeWidth = maxOf(1.5f, padR * 0.035f)
            val a = arm * 0.5f; val off = padR * 0.68f
            triangle(canvas, padX, padY - off, a, 0, arrowStroke)
            triangle(canvas, padX, padY + off, a, 2, arrowStroke)
            triangle(canvas, padX - off, padY, a, 3, arrowStroke)
            triangle(canvas, padX + off, padY, a, 1, arrowStroke)
            canvas.drawCircle(padX, padY, arm * 0.62f, pressShade)
            canvas.drawCircle(padX, padY, arm * 0.62f, arrowStroke)
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
                // botón redondo pequeño en relieve y el rótulo sobre la carcasa
                val dotR = r.height() * 0.32f
                val dotX = r.left + dotR * 1.2f
                shape.reset(); shape.addCircle(dotX, r.centerY(), dotR, Path.Direction.CW)
                key(canvas, on, buttonColor, 0f, RectF(dotX - dotR, r.centerY() - dotR, dotX + dotR, r.centerY() + dotR)) {}
                val size = r.height() * 0.32f
                shellLabel.textSize = size; shellLabelShadow.textSize = size
                val tx = dotX + dotR * 1.8f; val ty = r.centerY() + size * 0.36f
                canvas.drawText(b.label, tx, ty + maxOf(1f, size * 0.06f), shellLabelShadow)
                canvas.drawText(b.label, tx, ty, shellLabel)
            }
            Shape.ROUND -> {
                shape.reset(); shape.addCircle(r.centerX(), r.centerY(), r.width() / 2, Path.Direction.CW)
                key(canvas, on, buttonColor, 0f, r) { engraved(canvas, b.label, r.centerX(), r.centerY(), r.width() * 0.42f) }
            }
            Shape.PILL -> {
                shape.reset(); shape.addRoundRect(r, r.height() / 2, r.height() / 2, Path.Direction.CW)
                key(canvas, on, buttonColor, 0f, r) { engraved(canvas, b.label, r.centerX(), r.centerY(), r.height() * 0.5f) }
            }
        }
    }

    private fun drawTool(canvas: Canvas, t: Tool) {
        val r = t.rect
        if (t.action == ACT_MIC) {
            if (floating) {
                // micrófono en el centro de la bisagra: tres agujeros que se iluminan al soplar
                val hr = r.height() * 0.22f
                micHole.color = ACCENT
                for (i in -1..1) {
                    val x = r.centerX() + i * r.width() * 0.3f
                    if (micHeld) canvas.drawCircle(x, r.centerY(), hr, micHole) else hole(canvas, x, r.centerY(), hr)
                }
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

    private fun cancelMenuPress() {
        if (menuPending) { removeCallbacks(openMenu); menuPending = false }
    }

    /**
     * Pantalla completa: la mitad táctil manda el lápiz y la barra de accesos funciona como
     * siempre; mantener pulsada la mitad superior ~0,6 s también abre el menú (y el gesto de atrás).
     */
    private fun splitTouch(e: MotionEvent): Boolean {
        val action = e.actionMasked
        var touch = false
        var tool = -1
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                val x = e.getX(i); val y = e.getY(i)
                if (touchScreen.contains(x.toInt(), y.toInt())) {
                    touch = true
                    touchX = ((x - touchScreen.left) * 256f / touchScreen.width()).toInt().coerceIn(0, 255)
                    touchY = ((y - touchScreen.top) * 192f / touchScreen.height()).toInt().coerceIn(0, 191)
                } else toolAt(x, y).let { if (it >= 0) tool = it }
            }
        }
        // los accesos actúan al soltar, igual que con los controles
        if (tool != toolDown) {
            val released = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
            if (toolDown >= 0 && released) onAction(toolDown)
            if (tool >= 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toolDown = tool
            invalidate()
        }
        val idx = e.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx)
                if (!touchScreen.contains(x.toInt(), y.toInt()) && toolAt(x, y) < 0 && !menuPending) {
                    menuPending = true; menuDownX = x; menuDownY = y
                    postDelayed(openMenu, 600)
                }
            }
            MotionEvent.ACTION_MOVE -> if (menuPending) {
                val slop = 16 * resources.displayMetrics.density
                val moved = (0 until e.pointerCount).none { hypot(e.getX(it) - menuDownX, e.getY(it) - menuDownY) < slop }
                if (moved) cancelMenuPress()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> cancelMenuPress()
        }
        pressed = 0
        if (touch != touching || touch) { touching = touch; emit() }
        return true
    }

    fun releaseInputs() {
        cancelMenuPress()
        pressed = 0; touching = false; toolDown = -1
        if (micHeld) { micHeld = false; onMic(false) }
        emit(); invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (split) return splitTouch(e)
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
