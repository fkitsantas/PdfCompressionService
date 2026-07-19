# PDF Compression Service

[![CI](https://github.com/fkitsantas/PdfCompressionService/actions/workflows/ci.yml/badge.svg)](https://github.com/fkitsantas/PdfCompressionService/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

A small, self-contained web service that **shrinks PDF files by intelligently re-encoding their images** — downsampling and recompressing raster content to the resolution actually needed for on-screen and print use — while leaving text, vector graphics, transparency, and page layout untouched.

- **Table of Contents**
- [What it does](#what-it-does)
- [Download & run (no Java required)](#download--run-no-java-required)
- [Alternatives: portable jar & build from source](#alternatives)
- [HTTP API](#http-api)
- [Configuration](#configuration)
- [How compression works](#how-compression-works)
- [Development](#development)
- [Releasing](#releasing)
- [License](#license)

## What it does

- **Image-aware compression.** Each image is analysed for its *effective rendered DPI* (how many pixels actually land per inch on the page) and downsampled only when it is oversampled for its placement — never blindly.
- **Fidelity preserving.** Text and vector content are never rasterised. Transparency (soft masks) is retained, colour spaces are preserved (grayscale stays gray, bitonal scans stay 1-bit), and image orientation/aspect ratio are kept exactly.
- **Content-adaptive codecs.** Photographic images are recompressed as JPEG; line-art and low-colour images use lossless Flate to avoid ringing artefacts; bitonal scans stay CCITT.
- **Safety rails.** A replacement image is only kept if it is genuinely smaller (configurable threshold); otherwise the original is retained. The service never enlarges an image and never mutates your uploaded bytes.
- **Operational visibility.** Structured, content-free logs (sizes, counts, timing, correlation id) and a `/logs` page.

## Download & run (no Java required)

Every release ships **self-contained bundles that embed their own Java 21 runtime** — you do not need Java (or anything else) installed.

1. Go to the [**Releases**](https://github.com/fkitsantas/PdfCompressionService/releases) page and download the bundle for your OS:
   - `…-macos-arm64.zip` — macOS (Apple Silicon)
   - `…-macos-x64.zip` — macOS (Intel)
   - `…-linux-x64.zip` — Linux (x64)
   - `…-windows-x64.zip` — Windows (x64)
2. Unzip it and follow the included `INSTRUCTIONS.txt`. In short:

   | OS | Launch |
   |----|--------|
   | macOS | double-click `PdfCompressionService.app`, or `./PdfCompressionService.app/Contents/MacOS/PdfCompressionService` |
   | Linux | `./PdfCompressionService/bin/PdfCompressionService` |
   | Windows | run `PdfCompressionService\PdfCompressionService.exe` |

3. The service starts on **`http://localhost:7777`**. Compress a file from another terminal:

   ```bash
   curl -X POST -F 'file=@/path/to/input.pdf' \
        http://localhost:7777/compressPdf --output compressed.pdf
   ```

> **macOS first-launch note:** the bundle is unsigned, so Gatekeeper may block it. Right-click the app and choose **Open**, or clear the quarantine flag once with:
> ```bash
> xattr -dr com.apple.quarantine PdfCompressionService.app
> ```

## Alternatives

### Portable jar (OS-agnostic — if you already have Java 21)

The classic way to run it: one small, platform-independent jar. Every release includes `…-portable-jar.zip` alongside the OS bundles. This runs **exactly as before** — the only change from older releases is that it now requires Java 21 (rather than Java 8).

**Step 1: Download the jar**

Download `…-portable-jar.zip` from the [Releases](https://github.com/fkitsantas/PdfCompressionService/releases) page and unzip it into a folder. (It also ships an `INSTRUCTIONS.txt`.) Make sure Java 21+ is installed — check with `java -version`; get it from [adoptium.net](https://adoptium.net) if needed.

**Step 2: Run the jar**

Open a terminal, navigate to where you unzipped it, and run:

```bash
java -jar PdfCompressionService.jar
```

The service starts on `http://localhost:7777`.

**Step 3: Post a PDF to the service for testing**

From another terminal:

```bash
curl -X POST -F 'file=@/path/to/your/sample.pdf' \
     http://localhost:7777/compressPdf --output compressed.pdf
```

Replace `/path/to/your/sample.pdf` with the actual path to your PDF file. The optimized PDF is written to `compressed.pdf`.

### Build & run from source

Requires JDK 21. The Maven Wrapper is included, so no separate Maven install is needed.

```bash
git clone https://github.com/fkitsantas/PdfCompressionService.git
cd PdfCompressionService
./mvnw verify          # compile + run the full test suite
./mvnw spring-boot:run # start on http://localhost:7777
```

To produce a standalone bundle locally (needs a JDK 21 with `jlink`/`jpackage` on `PATH`):

```bash
./mvnw -B package
packaging/build-bundle.sh 1.0.0   # output staged under dist/stage/
```

## HTTP API

### `POST /compressPdf`

Compresses an uploaded PDF.

- **Request:** `multipart/form-data` with a part named **`file`** containing the PDF.
- **Success:** `200 OK`, `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="optimized.pdf"`, body = the optimized PDF.

```bash
curl -X POST -F 'file=@invoice.pdf' \
     http://localhost:7777/compressPdf --output optimized.pdf
```

**Error responses** are a stable JSON body — never a stack trace — carrying a `requestId` you can cross-reference in the server logs:

```json
{
  "timestamp": "2026-07-18T20:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "The uploaded file is not a valid PDF document.",
  "requestId": "5e2c…"
}
```

| Status | When |
|--------|------|
| `400 Bad Request` | the `file` part is missing from the request |
| `422 Unprocessable Entity` | the upload is not a valid PDF |
| `500 Internal Server Error` | an unexpected failure during compression |

### `GET /logs`

Returns an HTML page with the service's recent standard-output and error logs, for quick operational inspection.

### `GET /version`

Returns the running build's identity as JSON, so a deployed instance can be traced back to the exact build that produced it:

```json
{
  "name": "PdfCompressionService",
  "version": "0.2.0",
  "buildTime": "2026-07-18T22:03:40Z",
  "buildNumber": "147",
  "gitSha": "5e5d52c…"
}
```

`version` is the released semantic version (or `…-SNAPSHOT` for a dev build), `buildNumber` is the CI run number, and `gitSha` is the commit the build came from.

## Configuration

All settings live in `src/main/resources/application.properties` and can be overridden at runtime as command-line arguments (`--key=value`) or environment variables (Spring Boot relaxed binding).

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `7777` | HTTP port |
| `spring.servlet.multipart.max-file-size` | `100MB` | maximum upload size |
| `spring.servlet.multipart.max-request-size` | `100MB` | maximum request size |
| `pdf.compression.target-dpi` | `150` | target resolution images are downsampled toward |
| `pdf.compression.jpeg-quality` | `0.75` | JPEG quality for photographic images (0.0–1.0) |
| `pdf.compression.max-image-dimension` | `3000` | hard cap on any image edge, in pixels |
| `pdf.compression.min-dimension` | `16` | images smaller than this (px) are left untouched |
| `pdf.compression.min-byte-size` | `8192` | images encoded smaller than this (bytes) are skipped |
| `pdf.compression.min-reduction-ratio` | `0.10` | a re-encode is only kept if it saves at least this fraction |
| `pdf.compression.larger-result-policy` | `keep_original` | `keep_original` or `use_smallest` when a re-encode is not smaller |
| `pdf.compression.stream-cache` | `temp_file` | `temp_file` (low memory) or `memory` for PDFBox scratch data |
| `pdf.compression.recompress-cmyk` | `false` | whether to recompress CMYK images (changes colour space to RGB) |
| `pdf.compression.parallelism` | `0` | per-image resize/encode worker threads; `0` = auto (`availableProcessors()`), `1` = sequential |
| `pdf.compression.parallel-image-threshold` | `2` | minimum eligible images before the parallel path is used |
| `pdf.compression.max-concurrent-compressions` | `0` | admission gate bounding documents processed at once (peak-heap safety); `0` = auto (`cores × 4`); excess requests block |
| `spring.threads.virtual.enabled` | `true` | handle requests on Java 21 virtual threads (blocking-friendly concurrency) |

Example — run on a different port with more aggressive downsampling:

```bash
java -jar PdfCompressionService-*.jar --server.port=8080 --pdf.compression.target-dpi=120
```

## How compression works

1. **Load** the PDF with a memory-bounded stream cache (never overwriting the source bytes).
2. **Measure usage.** A content-stream engine records the maximum on-page size each image is drawn at, across every page, form XObject, and annotation appearance — yielding each image's *effective DPI*.
3. **Decide per image.** Skip masks, tiny, or already-small images; otherwise compute a single uniform downscale factor (never enlarging), pick a codec by content type, and high-quality bicubic-resample if warranted.
4. **Verify the win.** Keep the replacement only when it meets the reduction threshold; shared images are optimized once and re-referenced, so deduplicated resources stay deduplicated.
5. **Save** to a fresh byte stream and return statistics (bytes saved, images inspected/downsampled/recompressed/unchanged, timing).

## Development

- **Stack:** Java 21, Spring Boot 3.3.13, Apache PDFBox 3.0.8, jbig2-imageio (runtime).
- **Tests:** `./mvnw verify` runs the full suite (engine behaviour & fidelity, web contract, regression, concurrency). The tests are the executable specification for compression behaviour.
- **CI:** every push and pull request is built and tested on JDK 21 via [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## Releasing

Pushing a version tag builds and publishes the downloadable bundles automatically:

```bash
git tag v0.1.0
git push origin v0.1.0
```

[`.github/workflows/release.yml`](.github/workflows/release.yml) then builds the self-contained bundles for macOS (Intel + Apple Silicon), Linux, and Windows via `jlink` + `jpackage`, plus the portable jar, and attaches them all to a GitHub Release. (Running the workflow manually builds the bundles as downloadable workflow artifacts without publishing a release.)

## Versioning

The project follows **semantic versioning**, with git tags as the source of truth for releases:

- **Development builds** carry a `-SNAPSHOT` version (see [`pom.xml`](pom.xml)). Every CI build stamps itself with the workflow **run number** and the **commit SHA** into `META-INF/build-info.properties`, surfaced at [`GET /version`](#get-version) — so every successful build is uniquely identifiable.
- **Releases** are cut by pushing a `vMAJOR.MINOR.PATCH` tag. The release workflow sets the Maven project version to that tag (via `versions:set`) before packaging, so the published bundles, the jar, and `/version` all report the exact released version.

```bash
git tag v0.2.0
git push origin v0.2.0   # builds + publishes the release; no release is cut on ordinary pushes
```

## License

Distributed under the **GPL-3.0** License. See [`LICENSE`](LICENSE) for details.
