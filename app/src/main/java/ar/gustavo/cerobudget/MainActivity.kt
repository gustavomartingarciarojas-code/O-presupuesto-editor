package ar.gustavo.cerobudget

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/** Pantalla de inicio: elegir qué hace este equipo. */
class MainActivity : Activity() {

    private val ui = Ui(this)
    private var ocupado = false
    private lateinit var botonActualizar: Button
    private lateinit var estadoAct: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Ui.fondo)
        val (scroll, col) = ui.columnaConScroll()
        col.setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(40))
        col.addView(ui.texto("0 Presupuesto Editor", 30f, Ui.tinta, true))
        col.addView(ui.texto("¿Qué hace este equipo?", 16f, Ui.gris).apply { setPadding(0, ui.dp(4), 0, ui.dp(18)) })

        val bCel = ui.boton("Este es el CELULAR", true, 18f)
        col.addView(bCel, ui.lp())
        col.addView(ui.texto("Guarda los videos en 4K, arma las copias livianas y el video final.", 14f, Ui.gris).apply { setPadding(0, ui.dp(6), 0, 0) }, ui.lp())

        val bTab = ui.boton("Esta es la TABLET", true, 18f)
        col.addView(bTab, ui.lp(18))
        col.addView(ui.texto("Acá se edita: cortar, dividir, duplicar, velocidad, volumen, música y formato.", 14f, Ui.gris).apply { setPadding(0, ui.dp(6), 0, 0) }, ui.lp())

        val bAudio = ui.boton("Mejorar audio de un video", false)
        col.addView(bAudio, ui.lp(28))

        botonActualizar = ui.boton("Buscar actualización", false)
        col.addView(botonActualizar, ui.lp(28))
        estadoAct = ui.texto("Versión instalada: " + Actualizador.versionInstalada(this), 14f, Ui.gris).apply { setPadding(0, ui.dp(8), 0, 0) }
        col.addView(estadoAct, ui.lp())

        setContentView(scroll)

        bCel.setOnClickListener { recordar("celular"); startActivity(Intent(this, CelularActivity::class.java)) }
        bTab.setOnClickListener { recordar("tablet"); startActivity(Intent(this, TabletActivity::class.java)) }
        bAudio.setOnClickListener { startActivity(Intent(this, AudioActivity::class.java)) }
        botonActualizar.setOnClickListener { actualizar() }

        // Si ya se eligió antes, abre directo ese modo (una sola vez por arranque)
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) {
            when (getSharedPreferences("inicio", MODE_PRIVATE).getString("modo", null)) {
                "celular" -> startActivity(Intent(this, CelularActivity::class.java))
                "tablet" -> startActivity(Intent(this, TabletActivity::class.java))
            }
        }
    }

    private fun recordar(m: String) = getSharedPreferences("inicio", MODE_PRIVATE).edit().putString("modo", m).apply()

    private fun actualizar() {
        if (ocupado) return
        ocupado = true
        botonActualizar.isEnabled = false
        estadoAct.setTextColor(Ui.gris)
        estadoAct.text = "Buscando versión nueva…"
        Thread {
            try {
                val info = Actualizador.consultar() ?: throw IllegalStateException("No encontré la versión en GitHub.")
                val actual = Actualizador.versionInstalada(this)
                if (info.version <= actual) {
                    runOnUiThread { estadoAct.text = "Ya tenés la última versión ($actual)." }
                } else {
                    runOnUiThread { estadoAct.text = "Bajando la versión ${info.version}…" }
                    Actualizador.instalar(applicationContext, info.url) { p ->
                        runOnUiThread { estadoAct.text = "Bajando la versión ${info.version}… ${(p * 100).toInt()} %" }
                    }
                    runOnUiThread { estadoAct.text = "Confirmá la actualización en el cuadro de Android." }
                }
            } catch (e: Exception) {
                runOnUiThread { estadoAct.setTextColor(Ui.rojo); estadoAct.text = "No se pudo actualizar: " + (e.message ?: e.toString()) }
            } finally {
                runOnUiThread { ocupado = false; botonActualizar.isEnabled = true }
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
            PackageInstaller.STATUS_SUCCESS -> estadoAct.text = "Actualizada."
            else -> {
                estadoAct.setTextColor(Ui.rojo)
                estadoAct.text = "No se instaló: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelado")
            }
        }
    }
}
