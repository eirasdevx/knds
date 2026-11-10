package com.knds.app

import android.content.Context

/** Colores de la carcasa dibujada (cuerpo y botones), guardados en las preferencias. */
object ConsoleStyle {
    /** Colores predefinidos: nombre, cuerpo, botones. */
    val PRESETS = listOf(
        Triple("Negro mate", 0xFF1F2023.toInt(), 0xFF26272B.toInt()),
        Triple("Azul cobalto", 0xFF2378D9.toInt(), 0xFFDADBD8.toInt()),
        Triple("Índigo", 0xFF5A55B8.toInt(), 0xFFDCDCE4.toInt()),
        Triple("Blanco polar", 0xFFE9EBEF.toInt(), 0xFF8C9099.toInt()),
        Triple("Negro ónice", 0xFF26282E.toInt(), 0xFF4A4D55.toInt()),
        Triple("Rojo carmesí", 0xFFC8303F.toInt(), 0xFF2C2D33.toInt()),
        Triple("Rosa coral", 0xFFF08CA8.toInt(), 0xFFF6F2F4.toInt()),
        Triple("Verde menta", 0xFF4FB59A.toInt(), 0xFFE6EEEA.toInt()),
        Triple("Plata metálico", 0xFFA9AFB8.toInt(), 0xFF3A3D44.toInt()),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("knds", Context.MODE_PRIVATE)
    fun shell(context: Context) = prefs(context).getInt("shell_color", PRESETS[0].second)
    fun buttons(context: Context) = prefs(context).getInt("buttons_color", PRESETS[0].third)
    fun presetName(context: Context): String =
        PRESETS.firstOrNull { it.second == shell(context) && it.third == buttons(context) }?.first ?: "Personalizado"

    fun set(context: Context, shell: Int, buttons: Int) {
        prefs(context).edit().putInt("shell_color", shell).putInt("buttons_color", buttons).apply()
    }
}
