package ar.gustavo.cerobudget

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Modo TABLET: el editor. Trabaja con los proxies (copias livianas) que manda el celular.
 * Al exportar, le manda al celular solo la lista de edición y el celular arma el 4K.
 */
class TabletActivity : Activity() {

    private val ui = Ui(this)
    private val manejador = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tablet", MODE_PRIVATE) }

    // ------------------------------------------------------------------ datos
    private var ed = Edicion()
    private val historial = ArrayDeque<Edicion>()
    private var sel = -1
    private var clipsCel: List<Clip> = emptyList()
    private val bajando = mutableMapOf<String, Int>()   // id → % bajado
    private var pxPorMs = 0.03f
    private var selTexto: String? = null
    private var selMusica = false
    private var arrastreCapa = false
    private val cacheRotulos = mutableMapOf<String, Bitmap>()
    private val vistasRotulos = mutableMapOf<String, ImageView>()

    // ------------------------------------------------------------------ conexión
    @Volatile private var conectado = false
    @Volatile private var base = ""
    private var hiloConexion: Thread? = null
    @Volatile private var exportando = false

    // ------------------------------------------------------------------ reproducción
    private var exo: ExoPlayer? = null
    private var exoMus: ExoPlayer? = null
    private var indiceAplicado = -1
    private var arrastrando = false

    // ------------------------------------------------------------------ vistas
    private lateinit var raiz: LinearLayout
    private lateinit var marco: AspectRatioFrameLayout
    private lateinit var vista: PlayerView
    private lateinit var capaTextos: FrameLayout
    private lateinit var velo: View
    private val botonesTrans = mutableListOf<Pair<String, Button>>()
    private lateinit var panelTexto: LinearLayout
    private val botonesFuente = mutableListOf<Pair<String, Button>>()
    private val botonesColor = mutableListOf<Pair<String, Button>>()
    private val botonesEstilo = mutableListOf<Pair<String, Button>>()
    private lateinit var tamBarra: SeekBar
    private lateinit var tamTexto: TextView
    private val soloTexto = mutableListOf<View>()
    private var soloConMusica: List<View> = emptyList()
    private lateinit var capaMascara: ImageView
    private val botonesMascara = mutableListOf<Pair<String, Button>>()
    private lateinit var panelMascara: LinearLayout
    private lateinit var mTamBarra: SeekBar
    private lateinit var mSuaveBarra: SeekBar
    private var claveMascara = ""
    private lateinit var zoomBarra: SeekBar
    private lateinit var horBarra: SeekBar
    private lateinit var verBarra: SeekBar
    private lateinit var zoomTexto: TextView
    private lateinit var kfInfo: TextView
    private lateinit var kfTextoInfo: TextView
    private lateinit var kfMusicaInfo: TextView
    private val cacheMascaras = mutableMapOf<String, Bitmap>()
    private lateinit var aviso: TextView
    private lateinit var tiempo: TextView
    private lateinit var lineaT: LineaTiempo
    private lateinit var bPlay: Button
    private lateinit var bKey: Button
    private lateinit var ipCampo: EditText
    private lateinit var conEstado: TextView
    private lateinit var listaClips: LinearLayout
    private lateinit var tramoInfo: TextView
    private val botonesVel = mutableListOf<Pair<Float, Button>>()
    private lateinit var volBarra: SeekBar
    private lateinit var volTexto: TextView
    private lateinit var musNombre: TextView
    private lateinit var musBarra: SeekBar
    private lateinit var musTexto: TextView
    private val botonesFormato = mutableListOf<Pair<String, Button>>()
    private lateinit var bExportar: Button
    private lateinit var expBarra: ProgressBar
    private lateinit var expEstado: TextView
    private val conTramo = mutableListOf<View>()

    private fun dirProxies() = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "proxies-recibidos").apply { mkdirs() }
    private fun proxyLocal(id: String) = File(dirProxies(), "$id.mp4")
    private fun musicaLocal() = File(filesDir, "musica-tablet")
    private fun dirImagenes() = File(filesDir, "imagenes").apply { mkdirs() }
    private fun imagenLocal(id: String): File? = id.takeIf { it.isNotEmpty() }?.let { File(dirImagenes(), it) }
    private fun tieneProxy(id: String): Boolean {
        val f = proxyLocal(id)
        val c = clipsCel.firstOrNull { it.id == id }
        return f.exists() && f.length() > 0 && (c == null || c.proxyBytes == 0L || c.proxyBytes == f.length())
    }
    private fun nombreClip(id: String) = clipsCel.firstOrNull { it.id == id }?.nombre ?: "video"

    // ================================================================== ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Ui.fondo)
        try { ed = Edicion.de(JSONObject(prefs.getString("edicion", "{}") ?: "{}")) } catch (_: Exception) { }
        try {
            val a = JSONArray(prefs.getString("clips", "[]") ?: "[]")
            clipsCel = List(a.length()) { Clip.de(a.getJSONObject(it)) }
        } catch (_: Exception) { }
        crearReproductores()
        armar()
        reconstruir(0)
        manejador.post(reloj)
        if (!prefs.getString("ip", "").isNullOrBlank()) conectar()
    }

    override fun onDestroy() {
        super.onDestroy()
        conectado = false
        manejador.removeCallbacksAndMessages(null)
        exo?.release(); exo = null
        exoMus?.release(); exoMus = null
    }

    override fun onPause() {
        super.onPause()
        exo?.pause(); exoMus?.pause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        armar()
    }

    // ================================================================== pantalla

    private fun armar() {
        botonesVel.clear(); botonesFormato.clear(); conTramo.clear()
        botonesTrans.clear(); botonesFuente.clear(); botonesColor.clear(); botonesEstilo.clear(); vistasRotulos.clear()
        soloTexto.clear(); botonesMascara.clear(); claveMascara = ""
        val horizontal = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        raiz = LinearLayout(this).apply { orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; setBackgroundColor(Ui.fondo) }

        // ---------------- columna del editor
        val editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), ui.dp(12), ui.dp(if (horizontal) 6 else 14), ui.dp(8)) }

        val negro = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        marco = AspectRatioFrameLayout(this).apply { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT }
        vista = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            player = exo
        }
        marco.addView(vista, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        capaMascara = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
        marco.addView(capaMascara, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        capaTextos = FrameLayout(this)
        marco.addView(capaTextos, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        velo = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f; isClickable = false }
        marco.addView(velo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        capaTextos.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob -> if (r - l != orr - ol || b - t != ob - ot) manejador.post { dibujarTextos(true) } }
        negro.addView(marco, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        aviso = ui.texto("", 15f, Ui.tinta).apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), 0, ui.dp(24), 0) }
        negro.addView(aviso, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        negro.setOnClickListener { raiz.requestFocus() }
        val altoVista = if (horizontal) 0 else (resources.displayMetrics.widthPixels - ui.dp(28)) * 9 / 16
        editor.addView(negro, if (horizontal) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, altoVista))

        tiempo = ui.texto("00:00,0 / 00:00,0", 16f, Ui.tinta, true).apply { setPadding(0, ui.dp(6), 0, 0) }
        editor.addView(tiempo, ui.lp())
        if (::lineaT.isInitialized) pxPorMs = lineaT.pxPorMs
        lineaT = LineaTiempo(this, ui).apply { oyente = oyenteLinea; pxPorMs = this@TabletActivity.pxPorMs; isFocusable = false }
        editor.addView(lineaT, ui.lp(4))

        val f1 = ui.fila()
        fun ctrl(fila: LinearLayout, t: String, principal: Boolean, w: Float = 1f, accion: () -> Unit): Button {
            val b = ui.boton(t, principal, 15f)
            b.setOnClickListener { accion(); raiz.requestFocus() }
            fila.addView(b, ui.peso(w))
            return b
        }
        ctrl(f1, "−5 s", false) { mover(-5000) }
        bPlay = ctrl(f1, "Play", true, 1.3f) { alternar() }
        ctrl(f1, "+5 s", false) { mover(5000) }
        ctrl(f1, "Dividir", false) { dividir() }
        ctrl(f1, "Borrar", false) { borrar() }
        ctrl(f1, "Duplicar", false) { duplicar() }
        ctrl(f1, "Deshacer", false) { deshacer() }
        bKey = ctrl(f1, "◆ Agregar", true, 1.4f) { tocarKey() }
        editor.addView(f1, ui.lp(6))
        editor.addView(ui.texto("Línea: arrastrá a los costados para mover el cabezal · pellizcá para acercar o alejar · tocá un tramo, texto o sticker para elegirlo · manijas blancas = inicio y fin · mantené apretado el sticker para moverlo en el tiempo.\nTeclado: espacio play/pausa · ← → 5 s · K keyframe · S dividir · Supr borrar · D duplicar · T texto · Z deshacer", 12f, Ui.gris), ui.lp(2))

        // ---------------- columna de herramientas
        val (scroll, col) = ui.columnaConScroll()
        if (horizontal) {
            col.setPadding(ui.dp(6), ui.dp(12), ui.dp(14), ui.dp(40))
            raiz.addView(editor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.7f))
            raiz.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            raiz.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            raiz.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // 1 Conexión
        val b1 = ui.bloque("1  Conexión con el celular")
        ipCampo = EditText(this).apply {
            setText(prefs.getString("ip", ""))
            hint = "dirección que muestra el celular"
            setHintTextColor(Ui.gris); setTextColor(Ui.tinta)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            background = GradientDrawable().apply { cornerRadius = ui.dp(10).toFloat(); setColor(Ui.fondo); setStroke(ui.dp(1), Ui.linea) }
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        b1.addView(ipCampo, ui.lp())
        ipCampo.setOnEditorActionListener { _, _, _ -> raiz.requestFocus(); conectar(); true }
        val bCon = ui.boton("Conectar", true)
        bCon.setOnClickListener { raiz.requestFocus(); conectar() }
        b1.addView(bCon, ui.lp(8))
        conEstado = ui.texto(if (conectado) "Conectado." else "", 14f, Ui.gris).apply { setPadding(0, ui.dp(6), 0, 0) }
        b1.addView(conEstado, ui.lp())
        col.addView(b1, ui.lp())

        // 2 Videos
        val b2 = ui.bloque("2  Videos del celular")
        listaClips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        b2.addView(listaClips, ui.lp())
        col.addView(b2, ui.lp(10))

        // 3 Tramo elegido
        val b3 = ui.bloque("3  Tramo elegido")
        tramoInfo = ui.texto("Tocá un tramo de la línea de tiempo.", 14f, Ui.gris)
        b3.addView(tramoInfo, ui.lp())
        b3.addView(ui.texto("Velocidad", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }, ui.lp())
        val fv = ui.fila()
        for (v in listOf(0.5f, 1f, 1.5f, 2f, 3f)) {
            val t = if (v == 0.5f) "0,5×" else if (v == 1.5f) "1,5×" else "${v.toInt()}×"
            val b = ui.boton(t, false, 14f)
            b.setOnClickListener { cambiarTramo { it.copy(vel = v) }; raiz.requestFocus() }
            fv.addView(b, ui.peso(1f, 2))
            botonesVel += v to b
            conTramo += b
        }
        b3.addView(fv, ui.lp())
        volTexto = ui.texto("Volumen: 100 %", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, 0) }
        b3.addView(volTexto, ui.lp())
        volBarra = SeekBar(this).apply {
            max = 200; progress = 100
            progressTintList = ColorStateList.valueOf(Ui.amarillo); thumbTintList = ColorStateList.valueOf(Ui.amarillo)
            isFocusable = false
        }
        b3.addView(volBarra, ui.lp(2).apply { height = ui.dp(40) })
        conTramo += volBarra
        volBarra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, delUsuario: Boolean) {
                if (!delUsuario) return
                volTexto.text = "Volumen: $p %"
                editarEncuadre(false) { it.copy(vol = p / 100f) }
                aplicarItem(true)
            }
            override fun onStartTrackingTouch(s: SeekBar?) { guardarHistoria() }
            override fun onStopTrackingTouch(s: SeekBar?) { guardar(); raiz.requestFocus() }
        })
        b3.addView(ui.texto("Transición al entrar en este tramo", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }, ui.lp())
        val ft = ui.fila()
        for ((clave, t) in listOf("corte" to "Corte", "negro" to "Fundido negro", "blanco" to "Fundido blanco")) {
            val b = ui.boton(t, false, 13f)
            b.setOnClickListener { cambiarTramo(reconstruirPlayer = false) { it.copy(trans = clave) }; raiz.requestFocus() }
            ft.addView(b, ui.peso(1f, 2))
            botonesTrans += clave to b
            conTramo += b
        }
        b3.addView(ft, ui.lp())
        b3.addView(ui.texto("Máscara", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }, ui.lp())
        val fmk = ui.fila()
        for ((clave, t) in listOf("" to "Ninguna", "circulo" to "Círculo", "ovalo" to "Óvalo", "rectangulo" to "Rectángulo")) {
            val b = ui.boton(t, false, 13f)
            b.setOnClickListener { cambiarTramo(reconstruirPlayer = false) { it.copy(mascara = clave) }; raiz.requestFocus() }
            fmk.addView(b, ui.peso(1f, 2))
            botonesMascara += clave to b
            conTramo += b
        }
        b3.addView(fmk, ui.lp())
        panelMascara = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun barraMascara(titulo: String, aplicar: (Segmento, Int) -> Segmento): SeekBar {
            panelMascara.addView(ui.texto(titulo, 13f, Ui.gris).apply { setPadding(0, ui.dp(6), 0, 0) }, ui.lp())
            val sb = SeekBar(this).apply {
                max = 100
                progressTintList = ColorStateList.valueOf(Ui.amarillo); thumbTintList = ColorStateList.valueOf(Ui.amarillo)
                isFocusable = false
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, delUsuario: Boolean) {
                    if (delUsuario) cambiarTramo(historia = false, reconstruirPlayer = false) { aplicar(it, p) }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { guardarHistoria() }
                override fun onStopTrackingTouch(s: SeekBar?) { guardar(); refrescarPanel(); raiz.requestFocus() }
            })
            panelMascara.addView(sb, ui.lp().apply { height = ui.dp(36) })
            return sb
        }
        mTamBarra = barraMascara("Tamaño de la forma") { s, p -> s.copy(mTam = 0.2f + p / 100f * 0.9f) }
        mSuaveBarra = barraMascara("Borde difuminado") { s, p -> s.copy(mSuave = p / 100f * 0.5f) }
        b3.addView(panelMascara, ui.lp())

        // Zoom, encuadre y keyframes del tramo
        b3.addView(ui.texto("Zoom y encuadre", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(12), 0, 0) }, ui.lp())
        zoomTexto = ui.texto("", 13f, Ui.gris)
        b3.addView(zoomTexto, ui.lp())
        fun barraTramo(titulo: String?, maximo: Int, aplicar: (Kf, Int) -> Kf): SeekBar {
            if (titulo != null) b3.addView(ui.texto(titulo, 13f, Ui.gris).apply { setPadding(0, ui.dp(4), 0, 0) }, ui.lp())
            val sb = SeekBar(this).apply {
                max = maximo
                progressTintList = ColorStateList.valueOf(Ui.amarillo); thumbTintList = ColorStateList.valueOf(Ui.amarillo)
                isFocusable = false
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, delUsuario: Boolean) { if (delUsuario) editarEncuadre(false) { aplicar(it, p) } }
                override fun onStartTrackingTouch(s: SeekBar?) { exo?.pause(); guardarHistoria() }
                override fun onStopTrackingTouch(s: SeekBar?) { guardar(); refrescarPanel(); raiz.requestFocus() }
            })
            b3.addView(sb, ui.lp().apply { height = ui.dp(36) })
            conTramo += sb
            return sb
        }
        zoomBarra = barraTramo(null, 200) { k, p -> val z = 1f + p / 100f; val lim = Interp.limite(z); k.copy(zoom = z, px = k.px.coerceIn(-lim, lim), py = k.py.coerceIn(-lim, lim)) }
        horBarra = barraTramo("Encuadre horizontal (izquierda ↔ derecha)", 100) { k, p -> k.copy(px = (p - 50) / 50f * Interp.limite(k.zoom)) }
        verBarra = barraTramo("Encuadre vertical (arriba ↔ abajo)", 100) { k, p -> k.copy(py = (p - 50) / 50f * Interp.limite(k.zoom)) }
        val bKfTodos = ui.boton("Quitar todos los ◆ del tramo", false, 13f)
        bKfTodos.setOnClickListener { cambiarTramo(reconstruirPlayer = false) { s -> val k = s.encuadre(relEnTramo()); s.copy(kf = emptyList(), zoom = k.zoom, px = k.px, py = k.py, vol = k.vol) }; raiz.requestFocus() }
        conTramo += bKfTodos
        b3.addView(bKfTodos, ui.lp(6))
        kfInfo = ui.texto("", 13f, Ui.gris).apply { setPadding(0, ui.dp(4), 0, 0) }
        b3.addView(kfInfo, ui.lp())
        val fm = ui.fila()
        val bIzq = ui.boton("◀ Mover", false, 14f); val bDer = ui.boton("Mover ▶", false, 14f)
        bIzq.setOnClickListener { moverTramo(-1); raiz.requestFocus() }
        bDer.setOnClickListener { moverTramo(1); raiz.requestFocus() }
        fm.addView(bIzq, ui.peso(1f, 2)); fm.addView(bDer, ui.peso(1f, 2))
        conTramo += bIzq; conTramo += bDer
        b3.addView(fm, ui.lp(8))
        col.addView(b3, ui.lp(10))

        col.addView(armarBloqueTextos(), ui.lp(10))

        // 4 Música
        val b4 = ui.bloque("5  Música")
        musNombre = ui.texto("", 14f, Ui.gris)
        b4.addView(musNombre, ui.lp())
        val fmu = ui.fila()
        val bElegirMus = ui.boton("Elegir música", false, 14f); val bQuitarMus = ui.boton("Quitar", false, 14f)
        fmu.addView(bElegirMus, ui.peso(1.4f, 2)); fmu.addView(bQuitarMus, ui.peso(1f, 2))
        b4.addView(fmu, ui.lp(6))
        musTexto = ui.texto("", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(8), 0, 0) }
        b4.addView(musTexto, ui.lp())
        musBarra = SeekBar(this).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Ui.amarillo); thumbTintList = ColorStateList.valueOf(Ui.amarillo)
            isFocusable = false
        }
        b4.addView(musBarra, ui.lp(2).apply { height = ui.dp(40) })
        val fkm = ui.boton("Quitar todos los ◆ de la música", false, 13f)
        fkm.setOnClickListener { val v = ed.volMusicaEn(tiempoActual()); cambiar(ed.copy(kfMusica = emptyList(), volMusica = v), reconstruirPlayer = false); raiz.requestFocus() }
        b4.addView(fkm, ui.lp(4))
        kfMusicaInfo = ui.texto("", 13f, Ui.gris).apply { setPadding(0, ui.dp(4), 0, 0) }
        b4.addView(kfMusicaInfo, ui.lp())
        soloConMusica = listOf(fkm, kfMusicaInfo)
        bElegirMus.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
            startActivityForResult(i, 31)
        }
        bQuitarMus.setOnClickListener {
            if (ed.musica.isNotEmpty()) { guardarHistoria(); ed = ed.copy(musica = ""); guardar(); prepararMusica(); refrescarPanel() }
            raiz.requestFocus()
        }
        musBarra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, delUsuario: Boolean) {
                if (!delUsuario) return
                val v = p / 100f
                ed = if (ed.kfMusica.isEmpty()) ed.copy(volMusica = v) else {
                    val t = tiempoActual()
                    ed.copy(kfMusica = (ed.kfMusica.filter { kotlin.math.abs(it.t - t) > 150 } + KfM(t, v)).sortedBy { it.t })
                }
                musTexto.text = "Volumen de la música: $p %"
                exoMus?.volume = v
            }
            override fun onStartTrackingTouch(s: SeekBar?) { guardarHistoria() }
            override fun onStopTrackingTouch(s: SeekBar?) { guardar(); raiz.requestFocus() }
        })
        col.addView(b4, ui.lp(10))

        // 5 Formato
        val b5 = ui.bloque("6  Formato")
        val ff = ui.fila()
        for ((clave, t) in listOf("original" to "Original", "9:16" to "9:16", "16:9" to "16:9", "1:1" to "1:1", "4:5" to "4:5")) {
            val b = ui.boton(t, false, 14f)
            b.setOnClickListener {
                if (ed.formato != clave) { guardarHistoria(); ed = ed.copy(formato = clave); guardar(); refrescarPanel() }
                raiz.requestFocus()
            }
            ff.addView(b, ui.peso(if (clave == "original") 1.5f else 1f, 2))
            botonesFormato += clave to b
        }
        b5.addView(ff, ui.lp())
        b5.addView(ui.texto("9:16 para reels y TikTok. Se exporta en 4K y se recorta al centro.", 13f, Ui.gris).apply { setPadding(0, ui.dp(6), 0, 0) }, ui.lp())
        col.addView(b5, ui.lp(10))

        // 6 Exportar
        val b6 = ui.bloque("7  Exportar")
        b6.addView(ui.texto("El celular arma el video final en 4K con los originales y lo guarda en Movies/0-Presupuesto-Editor.", 13f, Ui.gris).apply { setPadding(0, 0, 0, ui.dp(8)) }, ui.lp())
        bExportar = ui.boton("Exportar en 4K", true)
        bExportar.setOnClickListener { raiz.requestFocus(); exportar() }
        b6.addView(bExportar, ui.lp())
        expBarra = ui.barra()
        b6.addView(expBarra, ui.lp(10))
        expEstado = ui.texto("", 14f, Ui.gris).apply { setPadding(0, ui.dp(6), 0, 0) }
        b6.addView(expEstado, ui.lp())
        col.addView(b6, ui.lp(10))
        Actualizar.agregar(this, ui, col)

        setContentView(raiz)
        raiz.isFocusableInTouchMode = true
        raiz.requestFocus()

        capaTextos.setOnTouchListener(gestoVista)

        refrescarPanel()
        refrescarClips()
    }

    /** Actualiza todo lo que depende de la edición. */
    private fun refrescarPanel() {
        if (sel >= ed.segmentos.size) sel = ed.segmentos.size - 1
        val primero = ed.segmentos.firstOrNull()?.let { s -> clipsCel.firstOrNull { it.id == s.clip } }
        marco.setAspectRatio(Edicion.proporcion(ed.formato, primero?.ancho ?: 1920, primero?.alto ?: 1080))
        for ((clave, b) in botonesFormato) ui.marcar(b, clave == ed.formato)

        val s = ed.segmentos.getOrNull(sel)
        conTramo.forEach { ui.activo(it, s != null) }
        if (s != null) {
            tramoInfo.setTextColor(Ui.tinta)
            tramoInfo.text = "Tramo ${sel + 1} de ${ed.segmentos.size} · ${nombreClip(s.clip)}\n" +
                "Del ${Datos.reloj(s.ini)} al ${Datos.reloj(s.fin)} del original · dura ${Datos.reloj(s.dura)}"
            for ((v, b) in botonesVel) ui.marcar(b, v == s.vel)
            for ((k, b) in botonesTrans) ui.marcar(b, k == s.trans)
            for ((k, b) in botonesMascara) ui.marcar(b, k == s.mascara)
            panelMascara.visibility = if (s.mascara.isEmpty()) View.GONE else View.VISIBLE
            mTamBarra.progress = ((s.mTam - 0.2f) / 0.9f * 100).toInt().coerceIn(0, 100)
            mSuaveBarra.progress = (s.mSuave / 0.5f * 100).toInt().coerceIn(0, 100)
            refrescarEncuadre()
        } else {
            tramoInfo.setTextColor(Ui.gris)
            tramoInfo.text = if (ed.segmentos.isEmpty()) "La línea de tiempo está vacía: agregá un video desde la lista." else "Tocá un tramo de la línea de tiempo."
            for ((_, b) in botonesVel) ui.marcar(b, false)
            for ((_, b) in botonesTrans) ui.marcar(b, false)
            for ((_, b) in botonesMascara) ui.marcar(b, false)
            panelMascara.visibility = View.GONE
            kfInfo.text = ""; zoomTexto.text = ""
        }
        refrescarTextos()

        if (ed.musica.isEmpty()) {
            musNombre.setTextColor(Ui.gris); musNombre.text = "Sin música."
            musTexto.visibility = View.GONE; musBarra.visibility = View.GONE
            soloConMusica.forEach { it.visibility = View.GONE }
        } else {
            musNombre.setTextColor(Ui.tinta); musNombre.text = ed.musica
            musTexto.visibility = View.VISIBLE; musBarra.visibility = View.VISIBLE
            soloConMusica.forEach { it.visibility = View.VISIBLE }
            val vm = ed.volMusicaEn(tiempoActual())
            musBarra.progress = (vm * 100).toInt()
            musTexto.text = "Volumen de la música: ${(vm * 100).toInt()} %"
            kfMusicaInfo.text = if (ed.kfMusica.isEmpty()) "Sin keyframes: el volumen es fijo. Para animarlo: tocá la pista verde de la música en la línea, poné el cabezal y tocá ◆ Agregar." else "Keyframes: " + ed.kfMusica.joinToString(" · ") { Datos.reloj(it.t) }
        }
        ui.activo(bExportar, ed.segmentos.isNotEmpty() && !exportando)
        dibujarTira()
        actualizarBotonKey(true)
    }

    private fun dibujarTira() {
        if (::lineaT.isInitialized) lineaT.refrescar()
    }

    private fun refrescarClips() {
        listaClips.removeAllViews()
        if (clipsCel.isEmpty()) {
            listaClips.addView(ui.texto(if (conectado) "El celular no tiene videos: agregalos desde el celular." else "Conectate para ver los videos.", 14f, Ui.gris), ui.lp())
            return
        }
        for (c in clipsCel) {
            val fila = ui.fila().apply { setPadding(0, ui.dp(6), 0, ui.dp(6)); gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(ui.texto(c.nombre, 15f, Ui.tinta, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
            val listo = tieneProxy(c.id)
            val estado = when {
                listo -> "${Datos.duracion(c.duracionMs)} · listo para editar"
                bajando.containsKey(c.id) -> "Bajando proxy… ${bajando[c.id]} %"
                c.estado == "armando" -> "El celular arma el proxy… ${c.avance} %"
                c.estado == "error" -> "Error en el celular: ${c.error}"
                c.estado == "listo" -> "Proxy listo en el celular, falta bajarlo"
                else -> "En espera en el celular"
            }
            col.addView(ui.texto(estado, 13f, if (listo) Ui.verde else if (c.estado == "error") Ui.rojo else Ui.gris))
            fila.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val b = ui.boton("Agregar", false, 14f)
            ui.activo(b, listo)
            b.setOnClickListener { agregar(c); raiz.requestFocus() }
            fila.addView(b)
            listaClips.addView(fila, ui.lp())
        }
    }

    // ================================================================== edición

    private fun guardar() {
        prefs.edit().putString("edicion", ed.json().toString()).apply()
    }

    private fun guardarHistoria() {
        historial.addLast(ed)
        while (historial.size > 60) historial.removeFirst()
    }

    /** Cambia la edición, la guarda y vuelve a armar la vista previa sin perder la posición. */
    private fun cambiar(nueva: Edicion, historia: Boolean = true, reconstruirPlayer: Boolean = true) {
        val t = tiempoActual()
        if (historia) guardarHistoria()
        ed = nueva
        guardar()
        if (reconstruirPlayer) reconstruir(t.coerceAtMost((ed.total - 1).coerceAtLeast(0)))
        refrescarPanel()
    }

    private fun cambiarTramo(historia: Boolean = true, reconstruirPlayer: Boolean = true, f: (Segmento) -> Segmento) {
        val s = ed.segmentos.getOrNull(sel) ?: return
        val l = ed.segmentos.toMutableList()
        l[sel] = f(s)
        if (historia || reconstruirPlayer) cambiar(ed.copy(segmentos = l), historia, reconstruirPlayer)
        else { ed = ed.copy(segmentos = l); refrescarTira(); claveMascara = "" }
    }

    private fun refrescarTira() = dibujarTira()

    private fun agregar(c: Clip) {
        val l = ed.segmentos.toMutableList()
        l += Segmento(c.id, 0, c.duracionMs)
        sel = l.lastIndex
        val nueva = ed.copy(segmentos = l)
        cambiar(nueva)
        irA(nueva.inicio(sel))
    }

    private fun dividir() {
        if (ed.segmentos.isEmpty()) return
        val t = tiempoActual()
        val (i, dentro) = ed.ubicar(t)
        val s = ed.segmentos[i]
        val corte = s.ini + (dentro * s.vel).toLong()
        if (corte - s.ini < 100 || s.fin - corte < 100) { avisar("Muy cerca del borde del tramo para dividir."); return }
        val l = ed.segmentos.toMutableList()
        l[i] = s.copy(fin = corte)
        l.add(i + 1, s.copy(ini = corte))
        sel = i + 1
        cambiar(ed.copy(segmentos = l))
    }

    private fun borrar() {
        if (selTexto != null) { borrarTexto(); return }
        if (ed.segmentos.isEmpty()) return
        val i = if (sel in ed.segmentos.indices) sel else ed.ubicar(tiempoActual()).first
        val inicio = ed.inicio(i)
        val l = ed.segmentos.toMutableList()
        l.removeAt(i)
        sel = if (l.isEmpty()) -1 else i.coerceAtMost(l.lastIndex)
        cambiar(ed.copy(segmentos = l), reconstruirPlayer = false)
        reconstruir(inicio.coerceAtMost((ed.total - 1).coerceAtLeast(0)))
    }

    private fun duplicar() {
        if (ed.segmentos.isEmpty()) return
        val i = if (sel in ed.segmentos.indices) sel else ed.ubicar(tiempoActual()).first
        val l = ed.segmentos.toMutableList()
        l.add(i + 1, l[i])
        sel = i + 1
        val nueva = ed.copy(segmentos = l)
        cambiar(nueva)
        irA(nueva.inicio(sel))
    }

    private fun moverTramo(d: Int) {
        val j = sel + d
        if (sel !in ed.segmentos.indices || j !in ed.segmentos.indices) return
        val l = ed.segmentos.toMutableList()
        val tmp = l[sel]; l[sel] = l[j]; l[j] = tmp
        sel = j
        val nueva = ed.copy(segmentos = l)
        cambiar(nueva)
        irA(nueva.inicio(sel))
    }

    private fun deshacer() {
        val previa = historial.removeLastOrNull() ?: run { avisar("No hay nada para deshacer."); return }
        val t = tiempoActual()
        ed = previa
        guardar()
        reconstruir(t.coerceAtMost((ed.total - 1).coerceAtLeast(0)))
        prepararMusica()
        refrescarPanel()
    }

    private fun avisar(msg: String) {
        tiempo.text = msg
        manejador.postDelayed({ actualizarTiempo() }, 1800)
    }

    // ================================================================== reproducción

    private fun crearReproductores() {
        val p = ExoPlayer.Builder(this).build()
        p.setSeekParameters(SeekParameters.EXACT)
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { aplicarItem(false) }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                bPlay.text = if (isPlaying) "Pausa" else "Play"
                sincronizarMusica(isPlaying)
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) exoMus?.pause()
            }
            override fun onPlayerError(error: PlaybackException) {
                aviso.text = "La vista previa falló: ${error.errorCodeName}"
            }
        })
        exo = p
        exoMus = ExoPlayer.Builder(this).build().apply { repeatMode = Player.REPEAT_MODE_ALL }
        prepararMusica()
    }

    private fun prepararMusica() {
        val m = exoMus ?: return
        m.stop(); m.clearMediaItems()
        val f = musicaLocal()
        if (ed.musica.isNotEmpty() && f.exists()) {
            m.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            m.prepare()
            m.volume = ed.volMusica
            if (exo?.isPlaying == true) sincronizarMusica(true)
        }
    }

    private fun sincronizarMusica(tocando: Boolean) {
        val m = exoMus ?: return
        if (ed.musica.isEmpty() || m.mediaItemCount == 0) { m.pause(); return }
        if (!tocando) { m.pause(); return }
        val d = m.duration
        val t = tiempoActual()
        m.seekTo(if (d != C.TIME_UNSET && d > 0) t % d else t)
        m.volume = ed.volMusica
        m.play()
    }

    /** Vuelve a cargar la lista de tramos en el reproductor y se para en el momento t de la línea de tiempo. */
    private fun reconstruir(t: Long) {
        val p = exo ?: return
        indiceAplicado = -1
        if (ed.segmentos.isEmpty()) {
            p.clearMediaItems()
            aviso.text = "Agregá un video desde la lista de la derecha."
            return
        }
        val faltan = ed.segmentos.map { it.clip }.distinct().filter { !tieneProxy(it) }
        if (faltan.isNotEmpty()) {
            p.clearMediaItems()
            aviso.text = "Falta bajar el proxy de: " + faltan.joinToString(", ") { nombreClip(it) } + ".\nConectate con el celular."
            return
        }
        aviso.text = ""
        val items = ed.segmentos.map { s ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(proxyLocal(s.clip)))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.ini).setEndPositionMs(s.fin).build())
                .build()
        }
        val (i, dentro) = ed.ubicar(t)
        val estaba = p.isPlaying
        p.setMediaItems(items, i, (dentro * ed.segmentos[i].vel).toLong())
        p.prepare()
        aplicarItem(true)
        if (!estaba) p.pause()
    }

    /** Aplica la velocidad y el volumen del tramo que está sonando. */
    private fun aplicarItem(forzar: Boolean) {
        val p = exo ?: return
        val i = p.currentMediaItemIndex
        if (!forzar && i == indiceAplicado) return
        val s = ed.segmentos.getOrNull(i) ?: return
        indiceAplicado = i
        p.setPlaybackSpeed(s.vel)
        p.volume = s.vol.coerceAtMost(1f)
    }

    private fun indiceActual(): Int = exo?.currentMediaItemIndex ?: -1

    private fun tiempoActual(): Long {
        val p = exo ?: return 0
        if (p.mediaItemCount == 0) return 0
        val i = p.currentMediaItemIndex
        val s = ed.segmentos.getOrNull(i) ?: return 0
        return ed.inicio(i) + (p.currentPosition / s.vel).toLong()
    }

    private fun irA(t: Long) {
        val p = exo ?: return
        if (p.mediaItemCount == 0 || ed.segmentos.isEmpty()) return
        val (i, dentro) = ed.ubicar(t.coerceIn(0, ed.total))
        p.seekTo(i, (dentro * ed.segmentos[i].vel).toLong())
        aplicarItem(true)
        if (p.isPlaying) sincronizarMusica(true)
    }

    private fun alternar() {
        val p = exo ?: return
        if (p.mediaItemCount == 0) return
        p.setSeekParameters(SeekParameters.EXACT)
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) irA(0)
            p.play()
        }
    }

    private fun mover(ms: Long) = irA(tiempoActual() + ms)

    private fun actualizarTiempo() {
        val total = ed.total
        val t = tiempoActual()
        tiempo.text = "${Datos.reloj(t)} / ${Datos.reloj(total)}"
    }

    private var ultimoIndiceTira = -2

    private val reloj = object : Runnable {
        override fun run() {
            if (exo != null) {
                aplicarItem(false)
                actualizarTiempo()
                val i = indiceActual()
                if (i != ultimoIndiceTira) { ultimoIndiceTira = i; dibujarTira() }
                lineaT.invalidate()
                actualizarBotonKey(false)
                dibujarTextos(false)
                dibujarMascara()
                aplicarEncuadre()
                val (f, color) = ed.fundido(tiempoActual())
                velo.setBackgroundColor(if (color == "blanco") Color.WHITE else Color.BLACK)
                velo.alpha = 1f - f
            }
            manejador.postDelayed(this, 50)
        }
    }

    // ================================================================== teclado

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus !is EditText && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SPACE -> { alternar(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { mover(if (event.isShiftPressed) -1000 else -5000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mover(if (event.isShiftPressed) 1000 else 5000); return true }
                KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_B -> { dividir(); return true }
                KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> { borrar(); return true }
                KeyEvent.KEYCODE_D -> { duplicar(); return true }
                KeyEvent.KEYCODE_Z -> { deshacer(); return true }
                KeyEvent.KEYCODE_T -> { agregarTexto(); return true }
                KeyEvent.KEYCODE_K -> { tocarKey(); return true }
                KeyEvent.KEYCODE_MOVE_HOME -> { irA(0); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ================================================================== textos

    private fun armarBloqueTextos(): LinearLayout {
        val b = ui.bloque("4  Textos y stickers")
        val fa = ui.fila()
        val bAgregar = ui.boton("Texto", true, 15f)
        val bSticker = ui.boton("Sticker", true, 15f)
        val bImagen = ui.boton("Imagen", true, 15f)
        bAgregar.setOnClickListener { agregarTexto() }
        bSticker.setOnClickListener { elegirSticker() }
        bImagen.setOnClickListener {
            if (ed.segmentos.isEmpty()) { avisar("Primero agregá un video."); return@setOnClickListener }
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
            startActivityForResult(i, 32)
        }
        fa.addView(bAgregar, ui.peso(1f, 2)); fa.addView(bSticker, ui.peso(1f, 2)); fa.addView(bImagen, ui.peso(1f, 2))
        b.addView(fa, ui.lp())
        b.addView(ui.texto("Aparece donde está el cabezal y dura 3 segundos, en su propia pista de la línea de tiempo. Sobre el video: arrastralo con un dedo para moverlo y pellizcalo con dos para agrandarlo o achicarlo. La imagen puede ser un PNG con fondo transparente (por ejemplo, un logo).", 13f, Ui.gris).apply { setPadding(0, ui.dp(6), 0, 0) }, ui.lp())

        panelTexto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fe = ui.fila()
        val bEditar = ui.boton("Cambiar texto", false, 14f); val bBorrar = ui.boton("Borrar texto", false, 14f)
        bEditar.setOnClickListener { editarTexto() }
        soloTexto += bEditar
        bBorrar.setOnClickListener { borrarTexto() }
        fe.addView(bEditar, ui.peso(1f, 2)); fe.addView(bBorrar, ui.peso(1f, 2))
        panelTexto.addView(fe, ui.lp(6))

        panelTexto.addView(ui.texto("Cuándo se ve", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }, ui.lp())
        val fti = ui.fila()
        val bDesde = ui.boton("Empieza en el cursor", false, 13f); val bHasta = ui.boton("Termina en el cursor", false, 13f)
        bDesde.setOnClickListener { cambiarTexto { t -> val ini = tiempoActual(); t.copy(ini = ini, fin = if (t.fin <= ini + 200) ini + 3000 else t.fin) } }
        bHasta.setOnClickListener { cambiarTexto { t -> val fin = tiempoActual(); if (fin <= t.ini + 200) t else t.copy(fin = fin) } }
        fti.addView(bDesde, ui.peso(1f, 2)); fti.addView(bHasta, ui.peso(1f, 2))
        panelTexto.addView(fti, ui.lp())

        panelTexto.addView(ui.texto("Tipografía", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)); soloTexto += this }, ui.lp())
        Rotulo.FUENTES.chunked(4).forEach { grupo ->
            val f = ui.fila()
            grupo.forEach { (clave, nombre) ->
                val bt = ui.boton(nombre, false, 13f).apply { typeface = Rotulo.fuente(this@TabletActivity, clave) }
                bt.setOnClickListener { cambiarTexto { it.copy(fuente = clave) } }
                f.addView(bt, ui.peso(1f, 2))
                botonesFuente += clave to bt
            }
            panelTexto.addView(f, ui.lp(4))
            soloTexto += f
        }

        tamTexto = ui.texto("Tamaño", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, 0) }
        panelTexto.addView(tamTexto, ui.lp())
        tamBarra = SeekBar(this).apply {
            max = 180
            progressTintList = ColorStateList.valueOf(Ui.amarillo); thumbTintList = ColorStateList.valueOf(Ui.amarillo)
            isFocusable = false
        }
        panelTexto.addView(tamBarra, ui.lp(2).apply { height = ui.dp(40) })
        tamBarra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, delUsuario: Boolean) {
                if (!delUsuario) return
                val tam = (20 + p) / 1000f
                tamTexto.text = "Tamaño: ${(tam * 1000).toInt() / 10f} % del alto".replace('.', ',')
                cambiarTexto(historia = false) { tx ->
                    if (tx.kf.isEmpty()) tx.copy(tam = tam)
                    else kfTextoEn(tx, tiempoActual()) { it.copy(esc = tam / tx.tam) }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { guardarHistoria() }
            override fun onStopTrackingTouch(s: SeekBar?) { guardar(); raiz.requestFocus() }
        })

        panelTexto.addView(ui.texto("Color", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)); soloTexto += this }, ui.lp())
        val fc = ui.fila()
        Rotulo.COLORES.forEach { hex ->
            val bc = Button(this).apply {
                isFocusable = false
                minHeight = ui.dp(40); minimumHeight = ui.dp(40); minWidth = 0; minimumWidth = 0
                setOnClickListener { cambiarTexto { it.copy(color = hex) } }
            }
            fc.addView(bc, LinearLayout.LayoutParams(0, ui.dp(40), 1f).apply { leftMargin = ui.dp(3); rightMargin = ui.dp(3) })
            botonesColor += hex to bc
        }
        panelTexto.addView(fc, ui.lp())
        soloTexto += fc

        panelTexto.addView(ui.texto("Lugar", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }, ui.lp())
        val fs = ui.fila()
        for ((clave, nombre) in listOf("contorno" to "Contorno", "caja" to "Caja", "simple" to "Simple")) {
            val bs = ui.boton(nombre, false, 13f)
            bs.setOnClickListener { cambiarTexto { it.copy(estilo = clave) } }
            fs.addView(bs, ui.peso(1f, 2))
            botonesEstilo += clave to bs
        }
        panelTexto.addView(fs, ui.lp())
        soloTexto += fs
        val fp = ui.fila()
        for ((nombre, y) in listOf("Arriba" to 0.12f, "Centro" to 0.5f, "Abajo" to 0.85f)) {
            val bp = ui.boton(nombre, false, 13f)
            bp.setOnClickListener { cambiarTexto { it.copy(x = 0.5f, y = y) } }
            fp.addView(bp, ui.peso(1f, 2))
        }
        panelTexto.addView(fp, ui.lp(4))
        panelTexto.addView(ui.texto("Animación", 14f, Ui.tinta, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }, ui.lp())
        val bKtT = ui.boton("Quitar todos los ◆", false, 13f)
        bKtT.setOnClickListener { cambiarTexto { tx -> val k = tx.pos(tiempoActual()); tx.copy(kf = emptyList(), x = k.x, y = k.y, tam = (tx.tam * k.esc).coerceIn(0.01f, 1.5f)) } }
        panelTexto.addView(bKtT, ui.lp())
        kfTextoInfo = ui.texto("", 13f, Ui.gris).apply { setPadding(0, ui.dp(4), 0, 0) }
        panelTexto.addView(kfTextoInfo, ui.lp())
        b.addView(panelTexto, ui.lp())
        return b
    }

    private fun refrescarTextos() {
        if (ed.textos.none { it.id == selTexto }) selTexto = null
        val t = ed.textos.firstOrNull { it.id == selTexto }
        panelTexto.visibility = if (t == null) View.GONE else View.VISIBLE
        if (t != null) {
            soloTexto.forEach { it.visibility = if (t.tipo == "texto") View.VISIBLE else View.GONE }
            for ((k, b) in botonesFuente) ui.marcar(b, k == t.fuente)
            for ((k, b) in botonesEstilo) ui.marcar(b, k == t.estilo)
            for ((hex, b) in botonesColor) b.background = GradientDrawable().apply {
                cornerRadius = ui.dp(8).toFloat()
                setColor(Color.parseColor(hex))
                setStroke(ui.dp(if (hex == t.color) 4 else 1), if (hex == t.color) Ui.amarillo else Ui.linea)
            }
            tamBarra.progress = ((t.tam * 1000).toInt() - 20).coerceIn(0, 180)
            kfTextoInfo.text = if (t.kf.isEmpty()) "Sin keyframes. Para animar: poné el cabezal donde arranca el movimiento y tocá ◆ Agregar. Después mové el cabezal y cambiá el lugar o el tamaño sobre el video: el segundo ◆ se crea solo."
                else "Keyframes: " + t.kf.joinToString(" · ") { Datos.reloj(it.t) }
            tamTexto.text = "Tamaño: ${(t.tam * 1000).toInt() / 10f} % del alto".replace('.', ',')
        }
        dibujarTextos(true)
    }

    private fun elegirSticker() {
        if (ed.segmentos.isEmpty()) { avisar("Primero agregá un video."); return }
        exo?.pause()
        val grilla = GridLayout(this).apply { columnCount = 8; setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)) }
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sticker")
            .setView(android.widget.ScrollView(this).apply { addView(grilla) })
            .setNegativeButton("Cancelar") { _, _ -> raiz.requestFocus() }
            .create()
        Rotulo.EMOJIS.forEach { e ->
            grilla.addView(TextView(this).apply {
                text = e; setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); gravity = Gravity.CENTER
                setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                setOnClickListener {
                    d.dismiss()
                    val ini = tiempoActual()
                    val t = Texto("t" + System.currentTimeMillis().toString(36), e, ini, (ini + 3000).coerceAtMost(ed.total.coerceAtLeast(ini + 500)),
                        x = 0.5f, y = 0.5f, tam = 0.15f, estilo = "simple", tipo = "emoji")
                    selTexto = t.id
                    cambiar(ed.copy(textos = ed.textos + t), reconstruirPlayer = false)
                    raiz.requestFocus()
                }
            })
        }
        d.show()
    }

    private fun agregarImagen(u: Uri) {
        val id = "i" + System.currentTimeMillis().toString(36)
        try {
            contentResolver.openInputStream(u)?.use { inp -> File(dirImagenes(), id).outputStream().use { inp.copyTo(it) } }
                ?: throw IllegalStateException("No se pudo leer la imagen.")
            val ini = tiempoActual()
            val t = Texto("t" + System.currentTimeMillis().toString(36), id, ini, (ini + 3000).coerceAtMost(ed.total.coerceAtLeast(ini + 500)),
                x = 0.5f, y = 0.5f, tam = 0.3f, estilo = "simple", tipo = "imagen")
            selTexto = t.id
            cambiar(ed.copy(textos = ed.textos + t), reconstruirPlayer = false)
        } catch (e: Exception) {
            avisar("No pude usar esa imagen: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- keyframes

    /** ms desde el inicio del tramo elegido hasta el cursor (en tiempo de línea). */
    private fun relEnTramo(): Long {
        val s = ed.segmentos.getOrNull(sel) ?: return 0
        return (tiempoActual() - ed.inicio(sel)).coerceIn(0, s.dura)
    }

    /**
     * Cambia zoom, encuadre o volumen del tramo elegido. Sin keyframes, cambia el valor fijo;
     * con keyframes, cambia (o crea) el keyframe donde está el cursor.
     */
    private fun editarEncuadre(historia: Boolean, f: (Kf) -> Kf) {
        val s = ed.segmentos.getOrNull(sel) ?: return
        if (indiceActual() != sel) irA(ed.inicio(sel))
        val nuevo = if (s.kf.isEmpty()) {
            val n = f(Kf(0, s.zoom, s.px, s.py, s.vol))
            s.copy(zoom = n.zoom, px = n.px, py = n.py, vol = n.vol)
        } else {
            val rel = relEnTramo()
            val n = f(s.encuadre(rel)).copy(t = rel)
            s.copy(kf = (s.kf.filter { kotlin.math.abs(it.t - rel) > 150 } + n).sortedBy { it.t })
        }
        val l = ed.segmentos.toMutableList(); l[sel] = nuevo
        if (historia) cambiar(ed.copy(segmentos = l), reconstruirPlayer = false)
        else { ed = ed.copy(segmentos = l); refrescarTira(); refrescarEncuadre(); aplicarEncuadre() }
    }

    private fun agregarKfTramo() {
        val s = ed.segmentos.getOrNull(sel) ?: return
        val rel = relEnTramo()
        val k = s.encuadre(rel).copy(t = rel)
        val l = ed.segmentos.toMutableList()
        l[sel] = s.copy(kf = (s.kf.filter { kotlin.math.abs(it.t - rel) > 150 } + k).sortedBy { it.t })
        cambiar(ed.copy(segmentos = l), reconstruirPlayer = false)
    }

    private fun borrarKfTramo() {
        val s = ed.segmentos.getOrNull(sel) ?: return
        val rel = relEnTramo()
        val cerca = s.kf.minByOrNull { kotlin.math.abs(it.t - rel) } ?: return
        if (kotlin.math.abs(cerca.t - rel) > lineaT.toleranciaMs()) return
        // Al borrar el último, el tramo queda con el valor de ese momento
        val nuevo = if (s.kf.size == 1) s.copy(kf = emptyList(), zoom = cerca.zoom, px = cerca.px, py = cerca.py, vol = cerca.vol)
            else s.copy(kf = s.kf - cerca)
        val l = ed.segmentos.toMutableList(); l[sel] = nuevo
        cambiar(ed.copy(segmentos = l), reconstruirPlayer = false)
    }

    private fun refrescarEncuadre() {
        val s = ed.segmentos.getOrNull(sel) ?: return
        val k = s.encuadre(relEnTramo())
        zoomBarra.progress = ((k.zoom - 1f) * 100).toInt().coerceIn(0, 200)
        val lim = Interp.limite(k.zoom)
        horBarra.progress = if (lim <= 0f) 50 else (50 + k.px / lim * 50).toInt().coerceIn(0, 100)
        verBarra.progress = if (lim <= 0f) 50 else (50 + k.py / lim * 50).toInt().coerceIn(0, 100)
        zoomTexto.text = "Zoom: ${(k.zoom * 100).toInt()} %" + if (k.zoom <= 1f) " (subí el zoom para poder mover el encuadre)" else ""
        volBarra.progress = (k.vol * 100).toInt()
        volTexto.text = "Volumen: ${(k.vol * 100).toInt()} %"
        kfInfo.text = if (s.kf.isEmpty()) "Sin keyframes: zoom, encuadre y volumen fijos en todo el tramo. Para animar: tocá el tramo en la línea, poné el cabezal y tocá ◆ Agregar; después mové el cabezal y pellizcá o arrastrá el video (o mové las barras): el segundo ◆ se crea solo."
            else "Keyframes: " + s.kf.joinToString(" · ") { Datos.reloj(ed.inicio(sel) + it.t) }
    }

    /** Aplica en la vista previa el zoom, el encuadre y los volúmenes que corresponden al cursor. */
    private fun aplicarEncuadre() {
        if (!::vista.isInitialized) return
        val i = indiceActual()
        val s = ed.segmentos.getOrNull(i)
        val t = tiempoActual()
        if (s == null) { vista.scaleX = 1f; vista.scaleY = 1f; vista.translationX = 0f; vista.translationY = 0f; return }
        val k = s.encuadre((t - ed.inicio(i)).coerceAtLeast(0))
        val lim = Interp.limite(k.zoom)
        val px = k.px.coerceIn(-lim, lim); val py = k.py.coerceIn(-lim, lim)
        vista.scaleX = k.zoom; vista.scaleY = k.zoom
        vista.translationX = -k.zoom * px * vista.width
        vista.translationY = -k.zoom * py * vista.height
        if (s.kf.isNotEmpty()) exo?.volume = k.vol.coerceAtMost(1f)
        if (ed.kfMusica.isNotEmpty()) exoMus?.volume = ed.volMusicaEn(t).coerceIn(0f, 1f)
    }

    /** Crea o corrige el keyframe del texto en el momento t. Si ya hay uno cerca del cabezal, corrige ese. */
    private fun kfTextoEn(tx: Texto, t: Long, f: (KfT) -> KfT): Texto {
        val tol = if (::lineaT.isInitialized) lineaT.toleranciaMs() else 150L
        val existente = tx.kf.filter { kotlin.math.abs(it.t - t) <= tol }.minByOrNull { kotlin.math.abs(it.t - t) }
        val tk = existente?.t ?: t
        val n = f(tx.pos(tk)).copy(t = tk)
        return tx.copy(kf = (tx.kf.filter { it !== existente && kotlin.math.abs(it.t - tk) > 150 } + n).sortedBy { it.t })
    }

    // ---------------------------------------------------------------- botón ◆ de la línea de tiempo

    /** A qué apunta el ◆: "capa" (texto o sticker elegido), "musica", "tramo" o "" (nada elegido). */
    private fun objetivoKey(): String = when {
        selTexto != null && ed.textos.any { it.id == selTexto } -> "capa"
        selMusica && ed.musica.isNotEmpty() -> "musica"
        sel in ed.segmentos.indices -> "tramo"
        else -> ""
    }

    /** ¿El cabezal está dentro del elemento elegido? */
    private fun cabezalDentro(): Boolean {
        val t = tiempoActual()
        return when (objetivoKey()) {
            "capa" -> ed.textos.first { it.id == selTexto }.let { t in it.ini..it.fin }
            "musica" -> true
            "tramo" -> indiceActual() == sel
            else -> false
        }
    }

    /** ¿Hay un keyframe del elemento elegido justo en el cabezal? */
    private fun kfEnCabezal(): Boolean {
        val t = tiempoActual()
        val tol = lineaT.toleranciaMs()
        return when (objetivoKey()) {
            "capa" -> ed.textos.first { it.id == selTexto }.kf.any { kotlin.math.abs(it.t - t) <= tol }
            "musica" -> ed.kfMusica.any { kotlin.math.abs(it.t - t) <= tol }
            "tramo" -> indiceActual() == sel && ed.segmentos[sel].kf.any { kotlin.math.abs(it.t - relEnTramo()) <= tol }
            else -> false
        }
    }

    private var estadoKey = ""

    private fun actualizarBotonKey(forzar: Boolean) {
        if (!::bKey.isInitialized) return
        val obj = objetivoKey()
        val quitar = obj.isNotEmpty() && kfEnCabezal()
        val activo = obj.isNotEmpty() && cabezalDentro()
        val estado = "$quitar|$activo"
        if (!forzar && estado == estadoKey) return
        estadoKey = estado
        bKey.text = if (quitar) "◆ Quitar" else "◆ Agregar"
        ui.activo(bKey, activo)
        bKey.isEnabled = true   // aunque se vea apagado, al tocarlo explica qué falta
    }

    /** El botón ◆: agrega un keyframe en el cabezal o, si ya hay uno ahí, lo quita. Actúa sobre lo elegido en la línea. */
    private fun tocarKey() {
        val obj = objetivoKey()
        if (obj.isEmpty()) { avisar("Tocá en la línea de tiempo el sticker, texto, tramo o música que querés animar."); return }
        if (!cabezalDentro()) {
            avisar(if (obj == "tramo") "Poné el cabezal dentro del tramo elegido." else "Poné el cabezal dentro de la pista elegida.")
            return
        }
        exo?.pause()
        val t = tiempoActual()
        val tol = lineaT.toleranciaMs()
        when (obj) {
            "capa" -> cambiarTexto { tx ->
                val cerca = tx.kf.minByOrNull { kotlin.math.abs(it.t - t) }
                if (cerca != null && kotlin.math.abs(cerca.t - t) <= tol) {
                    // Al quitar el último, el sticker queda como estaba en ese keyframe
                    if (tx.kf.size == 1) tx.copy(kf = emptyList(), x = cerca.x, y = cerca.y, tam = (tx.tam * cerca.esc).coerceIn(0.01f, 1.5f))
                    else tx.copy(kf = tx.kf - cerca)
                } else kfTextoEn(tx, t) { it }
            }
            "musica" -> {
                val cerca = ed.kfMusica.minByOrNull { kotlin.math.abs(it.t - t) }
                val nueva = if (cerca != null && kotlin.math.abs(cerca.t - t) <= tol) {
                    if (ed.kfMusica.size == 1) ed.copy(kfMusica = emptyList(), volMusica = cerca.vol) else ed.copy(kfMusica = ed.kfMusica - cerca)
                } else ed.copy(kfMusica = (ed.kfMusica.filter { kotlin.math.abs(it.t - t) > 150 } + KfM(t, ed.volMusicaEn(t))).sortedBy { it.t })
                cambiar(nueva, reconstruirPlayer = false)
            }
            "tramo" -> if (kfEnCabezal()) borrarKfTramo() else agregarKfTramo()
        }
        actualizarBotonKey(true)
        raiz.requestFocus()
    }

    /** Une la línea de tiempo con el editor. */
    private val oyenteLinea = object : LineaTiempo.Oyente {
        override fun edicion() = ed
        override fun tiempo() = tiempoActual()
        override fun tramoElegido() = sel
        override fun capaElegida(): String? = selTexto ?: if (selMusica) LineaTiempo.MUSICA else null
        override fun nombreClip(id: String) = this@TabletActivity.nombreClip(id)

        override fun buscar(t: Long, final: Boolean) {
            val p = exo ?: return
            if (!final) {
                if (!arrastrando) { arrastrando = true; p.pause(); p.setSeekParameters(SeekParameters.CLOSEST_SYNC) }
                irA(t)
            } else {
                arrastrando = false
                p.setSeekParameters(SeekParameters.EXACT)
                irA(t)
                refrescarPanel()
            }
            raiz.requestFocus()
        }

        override fun elegirTramo(i: Int) {
            sel = i; selTexto = null; selMusica = false
            refrescarPanel(); raiz.requestFocus()
        }

        override fun elegirCapa(id: String) {
            if (id == LineaTiempo.MUSICA) { selMusica = true; selTexto = null } else { selTexto = id; selMusica = false }
            refrescarPanel(); raiz.requestFocus()
        }

        override fun elegirNada() {
            selTexto = null; selMusica = false; sel = -1
            refrescarPanel(); raiz.requestFocus()
        }

        override fun cambiarCapa(id: String, ini: Long, fin: Long, mover: Boolean, final: Boolean) {
            if (final) {
                arrastreCapa = false
                guardar(); refrescarPanel(); raiz.requestFocus()
                return
            }
            if (!arrastreCapa) { guardarHistoria(); arrastreCapa = true }
            ed = ed.copy(textos = ed.textos.map {
                if (it.id != id) it
                else {
                    val d = ini - it.ini
                    // Al moverlo en el tiempo, los keyframes viajan con el sticker
                    if (mover) it.copy(ini = ini, fin = fin, kf = it.kf.map { k -> k.copy(t = k.t + d) })
                    else it.copy(ini = ini, fin = fin)
                }
            })
            dibujarTextos(false)
        }
    }

    /** Muestra la máscara del tramo que se está viendo. */
    private fun dibujarMascara() {
        if (!::capaMascara.isInitialized) return
        val s = ed.segmentos.getOrNull(indiceActual())
        val w = capaMascara.width.takeIf { it > 0 } ?: marco.width
        val h = capaMascara.height.takeIf { it > 0 } ?: marco.height
        if (s == null || s.mascara.isEmpty() || w <= 0 || h <= 0) {
            if (capaMascara.visibility != View.GONE) capaMascara.visibility = View.GONE
            claveMascara = ""
            return
        }
        val clave = "${s.mascara}|${s.mTam}|${s.mSuave}|$w|$h"
        if (clave != claveMascara) {
            if (cacheMascaras.size > 20) cacheMascaras.clear()
            val bmp = cacheMascaras.getOrPut(clave) { Rotulo.mascara(s.mascara, s.mTam, s.mSuave, w / 2, h / 2) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
            capaMascara.setImageBitmap(bmp)
            claveMascara = clave
        }
        capaMascara.visibility = View.VISIBLE
    }

    private fun pedirTexto(inicial: String, listo: (String) -> Unit) {
        val campo = EditText(this).apply {
            setText(inicial); setSelection(inicial.length)
            setTextColor(Ui.tinta); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
        }
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Texto")
            .setView(campo)
            .setPositiveButton("Listo") { _, _ -> val v = campo.text.toString().trim(); if (v.isNotEmpty()) listo(v); raiz.requestFocus() }
            .setNegativeButton("Cancelar") { _, _ -> raiz.requestFocus() }
            .create()
        d.setOnShowListener {
            campo.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(campo, InputMethodManager.SHOW_IMPLICIT)
        }
        d.show()
    }

    private fun agregarTexto() {
        if (ed.segmentos.isEmpty()) { avisar("Primero agregá un video."); return }
        exo?.pause()
        pedirTexto("") { v ->
            val ini = tiempoActual()
            val t = Texto("t" + System.currentTimeMillis().toString(36), v, ini, (ini + 3000).coerceAtMost(ed.total.coerceAtLeast(ini + 500)))
            selTexto = t.id
            cambiar(ed.copy(textos = ed.textos + t), reconstruirPlayer = false)
        }
    }

    private fun editarTexto() {
        val t = ed.textos.firstOrNull { it.id == selTexto } ?: return
        pedirTexto(t.texto) { v -> cambiarTexto { it.copy(texto = v) } }
    }

    private fun borrarTexto() {
        val id = selTexto ?: return
        selTexto = null
        cambiar(ed.copy(textos = ed.textos.filter { it.id != id }), reconstruirPlayer = false)
        raiz.requestFocus()
    }

    private fun cambiarTexto(historia: Boolean = true, f: (Texto) -> Texto) {
        val id = selTexto ?: return
        val nuevos = ed.textos.map { if (it.id == id) f(it) else it }
        if (historia) cambiar(ed.copy(textos = nuevos), reconstruirPlayer = false)
        else { ed = ed.copy(textos = nuevos); dibujarTextos(true) }
        raiz.requestFocus()
    }

    /** Muestra sobre la vista previa los textos que corresponden al momento actual. */
    private fun dibujarTextos(rehacer: Boolean) {
        if (!::capaTextos.isInitialized) return
        val w = capaTextos.width; val h = capaTextos.height
        if (w <= 0 || h <= 0) return
        val t = tiempoActual()
        val ids = ed.textos.map { it.id }.toSet()
        vistasRotulos.keys.filter { it !in ids }.forEach { capaTextos.removeView(vistasRotulos.remove(it)) }
        if (rehacer && cacheRotulos.size > 60) cacheRotulos.clear()
        for (tx in ed.textos) {
            val visible = t in tx.ini..tx.fin || tx.id == selTexto && exo?.isPlaying != true && t in (tx.ini - 1)..(tx.fin + 1)
            var iv = vistasRotulos[tx.id]
            if (iv == null) {
                iv = ImageView(this)
                capaTextos.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                vistasRotulos[tx.id] = iv
            }
            if (!visible) { iv.visibility = View.GONE; continue }
            val clave = tx.json().toString() + "|$w|$h"
            val bmp = cacheRotulos.getOrPut(clave) { Rotulo.bitmap(this, tx, w, h) { imagenLocal(it) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
            if (iv.tag != clave) { iv.setImageBitmap(bmp); iv.tag = clave }
            val k = tx.pos(t)
            val e = k.esc * (if (tx.id == gId) gEscVisual else 1f)
            iv.scaleX = e; iv.scaleY = e
            iv.translationX = k.x * w - bmp.width / 2f
            iv.translationY = k.y * h - bmp.height / 2f
            iv.visibility = View.VISIBLE
            iv.alpha = if (tx.id == selTexto) 1f else 0.97f
            iv.background = if (tx.id == selTexto && exo?.isPlaying != true) GradientDrawable().apply { setStroke(ui.dp(2), Ui.amarillo) } else null
        }
    }

    // ---------------------------------------------------------------- gestos sobre el video

    private var gObjetivo = 0            // 0 nada · 1 texto o sticker · 2 video (zoom y encuadre del tramo)
    private var gId: String? = null
    private var gEscVisual = 1f          // escala mientras se pellizca un sticker sin keyframes (se guarda al soltar)
    private var gMovio = false
    private var gCx0 = 0f
    private var gCy0 = 0f
    private var gDist0 = 0f
    private var gK0 = KfT(0, 0.5f, 0.5f, 1f)
    private var gKv0 = Kf(0)
    private var gTam0 = 0.1f
    private var gTocoCapa = false

    /** Centro y separación de los dedos (sin contar el que se está levantando). */
    private fun dedos(e: MotionEvent): Triple<Float, Float, Float> {
        val fuera = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != fuera) { sx += e.getX(i); sy += e.getY(i); n++ }
        if (n == 0) return Triple(e.x, e.y, 0f)
        val cx = sx / n; val cy = sy / n
        var d = 0f
        if (n >= 2) { for (i in 0 until e.pointerCount) if (i != fuera) d += kotlin.math.hypot(e.getX(i) - cx, e.getY(i) - cy); d /= n }
        return Triple(cx, cy, d)
    }

    /** El texto o sticker visible que está debajo del dedo (el de más arriba). */
    private fun capaEnVista(x: Float, y: Float): String? {
        val t = tiempoActual()
        for (tx in ed.textos.asReversed()) {
            if (t !in tx.ini..tx.fin) continue
            val iv = vistasRotulos[tx.id] ?: continue
            if (iv.visibility != View.VISIBLE) continue
            val d = iv.drawable ?: continue
            val bw = d.intrinsicWidth.toFloat(); val bh = d.intrinsicHeight.toFloat()
            val cx = iv.translationX + bw / 2f; val cy = iv.translationY + bh / 2f
            val mw = kotlin.math.max(bw * iv.scaleX / 2f, ui.dp(24).toFloat())
            val mh = kotlin.math.max(bh * iv.scaleY / 2f, ui.dp(24).toFloat())
            if (x in (cx - mw)..(cx + mw) && y in (cy - mh)..(cy + mh)) return tx.id
        }
        return null
    }

    /** Toma como punto de partida cómo está ahora el sticker o el video. */
    private fun gBase(e: MotionEvent) {
        // Si se venía pellizcando un sticker sin keyframes, primero se guarda ese tamaño
        val id = gId
        if (gObjetivo == 1 && id != null && gEscVisual != 1f) {
            val f = gEscVisual; gEscVisual = 1f
            ed = ed.copy(textos = ed.textos.map { if (it.id == id && it.kf.isEmpty()) it.copy(tam = (it.tam * f).coerceIn(0.01f, 1.5f)) else it })
        }
        val (cx, cy, d) = dedos(e)
        gCx0 = cx; gCy0 = cy; gDist0 = d
        val t = tiempoActual()
        ed.textos.firstOrNull { it.id == id }?.let { gK0 = it.pos(t); gTam0 = it.tam }
        ed.segmentos.getOrNull(sel)?.let { gKv0 = it.encuadre(relEnTramo()) }
    }

    /**
     * Gestos sobre la vista previa, como en CapCut: un dedo mueve, dos dedos agrandan o achican.
     * Actúa sobre el texto o sticker tocado (o el elegido); si no hay ninguno, sobre el zoom y el encuadre del tramo.
     * Si el elemento tiene keyframes, el cambio crea o corrige el keyframe donde está el cabezal.
     */
    private val gestoVista = View.OnTouchListener { v, e ->
        val w = capaTextos.width.toFloat(); val h = capaTextos.height.toFloat()
        if (w <= 0 || h <= 0) return@OnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                exo?.pause()
                gMovio = false; gEscVisual = 1f
                val tocada = capaEnVista(e.x, e.y)
                gTocoCapa = tocada != null
                val t = tiempoActual()
                val elegidaVisible = ed.textos.firstOrNull { it.id == selTexto }?.let { t in it.ini..it.fin } == true
                when {
                    tocada != null -> {
                        if (selTexto != tocada) { selTexto = tocada; selMusica = false; refrescarPanel() }
                        gObjetivo = 1; gId = tocada
                    }
                    elegidaVisible -> { gObjetivo = 1; gId = selTexto }
                    selTexto == null && !selMusica && sel in ed.segmentos.indices && sel == indiceActual() -> { gObjetivo = 2; gId = null }
                    else -> { gObjetivo = 0; gId = null }
                }
                if (gObjetivo != 0) guardarHistoria()
                gBase(e)
                true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (gObjetivo == 0 && e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && selTexto == null && !selMusica && ed.segmentos.isNotEmpty()) {
                    // Pellizco sobre el video sin nada elegido: se elige el tramo que se está viendo
                    sel = indiceActual(); gObjetivo = 2; guardarHistoria(); refrescarPanel()
                }
                gBase(e)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (gObjetivo == 0) return@OnTouchListener true
                val (cx, cy, d) = dedos(e)
                val slop = ui.dp(6)
                if (!gMovio && kotlin.math.abs(cx - gCx0) < slop && kotlin.math.abs(cy - gCy0) < slop && (gDist0 == 0f || kotlin.math.abs(d - gDist0) < slop)) return@OnTouchListener true
                gMovio = true
                val f = if (gDist0 > 0f && d > 0f) d / gDist0 else 1f
                val dx = (cx - gCx0) / w; val dy = (cy - gCy0) / h
                if (gObjetivo == 1) {
                    val id = gId ?: return@OnTouchListener true
                    val nx = (gK0.x + dx).coerceIn(0.02f, 0.98f); val ny = (gK0.y + dy).coerceIn(0.02f, 0.98f)
                    val t = tiempoActual()
                    ed = ed.copy(textos = ed.textos.map {
                        if (it.id != id) it
                        else if (it.kf.isEmpty()) { gEscVisual = f; it.copy(x = nx, y = ny) }
                        else kfTextoEn(it, t) { k -> k.copy(x = nx, y = ny, esc = (gK0.esc * f).coerceIn(0.05f, 12f)) }
                    })
                    dibujarTextos(false)
                } else {
                    val z = (gKv0.zoom * f).coerceIn(1f, 3f)
                    val lim = Interp.limite(z)
                    val px = (gKv0.px - dx / z).coerceIn(-lim, lim); val py = (gKv0.py - dy / z).coerceIn(-lim, lim)
                    editarEncuadre(false) { it.copy(zoom = z, px = px, py = py) }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!gMovio) {
                    // Fue un toque: elegir o soltar
                    if (gObjetivo != 0) historial.removeLastOrNull()
                    if (!gTocoCapa && e.actionMasked == MotionEvent.ACTION_UP) {
                        if (selTexto != null || selMusica) { selTexto = null; selMusica = false }
                        else if (ed.segmentos.isNotEmpty()) sel = indiceActual()
                        refrescarPanel()
                    }
                } else {
                    val id = gId
                    if (gObjetivo == 1 && id != null && gEscVisual != 1f) {
                        val f = gEscVisual
                        ed = ed.copy(textos = ed.textos.map { if (it.id == id && it.kf.isEmpty()) it.copy(tam = (gTam0 * f).coerceIn(0.01f, 1.5f)) else it })
                    }
                    guardar(); refrescarPanel()
                }
                gEscVisual = 1f; gObjetivo = 0; gId = null
                v.parent?.requestDisallowInterceptTouchEvent(false)
                raiz.requestFocus()
                true
            }
            else -> false
        }
    }

    // ================================================================== música

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == 32) { data?.data?.let { agregarImagen(it) }; return }
        if (requestCode != 31) return
        val u = data?.data ?: return
        val (nombre, _) = Datos.nombreDe(this, u)
        try {
            contentResolver.openInputStream(u)?.use { inp -> musicaLocal().outputStream().use { inp.copyTo(it) } }
                ?: throw IllegalStateException("No se pudo leer el archivo.")
            guardarHistoria()
            ed = ed.copy(musica = nombre)
            guardar()
            prepararMusica()
            refrescarPanel()
        } catch (e: Exception) {
            musNombre.setTextColor(Ui.rojo)
            musNombre.text = "No pude copiar la música: ${e.message}"
        }
    }

    // ================================================================== conexión con el celular

    private fun abrir(url: String, timeout: Int = 30_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = timeout
        return c
    }

    private fun conectar() {
        var dir = ipCampo.text.toString().trim().removePrefix("http://").trimEnd('/')
        if (dir.isEmpty()) { conEstado.setTextColor(Ui.rojo); conEstado.text = "Escribí la dirección que muestra el celular."; return }
        prefs.edit().putString("ip", dir).apply()
        if (!dir.contains(":")) dir += ":${Servidor.PUERTO}"
        base = "http://$dir"
        conEstado.setTextColor(Ui.gris)
        conEstado.text = "Conectando…"
        if (hiloConexion?.isAlive == true) return
        conectado = true
        hiloConexion = Thread { bucleConexion() }.apply { start() }
    }

    /** Mientras la pantalla esté abierta: consulta el celular y baja los proxies nuevos. */
    private fun bucleConexion() {
        var fallos = 0
        while (conectado) {
            try {
                val txt = abrir("$base/info", 8_000).inputStream.bufferedReader().use { it.readText() }
                val a = JSONObject(txt).getJSONArray("clips")
                val lista = List(a.length()) { Clip.de(a.getJSONObject(it)) }
                fallos = 0
                runOnUiThread {
                    clipsCel = lista
                    prefs.edit().putString("clips", a.toString()).apply()
                    conEstado.setTextColor(Ui.verde)
                    conEstado.text = "Conectado con el celular."
                    refrescarClips()
                }
                for (c in lista) {
                    if (!conectado) break
                    if (c.estado == "listo" && !(proxyLocal(c.id).exists() && proxyLocal(c.id).length() == c.proxyBytes)) bajarProxy(c)
                }
            } catch (e: Exception) {
                fallos++
                runOnUiThread {
                    conEstado.setTextColor(Ui.rojo)
                    conEstado.text = "No encuentro el celular (${e.message ?: "sin respuesta"}). Revisá que tenga abierta la app en modo Celular y que estén en el mismo Wi-Fi."
                }
            }
            try { Thread.sleep(if (fallos == 0) 3_000L else 6_000L) } catch (_: InterruptedException) { break }
        }
    }

    private fun bajarProxy(c: Clip) {
        val destino = proxyLocal(c.id)
        val tmp = File(destino.path + ".parcial")
        try {
            val con = abrir("$base/proxy?id=${URLEncoder.encode(c.id, "UTF-8")}")
            if (con.responseCode != 200) throw IllegalStateException("el celular respondió ${con.responseCode}")
            val total = con.contentLengthLong
            var bajados = 0L
            var ultimo = -1
            con.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bajados += n
                        if (total > 0) {
                            val p = (bajados * 100 / total).toInt()
                            if (p != ultimo && p % 5 == 0) { ultimo = p; runOnUiThread { bajando[c.id] = p; refrescarClips() } }
                        }
                    }
                }
            }
            con.disconnect()
            if (total > 0 && bajados != total) throw IllegalStateException("llegó incompleto")
            destino.delete()
            tmp.renameTo(destino)
            runOnUiThread {
                bajando.remove(c.id)
                refrescarClips()
                // Si la línea de tiempo esperaba este proxy, se arma la vista previa
                if (exo?.mediaItemCount == 0 && ed.segmentos.isNotEmpty()) reconstruir(0)
            }
        } catch (e: Exception) {
            tmp.delete()
            runOnUiThread { bajando.remove(c.id); refrescarClips() }
        }
    }

    // ================================================================== exportar

    private fun exportar() {
        if (exportando || ed.segmentos.isEmpty()) return
        if (base.isEmpty()) { expEstado.setTextColor(Ui.rojo); expEstado.text = "Primero conectate con el celular."; return }
        exportando = true
        exo?.pause()
        ui.activo(bExportar, false)
        expBarra.visibility = View.VISIBLE; expBarra.progress = 0
        expEstado.setTextColor(Ui.gris)
        expEstado.text = "Mandando la edición al celular…"
        val edicion = ed
        Thread {
            try {
                val conMusica = edicion.musica.isNotEmpty() && musicaLocal().exists()
                if (conMusica) {
                    runOnUiThread { expEstado.text = "Mandando la música al celular…" }
                    val f = musicaLocal()
                    val c = abrir("$base/musica", 60_000)
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setFixedLengthStreamingMode(f.length())
                    c.outputStream.use { o -> f.inputStream().use { it.copyTo(o) } }
                    if (c.responseCode != 200) throw IllegalStateException("el celular no recibió la música (${c.responseCode})")
                    c.disconnect()
                }
                val imagenes = edicion.textos.filter { it.tipo == "imagen" }.map { it.texto }.distinct()
                for (id in imagenes) {
                    val f = imagenLocal(id) ?: continue
                    if (!f.exists()) continue
                    runOnUiThread { expEstado.text = "Mandando las imágenes al celular…" }
                    val ci = abrir("$base/imagen?id=${URLEncoder.encode(id, "UTF-8")}", 60_000)
                    ci.requestMethod = "POST"; ci.doOutput = true
                    ci.setFixedLengthStreamingMode(f.length())
                    ci.outputStream.use { o -> f.inputStream().use { it.copyTo(o) } }
                    if (ci.responseCode != 200) throw IllegalStateException("el celular no recibió una imagen (${ci.responseCode})")
                    ci.disconnect()
                }
                val cuerpo = edicion.json().toString().toByteArray(Charsets.UTF_8)
                val c = abrir("$base/exportar?musica=${if (conMusica) 1 else 0}")
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(cuerpo.size)
                c.outputStream.use { it.write(cuerpo) }
                if (c.responseCode != 202) {
                    val msg = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                    throw IllegalStateException(msg ?: "el celular respondió ${c.responseCode}")
                }
                c.disconnect()
                // Seguir el avance
                var fallos = 0
                while (true) {
                    Thread.sleep(1_500)
                    val j = try {
                        JSONObject(abrir("$base/estado", 8_000).inputStream.bufferedReader().use { it.readText() }).also { fallos = 0 }
                    } catch (e: Exception) {
                        if (++fallos > 20) throw IllegalStateException("se perdió la conexión con el celular")
                        continue
                    }
                    val estado = j.optString("estado")
                    val p = j.optInt("progreso")
                    val msg = j.optString("mensaje")
                    runOnUiThread {
                        expBarra.progress = p * 10
                        expEstado.setTextColor(when (estado) { "listo" -> Ui.verde; "error" -> Ui.rojo; else -> Ui.gris })
                        expEstado.text = msg
                    }
                    if (estado == "listo" || estado == "error") break
                }
            } catch (e: Exception) {
                runOnUiThread {
                    expBarra.visibility = View.GONE
                    expEstado.setTextColor(Ui.rojo)
                    expEstado.text = "No se pudo exportar: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                runOnUiThread { exportando = false; ui.activo(bExportar, ed.segmentos.isNotEmpty()) }
            }
        }.start()
    }
}
