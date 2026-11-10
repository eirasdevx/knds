package com.knds.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

/** ROMs (también dentro de .zip), archivos de sistema, partidas y estados guardados. */
object RomStore {
    class Rom(val file: File, val id: String, val title: String)

    /**
     * Copia la ROM a la caché de la app (el núcleo la lee de ahí) y calcula su identificador.
     * Se hace por streaming porque una ROM de DS puede ocupar cientos de MB.
     */
    fun prepare(context: Context, uri: Uri): Rom {
        val dir = File(context.cacheDir, "rom").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // solo guardamos la ROM en uso
        val tmp = File(dir, "incoming.nds")
        val crc = CRC32()
        val header = ByteArray(0x200)
        var headerLen = 0
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val buffered = raw.buffered(1 shl 16)
            buffered.mark(4)
            val magic = ByteArray(2)
            val n = buffered.read(magic)
            buffered.reset()
            val src: InputStream = if (n == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                openZipEntry(buffered)
            } else buffered
            tmp.outputStream().buffered(1 shl 16).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val r = src.read(buf)
                    if (r < 0) break
                    if (headerLen < header.size) {
                        val k = minOf(r, header.size - headerLen)
                        System.arraycopy(buf, 0, header, headerLen, k)
                        headerLen += k
                    }
                    crc.update(buf, 0, r)
                    out.write(buf, 0, r)
                }
            }
        } ?: error("no se puede leer el archivo")
        require(headerLen >= 0x200) { "el archivo es demasiado pequeño para ser una ROM de DS" }
        val code = String(header, 0x0C, 4, Charsets.US_ASCII).filter { it.isLetterOrDigit() }.ifEmpty { "NDS" }
        val title = String(header, 0, 12, Charsets.US_ASCII).trim { it <= ' ' }
        val id = "%s_%08X".format(code, crc.value)
        val file = File(dir, "$id.nds")
        tmp.renameTo(file)
        return Rom(file, id, title)
    }

    private fun openZipEntry(input: InputStream): InputStream {
        val zis = ZipInputStream(input)
        var first = true
        while (true) {
            val e = zis.nextEntry ?: break
            if (e.isDirectory) continue
            val name = e.name.lowercase()
            if (name.endsWith(".nds") || name.endsWith(".dsi") || name.endsWith(".srl")) return zis
            first = false
        }
        error(if (first) "el .zip está vacío" else "el .zip no contiene ningún archivo .nds")
    }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name.substringBeforeLast('.')
            }
        }
        return uri.lastPathSegment ?: "ROM"
    }

    fun saveFile(context: Context, id: String) = File(context.filesDir, "saves").apply { mkdirs() }.resolve("$id.sav")

    const val SLOTS = 10

    /** Estado de la ranura [slot] (1..[SLOTS]); la 1 es el archivo de versiones anteriores. */
    fun stateFile(context: Context, id: String, slot: Int = 1): File {
        val dir = File(context.filesDir, "states").apply { mkdirs() }
        return dir.resolve(if (slot == 1) "$id.state" else "$id.$slot.state")
    }

    /** Miniatura PNG (las dos pantallas) del estado de la ranura [slot]. */
    fun thumbFile(context: Context, id: String, slot: Int): File =
        File(context.filesDir, "states").apply { mkdirs() }.resolve("$id.$slot.png")

    fun hasState(context: Context, id: String, slot: Int = 1) = stateFile(context, id, slot).isFile

    /** Los estados ocupan ~19 MB sin comprimir; comprimidos, bastante menos. */
    fun writeState(context: Context, id: String, data: ByteArray, slot: Int = 1) {
        val f = stateFile(context, id, slot)
        val tmp = File(f.path + ".tmp")
        GZIPOutputStream(tmp.outputStream().buffered(1 shl 16)).use { it.write(data) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun readState(context: Context, id: String, slot: Int = 1): ByteArray =
        GZIPInputStream(stateFile(context, id, slot).inputStream().buffered(1 shl 16)).use { it.readBytes() }

    /** Un estado guardado en disco. */
    class SavedState(val id: String, val slot: Int, val file: File, val thumb: File)

    /** Todos los estados guardados, del más reciente al más antiguo. */
    fun allStates(context: Context): List<SavedState> {
        val dir = File(context.filesDir, "states")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".state") } ?: return emptyList()
        return files.mapNotNull { f ->
            val base = f.name.removeSuffix(".state")
            val dot = base.lastIndexOf('.')
            val slot = if (dot > 0) base.substring(dot + 1).toIntOrNull() else 1
            if (slot == null || slot !in 1..SLOTS) return@mapNotNull null
            val id = if (dot > 0) base.substring(0, dot) else base
            SavedState(id, slot, f, thumbFile(context, id, slot))
        }.sortedByDescending { it.file.lastModified() }
    }

    fun stateCount(context: Context, id: String?): Int =
        if (id == null) 0 else (1..SLOTS).count { stateFile(context, id, it).isFile }

    // ---------------------------------------------------------------- biblioteca

    private fun prefs(context: Context) = context.getSharedPreferences("knds", Context.MODE_PRIVATE)

    /** Recuerda la relación ROM ↔ identificador para la biblioteca y el gestor de guardados. */
    fun remember(context: Context, uri: String, id: String, name: String) {
        prefs(context).edit().putString("romid_$uri", id).putString("rom_$id", "$uri\t$name").apply()
    }

    fun idFor(context: Context, uri: String): String? = prefs(context).getString("romid_$uri", null)

    fun romFor(context: Context, id: String): Pair<String, String>? =
        prefs(context).getString("rom_$id", null)?.takeIf { '\t' in it }
            ?.let { it.substringBefore('\t') to it.substringAfter('\t') }

    /** Código de cartucho legible, p. ej. «NTR-AMCE», a partir del identificador. */
    fun cartCode(id: String?): String = id?.substringBefore('_')?.takeIf { it.length == 4 && it != "NDS" }
        ?.let { "NTR-$it" } ?: "NTR-????"

    /**
     * (CRC32, código de cartucho) de una ROM sin copiarla: se recorre por streaming porque
     * puede ocupar cientos de MB. También dentro de .zip.
     */
    fun identify(context: Context, uri: Uri): Pair<String, String?> {
        val crc = CRC32()
        val header = ByteArray(0x10)
        var headerLen = 0
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val buffered = raw.buffered(1 shl 16)
            buffered.mark(4)
            val magic = ByteArray(2)
            val n = buffered.read(magic)
            buffered.reset()
            val src: InputStream = if (n == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                openZipEntry(buffered)
            } else buffered
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = src.read(buf)
                if (r < 0) break
                if (headerLen < header.size) {
                    val k = minOf(r, header.size - headerLen)
                    System.arraycopy(buf, 0, header, headerLen, k)
                    headerLen += k
                }
                crc.update(buf, 0, r)
            }
        } ?: error("no se puede leer el archivo")
        val serial = if (headerLen >= 0x10) String(header, 0x0C, 4, Charsets.US_ASCII).takeIf { s -> s.all { it.isLetterOrDigit() } } else null
        return "%08X".format(crc.value) to serial
    }

    /**
     * Solo el código de cartucho (p. ej. «AMCE»), leyendo la cabecera y no la ROM entera.
     * Basta para reconocer casi todos los juegos al momento.
     */
    fun serialOf(context: Context, uri: Uri): String? {
        val header = ByteArray(0x10)
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val buffered = raw.buffered(1 shl 12)
            buffered.mark(4)
            val magic = ByteArray(2)
            val n = buffered.read(magic)
            buffered.reset()
            val src: InputStream = if (n == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                openZipEntry(buffered)
            } else buffered
            var got = 0
            while (got < header.size) {
                val r = src.read(header, got, header.size - got)
                if (r < 0) return null
                got += r
            }
        } ?: return null
        return String(header, 0x0C, 4, Charsets.US_ASCII).takeIf { s -> s.all { it.isLetterOrDigit() } }
    }

    // ---------------------------------------------------------------- archivos de sistema

    enum class SystemFile(val fileName: String, val sizes: IntRange, val label: String) {
        BIOS9("bios9.bin", 0x1000..0x1000, "4 KB"),
        BIOS7("bios7.bin", 0x4000..0x4000, "16 KB"),
        FIRMWARE("firmware.bin", 0x20000..0x80000, "128–512 KB"),
    }

    fun systemFile(context: Context, f: SystemFile) = File(context.filesDir, f.fileName)

    fun readSystem(context: Context, f: SystemFile): ByteArray? {
        val file = systemFile(context, f)
        return if (file.isFile && file.length().toInt() in f.sizes) file.readBytes() else null
    }
}
