package ar.gustavo.cerobudget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs

/**
 * Caja de selección del texto o sticker elegido sobre el video, con 8 nodos (manijas):
 * las 4 esquinas escalan parejo y las 4 del medio de cada lado estiran solo el ancho o solo el alto.
 * Solo dibuja; los toques los maneja el editor.
 */
@SuppressLint("ViewConstructor")
class Manijas(ctx: Context, private val ui: Ui) : View(ctx) {

    /** Nodos: esquinas y lados. */
    enum class Nodo { SUP_IZQ, SUP_DER, INF_IZQ, INF_DER, IZQ, DER, SUP, INF }

    /** Caja actual en píxeles de la vista previa, o null si no hay nada elegido. */
    var caja: RectF? = null
        set(v) { if (v != field) { field = v?.let { RectF(it) }; invalidate() } }

    private val radio = ui.dp(7).toFloat()
    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = ui.dp(2).toFloat() * 0.75f }
    private val pNodo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pBordeNodo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#1A1A19"); strokeWidth = ui.dp(2).toFloat() * 0.75f }
    private val pLado = RectF()

    init { isClickable = false; isFocusable = false }

    /** Centro de cada nodo en la caja r. */
    fun punto(n: Nodo, r: RectF): Pair<Float, Float> = when (n) {
        Nodo.SUP_IZQ -> r.left to r.top
        Nodo.SUP_DER -> r.right to r.top
        Nodo.INF_IZQ -> r.left to r.bottom
        Nodo.INF_DER -> r.right to r.bottom
        Nodo.IZQ -> r.left to r.centerY()
        Nodo.DER -> r.right to r.centerY()
        Nodo.SUP -> r.centerX() to r.top
        Nodo.INF -> r.centerX() to r.bottom
    }

    /** El nodo que está debajo del dedo o del mouse (con margen generoso), o null. */
    fun nodoEn(x: Float, y: Float): Nodo? {
        val r = caja ?: return null
        // Margen generoso, pero en stickers chicos se achica para que igual se pueda agarrar el centro y moverlo
        val margen = minOf(ui.dp(22).toFloat(), maxOf(ui.dp(10).toFloat(), minOf(r.width(), r.height()) / 3f))
        return Nodo.values().map { it to punto(it, r) }
            .filter { (_, p) -> abs(p.first - x) <= margen && abs(p.second - y) <= margen }
            .minByOrNull { (_, p) -> abs(p.first - x) + abs(p.second - y) }?.first
    }

    override fun onDraw(c: Canvas) {
        val r = caja ?: return
        c.drawRect(r, pLinea)
        for (n in Nodo.values()) {
            val (x, y) = punto(n, r)
            val esquina = n.ordinal < 4
            if (esquina) {
                c.drawCircle(x, y, radio, pNodo); c.drawCircle(x, y, radio, pBordeNodo)
            } else {
                // Los del medio son pastillas a lo largo del lado, para distinguirlos de las esquinas
                val largo = radio * 1.6f; val ancho = radio * 0.75f
                if (n == Nodo.IZQ || n == Nodo.DER) pLado.set(x - ancho, y - largo, x + ancho, y + largo)
                else pLado.set(x - largo, y - ancho, x + largo, y + ancho)
                c.drawRoundRect(pLado, ancho, ancho, pNodo); c.drawRoundRect(pLado, ancho, ancho, pBordeNodo)
            }
        }
    }
}

/**
 * Guía amarilla de la máscara del tramo elegido (como en CapCut): las líneas o la forma, girada,
 * y el punto del centro, que se arrastra para mover la máscara. Solo dibuja.
 */
@SuppressLint("ViewConstructor")
class GuiaMascara(ctx: Context, private val ui: Ui) : View(ctx) {

    /** Lo que se dibuja: forma, tamaño, centro (0 a 1) y ángulo. null = no se muestra. */
    var datos: Segmento? = null
        set(v) {
            val antes = field
            field = v
            if (antes?.mascara != v?.mascara || antes?.mTam != v?.mTam || antes?.mX != v?.mX || antes?.mY != v?.mY || antes?.mAng != v?.mAng) invalidate()
        }

    private val amarillo = Color.parseColor("#FFE600")
    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = amarillo; strokeWidth = ui.dp(2).toFloat() }
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ui.dp(14).toFloat(); setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val r = RectF()

    init { isClickable = false; isFocusable = false }

    fun centro(): Pair<Float, Float>? = datos?.let { it.mX * width to it.mY * height }

    /** ¿El dedo o el mouse está sobre el punto del centro? */
    fun tocaCentro(x: Float, y: Float): Boolean {
        val (cx, cy) = centro() ?: return false
        return abs(x - cx) <= ui.dp(26) && abs(y - cy) <= ui.dp(26)
    }

    override fun onDraw(c: Canvas) {
        val s = datos ?: return
        if (s.mascara.isEmpty() || width <= 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val cx = s.mX * w; val cy = s.mY * h
        val lejos = (w + h) * 2f
        c.save()
        c.rotate(s.mAng, cx, cy)
        when (s.mascara) {
            "horizontal" -> c.drawLine(cx - lejos, cy, cx + lejos, cy, pLinea)
            "reflejo" -> { val d = s.mTam * h / 2f; c.drawLine(cx - lejos, cy - d, cx + lejos, cy - d, pLinea); c.drawLine(cx - lejos, cy + d, cx + lejos, cy + d, pLinea) }
            "circulo" -> c.drawCircle(cx, cy, s.mTam * minOf(w, h) / 2f, pLinea)
            "ovalo" -> { r.set(cx - s.mTam * w / 2f, cy - s.mTam * h / 2f, cx + s.mTam * w / 2f, cy + s.mTam * h / 2f); c.drawOval(r, pLinea) }
            else -> { r.set(cx - s.mTam * w / 2f, cy - s.mTam * h / 2f, cx + s.mTam * w / 2f, cy + s.mTam * h / 2f); c.drawRect(r, pLinea) }
        }
        c.restore()
        c.drawCircle(cx, cy, ui.dp(8).toFloat(), pLinea)
        if (s.mAng != 0f) c.drawText("${s.mAng.toInt()}°", cx + ui.dp(14), cy - ui.dp(14), pTexto)
    }
}
