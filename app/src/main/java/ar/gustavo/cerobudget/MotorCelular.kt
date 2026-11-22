package ar.gustavo.cerobudget

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * El trabajo del CELULAR, independiente de la pantalla: lista de videos, proxies, servidor para la tablet
 * y exportación en 4K. Lo mantiene vivo ServicioCelular (servicio en primer plano con notificación),
 * así sigue trabajando aunque se use otra app o se apague la pantalla.
 */
object MotorCelular : Servidor.Rutas {

    private lateinit var ctx: Context
    private val manejador = Handler(Looper.getMainLooper())
    private val clips = mutableListOf<Clip>()
    private var servidor: Servidor? = null
    @Volatile var iniciado = false
        private set

    // Proxy en curso
    private var tProxy: Transformer? = null
    private var clipEnCurso: Clip? = null

    // Exportación en curso
    @Volatile private var tFinal: Transformer? = null
    private var inicioFinal = 0L
    var archivoFinal: File? = null
        private set
    var uriFinal: Uri? = null
        private set
    @Volatile var estadoFinal: JSONObject = JSONObject().put("estado", "nada")
        private set
    @Volatile var errorServidor: String = ""
        private set
    @Volatile var avisoEnvio: String = ""
        private set

    /** La pantalla y la notificación se enteran de cada cambio por acá. */
    private val oyentes = mutableListOf<() -> Unit>()
    fun escuchar(f: () -> Unit) { synchronized(oyentes) { oyentes += f } }
    fun dejarDeEscuchar(f: () -> Unit) { synchronized(oyentes) { oyentes -= f } }
    private fun avisar() { val l = synchronized(oyentes) { oyentes.toList() }; manejador.post { l.forEach { it() } } }

    private fun dirProxies() = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "proxies").apply { mkdirs() }
    private fun archivoProxy(id: String) = File(dirProxies(), "$id.mp4")
    private fun archivoMusica() = File(ctx.filesDir, "musica-actual")
    private fun archivoClips() = File(ctx.filesDir, "clips.json")
    private fun dirImagenes() = File(ctx.filesDir, "imagenes").apply { mkdirs() }
    private fun archivoImagen(id: String): File? = id.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) }?.let { File(dirImagenes(), it) }

    // ================================================================== ciclo

    /** Lo llama el servicio al arrancar (en el hilo principal). */
    fun iniciar(c: Context) {
        if (iniciado) return
        ctx = c.applicationContext
        cargarClips()
        if (servidor == null) servidor = Servidor(this)
        try {
            servidor?.iniciar()
            errorServidor = ""
        } catch (e: Exception) {
            errorServidor = "No se pudo abrir el puerto ${Servidor.PUERTO}: ${e.message}"
        }
        iniciado = true
        manejador.post(reloj)
        siguienteProxy()
        avisar()
    }

    /** Lo llama el servicio al apagarse. */
    fun detener() {
        if (!iniciado) return
        iniciado = false
        manejador.removeCallbacks(reloj)
        try { tProxy?.cancel() } catch (_: Exception) { }
        try { tFinal?.cancel() } catch (_: Exception) { }
        tProxy = null; tFinal = null; clipEnCurso = null
        servidor?.detener()
        synchronized(clips) { clips.filter { it.estado == "armando" }.forEach { it.estado = "pendiente"; it.avance = 0 } }
        guardarClips()
        avisar()
    }

    val trabajando: Boolean get() = tProxy != null || tFinal != null

    fun copiaClips(): List<Clip> = synchronized(clips) { clips.map { Clip.de(it.json(true)) } }

    /** Texto corto para la notificación. */
    fun resumen(): String {
        val tf = tFinal
        if (tf != null) return estadoFinal.optString("mensaje", "Exportando…")
        val c = clipEnCurso
        if (c != null) return "Armando proxy de ${c.nombre}… ${c.avance} %"
        val ips = Datos.ips()
        val n = synchronized(clips) { clips.count { it.estado == "listo" } }
        return "Listo para la tablet · ${if (ips.isEmpty()) "sin Wi-Fi" else ips.first()} · $n videos"
    }

    // ================================================================== lista de clips

    private fun cargarClips() {
        try {
            val f = archivoClips()
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            synchronized(clips) {
                clips.clear()
                for (i in 0 until a.length()) {
                    val c = Clip.de(a.getJSONObject(i))
                    if (c.estado == "armando") { c.estado = "pendiente"; c.avance = 0 }
                    if (c.estado == "listo" && !archivoProxy(c.id).exists()) { c.estado = "pendiente"; c.proxyBytes = 0 }
                    clips += c
                }
            }
        } catch (_: Exception) { }
    }

    private fun guardarClips() {
        val a = JSONArray()
        synchronized(clips) { clips.forEach { a.put(it.json(true)) } }
        try { archivoClips().writeText(a.toString()) } catch (_: Exception) { }
    }

    /** Agrega videos elegidos en la pantalla. Devuelve cuántos no se pudieron leer. */
    fun agregar(uris: List<Uri>): Int {
        var errores = 0
        for (u in uris) {
            try {
                try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                val v = Datos.leerVideo(ctx, u)
                val id = "c" + System.currentTimeMillis().toString(36) + (0..999).random()
                synchronized(clips) {
                    if (clips.none { it.uri == u.toString() }) clips += Clip(id, u.toString(), v.nombre, v.duracionMs, v.ancho, v.alto, v.fps, v.bytes)
                }
            } catch (_: Exception) { errores++ }
        }
        guardarClips()
        avisar()
        siguienteProxy()
        return errores
    }

    fun quitar(id: String) {
        val c = synchronized(clips) { clips.firstOrNull { it.id == id } } ?: return
        if (clipEnCurso?.id == id) { try { tProxy?.cancel() } catch (_: Exception) { }; tProxy = null; clipEnCurso = null }
        archivoProxy(id).delete()
        synchronized(clips) { clips.removeAll { it.id == id } }
        try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(c.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        guardarClips()
        avisar()
        siguienteProxy()
    }

    // ================================================================== proxies

    private fun siguienteProxy() {
        if (!iniciado || tProxy != null || tFinal != null) return
        val c = synchronized(clips) { clips.firstOrNull { it.estado == "pendiente" } } ?: return
        clipEnCurso = c
        c.estado = "armando"; c.avance = 0
        val salida = archivoProxy(c.id)
        if (salida.exists()) salida.delete()
        try {
            val (t, comp) = Render.proxy(ctx, c, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    c.estado = "listo"; c.avance = 100; c.proxyBytes = salida.length()
                    terminoProxy()
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    c.estado = "error"; c.error = "${exportException.errorCodeName}: ${exportException.message}"
                    terminoProxy()
                }
            })
            tProxy = t
            t.start(comp, salida.absolutePath)
        } catch (e: Exception) {
            c.estado = "error"; c.error = e.message ?: e.javaClass.simpleName
            terminoProxy()
        }
        avisar()
    }

    private fun terminoProxy() {
        tProxy = null
        clipEnCurso = null
        guardarClips()
        avisar()
        siguienteProxy()
    }

    private val holder = ProgressHolder()

    private val reloj = object : Runnable {
        override fun run() {
            var cambio = false
            val c = clipEnCurso
            val t = tProxy
            if (c != null && t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE && holder.progress != c.avance) {
                c.avance = holder.progress; cambio = true
            }
            val tf = tFinal
            if (tf != null && tf.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val p = holder.progress
                val ms = SystemClock.elapsedRealtime() - inicioFinal
                val resta = if (p in 3..99) " · faltan unos " + Datos.duracion(ms * (100 - p) / p) else ""
                estadoFinal = JSONObject().put("estado", "renderizando").put("progreso", p)
                    .put("mensaje", "Exportando en 4K… $p % · van ${Datos.duracion(ms)}$resta")
                cambio = true
            }
            if (cambio) avisar()
            if (iniciado) manejador.postDelayed(this, 1000)
        }
    }

    // ================================================================== servidor

    override fun get(ruta: String, params: Map<String, String>): Servidor.Respuesta {
        return when (ruta) {
            "/info" -> {
                val a = JSONArray()
                synchronized(clips) { clips.forEach { a.put(it.json(false)) } }
                Servidor.Respuesta.json(JSONObject().put("app", "0-presupuesto-editor").put("clips", a).toString())
            }
            "/proxy" -> {
                val id = params["id"] ?: ""
                val c = synchronized(clips) { clips.firstOrNull { it.id == id } }
                val f = archivoProxy(id)
                if (c == null || c.estado != "listo" || !f.exists()) Servidor.Respuesta.texto("Ese proxy no está listo.", 404)
                else Servidor.Respuesta(200, "video/mp4", archivo = f)
            }
            "/estado" -> Servidor.Respuesta.json(estadoFinal.toString())
            else -> Servidor.Respuesta.texto("0 Presupuesto Editor", 200)
        }
    }

    override fun post(ruta: String, params: Map<String, String>, cuerpo: InputStream, largo: Long): Servidor.Respuesta {
        return when (ruta) {
            "/musica" -> {
                val tmp = File(ctx.filesDir, "musica-subiendo")
                tmp.outputStream().use { cuerpo.copyTo(it, 256 * 1024) }
                if (largo > 0 && tmp.length() != largo) { tmp.delete(); throw IllegalStateException("La música llegó incompleta.") }
                archivoMusica().delete()
                tmp.renameTo(archivoMusica())
                Servidor.Respuesta.json("{\"ok\":true}")
            }
            "/imagen" -> {
                val f = archivoImagen(params["id"] ?: "") ?: return Servidor.Respuesta.texto("Nombre de imagen inválido.", 409)
                val tmp = File(f.path + ".subiendo")
                tmp.outputStream().use { cuerpo.copyTo(it, 256 * 1024) }
                if (largo > 0 && tmp.length() != largo) { tmp.delete(); throw IllegalStateException("La imagen llegó incompleta.") }
                f.delete(); tmp.renameTo(f)
                Servidor.Respuesta.json("{\"ok\":true}")
            }
            "/exportar" -> {
                val txt = cuerpo.bufferedReader().use { it.readText() }
                val ed = Edicion.de(JSONObject(txt))
                if (ed.segmentos.isEmpty()) return Servidor.Respuesta.texto("La línea de tiempo está vacía.", 409)
                if (tFinal != null) return Servidor.Respuesta.texto("El celular ya está exportando otro video.", 409)
                val mapa = synchronized(clips) { clips.associateBy { it.id } }
                if (ed.segmentos.any { mapa[it.clip] == null }) return Servidor.Respuesta.texto("Hay un video que ya no está en el celular.", 409)
                estadoFinal = JSONObject().put("estado", "renderizando").put("progreso", 0).put("mensaje", "Preparando…")
                manejador.post { exportar(ed, mapa, params["musica"] == "1") }
                Servidor.Respuesta.json("{\"ok\":true}", 202)
            }
            else -> Servidor.Respuesta.texto("No existe.", 404)
        }
    }

    override fun enviando(archivo: File, enviados: Long, total: Long) {
        if (enviados == total) { avisoEnvio = "Proxy enviado a la tablet (${Datos.mb(total)})."; avisar() }
    }

    // ================================================================== exportar

    private fun destinoFinal(): File {
        val nombre = "Edicion-${Datos.fecha()}-${Datos.hora()}.mp4"
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "0-Presupuesto-Editor")
            dir.mkdirs()
            val f = File(dir, nombre)
            f.outputStream().use { }
            f.delete()
            return f
        } catch (_: Exception) { }
        return File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), nombre)
    }

    private fun exportar(ed: Edicion, mapa: Map<String, Clip>, conMusica: Boolean) {
        // Si se está armando un proxy, se pausa y se retoma después
        if (tProxy != null) {
            try { tProxy?.cancel() } catch (_: Exception) { }
            clipEnCurso?.let { it.estado = "pendiente"; it.avance = 0 }
            tProxy = null; clipEnCurso = null
        }
        val salida = destinoFinal()
        archivoFinal = salida
        uriFinal = null
        try {
            val (t, comp) = Render.final(ctx, mapa, ed, if (conMusica) archivoMusica() else null, { archivoImagen(it) }, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { finalListo(salida) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finalFallo("${exportException.errorCodeName}: ${exportException.message}")
                }
            })
            tFinal = t
            inicioFinal = SystemClock.elapsedRealtime()
            t.start(comp, salida.absolutePath)
        } catch (e: Exception) {
            finalFallo(e.message ?: e.javaClass.simpleName)
        }
        avisar()
    }

    private fun finalListo(f: File) {
        tFinal = null
        val ms = SystemClock.elapsedRealtime() - inicioFinal
        try {
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("video/mp4")) { _, u -> manejador.post { uriFinal = u; avisar() } }
        } catch (_: Exception) { }
        val donde = if (f.absolutePath.contains("/Android/data/")) "en la carpeta de la app" else "en Movies/0-Presupuesto-Editor"
        val msg = "Listo: ${f.name} (${Datos.mb(f.length())}), guardado $donde del celular. Tardó ${Datos.duracion(ms)}."
        estadoFinal = JSONObject().put("estado", "listo").put("progreso", 100).put("mensaje", msg)
            .put("archivo", f.name).put("bytes", f.length()).put("ms", ms)
        avisar()
        siguienteProxy()
    }

    private fun finalFallo(error: String) {
        tFinal = null
        archivoFinal?.let { if (it.exists() && it.length() == 0L) it.delete() }
        estadoFinal = JSONObject().put("estado", "error").put("mensaje", "No se pudo exportar: $error")
        avisar()
        siguienteProxy()
    }
}
