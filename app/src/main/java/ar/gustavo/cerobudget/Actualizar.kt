package ar.gustavo.cerobudget

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Botón "Buscar actualización" que se puede poner en cualquier pantalla. */
object Actualizar {

    /**
     * Barra fija de arriba (tablet): versión instalada y botón Actualizar siempre a la vista.
     * Al abrir, se fija sola si hay una versión nueva; si la hay, el botón se pinta de amarillo.
     */
    fun barra(a: Activity, ui: Ui): LinearLayout {
        val fila = ui.fila().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val actual = Actualizador.versionInstalada(a)
        val estado: TextView = ui.texto("0 Presupuesto Editor · versión $actual", 13f, Ui.gris).apply { maxLines = 1 }
        fila.addView(estado, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val b: Button = ui.boton("Actualizar", false, 13f).apply { minHeight = ui.dp(36); minimumHeight = ui.dp(36); isFocusable = false }
        fila.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(38)))
        fun instalar() {
            b.isEnabled = false
            estado.setTextColor(Ui.gris); estado.text = "Buscando versión nueva…"
            Thread {
                try {
                    val info = Actualizador.consultar() ?: throw IllegalStateException("no encontré la versión en GitHub")
                    if (info.version <= actual) {
                        a.runOnUiThread { estado.text = "Ya tenés la última versión ($actual)." }
                    } else {
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
        b.setOnClickListener { instalar() }
        // Aviso automático: si hay una versión nueva, el botón queda amarillo y dice a cuál
        Thread {
            try {
                val info = Actualizador.consultar() ?: return@Thread
                if (info.version > actual) a.runOnUiThread {
                    ui.marcar(b, true)
                    b.text = "Actualizar a la ${info.version}"
                    estado.setTextColor(Ui.tinta)
                    estado.text = "Versión $actual · hay una nueva"
                }
            } catch (_: Exception) { }
        }.start()
        return fila
    }

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
