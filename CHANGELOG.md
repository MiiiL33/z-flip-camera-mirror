# Changelog

Todos los cambios notables de este proyecto se documentarán en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/),
y este proyecto se adhiere a [Semantic Versioning](https://semver.org/lang/es/).

## [Unreleased]

### Added

- Toggle de encuadre del preview asistente en la cover (`com.flipmirror.app.viewfinder`): dos modos que solo cambian cómo se VE el preview, sin recortar ninguna captura. Modo 1:1 (por defecto) que muestra un cuadrado centrado que llena la cover casi cuadrada, para un encuadre cercano de la cara; y modo 9:16 que encaja la toma completa 9:16 dentro de la cover sin deformar, con barras laterales (pillarbox), para ver toda la composición. Un botón compacto arriba y centrado alterna entre ambos y su etiqueta (1:1 / 9:16) indica el modo activo; se ubica en la zona superior por ser la más segura de la cover del Flip, ya que abajo a la derecha están las cámaras (recorte) y abajo a la izquierda los botones de navegación. Los controles se mantienen dentro del área segura aplicando como padding los WindowInsets del recorte de cámaras (displayCutout) más las barras del sistema, mientras el preview sigue dibujándose de borde a borde bajo el recorte. El encuadre es una decisión de presentación a nivel de la vista (redimensiona la `PreviewView`), desacoplada de la captura: cuando se sume `ImageCapture` seguirá guardando el formato nativo completo. La lógica pura de cálculo de rectángulos se extrajo a `PreviewFraming` (reutiliza `MirrorGeometry` para el pillarbox 9:16) con unit tests JVM. El modo inicial puede preseleccionarse por adb con el extra de intent `preview_frame` (`1x1` / `9x16`) para QA determinista. Se mantiene intacto el manejo de postura y el rebind de la cámara del visor base.
- Visor de producto base del Sprint 1 (`com.flipmirror.app.viewfinder`): Activity `ViewfinderActivity` con preview de CameraX bindeada al lifecycle, pensada para la cover screen del Z Flip con el teléfono cerrado (showWhenLocked/turnScreenOn), layout a pantalla completa adaptado a la cover casi cuadrada, permiso de cámara en runtime y estados de error básicos. Observa las FoldingFeature de Jetpack WindowManager y rebindea la cámara al cambiar de postura o de configuración para no quedar en negro ni crashear. Se extrajo la clasificación de postura a una clase pura testeable (`FoldPosture`). El visor pasa a ser el launcher principal; las activities de spikes quedan exported para QA por adb. Fuera de alcance de este PR (PRs siguientes del Sprint 1): cambio de lente wide/ultrawide, captura de foto y toggle de encuadre 1:1 / 9:16.
- Scaffold del módulo Android (Kotlin + CameraX + Jetpack WindowManager) para el visor de cámara propio en la cover screen del Z Flip.
- CI con GitHub Actions para build, lint (ktlint) y unit tests en cada PR.
- Plantillas de issues y de Pull Request en `.github/`.
- Gradle Wrapper fijado en la versión 8.11.1.
- Licencia GPLv3 (`LICENSE`) para el repositorio.
