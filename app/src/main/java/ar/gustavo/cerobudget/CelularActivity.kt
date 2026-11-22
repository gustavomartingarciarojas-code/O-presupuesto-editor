package ar.gustavo.cerobudget

import android.app.Activity
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
 * Modo CELULAR: acá viven los videos crudos en 4K.
 * Arma los proxies, se los pasa a la tablet y, cuando la tablet manda la edición, arma el video final en 4K.
 */
class CelularActivity : Activity(), Servidor.Rutas {

    private val ui = Ui(this)
    private val manejador = Handler(Looper.getMainLooper())
    private val clips = mutableListOf<Clip>()
    private var servidor: Servidor? = null

    // Proxy en curso
    private var tProxy: Transformer? = null
    private var clipEnCurso: Clip? = null

    // Exportación en curso
    @Volatile private var tFinal: Transformer? = null
    private var uriFinal: Uri? = null
    private var inicioFinal = 0L
    private var archivoFinal: File? = null
    @Volatile private var estadoFinal: JSONObject = JSONObject().put("estado", "nada")

    private lateinit var lista: LinearLayout
    private lateinit var direccion: TextView
    private lateinit var aviso: TextView
    private lateinit var expTitulo: TextView
    private lateinit var expBarra: ProgressBar
    private lateinit var expEstado: TextView
    private lateinit var expVer: Button

    private fun dirProxies() = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "proxies").apply { mkdirs() }
    private fun archivoProxy(id: String) = File(dirProxies(), "$id.mp4")
    private fun archivoMusica() = File(filesDir, "musica-actual")
    private fun archivoClips() = File(filesDir, "clips.json")
    private fun dirImagenes() = File(filesDir, "imagenes").apply { mkdirs() }
    private fun archivoImagen(id: String): File? = id.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) }?.let { File(dirImagenes(), it) }

    // ================================================================== inicio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Ui.fondo)
        cargarClips()
        armar()
        iniciarServidor()
        siguienteProxy()
        manejador.post(reloj)
    }

    override fun onDestroy() {
        super.onDestroy()
        manejador.removeCallbacksAndMessages(null)
        try { tProxy?.cancel() } catch (_: Exception) { }
        try { tFinal?.cancel() } catch (_: Exception) { }
        servidor?.detener()
        // Si se cortó un proxy a mitad, queda pendiente para la próxima vez
        synchronized(clips) { clips.filter { it.estado == "armando" }.forEach { it.estado = "pendiente"; it.avance = 0 } }
        guardarClips()
    }

    private fun armar() {
        val (scroll, col) = ui.columnaConScroll()
        col.setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(40))
        col.addView(ui.texto("Celular", 28f, Ui.tinta, true))
        col.addView(ui.texto("Acá quedan los videos en 4K. La tablet edita con copias livianas y este equipo arma el video final.", 15f, Ui.gris).apply { setPadding(0, ui.dp(2), 0, ui.dp(12)) })

        val b0 = ui.bloque("Dirección para la tablet")
        direccion = ui.texto("—", 30f, Ui.amarillo, true).apply { setTextIsSelectable(true) }
        b0.addView(direccion, ui.lp())
        aviso = ui.texto("Los dos equipos en el mismo Wi-Fi. Dejá esta pantalla abierta mientras trabajás: queda prendida sola.", 14f, Ui.gris).apply { setPadding(0, ui.dp(4), 0, 0) }
        b0.addView(aviso, ui.lp())
        col.addView(b0, ui.lp())

        val b1 = ui.bloque("Videos")
        val agregar = ui.boton("Agregar videos", true)
        b1.addView(agregar, ui.lp())
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        b1.addView(lista, ui.lp(8))
        col.addView(b1, ui.lp(12))

        val b2 = ui.bloque("Exportación")
        expTitulo = ui.texto("Cuando toques Exportar en la tablet, el video final se arma acá.", 14f, Ui.gris)
        b2.addView(expTitulo, ui.lp())
        expBarra = ui.barra()
        b2.addView(expBarra, ui.lp(10))
        expEstado = ui.texto("", 15f, Ui.gris).apply { setPadding(0, ui.dp(6), 0, 0) }
        b2.addView(expEstado, ui.lp())
        expVer = ui.boton("Ver el video", false).apply { visibility = View.GONE }
        b2.addView(expVer, ui.lp(10))
        col.addView(b2, ui.lp(12))
        Actualizar.agregar(this, ui, col)

        setContentView(scroll)

        agregar.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(i, 21)
        }
        expVer.setOnClickListener { verFinal() }
        refrescarLista()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        val cd = data.clipData
        if (cd != null) for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri
        else data.data?.let { uris += it }
        var errores = 0
        for (u in uris) {
            try {
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                val v = Datos.leerVideo(this, u)
                val id = "c" + System.currentTimeMillis().toString(36) + (0..999).random()
                synchronized(clips) {
                    if (clips.none { it.uri == u.toString() }) {
                        clips += Clip(id, u.toString(), v.nombre, v.duracionMs, v.ancho, v.alto, v.fps, v.bytes)
                    }
                }
            } catch (_: Exception) { errores++ }
        }
        guardarClips()
        refrescarLista()
        if (errores > 0) { aviso.setTextColor(Ui.rojo); aviso.text = "No pude leer $errores de los videos elegidos." }
        siguienteProxy()
    }

    private fun quitar(c: Clip) {
        if (clipEnCurso?.id == c.id) { try { tProxy?.cancel() } catch (_: Exception) { }; tProxy = null; clipEnCurso = null }
        archivoProxy(c.id).delete()
        synchronized(clips) { clips.removeAll { it.id == c.id } }
        try { contentResolver.releasePersistableUriPermission(Uri.parse(c.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        guardarClips()
        refrescarLista()
        siguienteProxy()
    }

    private fun refrescarLista() {
        lista.removeAllViews()
        val copia = synchronized(clips) { clips.toList() }
        if (copia.isEmpty()) {
            lista.addView(ui.texto("Todavía no agregaste videos.", 15f, Ui.gris), ui.lp())
            return
        }
        for (c in copia) {
            val fila = ui.fila().apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(ui.texto(c.nombre, 16f, Ui.tinta, true))
            val estado = when (c.estado) {
                "listo" -> "Proxy listo (${Datos.mb(c.proxyBytes)})"
                "armando" -> "Armando proxy… ${c.avance} %"
                "error" -> "Error: ${c.error}"
                else -> "En espera"
            }
            col.addView(ui.texto("${c.ancho}×${c.alto} · ${Datos.duracion(c.duracionMs)} · ${Datos.mb(c.bytes)}\n$estado", 14f,
                when (c.estado) { "listo" -> Ui.verde; "error" -> Ui.rojo; else -> Ui.gris }))
            fila.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val q = ui.boton("Quitar", false, 14f)
            q.setOnClickListener { quitar(c) }
            fila.addView(q)
            lista.addView(fila, ui.lp())
        }
    }

    // ================================================================== proxies

    private fun siguienteProxy() {
        if (tProxy != null || tFinal != null) return
        val c = synchronized(clips) { clips.firstOrNull { it.estado == "pendiente" } } ?: run { pantallaPrendida(); return }
        clipEnCurso = c
        c.estado = "armando"; c.avance = 0
        val salida = archivoProxy(c.id)
        if (salida.exists()) salida.delete()
        val (t, comp) = Render.proxy(this, c, object : Transformer.Listener {
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
        pantallaPrendida()
        try { t.start(comp, salida.absolutePath) } catch (e: Exception) {
            c.estado = "error"; c.error = e.message ?: e.javaClass.simpleName
            terminoProxy()
        }
        refrescarLista()
    }

    private fun terminoProxy() {
        tProxy = null
        clipEnCurso = null
        guardarClips()
        refrescarLista()
        siguienteProxy()
    }

    /** La pantalla queda prendida mientras esté abierto el modo Celular: así no se corta el Wi-Fi con la tablet. */
    private fun pantallaPrendida() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val holder = ProgressHolder()
    private var ultimoRefresco = 0L

    private val reloj = object : Runnable {
        override fun run() {
            val c = clipEnCurso
            val t = tProxy
            if (c != null && t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                if (holder.progress != c.avance) {
                    c.avance = holder.progress
                    val ahora = SystemClock.elapsedRealtime()
                    if (ahora - ultimoRefresco > 1500) { ultimoRefresco = ahora; refrescarLista() }
                }
            }
            val tf = tFinal
            if (tf != null && tf.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val p = holder.progress
                val seg = SystemClock.elapsedRealtime() - inicioFinal
                val resta = if (p in 3..99) " · faltan unos " + Datos.duracion(seg * (100 - p) / p) else ""
                expBarra.progress = p * 10
                expEstado.text = "Armando el video final en 4K… $p % · van ${Datos.duracion(seg)}$resta"
                estadoFinal = JSONObject(estadoFinal.toString()).put("progreso", p).put("mensaje", expEstado.text.toString())
            }
            val ips = Datos.ips()
            direccion.text = if (ips.isEmpty()) "Sin Wi-Fi" else ips.first()
            manejador.postDelayed(this, 700)
        }
    }

    // ================================================================== servidor

    private fun iniciarServidor() {
        if (servidor == null) servidor = Servidor(this)
        try {
            servidor?.iniciar()
        } catch (e: Exception) {
            aviso.setTextColor(Ui.rojo)
            aviso.text = "No se pudo abrir el puerto ${Servidor.PUERTO}: ${e.message}. Cerrá la app y volvé a abrirla."
        }
    }

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
                val tmp = File(filesDir, "musica-subiendo")
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
                val falta = ed.segmentos.firstOrNull { mapa[it.clip] == null }
                if (falta != null) return Servidor.Respuesta.texto("Hay un video que ya no está en el celular.", 409)
                estadoFinal = JSONObject().put("estado", "renderizando").put("progreso", 0).put("mensaje", "Preparando…")
                manejador.post { exportar(ed, mapa, params["musica"] == "1") }
                Servidor.Respuesta.json("{\"ok\":true}", 202)
            }
            else -> Servidor.Respuesta.texto("No existe.", 404)
        }
    }

    override fun enviando(archivo: File, enviados: Long, total: Long) {
        // Aviso liviano en pantalla del envío de proxies
        if (enviados == total) runOnUiThread { aviso.setTextColor(Ui.tinta); aviso.text = "Proxy enviado a la tablet (${Datos.mb(total)})." }
    }

    // ================================================================== exportar

    private fun destinoFinal(): File {
        val nombre = "Edicion-${Datos.fecha()}-${Datos.hora()}.mp4"
        // Primero, directo en Movies/0-Presupuesto-Editor (Android 11 en adelante lo permite)
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "0-Presupuesto-Editor")
            dir.mkdirs()
            val f = File(dir, nombre)
            f.outputStream().use { }
            f.delete()
            return f
        } catch (_: Exception) { }
        return File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), nombre)
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
        expVer.visibility = View.GONE
        expTitulo.setTextColor(Ui.tinta)
        expTitulo.text = "Exportando ${ed.segmentos.size} tramos · formato ${if (ed.formato == "original") "original" else ed.formato}" +
            (if (conMusica) " · con música" else "") + "\nNo cierres la app ni apagues la pantalla."
        expBarra.visibility = View.VISIBLE; expBarra.progress = 0
        expEstado.setTextColor(Ui.gris)
        expEstado.text = "Preparando…"
        try {
            val (t, comp) = Render.final(this, mapa, ed, if (conMusica) archivoMusica() else null, { archivoImagen(it) }, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finalListo(salida)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finalFallo("${exportException.errorCodeName}: ${exportException.message}")
                }
            })
            tFinal = t
            inicioFinal = SystemClock.elapsedRealtime()
            pantallaPrendida()
            t.start(comp, salida.absolutePath)
        } catch (e: Exception) {
            finalFallo(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun finalListo(f: File) {
        tFinal = null
        val ms = SystemClock.elapsedRealtime() - inicioFinal
        uriFinal = null
        try {
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4")) { _, u -> runOnUiThread { uriFinal = u } }
        } catch (_: Exception) { }
        val donde = if (f.absolutePath.contains("/Android/data/")) "en la carpeta de la app" else "en Movies/0-Presupuesto-Editor"
        val msg = "Listo: ${f.name} (${Datos.mb(f.length())}), guardado $donde del celular. Tardó ${Datos.duracion(ms)}."
        estadoFinal = JSONObject().put("estado", "listo").put("progreso", 100).put("mensaje", msg)
            .put("archivo", f.name).put("bytes", f.length()).put("ms", ms)
        expBarra.progress = 1000
        expEstado.setTextColor(Ui.verde)
        expEstado.text = msg
        expVer.visibility = View.VISIBLE
        pantallaPrendida()
        siguienteProxy()
    }

    private fun finalFallo(error: String) {
        tFinal = null
        archivoFinal?.let { if (it.exists() && it.length() == 0L) it.delete() }
        estadoFinal = JSONObject().put("estado", "error").put("mensaje", "No se pudo exportar: $error")
        expBarra.visibility = View.GONE
        expEstado.setTextColor(Ui.rojo)
        expEstado.text = "No se pudo exportar: $error"
        pantallaPrendida()
        siguienteProxy()
    }

    private fun verFinal() {
        val u = uriFinal
        if (u == null) { expEstado.text = expEstado.text.toString() + "\nBuscalo en la galería, en la carpeta 0-Presupuesto-Editor."; return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            expEstado.text = expEstado.text.toString() + "\nNo pude abrirlo desde acá: buscalo en la galería."
        }
    }
}
