package com.knds.app

import android.content.Context
import android.view.KeyEvent

/**
 * Botones del mando físico o teclado → botones de la DS. Por defecto, por posición
 * (el de abajo es la B, el de la derecha la A, el de arriba la X y el de la izquierda la Y);
 * se puede reasignar cada botón desde el menú de pausa.
 */
object Keymap {
    val BUTTONS = listOf(
        Keys.A to "A", Keys.B to "B", Keys.X to "X", Keys.Y to "Y", Keys.L to "L", Keys.R to "R",
        Keys.START to "START", Keys.SELECT to "SELECT",
        Keys.UP to "Arriba", Keys.DOWN to "Abajo", Keys.LEFT to "Izquierda", Keys.RIGHT to "Derecha",
    )

    private fun prefs(context: Context) = context.getSharedPreferences("knds", Context.MODE_PRIVATE)

    fun defaultMask(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Keys.UP
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Keys.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Keys.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Keys.RIGHT
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_L -> Keys.A
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_K -> Keys.B
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_I -> Keys.X
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_J -> Keys.Y
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_Q -> Keys.L
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_E -> Keys.R
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> Keys.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE -> Keys.SELECT
        else -> 0
    }

    /** Asignaciones propias: tecla → botón de la DS. */
    fun custom(context: Context): Map<Int, Int> =
        (prefs(context).getString("keymap", "") ?: "").split(',').mapNotNull { e ->
            val p = e.split(':'); if (p.size == 2) p[0].toIntOrNull()?.let { k -> p[1].toIntOrNull()?.let { k to it } } else null
        }.toMap()

    fun mask(context: Context, custom: Map<Int, Int>, code: Int): Int = custom[code] ?: defaultMask(code).let { d ->
        // Si el botón por defecto se ha reasignado a otra tecla, esta deja de pulsarlo.
        if (d != 0 && custom.containsValue(d)) 0 else d
    }

    fun assign(context: Context, keyCode: Int, dsMask: Int) {
        val map = custom(context).filterValues { it != dsMask }.toMutableMap()
        map[keyCode] = dsMask
        prefs(context).edit().putString("keymap", map.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    fun reset(context: Context) = prefs(context).edit().remove("keymap").apply()

    fun label(context: Context, dsMask: Int): String {
        val code = custom(context).entries.firstOrNull { it.value == dsMask }?.key
        return code?.let { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_").replace('_', ' ') } ?: "por defecto"
    }
}
