# DUAL Motion — guía para IAs (y personas) que trabajan en este repositorio

Responde y comenta siempre **en español** (el dueño escribe en español, a menudo desde el teléfono).
Actualiza este archivo con cada cambio que mueva piezas.

## Qué es

App de **animación** para Android inspirada en la forma de trabajar de Macromedia (la animación de Flash 8 y el
dibujo vectorial de FreeHand), repartida en **dos teléfonos que funcionan como uno** (sistema ChronOS, carcasa 3D).
El nombre "Macromedia" o "Flash" **no aparece en la interfaz**: es la inspiración, no la marca.

| Papel | Teléfono | Qué muestra |
|---|---|---|
| **Principal** (`view`, arriba) | Redmi 9S | Herramientas, **línea de tiempo**, propiedades. La **vista previa** de la animación se reproduce aquí **a pantalla completa**. |
| **Extendido** (`edit`, abajo) | Poco M8 Pro | **La mesa de trabajo** (escenario): dibujar, seleccionar, mover, zoom con dos dedos. |
| **Un solo teléfono** (`solo`) | cualquiera | Todo junto: herramientas, línea de tiempo y la mesa debajo. |

Las dos pantallas son UNA: lo repartido se comporta como un solo elemento.

## Reglas que no se rompen

1. **Paquete `cl.dualmotion`**: no cambiarlo (los teléfonos perderían la app y su trabajo). Es una app aparte de
   ChronOS (`cl.mesadoble`), con su propia conexión entre teléfonos.
2. **Java, sin AndroidX ni librerías externas.** `minSdk 28`, `targetSdk 34`, AGP 8.5.2.
3. **Cada envío a `main` compila y publica** `DualMotion.apk` en Releases (`.github/workflows/build.yml`).
   Antes de subir: `git fetch` + `rebase` sobre `main`. Cambios solo en `.md` no publican.
4. La llave `app/debug.keystore` es fija (la misma de ChronOS) para que cada versión se instale encima.
5. Textos para el usuario en español de Chile, cortos. Comentarios del código en español.

## Mapa del código (`app/src/main/java/cl/dualmotion/`)

- `MotionActivity` — elige el papel (pref `role`), arma las pantallas, paneles, vista previa a pantalla completa,
  reproducción (`Choreographer`), menú, conexión, guardado automático (`files/actual.json`).
- `MDoc` — el documento: capas (0 = la de arriba) × fotogramas clave (`TreeMap`), formas `Shape` (nodos Bézier:
  x, y, manilla de entrada, manilla de salida), largo por capa (`len`). Órdenes `apply({op…})` y deshacer
  (cada paso guarda la capa tocada o el documento entero).
  Cada `Key` es como una instancia: sus formas se mueven juntas con `tx, ty, sx, sy, rot, alpha` alrededor de
  `px, py`. `tween` = interpolación de movimiento hasta el clave siguiente, con curva `ease` (Bézier de (0,0) a
  (1,1), puntos x1,y1,x2,y2) y `spin` giros extra. `MDoc.pose(L, f)` da lo que se ve en f (ya interpolado).
- `Files` — `.dmo` = ZIP con `documento.json` + `dmo.txt`; Guardar / Guardar como / Abrir con el selector de
  archivos de Android (SAF, permiso persistente; prefs `docUri`, `docName`). Al abrir se manda la orden `load`.
- `Render` — dibuja formas, capas con su pose (`Render.layer/pose/matrix`), fotogramas y papel cebolla; toques sobre formas (`hit`); suaviza trazos a mano alzada.
- `StageView` — la mesa: herramientas, zoom/encuadre con dos dedos, selección, subselección, pluma,
  transformación libre. Las herramientas de dibujo trabajan en coordenadas propias del clave (deshacen su pose).
- `PlayerBar` — barra de reproducción al estilo del control de video de ChronOS (fondo oscuro, botones redondos,
  ▶ rojo grande = vista previa a pantalla completa, barra de avance roja que mueve el cabezal con marcas en los
  claves de la capa actual, velocidad, repetir, papel cebolla). Va entre la línea de tiempo y la barra de
  herramientas de fotograma (● ○ ＋ − ↝ ✂).
- `TimelineView` — línea de tiempo dibujada a mano (nombres con ojo/candado/contorno, grilla, cabezal rojo).
- `State` — herramienta, colores, grosor, capa y fotograma actuales, papel cebolla (lo decide el principal).
- `Net` — conexión TCP por WiFi (puerto 47830) con anuncio UDP (47831); una sola fila de envío; latido cada 3 s.
- `LinkService` — servicio en primer plano (tipo connectedDevice) dueño de `Net`, para que la conexión siga viva
  con la pantalla en segundo plano. Atiende él mismo `moShow` (extendido: trae la mesa al frente), `moUpdate`,
  `moSnd`/`moSndReq`; lo demás va a la pantalla abierta (`attach`). `startOrNotify`: abre una pantalla desde
  segundo plano si hay permiso "mostrar sobre otras apps" (se pide en el extendido); si no, deja un aviso.
- Abrir desde segundo plano (Android 15): `LinkService.startOrNotify` pone antes una ventanita de 1 px (`blip`), porque
  el permiso "mostrar sobre otras apps" ya no basta sin una ventana visible. Además el manifiesto declara la meta-data
  `chronos.dual`: ChronOS (desde la versión de nov. 2026) abre DUAL Motion en el extendido cuando se abre en el principal.
- `Updater` — como en ChronOS: Releases de `edoloco/dual-motion`, descarga `DualMotion.apk`, `PackageInstaller`.
  "Actualizar los dos" manda `moUpdate`; el otro descarga e instala solo y responde con `moMsg`.
- `Sounds` — sonidos: original en `files/sonidos/id.ext`, PCM 16 bits estéreo 44,1 kHz en caché (`id.pcm`),
  picos cada 10 ms para la forma de onda, mezcla (`Plan` + `mix`) y `Player` (AudioTrack; la imagen sigue a lo que
  ya sonó). Traspaso: `moSnd {id,ext,i,n,d(base64)}` por la misma fila, antes de las órdenes que lo usan.
- `Export` — MP4: H.264 dibujando cada fotograma en la superficie del codificador + AAC con la mezcla; tiempos
  exactos n/fps; queda en Películas › DUAL Motion (MediaStore; en Android 9 archivo + permiso).
- `Ui` — cristal blanco con verde, panel naranja, botones de símbolo (nombre al mantener), íconos de herramientas.

## Sincronía (igual que DUAL-Draw)

- El **extendido es dueño del documento**: aplica cada orden con `doc.apply` y la reenvía `{t:"mo", op…}`.
  El principal aplica las mismas órdenes en el mismo orden (y así tiene la misma pila de deshacer).
- El principal **pide** órdenes: `{t:"moReq", op:{…}}`.
- Al conectarse, el extendido manda `moFull {doc}`; el principal manda su `moState`.
- `moState {tool, sc, fc, sw, l, f, on, ap?}`: `ap` = "s"/"f"/"w" aplica trazo/relleno/grosor a lo seleccionado.
- `moPlay {on, f}` y `moTick {f}` (cada 1 s): cada teléfono corre su propio reloj y se corrige.
- `moCmd {c}`: `delSel`, `front`, `back`, `fit` (la selección vive en la mesa). `moPick` (cuentagotas), `moSel {n}`.
- **Mesa visible**: el principal manda `moShow` al abrirse (o al conectarse) y `moHide` al irse a segundo plano
  (no si solo abrió un selector de archivos o un permiso: `keepStage`). El extendido responde `moVis {on}` y con
  `moHide` hace `moveTaskToBack`. El principal muestra "mesa a la vista / oculta (toca para mostrarla)".
- El sonido suena solo en el principal (o en solo), nunca en la mesa.
- Que la mesa aparezca en distintos teléfonos (Xiaomi bloquea abrir pantallas desde segundo plano): el principal
  pide `moShow` y lo repite 2 veces cada 2,5 s si no llega `moVis {on:true}`; el extendido siempre intenta
  `startActivity` y, si a los 1,5 s no se ve, deja un aviso con pantalla completa (`setFullScreenIntent`). La mesa
  puede salir sobre la pantalla de bloqueo (`setShowWhenLocked`, `setTurnScreenOn`). Al encender el extendido,
  `LinkService.Boot` deja la conexión escuchando. ☰ › Permisos para la mesa: en Xiaomi abre "Otros permisos"
  (ventanas emergentes en segundo plano, pantalla de bloqueo) e Inicio automático.
- Toques fantasma en la mesa (como en DUAL-Draw): la palma (toque > 20 mm) y los toques que empiezan a menos de
  4 dp del borde no hacen nada; con lápiz o pincel, un toque de menos de 120 ms sin mover no deja un punto.

### Órdenes del documento
`add {l,f,s}`, `set {l,f,is,ss}`, `del {l,f,is}`, `order {l,f,is,to}`, `key {l,f,blank?}` (F6/F7),
`frame {l,f}` (F5), `delFrame {l,f}`, `clearKey {l,f}`, `layer {l,name?,vis?,lock?,out?}`, `layerAdd {at}`,
`layerDel {l}`, `layerMove {l,to}`, `fps {v}`, `new`, `load {doc}`, `undo`, `redo`,
`xf {l,f,x?,y?,sx?,sy?,r?,a?,px?,py?}` (pose del clave), `tween {l,f,on}`, `ease {l,f,c:[x1,y1,x2,y2]}`, `spin {l,f,n}`,
`sndAdd {id,name,ext,dur}`, `layerAdd {at,audio:1}`, `clipAdd {l,c}`, `clipSet {l,i,c}`, `clipDel {l,i}`,
`clipSplit {l,i,t}` (clip `c = {s, at, in, len, v}` en ms). Las capas de sonido (`Layer.audio`) solo aceptan
órdenes `clip*` y `layer`; no alargan la animación (`length()` mira solo las de dibujo).
Dibujar en un fotograma después del final de la capa crea ahí un clave vacío (como en Flash).
En los intermedios se interpola la pose del clave **y también el contenido** (`MDoc.between`): si los dos claves
tienen la misma cantidad de formas y cada par es la misma (mismo símbolo o mismos nodos), se interpolan posición
(lo movido con Selección), nodos, grosor y colores. Queda en caché por capa y `MDoc.version`. La mesa toca lo que
se ve (`MDoc.shown`) y toda edición en un intermedio crea ahí un clave con lo interpolado (`editAt`), como en
Flash; la interpolación sigue a los dos lados y los símbolos siguen su ciclo donde iban.
Interpolación como en Flash 8: va del clave del tramo al **siguiente fotograma clave**, que es la referencia (lo
que se anima hacia él). En la línea de tiempo el tramo violeta incluye el clave final y la flecha sale del punto
del clave inicial y llega con la punta al punto del clave final (punteada si falta). `tween` sin clave final crea
uno al final del tramo (o 1 s después) y el principal lleva el cabezal ahí (`createTween`). `xf` o F6 en medio de una interpolación crean
ahí un clave con la pose interpolada.

## Herramientas (etapa 1)

Selección (mover, marco), Subselección (nodos y manillas), Transformación libre (todo el clave: dentro = mover,
manillas = escalar, por fuera de las esquinas = girar; escala y giro alrededor del centro), Pluma (tocar = recto, arrastrar = curva, primer punto
= cerrar, último punto o "✓ Terminar" = terminar), Línea, Rectángulo, Óvalo, Lápiz, Pincel (usa el color de
relleno), Cubo de pintura (como el balde de DUAL-Draw, pero vectorial; `Fill`): pinta lo que se ve en colores, toma la
zona del color tocado con **tolerancia** (`State.tol`, 0–120), cierra **huecos** (`State.gap`, px de la mesa:
engrosa las líneas, inunda y vuelve a crecer hasta la línea) y puede mirar solo esta capa o todas (`State.fillAll`).
La zona pasa a ser una forma de relleno sin trazo (con huecos por `Shape.sub`, par-impar) que **reemplaza** a los
rellenos que cubre (orden `fillArea {l,f,s,at,rm}`, un solo paso de deshacer): repintar una zona la cambia entera,
sin dejar borde. Si cubre justo un relleno, solo le cambia el color. Tocar sobre una línea no hace nada. Las
opciones aparecen en el panel derecho del principal al elegir el cubo.
Pincel (como en Flash pinta RELLENO: el trazo pasa a imagen y vuelve como forma de relleno sin línea, `Brush.paint`;
modos `State.brushMode`: Normal, Pintar rellenos = bajo las líneas, Pintar detrás, Pintar dentro = recortado al
relleno donde empieza; va con la orden `fillArea`), Borrador (borra por donde pasa, como un pincel, `Brush.erase`:
a los rellenos se les resta el trazo y las líneas se cortan donde pasa; modos `State.eraseMode`: Normal, Solo
rellenos, Solo líneas, Grifo = figura entera; orden `replace {l,f,rep:[{i,ss}]}`, un solo deshacer; no borra
símbolos), Bote de tinta, Cuentagotas, Borrador (borra formas enteras), Mano.

## Colores

- Tocar dos veces la muestra de trazo o relleno abre el **mezclador** (`ColorMixer`, como el de Flash): cuadro de
  saturación/brillo, barras de tono y transparencia, color anterior/nuevo, código hexadecimal (RRGGBB o AARRGGBB),
  "Muestras clásicas…" (los 216) y "＋ Mis colores".
- Paleta del panel (`Palette`): 14 colores editables + una fila de 7 "Mis colores" (casillas "+" vacías). Tocar =
  usar; mantener = editar / quitar / volver al original. Se guarda en el teléfono (pref `palette`).

## Imágenes

- Se eligen SIEMPRE en el principal (botón 🖼 de la barra de abajo o ☰ › Importar imagen; en el extendido solo
  avisa). `Images.importUri`: se achica a 2048 px de lado como máximo, se guarda como PNG (si tiene transparencia) o
  JPEG en `files/imagenes/id.ext`, viaja al extendido en trozos (`moImg`, `moImgReq` para pedir una que falta) antes
  de las órdenes `imgAdd {id,name,ext,w,h}` y `add` con la forma imagen.
- En el documento: biblioteca `MDoc.images` y objeto `Shape.img` (rectángulo n[0],n[1] – n[2],n[3]). Se mueve con
  Selección, se escala o gira con Transformación libre, se interpola, puede ir dentro de un símbolo y se guarda en
  el `.dmo` (`imagenes/`). Como en Flash, las herramientas de pintar no la tocan (`Shape.isObj()`: símbolos e
  imágenes): cubo, borrador, bote de tinta, cuentagotas y subselección la ignoran.

## Símbolos gráficos

- `MDoc.Symbol {id, name, layers}`: un dibujo con su propia línea de tiempo. En la escena se pone como
  **instancia**: una `Shape` con `sym` (id), posición en `n[0], n[1]`, `first` (fotograma con que empieza) y
  `loop` (0 repetir, 1 una vez, 2 un solo fotograma). La instancia muestra `MDoc.symFrame(inst, elapsed, largo)`,
  donde elapsed = fotogramas que lleva su clave (`Pose.frame - Pose.keyFrame`): así un mono que camina por dentro
  se repite en ciclo mientras la capa se interpola de izquierda a derecha. Al copiar un clave más adelante (F6 o
  `xf` en medio de una interpolación) las instancias avanzan `first` para no volver a empezar.
- `toSym {l,f,is,id}`: lo elegido pasa a un símbolo nuevo (centrado en su punto de registro) y queda una instancia.
- Toda orden de capas y fotogramas puede llevar `sym`: actúa sobre la línea de tiempo de ese símbolo
  (`MDoc.timeline(sym)`); dentro de un símbolo el deshacer guarda el documento entero. Sin sonido en símbolos.
- `Render` busca los símbolos en el documento de su hilo (`Render.use(doc)`), dibuja instancias recursivamente
  (hasta 8 niveles) y mide/toca instancias por su rectángulo (`instBounds`, `Render.at(elapsed)`).
- **Editar en el lugar**: doble toque con Selección sobre una instancia (o "✎ Editar el símbolo"). `State.sym` = id,
  `pl, pf, pi` = capa, fotograma e índice de la instancia en la escena. La mesa dibuja la escena atenuada y el
  símbolo encima con la matriz de su instancia (`StageView.base`); la línea de tiempo muestra las capas del
  símbolo. Volver: doble toque fuera, chip "↩ Escena 1" en la mesa, o tocar la ruta arriba en el principal.
  El extendido pide entrar/salir con `moEnter {l,f,i}` / `moExit` (el estado lo decide el principal).
- Panel derecho: "◈ Convertir en símbolo" o, con un símbolo elegido, Repetir / Una vez / 1 foto, Editar y
  Separar (`moCmd` `toSym`, `loop0..2`, `editSym`, `breakSym`). `moSel {n, sym, lp}`.
- `breakApart {l,f,is}` (Separar, como Modificar › Separar de Flash): la instancia se cambia por el dibujo que
  muestra en ese fotograma, con las poses de las capas del símbolo aplicadas (`MDoc.flatten`). El símbolo sigue
  en el documento.
- Como en Flash, un símbolo no se edita desde la escena: el cubo no lo pinta ni lo usa como borde, el borrador,
  el bote de tinta, el cuentagotas y la subselección lo ignoran. Se mueve, se transforma y se borra (Selección + ✕).

## Cerrar

⏻ en el encabezado del principal o ☰ › Cerrar DUAL Motion: guarda, manda `moQuit`, apaga el servicio y cierra
en los dos teléfonos.

## Línea de tiempo (principal)

- Mover fotogramas clave (como en Flash): tocar un clave para elegirlo y arrastrarlo por su fila; un marco
  naranjo muestra dónde queda. Orden `moveKey {l,f,to}` (si ahí había un clave, lo reemplaza; mover el primero
  deja fotogramas vacíos antes). La barra de abajo lleva las teclas de Flash: "● F6", "○ F7", "F5 ＋" (insertar,
  o extender la capa hasta el cabezal si está más allá del final), "F5 －" (quitar).
- Mantener presionado el nombre de una capa: Renombrar, Eliminar capa, Insertar capa encima, Subir, Bajar,
  Ocultar/Mostrar, Bloquear.
- Encabezado: ＋ capa, − capa (elimina la actual), ♪ agregar sonido (crea una capa de sonido arriba de la actual si hace falta y pone
  el sonido en el cabezal). En las filas de sonido: arrastrar un trozo = moverlo (por fotogramas); arrastrar sus
  bordes = recortar; mantener = menú (cortar en el cabezal, llevar al cabezal, volumen, duplicar, borrar).
  ✂ en la barra corta en el cabezal.
- Botones ● y ○ con variante (mantener presionado): aquí, o "→" en el fotograma siguiente (y el cabezal avanza).
  Prefs `keyNext`, `blankNext`.

## Etapas

1. ✅ Base: papeles, documento capas × claves, dibujo vectorial, línea de tiempo con cabezal, reproducir
   sincronizado, papel cebolla, vista previa a pantalla completa en el principal, guardado automático.
2. ✅ Animación clásica: interpolación de movimiento (posición, escala, giro, alfa) con curva de aceleración y
   giros; transformación libre; guardar/abrir `.dmo`.
3. Símbolos: ✅ gráfico (con ciclo, una vez, un fotograma), instancias, editar en el lugar. Falta: panel de
   biblioteca, clip de película, botón, color/filtros por instancia.
4. Interpolación de forma, guía de movimiento, máscara, filtros, mezcla; operaciones entre trazados.
5. Publicar: ✅ MP4 con sonido y ✅ capas de sonido; falta GIF y secuencia PNG.
6. Acciones simples, video de referencia, Lottie.

## Chequeo rápido sin Gradle

`javac --release 17 -cp android.jar -d /tmp/out app/src/main/java/cl/dualmotion/*.java` (android.jar de API 28;
el código no usa `R`).
