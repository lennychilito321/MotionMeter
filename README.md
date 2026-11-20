# Medidor de Movimiento

App Android en Kotlin que usa el acelerómetro del celular como un sismógrafo sencillo de laboratorio.
Proyecto de Física 2, Ingeniería de Software, Uniautónoma del Cauca. Autor: Lenny Alexander Chilito Lozada.

## Cómo abrirla

1. Android Studio (Koala o más reciente): **File → Open** y selecciona la carpeta `MedidorMovimiento`.
2. Espera a que Gradle sincronice. La primera vez descarga Gradle 8.9, el plugin de Android y MPAndroidChart.
3. Conecta un celular físico con la depuración USB activa y pulsa **Run**.
   El emulador no sirve para medir: su acelerómetro es simulado.

Requisitos: Android 8.0 (API 26) o superior en el celular, y JDK 17 (el que trae Android Studio).

## Cómo medir

1. Pon el celular **plano sobre la mesa**, con la pantalla hacia arriba. Así el eje z queda vertical.
2. Pulsa **Calibrar** y no toques la mesa durante 2 s. La app calcula el offset de cada eje y el nivel de ruido.
   Si sale el aviso de "ruido alto", repite la calibración.
3. Ajusta la **sensibilidad** (umbral = k × ruido). El valor por defecto es k = 5.
4. Pulsa **Medir** y mueve la mesa. La app detecta sola cuándo empieza y cuándo termina el movimiento.
5. Revisa el resultado, escribe una **etiqueta** (por ejemplo `fuerte-x-2`) y pulsa **Guardar**.
6. En **Historial** puedes exportar una medición o todas juntas en CSV, por correo, Drive o WhatsApp.

## Qué calcula

| Variable | Cómo se obtiene |
|---|---|
| Aceleración pico | Máximo de \|a\| = √(ax² + ay² + az²) entre el inicio y el fin |
| RMS | √(promedio de \|a\|²) en el mismo intervalo |
| Duración | Tiempo desde que \|a\| supera el umbral hasta la última vez que lo supera; el fin se confirma con 0,3 s de reposo |
| Eje dominante | El eje con mayor RMS |
| Velocidad y desplazamiento | Doble integración por la regla del trapecio, con corrección de deriva (ver abajo) |

**Corrección de deriva.** La mesa empieza y termina en reposo, así que la velocidad final real es 0.
Si la integración termina con v ≠ 0, ese sobrante es error acumulado. La app resta una rampa lineal
para que v(final) = 0 y luego integra la posición. Aun así, el desplazamiento solo es confiable en
movimientos cortos (de menos de 1 o 2 s).

## Archivos que exporta

- `resumen.csv`: una fila por medición con todas las variables, la etiqueta, el ruido, el umbral y la frecuencia de muestreo.
  Sirve para comparar intensidades en el experimento piloto.
- `medicion_AAAAMMDD_HHMMSS.csv`: todas las lecturas de una medición (t, ax, ay, az, |a|, vx, vy, vz, dx, dy, dz).
  Un t negativo corresponde al reposo previo al movimiento.

Ambos usan punto decimal. En Excel en español, impórtalos con **Datos → Desde texto/CSV** y elige el punto como separador decimal.

## Estructura del código

```
app/src/main/java/co/edu/uniautonoma/medidormovimiento/
  MotionAnalyzer.kt    Calibración, filtro de gravedad, detección de eventos, cálculo de variables (Kotlin puro)
  CsvFormat.kt         Formato de los CSV
  MeasurementStore.kt  Guardado en memoria interna y exportación (FileProvider)
  MainActivity.kt      Lectura del sensor, gráfica en tiempo real y pantalla principal
  HistoryActivity.kt   Lista de mediciones, exportar y borrar
app/src/test/.../MotionAnalyzerTest.kt   Pruebas con señales sintéticas de solución conocida
```

## Pruebas

`./gradlew test` (o clic derecho en `MotionAnalyzerTest` → Run). Las pruebas generan un pulso
a(t) = A·sin(ωt) con offset y ruido de sensor, y comprueban que la app recupere los valores teóricos:

- Pico ≈ A y RMS ≈ A/√2.
- v máx = 2A/ω ≈ 0,159 m/s.
- Desplazamiento x(T) = A·T/ω ≈ 3,98 cm, con A = 1 m/s² y f = 2 Hz.

## Detalles técnicos

- Sensor: `TYPE_LINEAR_ACCELERATION`, que ya descuenta la gravedad. Si el celular no lo tiene, usa
  `TYPE_ACCELEROMETER` con un filtro paso alto (τ = 0,5 s).
- Muestreo: `SENSOR_DELAY_FASTEST`. La frecuencia real se ve en pantalla y se guarda en el resumen.
  El manifiesto declara `HIGH_SAMPLING_RATE_SENSORS`, que Android 12+ exige para pasar de 200 Hz.
- Los tiempos salen de la marca de tiempo del sensor (`event.timestamp`, en ns), no del reloj del sistema.
- La app no hace vibrar el celular, porque la vibración se mediría como movimiento.
