package ar.gustavo.cerobudget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil

/**
 * Dibuja un texto como imagen. La misma función sirve para la vista previa de la tablet
 * y para el video final del celular, así se ve igual en los dos.
 */
object Rotulo {

    /** Fuentes incluidas en la app (licencias libres OFL y Apache, en assets/fuentes). */
    val FUENTES = listOf(
        "Poppins-Bold" to "Poppins Negrita",
        "Poppins-Regular" to "Poppins",
        "Anton-Regular" to "Anton",
        "BebasNeue-Regular" to "Bebas Neue",
        "Bangers-Regular" to "Bangers",
        "PermanentMarker-Regular" to "Marcador",
        "Lobster-Regular" to "Lobster",
        "Sistema" to "Sistema"
    )

    val COLORES = listOf("#FFFFFF", "#111111", "#FFE600", "#FF3B30", "#34C3FF", "#3DDC84", "#FF8A00", "#E040FB")

    private val cache = mutableMapOf<String, Typeface>()

    fun fuente(ctx: Context, clave: String): Typeface = synchronized(cache) {
        cache.getOrPut(clave) {
            if (clave == "Sistema") Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            else try { Typeface.createFromAsset(ctx.assets, "fuentes/$clave.ttf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        }
    }

    /** Imagen del texto para un cuadro de [ancho]×[alto] píxeles. Devuelve null si el texto está vacío. */
    fun bitmap(ctx: Context, t: Texto, ancho: Int, alto: Int): Bitmap? {
        if (t.texto.isBlank() || ancho <= 0 || alto <= 0) return null
        val tam = (t.tam * alto).coerceAtLeast(6f)
        val color = try { Color.parseColor(t.color) } catch (_: Exception) { Color.WHITE }
        val oscuro = Color.luminance(color) < 0.4f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = tam
            typeface = fuente(ctx, t.fuente)
            this.color = color
        }
        val maxAncho = (ancho * 0.92f).toInt().coerceAtLeast(10)
        val medida = armar(t.texto, paint, maxAncho)
        var real = 0f
        for (i in 0 until medida.lineCount) real = maxOf(real, medida.getLineWidth(i))
        val anchoTexto = ceil(real).toInt().coerceIn(1, maxAncho)
        val layout = armar(t.texto, paint, anchoTexto)

        val pad = when (t.estilo) { "caja" -> (tam * 0.35f).toInt(); else -> (tam * 0.12f).toInt() + 2 }
        val w = anchoTexto + pad * 2
        val h = layout.height + pad * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (t.estilo) {
            "caja" -> {
                val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = if (oscuro) Color.argb(210, 255, 255, 255) else Color.argb(190, 0, 0, 0) }
                c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), tam * 0.25f, tam * 0.25f, fondo)
            }
            "contorno" -> {
                // Primero el borde, después el relleno encima
                val borde = TextPaint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = tam * 0.12f
                    strokeJoin = Paint.Join.ROUND
                    this.color = if (oscuro) Color.WHITE else Color.BLACK
                }
                c.save(); c.translate(pad.toFloat(), pad.toFloat())
                armar(t.texto, borde, anchoTexto).draw(c)
                c.restore()
            }
            else -> paint.setShadowLayer(tam * 0.06f, 0f, tam * 0.03f, Color.argb(160, 0, 0, 0))
        }
        c.save(); c.translate(pad.toFloat(), pad.toFloat())
        layout.draw(c)
        c.restore()
        return bmp
    }

    private fun armar(texto: String, paint: TextPaint, ancho: Int): StaticLayout =
        StaticLayout.Builder.obtain(texto, 0, texto.length, paint, ancho)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()
}
