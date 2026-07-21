#!/usr/bin/env bash
#
# Builds a self-contained PDF Compression Service bundle that runs WITHOUT any
# separately installed Java. It bundles a trimmed Java 25 runtime (via jlink)
# together with the application (via jpackage --type app-image), then stages the
# native launcher next to a plain-language INSTRUCTIONS.txt ready to be zipped.
#
# Works on macOS, Linux and Windows (Git Bash / MSYS on GitHub Actions runners).
# Requires a JDK 25 on PATH (jlink + jpackage) and a prior `mvn package`/`verify`
# so that target/PdfCompressionService-<version>.jar exists.
#
# Usage: packaging/build-bundle.sh [bundle-version]
#   bundle-version  Version stamped onto the native bundle. macOS and the
#                   Windows installer require the first component to be >= 1, so
#                   this defaults to 1.0.0 and is independent of the Maven
#                   project version (which may legitimately start with 0.).
#
# Output: dist/stage/  (bundle + INSTRUCTIONS.txt, ready to archive)
#         The caller (CI or user) zips dist/stage into a release asset.
set -euo pipefail

BUNDLE_VERSION="${1:-1.0.0}"
APP_NAME="PdfCompressionService"

# --- locate repo root and the built fat jar ---------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# Pick the newest Spring Boot fat jar, excluding the *.jar.original plain jar
# (newest-by-mtime so a dirty target/ with several versions picks the fresh build).
APP_JAR=""
for f in target/${APP_NAME}-*.jar; do
  case "$f" in
    *.original) continue ;;
  esac
  [ -f "$f" ] || continue
  if [ -z "$APP_JAR" ] || [ "$f" -nt "$APP_JAR" ]; then
    APP_JAR="$f"
  fi
done
if [ -z "$APP_JAR" ]; then
  echo "ERROR: no application jar found in target/. Run 'mvn -B package' first." >&2
  exit 1
fi
APP_JAR_NAME="$(basename "$APP_JAR")"
# Human-facing version for INSTRUCTIONS.txt: the REAL project version, taken from
# the jar filename (release CI runs versions:set to the tag before packaging, so
# it is e.g. 0.3.14, or 0.2.0-SNAPSHOT for a local build). This differs from
# BUNDLE_VERSION, which is the synthetic jpackage --app-version (>= 1 major),
# used only for the OS bundle metadata.
DISPLAY_VERSION="${APP_JAR_NAME#${APP_NAME}-}"
DISPLAY_VERSION="${DISPLAY_VERSION%.jar}"
echo "==> Application jar: $APP_JAR_NAME"
echo "==> Release version: $DISPLAY_VERSION"
echo "==> Bundle version:  $BUNDLE_VERSION (jpackage app-version)"

# --- clean output dirs ------------------------------------------------------
DIST="$ROOT_DIR/dist"
BUILD="$DIST/build"
STAGE="$DIST/stage"
rm -rf "$DIST"
mkdir -p "$BUILD/input" "$STAGE"
cp "$APP_JAR" "$BUILD/input/"

# --- 1. trimmed runtime via jlink -------------------------------------------
# java.se aggregates every Java SE module (java.desktop for ImageIO/AWT that
# PDFBox needs, java.sql/naming/xml for Spring, etc.) so nothing reached via
# reflection is missing; the jdk.* additions cover TLS crypto, sun.misc.Unsafe,
# the zip filesystem, and full charset/locale support for PDF text handling.
# jdk.management adds com.sun.management (GC notification info) which java.se does
# NOT pull in; without it Micrometer's JvmGcMetrics logs a warning at startup and
# GC metrics are unavailable on /actuator/prometheus.
echo "==> jlink: building trimmed Java 25 runtime"
jlink \
  --add-modules java.se,jdk.management,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output "$BUILD/runtime"

# --- 2. self-contained app image via jpackage ------------------------------
# Platform-specific application icon (Finder/Dock/taskbar), else jpackage stamps
# the generic default Java icon. jpackage requires the native format per OS:
# .icns (macOS), .ico (Windows), .png (Linux).
ICON_DIR="$ROOT_DIR/packaging/icons"
case "$(uname -s)" in
  Darwin)                          ICON="$ICON_DIR/app.icns" ;;
  MINGW*|MSYS*|CYGWIN*|Windows_NT) ICON="$ICON_DIR/app.ico" ;;
  *)                               ICON="$ICON_DIR/app.png" ;;
esac
if [ -f "$ICON" ]; then
  echo "==> App icon: $ICON"
else
  echo "==> WARNING: app icon $ICON not found; using the default Java icon" >&2
  ICON=""
fi

# ${ICON:+...} keeps this safe under `set -u` and adds no args when ICON is unset;
# both tokens stay separately quoted so a path with spaces is preserved.
echo "==> jpackage: assembling native app image"
jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --input "$BUILD/input" \
  --main-jar "$APP_JAR_NAME" \
  --main-class org.springframework.boot.loader.launch.JarLauncher \
  --runtime-image "$BUILD/runtime" \
  --app-version "$BUNDLE_VERSION" \
  ${ICON:+--icon} ${ICON:+"$ICON"} \
  --java-options "-Xss4m" \
  --dest "$BUILD/image"

# --- 3. stage bundle + INSTRUCTIONS for archiving ---------------------------
OS="$(uname -s)"
case "$OS" in
  Darwin)
    cp -R "$BUILD/image/${APP_NAME}.app" "$STAGE/"
    RUN_HINT='Double-click PdfCompressionService.app, or from a terminal run:
    ./PdfCompressionService.app/Contents/MacOS/PdfCompressionService

  The first time you open it, macOS Gatekeeper may block an unsigned app.
  Either right-click the app and choose "Open", or clear the quarantine flag:
    xattr -dr com.apple.quarantine PdfCompressionService.app'
    ;;
  MINGW*|MSYS*|CYGWIN*|Windows_NT)
    cp -R "$BUILD/image/${APP_NAME}" "$STAGE/"
    RUN_HINT='Double-click PdfCompressionService\PdfCompressionService.exe, or from
  a terminal (PowerShell / cmd) run:
    PdfCompressionService\PdfCompressionService.exe

  Windows SmartScreen may warn about an unknown publisher on first launch;
  choose "More info" -> "Run anyway".'
    ;;
  *)
    cp -R "$BUILD/image/${APP_NAME}" "$STAGE/"
    RUN_HINT='From a terminal run:
    ./PdfCompressionService/bin/PdfCompressionService'
    ;;
esac

cat > "$STAGE/INSTRUCTIONS.txt" <<EOF
PDF Compression Service ${DISPLAY_VERSION} - Standalone Edition
================================================================

WHAT THIS IS
  A small web service that shrinks PDF files while preserving how they look:
  it re-encodes images (progressive JPEG, downsampling to the resolution
  actually used), subsets embedded fonts to the glyphs actually used, and
  merges duplicate images and colour profiles - leaving text, vector graphics,
  transparency and page layout intact. This bundle is fully self-contained: it
  includes its own Java runtime, so you do NOT need Java (or anything else)
  installed to run it.

RUNNING IT
  ${RUN_HINT}

  On start-up the service listens on:  http://localhost:7777
  Leave the window/process running while you use it. Stop it with Ctrl+C in
  the terminal, or by closing the process.

USING IT - IN A BROWSER (easiest)
  Open http://localhost:7777 and drag a PDF onto the page; the compressed file
  downloads back with a size summary. An "Advanced options" panel exposes the
  per-request settings below, and a "Run automatically" tab can set the service
  to start on login/boot with one click.

USING IT - FROM A TERMINAL
  curl -X POST -F 'file=@/path/to/input.pdf' \\
       http://localhost:7777/compressPdf --output compressed.pdf

  Optional per-request overrides (query/form parameters), e.g.:
    -F 'targetDpi=96'  -F 'jpegQuality=0.6'  -F 'stripMetadata=true'

OTHER PAGES
  http://localhost:7777/logs             live logs (incl. a per-PDF report of
                                         where its bytes are: images/fonts/...)
  http://localhost:7777/version          build identity (version, commit)
  http://localhost:7777/actuator/health  health / readiness
  For very large files, POST /jobs then poll GET /jobs/{id} (async).

ADVANCED (optional) - tuning, as launcher arguments
  Append Spring Boot overrides to the launch command, e.g.:
    <launcher>  --server.port=8080  --pdf.compression.target-dpi=120

  Common keys (defaults shown):
    --pdf.compression.target-dpi=150         image downsample target DPI
    --pdf.compression.jpeg-quality=0.75      JPEG quality (0.0-1.0)
    --pdf.compression.max-image-dimension=0  output edge cap in px (0 = no cap)
    --pdf.compression.subset-fonts=true      subset embedded fonts
    --pdf.compression.strip-metadata=false   drop XMP/Info metadata (opt-in)
    --pdf.compression.strip-private-data=false  drop editor blobs/thumbnails (opt-in)
    --server.port=7777

SUPPORT
  Project: https://github.com/fkitsantas/PdfCompressionService
  License: GPL-3.0
EOF

echo "==> Staged bundle in: $STAGE"
ls -la "$STAGE"
echo "==> Done. Archive the contents of dist/stage into a release zip."
