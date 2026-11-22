package ar.gustavo.cerobudget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Pantalla del CELULAR. Solo muestra y recibe órdenes: el trabajo lo hace MotorCelular dentro de
 * ServicioCelular, que sigue andando aunque esta pantalla se cierre o se use otra app.
 */
class CelularActivity : Activity() {

    private val ui = Ui(this)

    private lateinit var lista: LinearLayout
    private lateinit var direccion: TextView
    private lateinit var aviso: TextView
    private lateinit var expTitulo: TextView
    private lateinit var expBarra: ProgressBar
    private lateinit var expEstado: TextView
    private lateinit var expVer: Button
    private lateinit var bApagar: Button

    private val alCambiar: () -> Unit = { refrescar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Ui.fondo)
        armar()
        ServicioCelular.arrancar(this)
        pedirPermisos()
    }

    override fun onResume() {
        super.onResume()
        MotorCelular.escuchar(alCambiar)
        refrescar()
    }

    override fun onPause() {
        super.onPause()
        MotorCelular.dejarDeEscuchar(alCambiar)
    }

    /** Notificaciones (para ver el avance) y que el ahorro de batería no corte el trabajo. */
    private fun pedirPermisos() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        try {
            val pm = getSystemService(PowerManager::class.java)
            val prefs = getSharedPreferences("celular", MODE_PRIVATE)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("pidioBateria", false)) {
                prefs.edit().putBoolean("pidioBateria", true).apply()
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) { }
    }

    private fun armar() {
        val (scroll, col) = ui.columnaConScroll()
        col.setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(40))
        col.addView(ui.texto("Celular", 28f, Ui.tinta, true))
        col.addView(ui.texto("Acá quedan los videos en 4K. El celular trabaja en segundo plano: podés usar otras apps o apagar la pantalla. El avance se ve en la notificación.", 15f, Ui.gris).apply { setPadding(0, ui.dp(2), 0, ui.dp(12)) })

        val b0 = ui.bloque("Dirección para la tablet")
        direccion = ui.texto("—", 30f, Ui.amarillo, true).apply { setTextIsSelectable(true) }
        b0.addView(direccion, ui.lp())
        aviso = ui.texto("", 14f, Ui.gris).apply { setPadding(0, ui.dp(4), 0, 0) }
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

        bApagar = ui.boton("Apagar el modo celular", false)
        col.addView(bApagar, ui.lp(18))
        col.addView(ui.texto("Mientras esté prendido, la tablet puede conectarse cuando quiera. Apagalo cuando termines de editar.", 13f, Ui.gris).apply { setPadding(0, ui.dp(6), 0, 0) }, ui.lp())

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
        bApagar.setOnClickListener {
            if (MotorCelular.iniciado) { ServicioCelular.apagar(this); aviso.setTextColor(Ui.gris); aviso.text = "Modo celular apagado. Tocá de nuevo para prenderlo." }
            else { ServicioCelular.arrancar(this) }
            bApagar.postDelayed({ refrescar() }, 600)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        val cd = data.clipData
        if (cd != null) for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri
        else data.data?.let { uris += it }
        if (!MotorCelular.iniciado) ServicioCelular.arrancar(this)
        // El servicio puede tardar un instante en arrancar: se agrega cuando esté listo
        fun intentar(veces: Int) {
            if (MotorCelular.iniciado) {
                val errores = MotorCelular.agregar(uris)
                if (errores > 0) { aviso.setTextColor(Ui.rojo); aviso.text = "No pude leer $errores de los videos elegidos." }
            } else if (veces > 0) lista.postDelayed({ intentar(veces - 1) }, 300)
        }
        intentar(20)
    }

    private fun refrescar() {
        if (!::lista.isInitialized) return
        val ips = Datos.ips()
        direccion.text = if (ips.isEmpty()) "Sin Wi-Fi" else ips.first()
        bApagar.text = if (MotorCelular.iniciado) "Apagar el modo celular" else "Prender el modo celular"
        when {
            MotorCelular.errorServidor.isNotEmpty() -> { aviso.setTextColor(Ui.rojo); aviso.text = MotorCelular.errorServidor }
            MotorCelular.avisoEnvio.isNotEmpty() -> { aviso.setTextColor(Ui.tinta); aviso.text = MotorCelular.avisoEnvio }
            MotorCelular.iniciado -> { aviso.setTextColor(Ui.gris); aviso.text = "Los dos equipos en el mismo Wi-Fi." }
        }

        lista.removeAllViews()
        val copia = MotorCelular.copiaClips()
        if (copia.isEmpty()) lista.addView(ui.texto("Todavía no agregaste videos.", 15f, Ui.gris), ui.lp())
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
            q.setOnClickListener { MotorCelular.quitar(c.id) }
            fila.addView(q)
            lista.addView(fila, ui.lp())
        }

        val e = MotorCelular.estadoFinal
        when (e.optString("estado")) {
            "renderizando" -> {
                expTitulo.setTextColor(Ui.tinta); expTitulo.text = "Exportando. Podés usar otras apps: el avance sigue en la notificación."
                expBarra.visibility = View.VISIBLE; expBarra.progress = e.optInt("progreso") * 10
                expEstado.setTextColor(Ui.gris); expEstado.text = e.optString("mensaje")
                expVer.visibility = View.GONE
            }
            "listo" -> {
                expBarra.visibility = View.VISIBLE; expBarra.progress = 1000
                expEstado.setTextColor(Ui.verde); expEstado.text = e.optString("mensaje")
                expVer.visibility = View.VISIBLE
            }
            "error" -> {
                expBarra.visibility = View.GONE
                expEstado.setTextColor(Ui.rojo); expEstado.text = e.optString("mensaje")
            }
        }
    }

    private fun verFinal() {
        val u = MotorCelular.uriFinal
        if (u == null) { expEstado.text = expEstado.text.toString() + "\nBuscalo en la galería, en la carpeta 0-Presupuesto-Editor."; return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            expEstado.text = expEstado.text.toString() + "\nNo pude abrirlo desde acá: buscalo en la galería."
        }
    }
}
