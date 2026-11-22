package ar.gustavo.cerobudget

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Servidor mínimo del celular, dentro de la red Wi-Fi.
 * La tablet le pide la lista de clips, baja los proxies, le manda la música y la edición para exportar.
 */
class Servidor(private val rutas: Rutas) {

    companion object { const val PUERTO = 8766 }

    class Respuesta(val codigo: Int, val tipo: String, val cuerpo: ByteArray? = null, val archivo: File? = null) {
        companion object {
            fun json(s: String, codigo: Int = 200) = Respuesta(codigo, "application/json; charset=utf-8", s.toByteArray(Charsets.UTF_8))
            fun texto(s: String, codigo: Int) = Respuesta(codigo, "text/plain; charset=utf-8", s.toByteArray(Charsets.UTF_8))
        }
    }

    interface Rutas {
        fun get(ruta: String, params: Map<String, String>): Respuesta
        fun post(ruta: String, params: Map<String, String>, cuerpo: InputStream, largo: Long): Respuesta
        fun enviando(archivo: File, enviados: Long, total: Long) {}
    }

    @Volatile private var socket: ServerSocket? = null
    val activo: Boolean get() = socket?.isClosed == false

    fun iniciar() {
        if (activo) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(PUERTO))
        socket = s
        Thread {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                Thread { atender(c) }.start()
            }
        }.start()
    }

    fun detener() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun atender(c: Socket) {
        try {
            c.soTimeout = 60_000
            c.sendBufferSize = 1 shl 20
            val entrada = BufferedInputStream(c.getInputStream())
            val pedido = leerLinea(entrada)
            var largo = 0L
            while (true) {
                val l = leerLinea(entrada)
                if (l.isEmpty()) break
                if (l.startsWith("content-length:", ignoreCase = true)) largo = l.substringAfter(':').trim().toLongOrNull() ?: 0L
            }
            val partes = pedido.split(" ")
            val metodo = partes.getOrNull(0) ?: "GET"
            val completa = partes.getOrNull(1) ?: "/"
            val ruta = completa.substringBefore('?')
            val params = completa.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val r = try {
                if (metodo == "POST") rutas.post(ruta, params, LimiteStream(entrada, largo), largo) else rutas.get(ruta, params)
            } catch (e: Exception) {
                Respuesta.texto(e.message ?: e.javaClass.simpleName, 500)
            }
            val salida = c.getOutputStream()
            val f = r.archivo
            if (f != null) {
                val total = f.length()
                cabecera(salida, r.codigo, r.tipo, total)
                var enviados = 0L
                f.inputStream().use { inp ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        salida.write(buf, 0, n)
                        enviados += n
                        rutas.enviando(f, enviados, total)
                    }
                }
            } else {
                val cuerpo = r.cuerpo ?: ByteArray(0)
                cabecera(salida, r.codigo, r.tipo, cuerpo.size.toLong())
                salida.write(cuerpo)
            }
            salida.flush()
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) { }
        }
    }

    private fun cabecera(o: OutputStream, codigo: Int, tipo: String, largo: Long) {
        val estado = when (codigo) { 200 -> "OK"; 202 -> "Accepted"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error" }
        o.write(("HTTP/1.1 $codigo $estado\r\nContent-Type: $tipo\r\nContent-Length: $largo\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
    }

    private fun leerLinea(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    /** Lee como máximo [largo] bytes del cuerpo del pedido. */
    private class LimiteStream(private val base: InputStream, private var quedan: Long) : InputStream() {
        override fun read(): Int {
            if (quedan <= 0) return -1
            val b = base.read()
            if (b >= 0) quedan--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (quedan <= 0) return -1
            val n = base.read(b, off, minOf(len.toLong(), quedan).toInt())
            if (n > 0) quedan -= n
            return n
        }
    }
}
