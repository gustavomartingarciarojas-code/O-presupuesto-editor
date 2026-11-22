package ar.gustavo.cerobudget

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Botón "Buscar actualización" que se puede poner en cualquier pantalla. */
object Actualizar {

    fun agregar(a: Activity, ui: Ui, col: LinearLayout) {
        val b: Button = ui.boton("Buscar actualización", false)
        col.addView(b, ui.lp(24))
        val estado: TextView = ui.texto("Versión instalada: " + Actualizador.versionInstalada(a), 14f, Ui.gris).apply { setPadding(0, ui.dp(8), 0, 0) }
        col.addView(estado, ui.lp())
        b.setOnClickListener {
            b.isEnabled = false
            estado.setTextColor(Ui.gris)
            estado.text = "Buscando versión nueva…"
            Thread {
                try {
                    val info = Actualizador.consultar() ?: throw IllegalStateException("No encontré la versión en GitHub.")
                    val actual = Actualizador.versionInstalada(a)
                    if (info.version <= actual) {
                        a.runOnUiThread { estado.text = "Ya tenés la última versión ($actual)." }
                    } else {
                        a.runOnUiThread { estado.text = "Bajando la versión ${info.version}…" }
                        Actualizador.instalar(a.applicationContext, info.url) { p ->
                            a.runOnUiThread { estado.text = "Bajando la versión ${info.version}… ${(p * 100).toInt()} %" }
                        }
                        a.runOnUiThread { estado.text = "Confirmá la actualización en el cuadro de Android." }
                    }
                } catch (e: Exception) {
                    a.runOnUiThread { estado.setTextColor(Ui.rojo); estado.text = "No se pudo actualizar: " + (e.message ?: e.toString()) }
                } finally {
                    a.runOnUiThread { b.isEnabled = true }
                }
            }.start()
        }
    }
}
