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
- `Render` — dibuja formas, fotogramas y papel cebolla; toques sobre formas (`hit`); suaviza trazos a mano alzada.
- `StageView` — la mesa: herramientas, zoom/encuadre con dos dedos, selección, subselección, pluma.
- `TimelineView` — línea de tiempo dibujada a mano (nombres con ojo/candado/contorno, grilla, cabezal rojo).
- `State` — herramienta, colores, grosor, capa y fotograma actuales, papel cebolla (lo decide el principal).
- `Net` — conexión TCP por WiFi (puerto 47830) con anuncio UDP (47831); una sola fila de envío; latido cada 3 s.
- `Ui` — cristal blanco con verde, panel naranja, botones de símbolo (nombre al mantener), íconos de herramientas.

## Sincronía (igual que DUAL-Draw)

- El **extendido es dueño del documento**: aplica cada orden con `doc.apply` y la reenvía `{t:"mo", op…}`.
  El principal aplica las mismas órdenes en el mismo orden (y así tiene la misma pila de deshacer).
- El principal **pide** órdenes: `{t:"moReq", op:{…}}`.
- Al conectarse, el extendido manda `moFull {doc}`; el principal manda su `moState`.
- `moState {tool, sc, fc, sw, l, f, on, ap?}`: `ap` = "s"/"f"/"w" aplica trazo/relleno/grosor a lo seleccionado.
- `moPlay {on, f}` y `moTick {f}` (cada 1 s): cada teléfono corre su propio reloj y se corrige.
- `moCmd {c}`: `delSel`, `front`, `back`, `fit` (la selección vive en la mesa). `moPick` (cuentagotas), `moSel {n}`.

### Órdenes del documento
`add {l,f,s}`, `set {l,f,is,ss}`, `del {l,f,is}`, `order {l,f,is,to}`, `key {l,f,blank?}` (F6/F7),
`frame {l,f}` (F5), `delFrame {l,f}`, `clearKey {l,f}`, `layer {l,name?,vis?,lock?,out?}`, `layerAdd {at}`,
`layerDel {l}`, `layerMove {l,to}`, `fps {v}`, `new`, `load {doc}`, `undo`, `redo`.
Dibujar en un fotograma después del final de la capa crea ahí un clave vacío (como en Flash).

## Herramientas (etapa 1)

Selección (mover, marco), Subselección (nodos y manillas), Pluma (tocar = recto, arrastrar = curva, primer punto
= cerrar, último punto o "✓ Terminar" = terminar), Línea, Rectángulo, Óvalo, Lápiz, Pincel (usa el color de
relleno), Cubo de pintura, Bote de tinta, Cuentagotas, Borrador (borra formas enteras), Mano.

## Etapas

1. ✅ Base: papeles, documento capas × claves, dibujo vectorial, línea de tiempo con cabezal, reproducir
   sincronizado, papel cebolla, vista previa a pantalla completa en el principal, guardado automático.
2. Animación clásica: interpolación de movimiento con aceleración (curva), guardar/abrir `.dmo` (ZIP con JSON).
3. Símbolos: biblioteca, gráfico, clip de película, botón; instancias; editar en el lugar.
4. Interpolación de forma, guía de movimiento, máscara, filtros, mezcla; operaciones entre trazados.
5. Publicar: MP4 (`MediaCodec`), GIF, secuencia PNG; capa de sonido.
6. Acciones simples, video de referencia, Lottie.

## Chequeo rápido sin Gradle

`javac --release 17 -cp android.jar -d /tmp/out app/src/main/java/cl/dualmotion/*.java` (android.jar de API 28;
el código no usa `R`).
