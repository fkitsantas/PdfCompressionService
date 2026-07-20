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
echo "==> jlink: building trimmed Java 25 runtime"
jlink \
  --add-modules java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output "$BUILD/runtime"

# --- 2. self-contained app image via jpackage ------------------------------
echo "==> jpackage: assembling native app image"
jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --input "$BUILD/input" \
  --main-jar "$APP_JAR_NAME" \
  --main-class org.springframework.boot.loader.launch.JarLauncher \
  --runtime-image "$BUILD/runtime" \
  --app-version "$BUNDLE_VERSION" \
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
  A small web service that compresses PDF files by re-encoding their images,
  while preserving text, vector graphics, transparency and page layout.
  This bundle is fully self-contained: it includes its own Java runtime, so
  you do NOT need Java (or anything else) installed to run it.

RUNNING IT
  ${RUN_HINT}

  On start-up the service listens on:  http://localhost:7777
  Leave the window/process running while you use it. Stop it with Ctrl+C in
  the terminal, or by closing the process.

COMPRESSING A PDF (from another terminal)
  curl -X POST -F 'file=@/path/to/input.pdf' \\
       http://localhost:7777/compressPdf --output compressed.pdf

  Replace /path/to/input.pdf with your file. The optimized PDF is written to
  compressed.pdf in the current folder.

VIEWING LOGS
  Open http://localhost:7777/logs in a browser to see recent service logs.

ADVANCED (optional) - tuning compression
  You can pass Spring Boot overrides as command-line arguments to the launcher,
  for example a lower target resolution or a different port:
    <launcher> --server.port=8080 --pdf.compression.target-dpi=120

  Common keys (defaults shown):
    --pdf.compression.target-dpi=150        image downsample target DPI
    --pdf.compression.jpeg-quality=0.75     JPEG quality (0.0-1.0)
    --pdf.compression.max-image-dimension=0 output edge cap in px (0 = no cap)
    --pdf.compression.strip-metadata=false  drop XMP/Info metadata (opt-in)
    --server.port=7777

  You can also override any of these per request as query/form parameters on
  POST /compressPdf (e.g. -F 'targetDpi=96'), and there is a browser UI at
  http://localhost:7777/ plus an async job API under /jobs.

SUPPORT
  Project: https://github.com/fkitsantas/PdfCompressionService
  License: GPL-3.0
EOF

echo "==> Staged bundle in: $STAGE"
ls -la "$STAGE"
echo "==> Done. Archive the contents of dist/stage into a release zip."
