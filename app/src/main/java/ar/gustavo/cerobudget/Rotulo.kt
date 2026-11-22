package ar.gustavo.cerobudget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
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

    val EMOJIS = listOf(
        "🔥", "⭐", "✅", "❌", "⚠️", "👉", "👈", "👆", "👇", "👍", "👏", "💪",
        "😎", "😂", "😍", "🤯", "😱", "🙌", "🚗", "🏎️", "🛠️", "🎨", "✨", "💯",
        "❤️", "💥", "📌", "📍", "🆕", "🔝", "🎯", "📢"
    )

    /** Dibuja un sticker de imagen: alto = tam del cuadro, sin pasarse del ancho. */
    private fun imagen(t: Texto, ancho: Int, alto: Int, archivo: File?): Bitmap? {
        if (archivo == null || !archivo.exists()) return null
        val opc = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(archivo.path, opc)
        if (opc.outWidth <= 0 || opc.outHeight <= 0) return null
        var h = (t.tam * alto).toInt().coerceAtLeast(8)
        var w = (opc.outWidth.toLong() * h / opc.outHeight).toInt()
        val maxW = (ancho * 0.95f).toInt()
        if (w > maxW) { h = (h.toLong() * maxW / w).toInt(); w = maxW }
        var muestra = 1
        while (opc.outWidth / (muestra * 2) >= w && opc.outHeight / (muestra * 2) >= h) muestra *= 2
        val crudo = BitmapFactory.decodeFile(archivo.path, BitmapFactory.Options().apply { inSampleSize = muestra }) ?: return null
        return Bitmap.createScaledBitmap(crudo, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
    }

    /**
     * Máscara de un tramo: negro alrededor y la forma transparente, con el borde difuminado.
     * Se dibuja a la mitad del tamaño y se agranda al doble al usarla (ahorra memoria en 4K).
     */
    fun mascara(forma: String, tam: Float, suave: Float, ancho: Int, alto: Int): Bitmap? {
        if (forma.isEmpty() || ancho <= 1 || alto <= 1) return null
        val w = ancho; val h = alto
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            val radio = suave * minOf(w, h) * 0.5f
            if (radio >= 1f) maskFilter = BlurMaskFilter(radio, BlurMaskFilter.Blur.NORMAL)
        }
        val cx = w / 2f; val cy = h / 2f
        when (forma) {
            "circulo" -> c.drawCircle(cx, cy, tam * minOf(w, h) / 2f, p)
            "ovalo" -> c.drawOval(RectF(cx - tam * w / 2f, cy - tam * h / 2f, cx + tam * w / 2f, cy + tam * h / 2f), p)
            else -> {
                val r = RectF(cx - tam * w / 2f, cy - tam * h / 2f, cx + tam * w / 2f, cy + tam * h / 2f)
                c.drawRoundRect(r, minOf(w, h) * 0.04f, minOf(w, h) * 0.04f, p)
            }
        }
        return bmp
    }

    /** Imagen del texto para un cuadro de [ancho]×[alto] píxeles. Devuelve null si el texto está vacío. */
    fun bitmap(ctx: Context, t: Texto, ancho: Int, alto: Int, archivoImagen: (String) -> File? = { null }): Bitmap? {
        if (t.tipo == "imagen") return imagen(t, ancho, alto, archivoImagen(t.texto))
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
            else -> if (t.tipo != "emoji") paint.setShadowLayer(tam * 0.06f, 0f, tam * 0.03f, Color.argb(160, 0, 0, 0))
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
