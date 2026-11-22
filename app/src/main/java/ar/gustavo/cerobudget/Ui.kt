package ar.gustavo.cerobudget

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Colores y piezas de interfaz comunes (siempre modo oscuro). */
class Ui(private val a: Activity) {

    companion object {
        val fondo = Color.parseColor("#1A1A19")
        val panel = Color.parseColor("#232322")
        val linea = Color.parseColor("#3A3A38")
        val tinta = Color.parseColor("#ECECE8")
        val gris = Color.parseColor("#A3A39E")
        val amarillo = Color.parseColor("#FFE600")
        val rojo = Color.parseColor("#FF7A6B")
        val verde = Color.parseColor("#5FD38A")
        val azul = Color.parseColor("#3D5A80")
    }

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()

    fun texto(t: String, sp: Float, color: Int, negrita: Boolean = false) = TextView(a).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (negrita) typeface = Typeface.DEFAULT_BOLD
    }

    fun fondoBoton(principal: Boolean, borde: Int = linea) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        if (principal) setColor(amarillo) else { setColor(Color.TRANSPARENT); setStroke(dp(1), borde) }
    }

    fun boton(t: String, principal: Boolean, sp: Float = 16f) = Button(a).apply {
        text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
        setTextColor(if (principal) fondo else tinta)
        background = fondoBoton(principal)
        isFocusable = false
    }

    /** Botón de opción: amarillo cuando está elegido. */
    fun marcar(b: Button, elegido: Boolean) {
        b.setTextColor(if (elegido) fondo else tinta)
        b.background = fondoBoton(elegido)
    }

    fun activo(b: View, si: Boolean) { b.isEnabled = si; b.alpha = if (si) 1f else 0.4f }

    fun bloque(titulo: String): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(panel); setStroke(dp(1), linea) }
        addView(texto(titulo, 17f, tinta, true).apply { setPadding(0, 0, 0, dp(8)) })
    }

    fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    fun peso(w: Float, margen: Int = 3) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { leftMargin = dp(margen); rightMargin = dp(margen) }

    fun fila() = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }

    fun barra() = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = ColorStateList.valueOf(amarillo); visibility = View.GONE
    }

    fun columnaConScroll(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(a).apply { setBackgroundColor(fondo); isFillViewport = true }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(40)) }
        scroll.addView(col)
        return scroll to col
    }
}
