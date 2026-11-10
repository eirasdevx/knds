package com.knds.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Trucos Action Replay de cada juego, guardados en filesDir/cheats/<id>.json.
 * Un código son pares de palabras hexadecimales de 32 bits («02000000 00000063»).
 */
object CheatStore {
    class Cheat(var name: String, var code: String, var enabled: Boolean)

    private fun file(context: Context, id: String) = File(context.filesDir, "cheats").apply { mkdirs() }.resolve("$id.json")

    fun load(context: Context, id: String): MutableList<Cheat> = runCatching {
        val arr = JSONArray(file(context, id).readText())
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Cheat(o.optString("name"), o.optString("code"), o.optBoolean("on"))
        }
    }.getOrDefault(mutableListOf())

    fun save(context: Context, id: String, cheats: List<Cheat>) {
        val arr = JSONArray()
        cheats.forEach { arr.put(JSONObject().put("name", it.name).put("code", it.code).put("on", it.enabled)) }
        file(context, id).writeText(arr.toString())
    }

    /** ¿Tiene forma de código Action Replay? (pares de palabras hex de 8 cifras) */
    fun isValid(code: String): Boolean {
        val words = code.split(Regex("[^0-9A-Fa-f]+")).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.size % 2 == 0 && words.all { it.length == 8 }
    }

    /** Códigos activos, listos para el núcleo. */
    fun active(context: Context, id: String) = load(context, id).filter { it.enabled && isValid(it.code) }.map { it.code }
}
