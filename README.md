# WARP TV

Aplicación Android para Android TV, móviles y tablets que integra WireGuard y genera una configuración Cloudflare WARP directamente en el dispositivo.

## Versión actual

- Versión visible: `V4`
- Versión interna: `4.0.0`
- Código de versión: `13`
- APK universal: un único APK para todos los dispositivos compatibles.

## Funciones

- Generación y almacenamiento local de la configuración WireGuard/WARP.
- Activación y desactivación manual de la VPN.
- Comprobación del estado real del túnel al volver a abrir la aplicación.
- Tiempo de conexión y estadísticas de descarga y subida.
- IP pública sin VPN, IP pública con WARP y localización de Cloudflare.
- DNS configuradas automáticamente:

  ```text
  DNS = 94.140.14.14, 94.140.15.15, 2a10:50c0::ad1:ff, 2a10:50c0::ad2:ff
  ```

- Información de bloqueos relacionados con fútbol, con IPs y operadores afectados.
- Próximos partidos televisados de Real Madrid, At. Madrid y Barcelona.
- Actualización automática de los partidos cada vez que se abre la aplicación.
- Diseño adaptado para Android TV, móviles y tablets.
- Iconos y banners diferenciados para Android TV hasta la versión 12 y Android TV 13 o superior.

## Compatibilidad

- Android 7.0 o superior (`minSdk 24`).
- Android TV, móviles y tablets.
- En móviles se permite utilizar la aplicación en orientación vertical y horizontal.
- Android TV mantiene su distribución horizontal optimizada para televisión.

## Compilación con GitHub Actions

El repositorio incluye el workflow `.github/workflows/build-apk.yml`.

Para generar el APK:

1. Sube el contenido del proyecto al repositorio de GitHub.
2. Abre la pestaña **Actions**.
3. Selecciona **Build WARP TV universal APK**.
4. Pulsa **Run workflow** o espera a que se ejecute tras subir cambios a `main`.
5. Descarga el artefacto `warp-tv-universal-debug-apk`.

El APK generado se encuentra en `app/build/outputs/apk/debug/app-debug.apk`.

El workflow utiliza JDK 17, Gradle 8.13 y Android SDK 36.

## Seguridad y almacenamiento

La clave privada WireGuard se almacena cifrada mediante Android Keystore y no se muestra intencionadamente en la interfaz.

La autorización de VPN la gestiona Android mediante `VpnService.prepare()`. El aviso de Google Play Protect puede aparecer porque el APK se distribuye fuera de Google Play y está firmado como aplicación de depuración.

## Fuentes externas

La aplicación consulta información pública de estas páginas:

- Bloqueos de fútbol: `https://hayahora.futbol/`
- Real Madrid: `https://www.futbolenlatv.es/equipo/real-madrid`
- At. Madrid: `https://www.futbolenlatv.es/equipo/at-madrid`
- Barcelona: `https://www.futbolenlatv.es/equipo/fc-barcelona`

Si alguna web cambia su estructura o deja de estar disponible, la aplicación mostrará que la información no está disponible sin impedir el funcionamiento de la VPN.

## Limitaciones

El registro de Cloudflare WARP utiliza un endpoint no oficial. Su disponibilidad o formato de respuesta puede cambiar. La aplicación valida los datos recibidos y muestra un error si no puede completar el registro.

## Licencias

La biblioteca embebida de WireGuard Android utiliza licencia Apache-2.0. El proyecto original del generador WARP tiene sus propias condiciones de licencia y atribución, que deben revisarse antes de redistribuir la aplicación.
