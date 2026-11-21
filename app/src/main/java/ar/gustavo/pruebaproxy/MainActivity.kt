package ar.gustavo.pruebaproxy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // ------------------------------------------------------------------ colores (modo oscuro)
    private val fondo = Color.parseColor("#1A1A19")
    private val panel = Color.parseColor("#232322")
    private val linea = Color.parseColor("#3A3A38")
    private val tinta = Color.parseColor("#ECECE8")
    private val gris = Color.parseColor("#A3A39E")
    private val amarillo = Color.parseColor("#FFE600")
    private val rojo = Color.parseColor("#FF7A6B")
    private val verde = Color.parseColor("#5FD38A")

    private val manejador = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("prueba", MODE_PRIVATE) }
    private val AR = Datos.AR

    private var modo = "tablet"
    private var ocupado = false

    // Raíz y elementos comunes
    private lateinit var raiz: LinearLayout
    private lateinit var contenido: LinearLayout
    private lateinit var botonModoCel: Button
    private lateinit var botonModoTab: Button
    private var botonCopiar: Button? = null
    private var botonActualizar: Button? = null
    private var estadoAct: TextView? = null

    // ------------------------------------------------------------------ CELULAR
    private var videoUri: Uri? = null
    private var videoDatos: Datos.Video? = null
    private var transformer: Transformer? = null
    private var servidor: Servidor? = null
    private var inicioProxy = 0L
    private lateinit var celInfoVideo: TextView
    private lateinit var celBotonCrear: Button
    private lateinit var celBarra: ProgressBar
    private lateinit var celEstado: TextView
    private lateinit var celDireccion: TextView
    private lateinit var celEnvio: TextView

    // ------------------------------------------------------------------ TABLET
    private var exo: ExoPlayer? = null
    private var vista: PlayerView? = null
    private lateinit var tabIp: EditText
    private lateinit var tabBotonTraer: Button
    private lateinit var tabBotonLocal: Button
    private lateinit var tabBarra: ProgressBar
    private lateinit var tabEstado: TextView
    private lateinit var tabTiempo: TextView
    private lateinit var tabLinea: SeekBar
    private lateinit var tabCortes: TextView
    private lateinit var tabBotonMedir: Button
    private lateinit var tabEstadoMedir: TextView
    private val controles = mutableListOf<Button>()
    private var arrastrando = false
    private val cortes = mutableListOf<Long>()
    private var midiendo = false

    private val mostrados = AtomicInteger(0)
    private val perdidos = AtomicInteger(0)
    private var decodificador = "?"
    private var alCuadro: ((Long) -> Unit)? = null
    private var t0Salto = 0L
    private var turnoSalto = 0

    // Datos para el informe
    private var infoCelular: JSONObject? = null
    private var lineaTransferencia: String? = null
    private var lineaArchivo: String? = null
    private var lineasFluidez: String? = null

    // ================================================================== ayudas de interfaz

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun texto(t: String, sp: Float, color: Int, negrita: Boolean = false) = TextView(this).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (negrita) typeface = Typeface.DEFAULT_BOLD
        setTextIsSelectable(false)
    }

    private fun fondoBoton(principal: Boolean, borde: Int = linea) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        if (principal) setColor(amarillo) else { setColor(Color.TRANSPARENT); setStroke(dp(1), borde) }
    }

    private fun boton(t: String, principal: Boolean) = Button(this).apply {
        text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(52)
        setTextColor(if (principal) fondo else tinta)
        background = fondoBoton(principal)
        isFocusable = false
    }

    private fun Button.activo(b: Boolean) { isEnabled = b; alpha = if (b) 1f else 0.4f }

    private fun bloque(titulo: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(panel); setStroke(dp(1), linea) }
        addView(texto(titulo, 18f, tinta, true).apply { setPadding(0, 0, 0, dp(10)) })
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun barra() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = ColorStateList.valueOf(amarillo); visibility = View.GONE
    }

    // ================================================================== inicio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modo = prefs.getString("modo", null) ?: if (resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "celular"
        window.decorView.setBackgroundColor(fondo)
        armar()
    }

    override fun onDestroy() {
        super.onDestroy()
        manejador.removeCallbacksAndMessages(null)
        try { transformer?.cancel() } catch (_: Exception) { }
        servidor?.detener()
        exo?.release(); exo = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (modo == "tablet" && !midiendo && !ocupado) armar()
    }

    private fun armar() {
        // Al rearmar la pantalla se suelta el reproductor (se vuelve a cargar abajo)
        val archivoCargado = exo?.currentMediaItem?.localConfiguration?.uri
        val posicion = exo?.currentPosition ?: 0L
        exo?.release(); exo = null; vista = null
        controles.clear()

        raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(fondo) }

        // Cabecera con el selector de equipo
        val cab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(8)) }
        cab.addView(texto("Prueba Proxy", 28f, tinta, true))
        cab.addView(texto("¿La tablet puede editar con una copia liviana del video del celular?", 15f, gris).apply { setPadding(0, dp(2), 0, dp(12)) })
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        botonModoCel = boton("Este es el CELULAR", modo == "celular")
        botonModoTab = boton("Esta es la TABLET", modo == "tablet")
        fila.addView(botonModoCel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        fila.addView(botonModoTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        cab.addView(fila)
        raiz.addView(cab)

        botonModoCel.setOnClickListener { cambiarModo("celular") }
        botonModoTab.setOnClickListener { cambiarModo("tablet") }

        if (modo == "celular") armarCelular() else armarTablet()
        setContentView(raiz)
        // El foco queda en la pantalla (no en el campo de texto), así el teclado controla el editor
        raiz.isFocusableInTouchMode = true
        raiz.requestFocus()

        if (modo == "tablet" && archivoCargado != null) {
            cargar(archivoCargado, null)
            exo?.seekTo(posicion)
        }
    }

    private fun cambiarModo(m: String) {
        if (m == modo) return
        if (ocupado || midiendo) return
        modo = m
        prefs.edit().putString("modo", m).apply()
        if (m == "tablet") { servidor?.detener(); servidor = null }
        armar()
    }

    private fun columnaConScroll(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply { setBackgroundColor(fondo); isFillViewport = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(40)) }
        scroll.addView(col)
        return scroll to col
    }

    private fun agregarPie(col: LinearLayout) {
        val bE = bloque("Este equipo")
        bE.addView(texto(Datos.equipo(this), 14f, gris), lp())
        col.addView(bE, lp(12))

        val bR = bloque("Resultados para pasarle a Claude")
        val bc = boton("Copiar resultados", false)
        botonCopiar = bc
        marcarCopiado(false)
        bc.setOnClickListener { copiar() }
        bR.addView(texto(if (modo == "tablet") "Copialos acá, en la tablet: incluyen también los datos del celular." else "Si la tablet no llegó a bajar el proxy, copiá estos.", 14f, gris).apply { setPadding(0, 0, 0, dp(10)) }, lp())
        bR.addView(bc, lp())
        col.addView(bR, lp(12))

        val ba = boton("Buscar actualización", false)
        botonActualizar = ba
        col.addView(ba, lp(24))
        val ea = texto("Versión instalada: " + Actualizador.versionInstalada(this), 14f, gris).apply { setPadding(0, dp(8), 0, 0) }
        estadoAct = ea
        col.addView(ea, lp())
        ba.setOnClickListener { actualizar() }
    }

    // ================================================================== CELULAR

    private fun armarCelular() {
        val (scroll, col) = columnaConScroll()
        raiz.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val b1 = bloque("1  Elegí el video crudo")
        val bElegir = boton("Elegir video", false)
        b1.addView(bElegir, lp())
        celInfoVideo = texto("Elegí uno de 10 a 15 minutos, como los que grabás siempre.", 15f, gris).apply { setPadding(0, dp(10), 0, 0) }
        b1.addView(celInfoVideo, lp())
        col.addView(b1, lp())

        val b2 = bloque("2  Armar el proxy")
        b2.addView(texto("Copia liviana: 720p, 30 fps, H.264, un cuadro clave por segundo (para que los saltos sean rápidos).", 14f, gris).apply { setPadding(0, 0, 0, dp(10)) }, lp())
        celBotonCrear = boton("Armar proxy", true)
        celBotonCrear.activo(false)
        b2.addView(celBotonCrear, lp())
        celBarra = barra()
        b2.addView(celBarra, lp(14))
        celEstado = texto("", 15f, gris).apply { setPadding(0, dp(8), 0, 0) }
        b2.addView(celEstado, lp())
        col.addView(b2, lp(12))

        val b3 = bloque("3  Pasarlo a la tablet")
        b3.addView(texto("Los dos equipos en el mismo Wi-Fi. En la tablet, escribí esta dirección:", 14f, gris), lp())
        celDireccion = texto("—", 30f, amarillo, true).apply { setPadding(0, dp(8), 0, dp(4)); setTextIsSelectable(true) }
        b3.addView(celDireccion, lp())
        celEnvio = texto("", 15f, gris)
        b3.addView(celEnvio, lp())
        col.addView(b3, lp(12))

        agregarPie(col)

        bElegir.setOnClickListener {
            if (ocupado) return@setOnClickListener
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*" }
            startActivityForResult(i, 7)
        }
        celBotonCrear.setOnClickListener { crearProxy() }

        // Si ya hay un proxy armado de antes, se ofrece igual
        val previo = prefs.getString("proxyInfo", null)
        if (previo != null && archivoProxy().exists()) {
            celEstado.setTextColor(tinta)
            celEstado.text = "Proxy armado antes, listo para mandar:\n" + resumenProxy(JSONObject(previo))
        }
        iniciarServidor()
    }

    private fun archivoProxy() = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "proxy.mp4")

    private fun iniciarServidor() {
        if (servidor == null) {
            servidor = Servidor(
                archivo = { archivoProxy().takeIf { it.exists() && prefs.getString("proxyInfo", null) != null } },
                info = { prefs.getString("proxyInfo", null) ?: "{}" },
                aviso = { msg -> runOnUiThread { if (modo == "celular") { celEnvio.setTextColor(tinta); celEnvio.text = msg } } }
            )
        }
        try {
            servidor?.iniciar()
            val ips = Datos.ips()
            celDireccion.text = if (ips.isEmpty()) "Sin Wi-Fi" else ips.first()
            if (ips.size > 1) celEnvio.text = "Si no conecta, probá con: " + ips.drop(1).joinToString(" · ")
        } catch (e: Exception) {
            celDireccion.text = "Error"
            celEnvio.setTextColor(rojo)
            celEnvio.text = "No se pudo abrir el puerto ${Servidor.PUERTO}: ${e.message}"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val u = data?.data ?: return
        if (requestCode == 7 && modo == "celular") {
            videoUri = u
            try {
                val v = Datos.leerVideo(this, u)
                videoDatos = v
                celInfoVideo.setTextColor(tinta)
                celInfoVideo.text = v.resumen()
                celBotonCrear.activo(true)
            } catch (e: Exception) {
                videoDatos = null
                celInfoVideo.setTextColor(rojo)
                celInfoVideo.text = "No pude leer el video: ${e.message}"
                celBotonCrear.activo(false)
            }
        } else if (requestCode == 8 && modo == "tablet") {
            infoCelular = null
            lineaTransferencia = "Transferencia: no hubo (video elegido de la tablet)"
            cargar(u, "Video de la tablet")
        }
    }

    /** Medida del proxy: el lado corto a 720, manteniendo la proporción (números pares). */
    private fun medidaProxy(w: Int, h: Int): Pair<Int, Int> {
        val corto = minOf(w, h)
        if (corto <= 720) return (w / 2 * 2) to (h / 2 * 2)
        val k = 720.0 / corto
        val nw = ((w * k) / 2).roundToInt() * 2
        val nh = ((h * k) / 2).roundToInt() * 2
        return nw to nh
    }

    private fun crearProxy() {
        val u = videoUri ?: return
        val v = videoDatos ?: return
        if (ocupado) return
        ocupado = true
        celBotonCrear.activo(false)
        celBarra.visibility = View.VISIBLE; celBarra.progress = 0
        celEstado.setTextColor(gris)
        celEstado.text = "Armando el proxy… no cierres la app ni apagues la pantalla."
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val salida = archivoProxy()
        salida.parentFile?.mkdirs()
        if (salida.exists()) salida.delete()
        prefs.edit().remove("proxyInfo").apply()

        val (w, h) = medidaProxy(v.ancho, v.alto)
        val efectos = mutableListOf<Effect>(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
        if (v.fps == 0f || v.fps > 31f) efectos.add(FrameDropEffect.createDefaultFrameDropEffect(30f))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(u)).setEffects(Effects(emptyList(), efectos)).build()
        val comp = Composition.Builder(EditedMediaItemSequence(listOf(item)))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val codificador = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(2_500_000).setiFrameIntervalSeconds(1f).build()
            )
            .setEnableFallback(true)
            .build()

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(codificador)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    proxyTerminado(v, salida, exportResult)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    proxyFallo(exportException)
                }
            })
            .build()
        transformer = t
        inicioProxy = SystemClock.elapsedRealtime()
        try {
            t.start(comp, salida.absolutePath)
        } catch (e: Exception) {
            proxyFallo(e)
            return
        }
        val holder = ProgressHolder()
        manejador.post(object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                if (!ocupado) return
                val estado = tr.getProgress(holder)
                val seg = (SystemClock.elapsedRealtime() - inicioProxy) / 1000
                if (estado == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val p = holder.progress
                    celBarra.progress = p * 10
                    val resta = if (p in 3..99) " · faltan unos " + Datos.duracion(seg * 1000 * (100 - p) / p) else ""
                    celEstado.text = "Armando el proxy… $p % · van ${Datos.duracion(seg * 1000)}$resta"
                } else {
                    celEstado.text = "Armando el proxy… van ${Datos.duracion(seg * 1000)}"
                }
                manejador.postDelayed(this, 500)
            }
        })
    }

    private fun proxyTerminado(v: Datos.Video, salida: File, r: ExportResult) {
        val ms = SystemClock.elapsedRealtime() - inicioProxy
        val j = JSONObject()
        j.put("nombre", v.nombre)
        j.put("origBytes", v.bytes)
        j.put("origAncho", v.ancho); j.put("origAlto", v.alto)
        j.put("origFps", v.fps.toDouble())
        j.put("origCodec", v.codec())
        j.put("hdr", v.hdr)
        j.put("duracionMs", v.duracionMs)
        j.put("proxyBytes", salida.length())
        j.put("proxyAncho", r.width); j.put("proxyAlto", r.height)
        j.put("proxyCuadros", r.videoFrameCount)
        j.put("creacionMs", ms)
        j.put("codificador", r.videoEncoderName ?: "?")
        j.put("equipo", "${Build.MANUFACTURER} ${Build.MODEL} · ${Datos.procesador()} · RAM ${Datos.ramTotalGb(this)}")
        prefs.edit().putString("proxyInfo", j.toString()).apply()

        ocupado = false
        transformer = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (modo != "celular") return
        celBarra.progress = 1000
        celBotonCrear.activo(true)
        celEstado.setTextColor(tinta)
        celEstado.text = "Listo.\n" + resumenProxy(j)
        iniciarServidor()
    }

    private fun proxyFallo(e: Exception) {
        ocupado = false
        transformer = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (modo != "celular") return
        celBotonCrear.activo(true)
        celBarra.visibility = View.GONE
        celEstado.setTextColor(rojo)
        val codigo = if (e is ExportException) " (${e.errorCodeName})" else ""
        celEstado.text = "No se pudo armar el proxy$codigo: ${e.message ?: e.javaClass.simpleName}"
        prefs.edit().putString("errorProxy", "Error al armar el proxy$codigo: ${e.message ?: e.javaClass.simpleName}").apply()
    }

    private fun resumenProxy(j: JSONObject): String {
        val orig = j.optLong("origBytes")
        val prox = j.optLong("proxyBytes")
        val veces = if (prox > 0) orig.toDouble() / prox else 0.0
        val dur = j.optLong("duracionMs")
        val crea = j.optLong("creacionMs")
        val ritmo = if (dur > 0) String.format(AR, "%.2f", crea.toDouble() / dur) else "?"
        return "Original: ${j.optInt("origAncho")}×${j.optInt("origAlto")} · ${String.format(AR, "%.0f", j.optDouble("origFps"))} fps · ${j.optString("origCodec")}" +
            "${if (j.optBoolean("hdr")) " · HDR" else ""} · ${Datos.duracion(dur)} · ${Datos.mb(orig)}\n" +
            "Proxy: ${j.optInt("proxyAncho")}×${j.optInt("proxyAlto")} · ${Datos.mb(prox)} (${String.format(AR, "%.0f", veces)} veces más liviano)\n" +
            "Tardó en armarlo: ${Datos.duracion(crea)} ($ritmo × la duración del video)\n" +
            "Codificador: ${j.optString("codificador")}"
    }

    // ================================================================== TABLET

    private fun armarTablet() {
        val horizontal = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cuerpo = LinearLayout(this).apply { orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        raiz.addView(cuerpo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- Columna del editor (fija, sin scroll)
        val editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(if (horizontal) 8 else 20), dp(12)) }
        val pv = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        vista = pv
        pv.setOnClickListener { raiz.requestFocus() }
        val anchoPantalla = resources.displayMetrics.widthPixels
        val altoVideo = if (horizontal) ViewGroup.LayoutParams.MATCH_PARENT else (anchoPantalla - dp(40)) * 9 / 16
        editor.addView(pv, if (horizontal) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, altoVideo))

        tabTiempo = texto("00:00 / 00:00", 16f, tinta, true).apply { setPadding(0, dp(8), 0, 0) }
        editor.addView(tabTiempo, lp())
        tabLinea = SeekBar(this).apply {
            max = 10_000
            progressTintList = ColorStateList.valueOf(amarillo)
            thumbTintList = ColorStateList.valueOf(amarillo)
            isFocusable = false
        }
        editor.addView(tabLinea, lp(4).apply { height = dp(40) })

        val filaCtrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun ctrl(t: String, principal: Boolean, accion: () -> Unit): Button {
            val b = boton(t, principal)
            b.setOnClickListener { if (!midiendo) accion() }
            filaCtrl.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
            controles += b
            return b
        }
        ctrl("−5 s", false) { mover(-5000) }
        ctrl("Play / Pausa", true) { alternar() }
        ctrl("+5 s", false) { mover(5000) }
        ctrl("Marcar corte", false) { marcarCorte() }
        editor.addView(filaCtrl, lp(6))
        tabCortes = texto("Teclado: espacio = play/pausa · flechas = 5 s · C = marcar corte", 13f, gris).apply { setPadding(0, dp(6), 0, 0) }
        editor.addView(tabCortes, lp())
        controles.forEach { it.activo(false) }
        tabLinea.isEnabled = false

        // ---- Columna de pasos (con scroll)
        val (scroll, col) = columnaConScroll()
        if (horizontal) {
            col.setPadding(dp(8), dp(8), dp(20), dp(40))
            cuerpo.addView(editor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.45f))
            cuerpo.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            cuerpo.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cuerpo.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val b1 = bloque("1  Traer el proxy del celular")
        b1.addView(texto("Dirección que muestra el celular:", 14f, gris), lp())
        tabIp = EditText(this).apply {
            setText(prefs.getString("ip", ""))
            hint = "por ejemplo 192.168.0.25"
            setHintTextColor(gris); setTextColor(tinta)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(fondo); setStroke(dp(1), linea) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        b1.addView(tabIp, lp(6))
        tabIp.setOnEditorActionListener { _, _, _ -> raiz.requestFocus(); traerProxy(); true }
        tabBotonTraer = boton("Traer proxy", true)
        b1.addView(tabBotonTraer, lp(10))
        tabBarra = barra()
        b1.addView(tabBarra, lp(12))
        tabEstado = texto("", 15f, gris).apply { setPadding(0, dp(8), 0, 0) }
        b1.addView(tabEstado, lp())
        tabBotonLocal = boton("Comparar: abrir un video de la tablet", false)
        b1.addView(tabBotonLocal, lp(14))
        b1.addView(texto("Sirve para probar el 4K original, si lo pasás a la tablet, y ver la diferencia.", 13f, gris).apply { setPadding(0, dp(6), 0, 0) }, lp())
        col.addView(b1, lp())

        val b2 = bloque("2  Probarlo a mano")
        b2.addView(texto("Arrastrá la línea de tiempo, saltá, marcá cortes. Fijate si responde al toque o se traba.", 14f, gris), lp())
        col.addView(b2, lp(12))

        val b3 = bloque("3  Medición automática (1 minuto)")
        b3.addView(texto("Reproduce 20 s y hace 24 saltos. Mide cuadros perdidos y cuánto tarda en aparecer la imagen después de cada salto.", 14f, gris).apply { setPadding(0, 0, 0, dp(10)) }, lp())
        tabBotonMedir = boton("Medir fluidez", true)
        tabBotonMedir.activo(false)
        b3.addView(tabBotonMedir, lp())
        tabEstadoMedir = texto("", 15f, gris).apply { setPadding(0, dp(8), 0, 0) }
        b3.addView(tabEstadoMedir, lp())
        col.addView(b3, lp(12))

        agregarPie(col)

        tabBotonTraer.setOnClickListener { traerProxy() }
        tabBotonLocal.setOnClickListener {
            if (ocupado || midiendo) return@setOnClickListener
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*" }
            startActivityForResult(i, 8)
        }
        tabBotonMedir.setOnClickListener { medir() }

        tabLinea.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, delUsuario: Boolean) {
                if (!delUsuario) return
                val e = exo ?: return
                val d = e.duration
                if (d == C.TIME_UNSET) return
                e.seekTo(d * p / 10_000)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {
                arrastrando = true
                exo?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            }
            override fun onStopTrackingTouch(s: SeekBar?) {
                arrastrando = false
                val e = exo ?: return
                e.setSeekParameters(SeekParameters.EXACT)
                val d = e.duration
                if (d != C.TIME_UNSET) e.seekTo(d * tabLinea.progress / 10_000)
            }
        })

        // Si ya se había bajado un proxy, queda a mano
        val previo = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "proxy-recibido.mp4")
        if (exo == null && previo.exists() && previo.length() > 0) {
            tabEstado.setTextColor(gris)
            tabEstado.text = "Hay un proxy bajado antes (${Datos.mb(previo.length())}). Tocá \"Traer proxy\" para bajarlo de nuevo, o probalo así."
            prefs.getString("infoCelular", null)?.let { infoCelular = try { JSONObject(it) } catch (_: Exception) { null } }
            lineaTransferencia = prefs.getString("lineaTransferencia", null)
            manejador.post { if (exo == null) cargar(Uri.fromFile(previo), "Proxy") }
        }

        actualizarReloj()
    }

    private fun crearReproductor(): ExoPlayer {
        val p = ExoPlayer.Builder(this).build()
        p.setSeekParameters(SeekParameters.EXACT)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { cuadroTrasSalto() }
            override fun onPlayerError(error: PlaybackException) {
                tabEstado.setTextColor(rojo)
                tabEstado.text = "El reproductor falló: ${error.errorCodeName} · ${error.message}"
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                perdidos.addAndGet(droppedFrames)
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                decodificador = decoderName
            }
        })
        p.setVideoFrameMetadataListener { _, _, _, _ -> mostrados.incrementAndGet() }
        vista?.player = p
        exo = p
        return p
    }

    private fun cargar(uri: Uri, etiqueta: String?) {
        val p = exo ?: crearReproductor()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.pause()
        cortes.clear()
        tabCortes.text = "Teclado: espacio = play/pausa · flechas = 5 s · C = marcar corte"
        lineasFluidez = null
        if (etiqueta != null) {
            lineaArchivo = try {
                val v = Datos.leerVideo(this, uri)
                "Archivo probado: $etiqueta · ${v.ancho}×${v.alto} · ${String.format(AR, "%.0f", v.fps)} fps · ${v.codec()} · ${Datos.duracion(v.duracionMs)} · ${Datos.mb(v.bytes)}"
            } catch (e: Exception) { "Archivo probado: $etiqueta" }
            prefs.edit().putString("lineaArchivo", lineaArchivo).apply()
        } else if (lineaArchivo == null) {
            lineaArchivo = prefs.getString("lineaArchivo", null)
        }
        controles.forEach { it.activo(true) }
        tabLinea.isEnabled = true
        tabBotonMedir.activo(true)
        tabEstadoMedir.text = ""
        marcarCopiado(false)
    }

    private fun traerProxy() {
        if (ocupado || midiendo) return
        var dir = tabIp.text.toString().trim().removePrefix("http://").trimEnd('/')
        if (dir.isEmpty()) { tabEstado.setTextColor(rojo); tabEstado.text = "Escribí la dirección que muestra el celular."; return }
        prefs.edit().putString("ip", dir).apply()
        raiz.requestFocus()
        if (!dir.contains(":")) dir += ":${Servidor.PUERTO}"
        val base = "http://$dir"
        ocupado = true
        tabBotonTraer.activo(false)
        tabBarra.visibility = View.VISIBLE; tabBarra.progress = 0
        tabEstado.setTextColor(gris)
        tabEstado.text = "Conectando con el celular…"
        exo?.stop()
        val destino = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "proxy-recibido.mp4")
        destino.parentFile?.mkdirs()
        Thread {
            try {
                val info = abrir("$base/info").inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(info)
                if (!j.has("proxyBytes")) throw IllegalStateException("El celular todavía no armó el proxy.")
                val c = abrir("$base/proxy")
                if (c.responseCode != 200) throw IllegalStateException("El celular respondió ${c.responseCode}.")
                val total = c.contentLengthLong
                val tmp = File(destino.path + ".parcial")
                val t0 = SystemClock.elapsedRealtime()
                var bajados = 0L
                var ultimo = -1
                c.inputStream.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            bajados += n
                            if (total > 0) {
                                val p = (bajados * 1000 / total).toInt()
                                if (p / 10 != ultimo) {
                                    ultimo = p / 10
                                    val seg = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1) / 1000.0
                                    runOnUiThread {
                                        tabBarra.progress = p
                                        tabEstado.text = "Bajando el proxy… ${p / 10} % · ${String.format(AR, "%.1f", bajados / 1_048_576.0 / seg)} MB/s"
                                    }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                if (total > 0 && bajados != total) throw IllegalStateException("Llegó incompleto (${Datos.mb(bajados)} de ${Datos.mb(total)}).")
                if (destino.exists()) destino.delete()
                tmp.renameTo(destino)
                val ms = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
                val vel = bajados / 1_048_576.0 / (ms / 1000.0)
                val linea = "Transferencia por Wi-Fi: ${Datos.mb(bajados)} en ${Datos.duracion(ms)} (${String.format(AR, "%.1f", vel)} MB/s)"
                runOnUiThread {
                    ocupado = false
                    infoCelular = j
                    lineaTransferencia = linea
                    prefs.edit().putString("infoCelular", j.toString()).putString("lineaTransferencia", linea).apply()
                    tabBotonTraer.activo(true)
                    tabBarra.progress = 1000
                    tabEstado.setTextColor(tinta)
                    tabEstado.text = "Listo. $linea\nYa está cargado en el editor."
                    cargar(Uri.fromFile(destino), "Proxy")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    ocupado = false
                    tabBotonTraer.activo(true)
                    tabBarra.visibility = View.GONE
                    tabEstado.setTextColor(rojo)
                    tabEstado.text = "No se pudo: ${e.message ?: e.javaClass.simpleName}\n" +
                        "Revisá que el celular tenga abierta la app en modo CELULAR y que los dos estén en el mismo Wi-Fi."
                }
            }
        }.start()
    }

    private fun abrir(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 30_000
        return c
    }

    // ---- controles del editor

    private fun alternar() {
        val e = exo ?: return
        e.setSeekParameters(SeekParameters.EXACT)
        if (e.isPlaying) e.pause() else {
            if (e.playbackState == Player.STATE_ENDED) e.seekTo(0)
            e.play()
        }
    }

    private fun mover(ms: Long) {
        val e = exo ?: return
        e.setSeekParameters(SeekParameters.EXACT)
        val d = e.duration
        var p = e.currentPosition + ms
        if (p < 0) p = 0
        if (d != C.TIME_UNSET && p > d) p = d
        e.seekTo(p)
    }

    private fun marcarCorte() {
        val e = exo ?: return
        cortes += e.currentPosition
        cortes.sort()
        tabCortes.setTextColor(tinta)
        tabCortes.text = "Cortes: " + cortes.joinToString("  ·  ") { Datos.reloj(it) } +
            "  →  ${cortes.size + 1} partes"
    }

    private fun actualizarReloj() {
        manejador.removeCallbacks(reloj)
        manejador.post(reloj)
    }

    private val reloj = object : Runnable {
        override fun run() {
            val e = exo
            if (modo == "tablet" && e != null) {
                val d = e.duration
                val p = e.currentPosition
                if (d != C.TIME_UNSET && d > 0) {
                    tabTiempo.text = "${Datos.reloj(p)} / ${Datos.reloj(d)}"
                    if (!arrastrando) tabLinea.progress = (p * 10_000 / d).toInt()
                }
            }
            manejador.postDelayed(this, 200)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (modo == "tablet" && exo != null && !midiendo && currentFocus !is EditText && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SPACE -> { alternar(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { mover(-5000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mover(5000); return true }
                KeyEvent.KEYCODE_C -> { marcarCorte(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- medición automática

    private fun saltar(ms: Long, parametros: SeekParameters, cb: (Long) -> Unit) {
        val e = exo ?: return
        e.setSeekParameters(parametros)
        val turno = ++turnoSalto
        alCuadro = cb
        t0Salto = SystemClock.elapsedRealtime()
        e.seekTo(ms)
        manejador.postDelayed({
            if (turnoSalto == turno && alCuadro != null) {
                val f = alCuadro
                alCuadro = null
                f?.invoke(-1)
            }
        }, 8_000)
    }

    private fun cuadroTrasSalto() {
        val f = alCuadro ?: return
        alCuadro = null
        f(SystemClock.elapsedRealtime() - t0Salto)
    }

    private fun serie(posiciones: List<Long>, parametros: SeekParameters, nombre: String, i: Int, tiempos: MutableList<Long>, fin: (List<Long>) -> Unit) {
        if (i >= posiciones.size) { fin(tiempos); return }
        tabEstadoMedir.text = "$nombre: ${i + 1} de ${posiciones.size}…"
        saltar(posiciones[i], parametros) { ms ->
            tiempos += ms
            manejador.postDelayed({ serie(posiciones, parametros, nombre, i + 1, tiempos, fin) }, 300)
        }
    }

    private fun medir() {
        val e = exo ?: return
        if (midiendo || ocupado) return
        val d = e.duration
        if (d == C.TIME_UNSET || d < 30_000) {
            tabEstadoMedir.setTextColor(rojo)
            tabEstadoMedir.text = "El video tiene que durar al menos 30 segundos."
            return
        }
        midiendo = true
        controles.forEach { it.activo(false) }
        tabLinea.isEnabled = false
        tabBotonMedir.activo(false)
        tabBotonTraer.activo(false)
        tabEstadoMedir.setTextColor(gris)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val azar = java.util.Random(42)
        fun posiciones() = List(12) { (d * (0.05 + 0.9 * azar.nextDouble())).toLong() }
        val rapidas = posiciones()
        val exactas = posiciones()

        tabEstadoMedir.text = "Reproduciendo 20 segundos…"
        saltar((d * 0.1).toLong(), SeekParameters.EXACT) { _ ->
            manejador.postDelayed({
                mostrados.set(0); perdidos.set(0)
                val ini = SystemClock.elapsedRealtime()
                e.play()
                manejador.postDelayed({
                    e.pause()
                    manejador.postDelayed({
                        val seg = (SystemClock.elapsedRealtime() - ini) / 1000.0
                        val m = mostrados.get()
                        val p = perdidos.get()
                        serie(rapidas, SeekParameters.CLOSEST_SYNC, "Saltos rápidos", 0, mutableListOf()) { tR ->
                            serie(exactas, SeekParameters.EXACT, "Saltos exactos", 0, mutableListOf()) { tE ->
                                terminarMedicion(m, p, seg, tR, tE)
                            }
                        }
                    }, 700)
                }, 20_000)
            }, 500)
        }
    }

    private fun terminarMedicion(mostr: Int, perd: Int, seg: Double, rapidos: List<Long>, exactos: List<Long>) {
        midiendo = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controles.forEach { it.activo(true) }
        tabLinea.isEnabled = true
        tabBotonMedir.activo(true)
        tabBotonTraer.activo(true)

        fun resumen(t: List<Long>): Triple<Long, Long, Int> {
            val ok = t.filter { it >= 0 }
            val fallas = t.size - ok.size
            return Triple(if (ok.isEmpty()) -1 else ok.sum() / ok.size, ok.maxOrNull() ?: -1, fallas)
        }
        val (promR, maxR, fallR) = resumen(rapidos)
        val (promE, maxE, fallE) = resumen(exactos)
        val total = mostr + perd
        val pctPerd = if (total > 0) perd * 100.0 / total else 100.0
        val fps = mostr / seg

        val veredicto = when {
            total == 0 || promR < 0 || promE < 0 -> "NO ALCANZA: el video no se mostró bien."
            pctPerd < 1.0 && promR <= 150 && promE <= 400 && fallR + fallE == 0 -> "FLUIDO: la tablet edita este archivo sin problema."
            pctPerd < 5.0 && promE <= 1000 && fallR + fallE == 0 -> "ACEPTABLE: se puede editar, con algo de demora al saltar."
            else -> "NO ALCANZA: se traba."
        }
        fun ms(v: Long) = if (v < 0) "—" else "$v ms"
        val texto = "Reproducción 20 s: ${String.format(AR, "%.1f", fps)} cuadros por segundo, $perd perdidos de $total (${String.format(AR, "%.1f", pctPerd)} %)\n" +
            "Saltos rápidos (cuadro clave): promedio ${ms(promR)} · máximo ${ms(maxR)}${if (fallR > 0) " · $fallR sin imagen" else ""}\n" +
            "Saltos exactos (cuadro justo): promedio ${ms(promE)} · máximo ${ms(maxE)}${if (fallE > 0) " · $fallE sin imagen" else ""}\n" +
            "Decodificador: $decodificador\n" +
            "Veredicto: $veredicto"
        lineasFluidez = texto
        tabEstadoMedir.setTextColor(tinta)
        tabEstadoMedir.text = texto
        marcarCopiado(false)
    }

    // ================================================================== informe y copiar

    private fun informe(): String {
        val b = StringBuilder()
        b.append("PRUEBA PROXY · ${SimpleDateFormat("dd-MM-yyyy HH:mm", AR).format(Date())} · v${Actualizador.versionInstalada(this)}\n\n")
        if (modo == "tablet") {
            b.append("TABLET\n").append(Datos.equipo(this)).append("\n\n")
            infoCelular?.let { j ->
                b.append("CELULAR\n").append(j.optString("equipo")).append("\n").append(resumenProxy(j)).append("\n\n")
            }
            lineaTransferencia?.let { b.append(it).append("\n") }
            lineaArchivo?.let { b.append(it).append("\n") }
            b.append("\nFLUIDEZ EN LA TABLET\n").append(lineasFluidez ?: "Todavía no se midió.").append("\n")
            if (cortes.isNotEmpty()) b.append("Cortes marcados a mano: ${cortes.size}\n")
        } else {
            b.append("CELULAR\n").append(Datos.equipo(this)).append("\n\n")
            val j = prefs.getString("proxyInfo", null)
            if (j != null) b.append("PROXY\n").append(resumenProxy(JSONObject(j))).append("\n")
            prefs.getString("errorProxy", null)?.let { if (j == null) b.append(it).append("\n") }
        }
        return b.toString().trim()
    }

    private fun copiar() {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Prueba Proxy", informe()))
            marcarCopiado(true)
        } catch (e: Exception) {
            marcarCopiado(false)
        }
    }

    private fun marcarCopiado(ok: Boolean) {
        val b = botonCopiar ?: return
        if (ok) {
            b.text = "Copiado ✓"
            b.setTextColor(fondo)
            b.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(verde) }
        } else {
            b.text = "Copiar resultados · sin copiar"
            b.setTextColor(rojo)
            b.background = fondoBoton(false, rojo)
        }
    }

    // ================================================================== actualización

    private fun actualizar() {
        if (ocupado || midiendo) return
        val ba = botonActualizar ?: return
        val ea = estadoAct ?: return
        ocupado = true
        ba.isEnabled = false
        ea.setTextColor(gris)
        ea.text = "Buscando versión nueva…"
        Thread {
            try {
                val info = Actualizador.consultar() ?: throw IllegalStateException("No encontré la versión en GitHub.")
                val actual = Actualizador.versionInstalada(this)
                if (info.version <= actual) {
                    runOnUiThread { ea.text = "Ya tenés la última versión ($actual)." }
                } else {
                    runOnUiThread { ea.text = "Bajando la versión ${info.version}…" }
                    Actualizador.instalar(applicationContext, info.url) { p ->
                        runOnUiThread { ea.text = "Bajando la versión ${info.version}… ${(p * 100).toInt()} %" }
                    }
                    runOnUiThread { ea.text = "Confirmá la actualización en el cuadro de Android." }
                }
            } catch (e: Exception) {
                runOnUiThread { ea.setTextColor(rojo); ea.text = "No se pudo actualizar: " + (e.message ?: e.toString()) }
            } finally {
                runOnUiThread { ocupado = false; ba.isEnabled = true }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun intentDeConfirmacion(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra(Intent.EXTRA_INTENT)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Actualizador.ACCION) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmar = intentDeConfirmacion(intent)
                if (confirmar != null) startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> estadoAct?.text = "Actualizada."
            else -> {
                estadoAct?.setTextColor(rojo)
                estadoAct?.text = "No se instaló: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelado")
            }
        }
    }
}
