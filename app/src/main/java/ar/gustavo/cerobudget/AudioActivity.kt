package ar.gustavo.cerobudget

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class AudioActivity : Activity() {

    private val fondo = Color.parseColor("#1A1A19")
    private val panel = Color.parseColor("#232322")
    private val linea = Color.parseColor("#3A3A38")
    private val tinta = Color.parseColor("#ECECE8")
    private val gris = Color.parseColor("#A3A39E")
    private val amarillo = Color.parseColor("#FFE600")
    private val rojo = Color.parseColor("#FF7A6B")

    private var video: Uri? = null
    private var nombre = "video"
    private var resultado: Uri? = null
    private var ocupado = false

    private lateinit var info: TextView
    private lateinit var grupo: RadioGroup
    private lateinit var botonIr: Button
    private lateinit var barra: ProgressBar
    private lateinit var estado: TextView
    private lateinit var botonVer: Button
    private lateinit var botonElegir: Button

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun texto(t: String, sp: Float, color: Int, negrita: Boolean = false) = TextView(this).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (negrita) typeface = Typeface.DEFAULT_BOLD
    }

    private fun boton(t: String, principal: Boolean) = Button(this).apply {
        text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(56)
        setTextColor(if (principal) fondo else tinta)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (principal) setColor(amarillo) else { setColor(Color.TRANSPARENT); setStroke(dp(1), linea) }
        }
    }

    private fun bloque(titulo: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(panel); setStroke(dp(1), linea) }
        addView(texto(titulo, 18f, tinta, true).apply { setPadding(0, 0, 0, dp(10)) })
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(fondo); isFillViewport = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(40)) }
        scroll.addView(col)

        col.addView(texto("Mejorar audio", 30f, tinta, true))
        col.addView(texto("Mejorar el audio de un video: saca ruido y eco de la sala. La imagen se copia sin tocar.", 16f, gris).apply { setPadding(0, dp(4), 0, dp(20)) })

        val b1 = bloque("1  Elegí el video")
        botonElegir = boton("Elegir video", false)
        b1.addView(botonElegir, lp())
        info = texto("", 15f, gris).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        b1.addView(info, lp())
        col.addView(b1, lp())

        val b2 = bloque("2  Cuánto filtrar")
        grupo = RadioGroup(this)
        val opciones = listOf(
            "normal" to "Normal · saca ruido y algo de eco",
            "fuerte" to "Fuerte · saca ruido y bastante eco",
            "maxima" to "Máxima · filtra todo: ruido, eco y colas entre frases"
        )
        opciones.forEachIndexed { i, (clave, etiqueta) ->
            grupo.addView(RadioButton(this).apply {
                id = 100 + i; tag = clave; text = etiqueta
                setTextColor(tinta); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                buttonTintList = ColorStateList.valueOf(amarillo)
                minHeight = dp(48)
            })
        }
        grupo.check(102)
        b2.addView(grupo, lp())
        col.addView(b2, lp(12))

        val b3 = bloque("3  Procesar")
        botonIr = boton("Mejorar audio", true).apply { isEnabled = false; alpha = 0.4f }
        b3.addView(botonIr, lp())
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(amarillo); visibility = View.GONE
        }
        b3.addView(barra, lp(14))
        estado = texto("", 15f, gris).apply { setPadding(0, dp(8), 0, 0) }
        b3.addView(estado, lp())
        botonVer = boton("Ver el video mejorado", false).apply { visibility = View.GONE }
        b3.addView(botonVer, lp(12))
        col.addView(b3, lp(12))

        col.addView(texto("Los videos mejorados se guardan en la carpeta Movies/0-Presupuesto-Editor del equipo.", 14f, gris).apply {
            setPadding(0, dp(16), 0, 0); gravity = Gravity.START
        })


        setContentView(scroll)

        botonElegir.setOnClickListener {
            if (ocupado) return@setOnClickListener
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*" }
            startActivityForResult(i, 7)
        }
        botonIr.setOnClickListener { iniciar() }
        botonVer.setOnClickListener {
            val u = resultado ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) { estado.text = "No hay una app para reproducir el video. Buscalo en Movies/0-Presupuesto-Editor." }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val u = data?.data ?: return
        video = u
        var tam = 0L
        nombre = "video"
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val iS = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iN >= 0) nombre = c.getString(iN) ?: "video"
                    if (iS >= 0 && !c.isNull(iS)) tam = c.getLong(iS)
                }
            }
        } catch (e: Exception) { }
        val mb = String.format(Locale("es", "AR"), "%,d MB", tam / 1_048_576)
        info.text = "$nombre · $mb"
        info.setTextColor(tinta)
        info.visibility = View.VISIBLE
        botonIr.isEnabled = true; botonIr.alpha = 1f
        botonVer.visibility = View.GONE
        estado.text = ""
    }

    private fun iniciar() {
        val u = video ?: return
        if (ocupado) return
        ocupado = true
        val intensidad = findViewById<RadioButton>(grupo.checkedRadioButtonId)?.tag as? String ?: "maxima"
        botonIr.isEnabled = false; botonIr.alpha = 0.4f; botonElegir.isEnabled = false
        botonVer.visibility = View.GONE
        barra.visibility = View.VISIBLE; barra.progress = 0
        estado.setTextColor(gris)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            try {
                val r = Procesador(applicationContext).procesar(u, nombre, intensidad) { p, msg ->
                    runOnUiThread { barra.progress = (p * 1000).toInt(); estado.text = msg }
                }
                runOnUiThread {
                    resultado = r.uri
                    barra.progress = 1000
                    estado.setTextColor(tinta)
                    estado.text = "Listo. Guardado como ${r.nombre} en Movies/0-Presupuesto-Editor.\n" +
                        "Distancia voz / ruido de fondo: ${r.antes.toInt()} → ${r.despues.toInt()} dB\n" +
                        "Volumen final: ${String.format(Locale("es", "AR"), "%.1f", r.lufs)} LUFS (estándar para redes: −14)"
                    botonVer.visibility = View.VISIBLE
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    estado.setTextColor(rojo)
                    estado.text = "No se pudo: " + (e.message ?: e.toString())
                }
            } finally {
                runOnUiThread {
                    ocupado = false
                    botonIr.isEnabled = true; botonIr.alpha = 1f; botonElegir.isEnabled = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }.start()
    }
}
