# 0 Presupuesto Editor

App propia de edición de video para Android (Motorola Edge 70 Fusion y Redmi Pad 2).

**Versión 0.2:** Mejorar audio. Elegís un video, la app saca el ruido (RNNoise, red neuronal de Xiph, licencia BSD), saca el eco de la sala, baja las colas entre frases, ecualiza la voz, empareja el volumen a −14 LUFS y guarda el mismo video (imagen sin tocar) en `Movies/0-Presupuesto-Editor`.

La APK se compila sola con GitHub Actions en cada cambio y queda en la versión **ultima** (Releases), archivo `0-Presupuesto-Editor.apk`.

**Versión 0.4 · Etapa 1 (edición por proxy):**
- **Celular:** agregás los videos crudos en 4K; arma una copia liviana (proxy 720p) de cada uno y se la pasa a la tablet por Wi-Fi. Al exportar, arma el video final en 4K con los originales.
- **Tablet:** editor con línea de tiempo: cortar (borrar), dividir, duplicar, mover tramos, velocidad (0,5× a 3×), volumen (0 a 200 %), música de fondo con volumen, formato (original, 9:16, 16:9, 1:1, 4:5), deshacer y exportar.
- Solo viaja la lista de edición (unos KB): los videos pesados no salen del celular.

**Versión 0.5 · Etapa 2a:** textos sobre el video (7 tipografías libres, tamaño, color, contorno o caja, posición arrastrando, cuándo aparece y cuándo se va) y transiciones entre tramos (fundido a negro y a blanco). Se ven en la tablet igual que en el video final.

**Versión 0.6 · Etapa 2b:** stickers (emojis), imágenes encima del video (por ejemplo un logo en PNG) y máscara por tramo (círculo, óvalo o rectángulo, con tamaño y borde difuminado).

**Versión 0.7 · Etapa 3a (keyframes):** zoom y encuadre por tramo, volumen del tramo, volumen de la música, y posición y tamaño de textos y stickers, animados entre keyframes (◆).

**Línea de tiempo tipo CapCut:** cabezal fijo en el centro, pistas propias para cada texto o sticker (con manijas de inicio y fin) y para la música, y los keyframes como diamantes sobre cada pista. El botón ◆ (junto a Deshacer) agrega o quita el keyframe en el cabezal para lo que esté elegido. Sobre el video: un dedo mueve, dos dedos agrandan o achican; si el elemento ya tiene keyframes, el cambio crea el siguiente solo.

**Caja con 8 nodos:** el texto o sticker elegido muestra su caja con 4 esquinas (escalan parejo) y 4 nodos en el medio de cada lado (estiran solo el ancho o solo el alto). Se usan con el dedo o con el mouse, y también quedan en los keyframes. Con el mouse, la ruedita acerca o aleja la línea de tiempo.

**Velocidad de 0,1× a 100×:** botones 0,1× · 1× · 2× · 5× · 10× · 100× y una barra para cualquier valor intermedio. Por encima de 8× la vista previa va sin sonido (límite del audio de Android); el video exportado lleva el audio acelerado.

**Máscaras nuevas:** Horizontal (se ve de un lado de la línea) y Reflejo (franja entre dos líneas), además de círculo, óvalo y rectángulo. Todas se mueven (punto amarillo en el video o barras), giran de −180° a +180°, se cierran del todo con tamaño 0 y tienen borde difuminado.

Próximas etapas: 3b filtros de audio en el editor · 3 keyframes y filtros de audio · 4 subtítulos, traducción y estabilización · 5 doblaje.
