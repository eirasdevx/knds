package com.knds.app

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class EmulatorActivity : Activity() {
    companion object {
        /** Ranura (1..10) que se carga nada más abrir el juego. */
        const val EXTRA_LOAD_SLOT = "load_slot"
        private const val REQ_MIC = 7
    }

    private var emu: Emulator? = null
    private lateinit var pad: GamepadView
    private lateinit var surface: SurfaceView
    private lateinit var bootView: BootView
    private var romId = ""
    private var slot = 1
    private var dialogs = 0
    private val menuOpen get() = dialogs > 0
    private var lidClosed = false
    private var booting = true
    private var bootFinished = false
    private var activityResumed = false
    private var axisKeys = 0
    private var buttonKeys = 0
    private var keymap: Map<Int, Int> = emptyMap()
    /** Mientras no es null, la próxima tecla se asigna a este botón de la DS. */
    private var remapTarget: Int? = null
    private var remapDialog: AppDialog? = null
    @Volatile private var micThread: Thread? = null
    private val prefs by lazy { getSharedPreferences("knds", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.applyTheme(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = SurfaceView(this)
        pad = GamepadView(this)
        pad.buttonsVisible = prefs.getBoolean("pad", true)
        pad.swapped = prefs.getBoolean("swap", false)
        pad.skin = prefs.getBoolean("skin", true)
        applySplit(prefs.getBoolean("split", false))
        keymap = Keymap.custom(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        root.addView(pad, FrameLayout.LayoutParams(-1, -1))
        GameWindow.configure(window, root) { pad.relayout(); bootView.invalidate() }
        bootView = BootView(this, { pad.skin }, { pad.swapped }, { prefs.getBoolean("sound", true) }) {
            bootFinished = true; finishBoot()
        }
        root.addView(bootView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemBars()

        val uri = intent.data
        if (uri == null) { finish(); return }

        // Copiar la ROM y arrancar melonDS lleva un momento: fuera del hilo de la interfaz
        Thread {
            try {
                val rom = RomStore.prepare(this, uri)
                RomStore.remember(this, uri.toString(), rom.id, RomStore.displayName(this, uri))
                NdsCore.nativeSetBaseDir(filesDir.absolutePath)
                val nds = Nds.create(
                    romPath = rom.file.absolutePath,
                    savePath = RomStore.saveFile(this, rom.id).absolutePath,
                    bios9 = RomStore.readSystem(this, RomStore.SystemFile.BIOS9),
                    bios7 = RomStore.readSystem(this, RomStore.SystemFile.BIOS7),
                    firmware = RomStore.readSystem(this, RomStore.SystemFile.FIRMWARE),
                    jit = prefs.getBoolean("jit", true),
                    language = firmwareLanguage(),
                    nickname = prefs.getString("nickname", "KNDS") ?: "KNDS",
                    threaded3D = prefs.getBoolean("threaded3d", true),
                )
                runOnUiThread { start(Emulator(nds), rom.id) }
            } catch (ex: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.rom_error, ex.message ?: ex.toString()), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    /** Idioma del firmware según el del sistema (los juegos multilenguaje lo usan). */
    private fun firmwareLanguage(): Int = when (Locale.getDefault().language) {
        "ja" -> 0; "en" -> 1; "fr" -> 2; "de" -> 3; "it" -> 4; "es", "ca", "gl", "eu" -> 5; else -> 1
    }

    private fun start(e: Emulator, id: String) {
        if (isFinishing || isDestroyed) { e.shutdown(); e.start(); return } // el hilo cierra la consola al salir
        romId = id
        emu = e
        slot = prefs.getInt("slot_$id", 1).coerceIn(1, RomStore.SLOTS)
        pad.slot = slot
        e.soundOn = prefs.getBoolean("sound", true)
        e.swapped = pad.swapped
        e.skin = pad.skin
        e.ffSpeed = prefs.getInt("ffspeed", 3)
        e.smooth = prefs.getBoolean("smooth", false)
        pad.onInput = { k, t, x, y -> e.keys = k; e.touchX = x; e.touchY = y; e.touching = t }
        pad.onMic = { on -> e.post { it.setMicBlow(on) } }
        pad.onAction = { a ->
            when (a) {
                GamepadView.ACT_MENU -> showMenu()
                GamepadView.ACT_SAVE -> saveSlot(slot)
                GamepadView.ACT_LOAD -> loadSlot(slot)
                GamepadView.ACT_SLOT -> slotDialog(SlotMode.PICK)
                GamepadView.ACT_FF -> toggleFastForward()
                GamepadView.ACT_SWAP -> toggleSwap()
            }
        }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = e.setSurface(holder, w, h)
            override fun surfaceDestroyed(holder: SurfaceHolder) = e.setSurface(null, 0, 0)
        })
        if (surface.holder.surface.isValid) e.setSurface(surface.holder, surface.width, surface.height)
        applyCheats(quiet = true)
        e.pauseEmu()
        e.start()
        // Abierto desde «Guardados»: carga directamente la ranura elegida.
        intent.getIntExtra(EXTRA_LOAD_SLOT, 0).takeIf { it in 1..RomStore.SLOTS }?.let { n ->
            intent.removeExtra(EXTRA_LOAD_SLOT)
            setSlot(n); loadSlot(n)
        }
        finishBoot()
    }

    /** El juego arranca cuando han terminado la animación y la carga de la ROM. */
    private fun finishBoot() {
        val e = emu ?: return
        if (!bootFinished || !booting || isFinishing || isDestroyed) return
        booting = false
        (bootView.parent as? ViewGroup)?.removeView(bootView)
        if (prefs.getBoolean("realmic", false)) startMic()
        if (activityResumed && !menuOpen) e.resumeEmu()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        activityResumed = false
        bootView.pause()
        pad.releaseInputs()
        buttonKeys = 0; axisKeys = 0; updateHw()
        stopMic()
        emu?.pauseEmu()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        bootView.resume()
        if (!booting && !menuOpen) {
            if (prefs.getBoolean("realmic", false)) startMic()
            emu?.resumeEmu()
        }
    }

    override fun onDestroy() {
        stopMic()
        emu?.shutdown()
        emu?.join(3000)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = showMenu()

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dialog() = AppDialog.Builder(this)

    /**
     * Diálogos durante el juego: la emulación se pausa mientras haya alguno abierto. Se cuentan,
     * porque al pasar de un diálogo a otro el aviso de cierre del primero llega después.
     */
    private fun show(builder: AppDialog.Builder, onDismiss: (() -> Unit)? = null): AppDialog {
        dialogs++
        emu?.pauseEmu()
        pad.releaseInputs()
        builder.setOnDismissListener {
            onDismiss?.invoke()
            dialogs--
            if (dialogs == 0) {
                hideSystemBars()
                if (!isFinishing && !booting && activityResumed) emu?.resumeEmu()
            }
        }
        return builder.show()
    }

    // ---------------------------------------------------------------- menú de pausa

    private fun showMenu() {
        val e = emu ?: return
        if (menuOpen || booting) return
        val ui = AppUi(this)
        val title = intent.data?.let { CoverStore.officialTitle(this, it.toString()) }
            ?: intent.data?.let { RomStore.displayName(this, it) } ?: getString(R.string.menu_title)
        fun check(on: Boolean) = if (on) "  ✓" else ""
        val groups = linkedMapOf<String, List<Pair<String, () -> Unit>>>()
        groups["Partida"] = listOf(
            "Ranuras de estado" to { slotDialog(SlotMode.PICK) },
            "Trucos (Action Replay) · ${CheatStore.active(this, romId).size} activos" to { cheatsDialog() },
        )
        groups["Pantallas"] = listOf(
            "Intercambiar pantallas" + check(pad.swapped) to { toggleSwap() },
            "Cerrar la tapa" + check(lidClosed) to { lidClosed = !lidClosed; val c = lidClosed; e.post { it.setLidClosed(c) } },
            "Avance rápido" + check(e.fastForward) to { toggleFastForward() },
        )
        groups["Sonido y micrófono"] = listOf(
            "Sonido" + check(e.soundOn) to { e.soundOn = !e.soundOn; prefs.edit().putBoolean("sound", e.soundOn).apply() },
            "Micrófono real" + check(prefs.getBoolean("realmic", false)) to { toggleRealMic() },
        )
        groups["Controles"] = listOf(
            "Mostrar carcasa de consola" + check(pad.skin) to {
                pad.skin = !pad.skin; e.skin = pad.skin; prefs.edit().putBoolean("skin", pad.skin).apply()
            },
            "Mostrar controles táctiles" + check(pad.buttonsVisible) to {
                pad.buttonsVisible = !pad.buttonsVisible
                prefs.edit().putBoolean("pad", pad.buttonsVisible).apply()
                if (!pad.buttonsVisible) e.keys = 0
            },
            "Mando físico: asignar botones…" to { remapMenu() },
        )
        groups["Consola"] = listOf(
            getString(R.string.reset) to {
                show(dialog().setTitle(R.string.reset)
                    .setMessage("Se perderá el progreso que no hayas guardado. ¿Reiniciar el juego?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton(R.string.reset) { _, _ -> e.post { it.reset() } })
            },
            getString(R.string.exit) to { finish() },
        )
        val descriptions = mapOf(
            "Partida" to ("Ranuras y trucos" to "▦"),
            "Pantallas" to ("Intercambiar, tapa y avance rápido" to "⇅"),
            "Sonido y micrófono" to ("Audio y micrófono real para soplar" to "♪"),
            "Controles" to ("Carcasa, botones y mando físico" to "✥"),
            "Consola" to ("Reiniciar o volver a la biblioteca" to "↺"),
        )
        val body = ui.column()
        lateinit var d: AppDialog
        body.addView(ui.button("▶  " + getString(R.string.resume), true) { d.dismiss() })
        val quick = LinearLayout(this)
        quick.addView(ui.button("Guardar · ranura $slot") { d.dismiss(); saveSlot(slot) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, ui.dp(6), ui.dp(4), ui.dp(6)) })
        quick.addView(ui.button("Cargar · ranura $slot") { d.dismiss(); loadSlot(slot) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(4), ui.dp(6), 0, ui.dp(6)) })
        body.addView(quick)
        val split = ScreenLayout.split
        body.addView(ui.button(if (split) "Pantalla completa ✓  ·  volver a los controles" else "Pantalla completa (vertical, pantallas mitad y mitad)") {
            d.dismiss(); toggleSplit()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        body.addView(ui.section("OPCIONES DEL JUEGO"))
        groups.forEach { (category, actions) ->
            val (subtitle, icon) = descriptions[category] ?: ("" to "›")
            body.addView(ui.row(category, subtitle, icon) { d.dismiss(); showPauseCategory(category, actions) })
        }
        d = show(dialog().setTitle(title).setView(body).setNegativeButton("Volver al juego", null))
    }

    private fun showPauseCategory(title: String, actions: List<Pair<String, () -> Unit>>) {
        var backToMenu = false
        show(dialog().setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Volver al menú") { _, _ -> backToMenu = true }) {
            if (backToMenu) window.decorView.post { showMenu() }
        }
    }

    /**
     * Pantalla completa: obliga a jugar en vertical con la pantalla superior en la mitad de arriba
     * del móvil y la táctil en la de abajo. El menú se abre manteniendo pulsada la mitad superior
     * o con el gesto de atrás.
     */
    private fun applySplit(on: Boolean) {
        ScreenLayout.split = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        pad.releaseInputs()
        emu?.keys = 0
        pad.relayout()
        if (::bootView.isInitialized) bootView.invalidate()
    }

    private fun toggleSplit() {
        val on = !ScreenLayout.split
        prefs.edit().putBoolean("split", on).apply()
        applySplit(on)
        Toast.makeText(this, if (on) "Pantalla completa: mantén pulsada la pantalla de arriba para abrir el menú"
            else "Controles táctiles de nuevo visibles", Toast.LENGTH_LONG).show()
    }

    private fun toggleFastForward() {
        val e = emu ?: return
        e.fastForward = !e.fastForward
        pad.fastForward = e.fastForward
    }

    private fun toggleSwap() {
        pad.swapped = !pad.swapped
        emu?.swapped = pad.swapped
        prefs.edit().putBoolean("swap", pad.swapped).apply()
    }

    // ---------------------------------------------------------------- ranuras de estado

    private enum class SlotMode { PICK, SAVE, LOAD }

    private fun setSlot(n: Int) {
        slot = n; pad.slot = n
        prefs.edit().putInt("slot_$romId", n).apply()
    }

    private fun saveSlot(n: Int) {
        val e = emu ?: return
        val id = romId
        e.post { nds ->
            val data = nds.saveState()
            val ok = data != null && try {
                RomStore.writeState(this, id, data, n)
                // miniatura con las dos pantallas, a mitad de tamaño
                val shot = e.snapshot()
                val small = Bitmap.createScaledBitmap(shot, Nds.WIDTH / 2, Nds.HEIGHT, true)
                RomStore.thumbFile(this, id, n).outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 90, it) }
                true
            } catch (_: Exception) { false }
            runOnUiThread { toast(if (ok) "Estado guardado en la ranura $n" else "No se pudo guardar el estado") }
        }
    }

    private fun loadSlot(n: Int) {
        val e = emu ?: return
        val id = romId
        if (!RomStore.hasState(this, id, n)) { toast("La ranura $n está vacía"); return }
        e.post { nds ->
            val ok = try { nds.loadState(RomStore.readState(this, id, n)) } catch (_: Exception) { false }
            runOnUiThread { toast(if (ok) "Estado de la ranura $n cargado" else "No se pudo cargar el estado") }
        }
    }

    /** Las diez ranuras con la miniatura de las dos pantallas, con el mismo aspecto que en KGBA. */
    private fun slotDialog(mode: SlotMode) {
        if (emu == null) return
        val ui = AppUi(this)
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val body = ui.column()
        body.addView(ui.text(when (mode) {
            SlotMode.SAVE -> "Elige dónde guardar tu progreso."
            SlotMode.LOAD -> "Continúa desde una partida guardada."
            SlotMode.PICK -> "Elige la ranura para guardar y cargar rápidamente."
        }, 14, color = AppUi.MUTED).apply { setPadding(0, 0, 0, ui.dp(16)) })
        lateinit var dialog: AppDialog
        for (n in 1..RomStore.SLOTS) {
            val state = RomStore.stateFile(this, romId, n)
            val thumb = RomStore.thumbFile(this, romId, n)
            val occupied = state.isFile
            val selected = n == slot
            val enabled = mode != SlotMode.LOAD || occupied
            val row = ui.line().apply {
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                minimumHeight = ui.dp(88)
                background = ui.ripple(ui.shape(if (selected) AppUi.SURFACE_HIGH else AppUi.PANEL,
                    18, if (selected) AppUi.PRIMARY else AppUi.BORDER))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
                isSelected = selected
                isEnabled = enabled
                isFocusable = enabled
                alpha = if (enabled) 1f else 0.55f
            }
            val preview = FrameLayout(this).apply {
                background = ui.shape(AppUi.SURFACE_LOWEST, 10, AppUi.BORDER)
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val bitmap = if (occupied && thumb.isFile) BitmapFactory.decodeFile(thumb.path) else null
            if (bitmap != null) preview.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
            }, FrameLayout.LayoutParams(-1, -1))
            else preview.addView(ui.icon(if (occupied) R.drawable.ic_nav_saves else R.drawable.ic_add,
                AppUi.OUTLINE, 24), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            // Las dos pantallas una encima de otra: proporción 2:3.
            row.addView(preview, LinearLayout.LayoutParams(ui.dp(48), ui.dp(72)))
            val date = if (occupied) fmt.format(Date(state.lastModified())) else "Vacía"
            val labels = ui.column().apply {
                addView(ui.text("Ranura $n", 16, true))
                if (selected) addView(ui.text("ACTUAL", 10, true, AppUi.PRIMARY))
                addView(ui.text(date, 12, color = AppUi.MUTED))
            }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(14) })
            row.contentDescription = "Ranura $n, $date" + if (selected) ", ranura actual" else ""
            if (enabled) row.setOnClickListener {
                setSlot(n)
                when (mode) {
                    SlotMode.LOAD -> { loadSlot(n); dialog.dismiss() }
                    SlotMode.SAVE -> if (occupied) confirmOverwrite(n) { saveSlot(n); dialog.dismiss() }
                        else { saveSlot(n); dialog.dismiss() }
                    SlotMode.PICK -> { dialog.dismiss(); slotActions(n) }
                }
            }
            body.addView(row)
        }
        val title = when (mode) { SlotMode.SAVE -> "Guardar en ranura"; SlotMode.LOAD -> "Cargar de ranura"; SlotMode.PICK -> "Ranuras de estado" }
        dialog = show(dialog().setTitle(title).setView(body).setNegativeButton("Cancelar", null))
    }

    private fun confirmOverwrite(n: Int, action: () -> Unit) {
        show(dialog().setTitle("¿Sobrescribir la ranura $n?")
            .setMessage("Se reemplazará el estado guardado en esta ranura.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Sobrescribir") { _, _ -> action() })
    }

    private fun slotActions(n: Int) {
        val has = RomStore.hasState(this, romId, n)
        val items = if (has) arrayOf("Guardar aquí", "Cargar", "Borrar") else arrayOf("Guardar aquí")
        show(dialog().setTitle("Ranura $n").setItems(items) { _, w ->
            when (w) {
                0 -> if (has) confirmOverwrite(n) { saveSlot(n) } else saveSlot(n)
                1 -> loadSlot(n)
                2 -> { RomStore.stateFile(this, romId, n).delete(); RomStore.thumbFile(this, romId, n).delete(); toast("Ranura $n borrada") }
            }
        })
    }

    // ---------------------------------------------------------------- trucos

    private fun applyCheats(quiet: Boolean = false) {
        val e = emu ?: return
        val codes = CheatStore.active(this, romId)
        e.post { nds ->
            val ok = nds.setCheats(codes)
            if (!quiet) runOnUiThread { toast(if (ok) "Trucos activos: ${codes.size}" else "Algún código no es válido y se ha ignorado") }
        }
    }

    private fun cheatsDialog() {
        val cheats = CheatStore.load(this, romId)
        val names = cheats.map { it.name.ifBlank { "Sin nombre" } }.toTypedArray()
        val checked = cheats.map { it.enabled }.toBooleanArray()
        val b = dialog().setTitle("Trucos (Action Replay)")
        if (cheats.isEmpty()) b.setMessage("Este juego no tiene trucos. Añade un código Action Replay: pares de números hexadecimales, por ejemplo «02000000 00000063».")
        else b.setMultiChoiceItems(names, checked) { _, i, on -> cheats[i].enabled = on }
        b.setPositiveButton("Aplicar") { _, _ -> CheatStore.save(this, romId, cheats); applyCheats() }
        b.setNeutralButton("Añadir") { _, _ -> CheatStore.save(this, romId, cheats); addCheat() }
        if (cheats.isNotEmpty()) b.setNegativeButton("Borrar…") { _, _ ->
            show(dialog().setTitle("Borrar truco").setItems(names) { _, i ->
                cheats.removeAt(i); CheatStore.save(this, romId, cheats); applyCheats(quiet = true)
            })
        }
        show(b)
    }

    private fun addCheat() {
        val ui = AppUi(this)
        val box = ui.column(20)
        val name = EditText(this).apply { hint = "Nombre (p. ej. Dinero infinito)"; setSingleLine() }
        val code = EditText(this).apply {
            hint = "Código: 02000000 00000063"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            minLines = 3; gravity = Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
        }
        box.addView(name); box.addView(code)
        show(dialog().setTitle("Nuevo truco").setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val text = code.text.toString().trim()
                if (!CheatStore.isValid(text)) { toast("El código debe tener pares de números hexadecimales de 8 cifras"); return@setPositiveButton }
                val list = CheatStore.load(this, romId)
                list += CheatStore.Cheat(name.text.toString().trim(), text, true)
                CheatStore.save(this, romId, list)
                applyCheats()
            })
    }

    // ---------------------------------------------------------------- micrófono real

    private fun toggleRealMic() {
        val on = !prefs.getBoolean("realmic", false)
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        prefs.edit().putBoolean("realmic", on).apply()
        if (on) startMic() else stopMic()
        toast(if (on) "Micrófono real activado" else "Micrófono real desactivado")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("realmic", true).apply()
            if (!booting) startMic()
            toast("Micrófono real activado")
        } else if (requestCode == REQ_MIC) toast("Sin permiso de micrófono: se usa el botón MIC para soplar")
    }

    /** Graba del micrófono del móvil (44,1 kHz mono) y se lo pasa a la consola. */
    @Suppress("MissingPermission")
    private fun startMic() {
        val e = emu ?: return
        if (micThread != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val nds = e.nds
        val t = Thread({
            val rate = 44100
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 2)
            } catch (_: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); micThread = null; return@Thread }
            nds.setMicLive(true)
            val buf = ShortArray(1024)
            try {
                rec.startRecording()
                while (micThread === Thread.currentThread()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) nds.pushMic(buf, n)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "KNDS-mic")
        micThread = t
        t.start()
    }

    private fun stopMic() {
        val t = micThread ?: return
        micThread = null
        t.join(500)
        emu?.nds?.setMicLive(false)
    }

    // ---------------------------------------------------------------- mandos físicos y teclado

    private fun remapMenu() {
        val labels = Keymap.BUTTONS.map { (mask, name) -> "$name  ·  ${Keymap.label(this, mask)}" } + listOf("Restablecer todo")
        show(dialog().setTitle("Reasignar mando").setItems(labels.toTypedArray()) { _, i ->
            if (i == Keymap.BUTTONS.size) { Keymap.reset(this); keymap = Keymap.custom(this); toast("Mando restablecido"); return@setItems }
            val (mask, name) = Keymap.BUTTONS[i]
            remapTarget = mask
            remapDialog = show(dialog().setTitle("Botón $name").setMessage("Pulsa ahora el botón del mando o la tecla que quieras usar.")
                .setNegativeButton("Cancelar") { _, _ -> remapTarget = null }
                .setOnKeyListener { dlg, code, ev ->
                    val target = remapTarget
                    if (target != null && ev.action == KeyEvent.ACTION_DOWN && code != KeyEvent.KEYCODE_BACK) {
                        Keymap.assign(this, code, target); keymap = Keymap.custom(this)
                        remapTarget = null; dlg.dismiss(); toast("$name asignado"); true
                    } else false
                })
        })
    }

    private fun updateHw() { emu?.hwKeys = buttonKeys or axisKeys }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) { showMenu(); return true }
        val m = Keymap.mask(this, keymap, keyCode)
        if (m == 0) return super.onKeyDown(keyCode, event)
        buttonKeys = buttonKeys or m; updateHw()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val m = Keymap.mask(this, keymap, keyCode)
        if (m == 0) return super.onKeyUp(keyCode, event)
        buttonKeys = buttonKeys and m.inv(); updateHw()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var m = 0
            if (x < -0.5f) m = m or Keys.LEFT
            if (x > 0.5f) m = m or Keys.RIGHT
            if (y < -0.5f) m = m or Keys.UP
            if (y > 0.5f) m = m or Keys.DOWN
            axisKeys = m; updateHw()
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}
