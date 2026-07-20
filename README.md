# PDF Compression Service

[![CI](https://github.com/fkitsantas/PdfCompressionService/actions/workflows/ci.yml/badge.svg)](https://github.com/fkitsantas/PdfCompressionService/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/fkitsantas/PdfCompressionService?sort=semver)](https://github.com/fkitsantas/PdfCompressionService/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

A fidelity-preserving PDF compression microservice (Java 25, Spring Boot 4, Apache PDFBox) that **shrinks PDF files by intelligently re-encoding their images**, downsampling and recompressing raster content to the resolution actually needed for screen and print, while leaving text, vector graphics, transparency, and page layout untouched. Use it over a REST API, from the drag-drop web UI, or as an async job for very large files.

- **Table of Contents**
- [What it does](#what-it-does)
- [Download & run (no Java required)](#download--run-no-java-required)
- [Alternatives: portable jar & build from source](#alternatives)
- [Run it as a background service (auto-start on boot)](#run-it-as-a-background-service-auto-start-on-boot)
- [HTTP API](#http-api)
- [Configuration](#configuration)
- [How compression works](#how-compression-works)
- [Development](#development)
- [Releasing](#releasing)
- [License](#license)

## What it does

- **Image-aware compression.** Each image is analysed for its *effective rendered DPI* (how many pixels actually land per inch on the page) and downsampled only when it is oversampled for its placement, never blindly.
- **Fidelity preserving.** Text and vector content are never rasterised. Transparency (soft masks) is retained, colour spaces are preserved (grayscale stays gray, bitonal scans stay 1-bit), and image orientation/aspect ratio are kept exactly.
- **Content-adaptive codecs.** Photographic images are recompressed as JPEG; line-art and low-colour images use lossless Flate to avoid ringing artefacts; bitonal scans stay CCITT. JPEG2000 (JPXDecode) and JBIG2 images are decoded via bundled ImageIO plugins and recompressed instead of passing through.
- **Safety rails.** A replacement image is only kept if it is genuinely smaller (configurable threshold); otherwise the original is retained. The service never enlarges an image and never mutates your uploaded bytes.
- **Operational visibility.** Structured, content-free logs (sizes, counts, timing, correlation id) and a `/logs` page.
- **Browser UI.** A self-contained drag-drop page at `http://localhost:7777/` for compressing a file without the terminal; the `curl`/API flow is unchanged and remains the primary interface.

## Download & run (no Java required)

Every release ships **self-contained bundles that embed their own Java 25 runtime**. You do not need Java (or anything else) installed.

1. Go to the [**Releases**](https://github.com/fkitsantas/PdfCompressionService/releases) page and download the bundle for your OS:
   - `…-macos-arm64.zip`, macOS (Apple Silicon)
   - `…-linux-x64.zip`, Linux (x64)
   - `…-windows-x64.zip`, Windows (x64)

   > **On an Intel Mac?** There is no native Intel bundle: GitHub has retired its Intel (x64) macOS build runners, and `jpackage` cannot cross-build an Intel `.app` from an Apple Silicon runner. Use the [**portable jar**](#portable-jar-os-agnostic-if-you-already-have-java-25) instead, `java -jar PdfCompressionService.jar`, which runs on any OS, including Intel Macs, and only needs Java 25.
2. Unzip it and follow the included `INSTRUCTIONS.txt`. In short:

   | OS | Launch |
   |----|--------|
   | macOS | double-click `PdfCompressionService.app`, or `./PdfCompressionService.app/Contents/MacOS/PdfCompressionService` |
   | Linux | `./PdfCompressionService/bin/PdfCompressionService` |
   | Windows | run `PdfCompressionService\PdfCompressionService.exe` |

3. The service starts on **`http://localhost:7777`**. Open that address in a browser for the drag-drop UI, or compress a file from another terminal:

   ```bash
   curl -X POST -F 'file=@/path/to/input.pdf' \
        http://localhost:7777/compressPdf --output compressed.pdf
   ```

> **macOS first-launch note:** the bundle is unsigned, so Gatekeeper may block it. Right-click the app and choose **Open**, or clear the quarantine flag once with:
> ```bash
> xattr -dr com.apple.quarantine PdfCompressionService.app
> ```

## Alternatives

### Portable jar (OS-agnostic, if you already have Java 25)

The classic way to run it: one small, platform-independent jar. Every release includes `…-portable-jar.zip` alongside the OS bundles. This runs **exactly as before**. The only change from older releases is that it now requires Java 25 (rather than Java 8).

**Step 1: Download the jar**

Download `…-portable-jar.zip` from the [Releases](https://github.com/fkitsantas/PdfCompressionService/releases) page and unzip it into a folder. (It also ships an `INSTRUCTIONS.txt`.) Make sure Java 25+ is installed, check with `java -version`; get it from [adoptium.net](https://adoptium.net) if needed.

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

Requires JDK 25. The Maven Wrapper is included, so no separate Maven install is needed.

```bash
git clone https://github.com/fkitsantas/PdfCompressionService.git
cd PdfCompressionService
./mvnw verify          # compile + run the full test suite
./mvnw spring-boot:run # start on http://localhost:7777
```

To produce a standalone bundle locally (needs a JDK 25 with `jlink`/`jpackage` on `PATH`):

```bash
./mvnw -B package
packaging/build-bundle.sh 1.0.0   # output staged under dist/stage/
```

## Run it as a background service (auto-start on boot)

Once you have picked a way to run it above (a self-contained bundle or the portable jar), register that with your OS service manager to keep it running unattended, restart it if it crashes, and start it again automatically after a reboot. Each recipe below starts the service **at boot, before/without any user login**, and restarts it if it exits. The service listens on `http://localhost:7777`; append tuning flags (e.g. `--server.port=8080`) to the launch command if needed.

### macOS (launchd)

Works for both the **Apple Silicon bundle** and, on **Intel Macs, the portable jar** (there is no native Intel bundle). Create a system daemon at `/Library/LaunchDaemons/com.fkitsantas.pdfcompressionservice.plist`.

**Apple Silicon bundle** (adjust the path to where you unzipped the `.app`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>            <string>com.fkitsantas.pdfcompressionservice</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Applications/PdfCompressionService.app/Contents/MacOS/PdfCompressionService</string>
    </array>
    <key>RunAtLoad</key>        <true/>   <!-- start at boot -->
    <key>KeepAlive</key>        <true/>   <!-- restart if it exits -->
    <key>StandardOutPath</key>  <string>/var/log/pdfcompressionservice.out.log</string>
    <key>StandardErrorPath</key> <string>/var/log/pdfcompressionservice.err.log</string>
</dict>
</plist>
```

**Intel Mac (portable jar).** Same file, but launch the jar with Java 25. Find your Java path with `/usr/libexec/java_home -v 25`, then use its absolute `bin/java` (a `launchd` daemon has a minimal `PATH`, so a bare `java` will not resolve):

```xml
    <key>ProgramArguments</key>
    <array>
        <string>/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java</string>
        <string>-jar</string>
        <string>/opt/pdfcompressionservice/PdfCompressionService.jar</string>
    </array>
```

Then load and start it (and it will come back on every reboot):

```bash
sudo chown root:wheel /Library/LaunchDaemons/com.fkitsantas.pdfcompressionservice.plist
sudo launchctl bootstrap system /Library/LaunchDaemons/com.fkitsantas.pdfcompressionservice.plist
# stop / unregister later:
sudo launchctl bootout system/com.fkitsantas.pdfcompressionservice
```

> Prefer it to run only while **you** are logged in? Put the same plist in `~/Library/LaunchAgents/` instead and use `launchctl bootstrap gui/$(id -u) …`.

### Linux (systemd)

Create `/etc/systemd/system/pdfcompressionservice.service`:

```ini
[Unit]
Description=PDF Compression Service
After=network.target

[Service]
Type=simple
# Self-contained bundle (no system Java needed):
ExecStart=/opt/PdfCompressionService/bin/PdfCompressionService
# ...or the portable jar instead (needs Java 25 on PATH):
# ExecStart=/usr/bin/java -jar /opt/pdfcompressionservice/PdfCompressionService.jar
Restart=always
RestartSec=5
# Run as an unprivileged user you created for it:
User=pdfsvc

[Install]
WantedBy=multi-user.target
```

Enable it to start now **and** on every boot:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now pdfcompressionservice
sudo systemctl status pdfcompressionservice   # check it is running
journalctl -u pdfcompressionservice -f        # follow its logs
```

### Windows

Simplest, a **Scheduled Task** that runs at startup as SYSTEM (adjust the path to the unzipped bundle):

```powershell
schtasks /Create /TN "PdfCompressionService" /SC ONSTART /RL HIGHEST /RU SYSTEM `
  /TR "C:\PdfCompressionService\PdfCompressionService.exe"
schtasks /Run /TN "PdfCompressionService"     # start it now without rebooting
```

For a **true Windows service** with automatic restart on crash, use [NSSM](https://nssm.cc):

```powershell
nssm install PdfCompressionService "C:\PdfCompressionService\PdfCompressionService.exe"
nssm set PdfCompressionService Start SERVICE_AUTO_START
nssm start PdfCompressionService
```

## HTTP API

### Web UI (`GET /`)

Opening the service root in a browser (`http://localhost:7777/`) serves a self-contained drag-drop page for compressing a PDF without the terminal:

- Drop or pick a PDF and click **Compress**; the optimized file downloads back with an original / compressed / saved-percent summary.
- An **Advanced options** panel exposes the same per-request overrides the API accepts (`targetDpi`, `jpegQuality`, `maxImageDimension`, `stripMetadata`, `deduplicateImages`); leave them blank to use the server defaults.
- A navigation menu links to the other views: **Live Logs** (`/logs`), **Health** (`/actuator/health`), and **Version** (`/version`).

The UI is a thin client over `POST /compressPdf` and is purely additive: the `curl` / API flow below is unchanged and remains the primary interface.

### `POST /compressPdf`

Compresses an uploaded PDF.

- **Request:** `multipart/form-data` with a part named **`file`** containing the PDF.
- **Success:** `200 OK`, `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="optimized.pdf"`, body = the optimized PDF.

```bash
curl -X POST -F 'file=@invoice.pdf' \
     http://localhost:7777/compressPdf --output optimized.pdf
```

**Optional per-request overrides.** Any of the tunables below may be supplied as query or form parameters to override the configured default for that one request; omit them all and the defaults apply, so the plain call above is unchanged. An out-of-range value is rejected as `400 Bad Request` (JSON error body).

| Parameter | Overrides |
|-----------|-----------|
| `targetDpi` | `pdf.compression.target-dpi` |
| `jpegQuality` | `pdf.compression.jpeg-quality` (0.0-1.0) |
| `maxImageDimension` | `pdf.compression.max-image-dimension` (0 = no cap) |
| `stripMetadata` | `pdf.compression.strip-metadata` |
| `deduplicateImages` | `pdf.compression.deduplicate-images` |

```bash
# More aggressive downsampling and metadata stripping, just for this request:
curl -X POST -F 'file=@invoice.pdf' \
     -F 'targetDpi=96' -F 'stripMetadata=true' \
     http://localhost:7777/compressPdf --output optimized.pdf
```

**Error responses** are a stable JSON body, never a stack trace, carrying a `requestId` you can cross-reference in the server logs:

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
| `400 Bad Request` | the `file` part is missing, or a per-request override is out of range |
| `422 Unprocessable Entity` | the upload is not a valid PDF |
| `500 Internal Server Error` | an unexpected failure during compression |

### Asynchronous compression: `POST /jobs`, `GET /jobs/{id}`, `GET /jobs/{id}/result`

For very large uploads a client can avoid holding the HTTP request open for the whole compression: submit the file, get a job id back immediately, poll for completion, then download the result. The same optional per-request parameters as `POST /compressPdf` apply.

```bash
# 1. Submit (returns 202 with a job id and a Location header):
curl -X POST -F 'file=@big.pdf' http://localhost:7777/jobs
# {"jobId":"5e2c…","status":"QUEUED","filename":"big.pdf", …}

# 2. Poll until "status":"SUCCEEDED":
curl http://localhost:7777/jobs/5e2c…
# {"jobId":"5e2c…","status":"SUCCEEDED","resultUrl":"/jobs/5e2c…/result","stats":{ …}}

# 3. Download the compressed PDF:
curl http://localhost:7777/jobs/5e2c…/result --output optimized.pdf
```

| Endpoint | Result |
|----------|--------|
| `POST /jobs` | `202 Accepted` with the job view and a `Location` header; `429` if too many jobs are in flight |
| `GET /jobs/{id}` | the job's status view (`QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`); `404` if unknown or evicted |
| `GET /jobs/{id}/result` | the compressed PDF once `SUCCEEDED`; `409` if not ready, `404` if unknown |

Jobs (and their results) are retained for a configurable window after completion, then evicted and their temp files deleted. The synchronous `POST /compressPdf` endpoint is unchanged and remains the simplest path for ordinary files.

### `GET /logs`

Returns an HTML page with the service's recent standard-output and error logs, for quick operational inspection.

Among the per-request lines is a **composition report** logged after each PDF is processed (`action=composition`), a byte breakdown by `images` / `fonts` / `vectors` / `other`, with an `addressablePercent` (fonts + vectors) and a plain-English `note`. It shows where each document's bytes actually live, so you can decide from real traffic whether vector/font-level optimization would pay off beyond the image pass. Toggle it with `pdf.compression.log-composition`.

```
action=composition pages=12 streamBytes=812345 imagePercent=96.4 fontPercent=1.1 vectorPercent=2.1 otherPercent=0.4 addressablePercent=3.2 note="Images are 96% of stream bytes; image optimization is where the savings are, vector/font optimization would move only ~3%."
```

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

### Operational endpoints (`/actuator`)

Spring Boot Actuator exposes a deliberately small operational surface (only `health`, `info`, `metrics`, and `prometheus`, never `env`/`beans`/`heapdump`):

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | overall health, including a custom `compression` indicator that probes temp-dir writability (every request streams through temp files) and reports admission-gate usage |
| `GET /actuator/health/liveness` | liveness probe group for orchestrators |
| `GET /actuator/health/readiness` | readiness probe group |
| `GET /actuator/prometheus` | Micrometer metrics in Prometheus text format |
| `GET /actuator/metrics` | metric names and per-metric drill-down |

The service publishes its own metrics under the `pcs.` prefix, alongside the standard JVM/HTTP ones:

| Metric | Type | Meaning |
|--------|------|---------|
| `pcs.compression.duration` | timer | engine processing time, tagged `outcome`=compressed\|original |
| `pcs.compression.requests` | counter | completed compressions, tagged `outcome` |
| `pcs.compression.failures` | counter | failed compressions, tagged `reason` |
| `pcs.compression.bytes.in` / `.out` / `.saved` | counter | cumulative byte totals |
| `pcs.compression.saved.percent` | summary | distribution of per-request size reduction |
| `pcs.images` | counter | images handled, tagged `action`=inspected\|downsampled\|recompressed\|unchanged |
| `pcs.compression.slots.max` / `.inflight` | gauge | admission-gate capacity and current in-flight documents |

## Configuration

All settings live in `src/main/resources/application.properties` and can be overridden at runtime as command-line arguments (`--key=value`) or environment variables (Spring Boot relaxed binding).

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `7777` | HTTP port |
| `spring.servlet.multipart.max-file-size` | `100MB` | maximum upload size |
| `spring.servlet.multipart.max-request-size` | `100MB` | maximum request size |
| `pdf.compression.target-dpi` | `150` | target resolution images are downsampled toward |
| `pdf.compression.jpeg-quality` | `0.75` | JPEG quality for photographic images (0.0–1.0) |
| `pdf.compression.max-image-dimension` | `0` | optional cap on a re-encoded image's longest edge, in pixels (`0` = no cap; not an acceptance limit, any input size is processed) |
| `pdf.compression.max-decode-pixels` | `500000000` | decode-bomb guard: images with more declared pixels than this are passed through unoptimized instead of decoded (`0` = disabled; not an acceptance limit, the document is always returned) |
| `pdf.compression.min-dimension` | `16` | images smaller than this (px) are left untouched |
| `pdf.compression.min-byte-size` | `8192` | images encoded smaller than this (bytes) are skipped |
| `pdf.compression.min-reduction-ratio` | `0.10` | a re-encode is only kept if it saves at least this fraction |
| `pdf.compression.larger-result-policy` | `keep_original` | `keep_original` or `use_smallest` when a re-encode is not smaller |
| `pdf.compression.stream-cache` | `temp_file` | `temp_file` (low memory) or `memory` for PDFBox scratch data |
| `pdf.compression.recompress-cmyk` | `false` | whether to recompress CMYK images (changes colour space to RGB) |
| `pdf.compression.deduplicate-images` | `true` | merge byte-identical images embedded as separate objects (e.g. a per-page logo) into one shared object |
| `pdf.compression.strip-metadata` | `false` | strip XMP/Info metadata (titles, authors, timestamps, producer) from the output (opt-in) |
| `pdf.compression.log-composition` | `true` | after each PDF, log a byte-composition report (images / fonts / vectors / other) at INFO, visible on `/logs`; diagnostic only, no effect on output |
| `pdf.compression.parallelism` | `0` | per-image resize/encode worker threads; `0` = auto (`availableProcessors()`), `1` = sequential |
| `pdf.compression.parallel-image-threshold` | `2` | minimum eligible images before the parallel path is used |
| `pdf.compression.max-concurrent-compressions` | `0` | admission gate bounding documents processed at once (peak-heap safety); `0` = auto (`cores × 4`); excess requests block |
| `pdf.compression.async.max-active-jobs` | `100` | max in-flight `/jobs` submissions before new ones get `429` (bounds queued upload data on disk) |
| `pdf.compression.async.retention` | `1h` | how long a finished job and its result are retained before eviction/temp-file deletion |
| `spring.threads.virtual.enabled` | `true` | handle requests on Java 25 virtual threads (blocking-friendly concurrency) |

Example, run on a different port with more aggressive downsampling:

```bash
java -jar PdfCompressionService-*.jar --server.port=8080 --pdf.compression.target-dpi=120
```

## How compression works

1. **Load** the PDF with a memory-bounded stream cache (never overwriting the source bytes).
2. **Measure usage.** A content-stream engine records the maximum on-page size each image is drawn at, across every page, form XObject, and annotation appearance, yielding each image's *effective DPI*.
3. **Decide per image.** Skip masks, tiny, or already-small images; otherwise compute a single uniform downscale factor (never enlarging), pick a codec by content type, and high-quality bicubic-resample if warranted.
4. **Verify the win.** Keep the replacement only when it meets the reduction threshold; shared images are optimized once and re-referenced, so deduplicated resources stay deduplicated.
5. **Save** to a fresh byte stream and return statistics (bytes saved, images inspected/downsampled/recompressed/unchanged, timing).

## Development

- **Stack:** Java 25, Spring Boot 4.1.0 (with Actuator + Micrometer/Prometheus), Apache PDFBox 3.0.8, jbig2-imageio and jai-imageio JPEG2000 (runtime image codecs).
- **Tests:** `./mvnw verify` runs the full suite (engine behaviour & fidelity, web contract, regression, concurrency). The tests are the executable specification for compression behaviour.
- **CI:** every push and pull request is built and tested on JDK 25 via [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## Releasing

Pushing a version tag builds and publishes the downloadable bundles automatically:

```bash
git tag v0.1.0
git push origin v0.1.0
```

[`.github/workflows/release.yml`](.github/workflows/release.yml) then creates the GitHub Release and builds the self-contained bundles for macOS (Apple Silicon), Linux, and Windows via `jlink` + `jpackage`, plus the OS-agnostic portable jar (which covers Intel Macs). Each platform **attaches its own asset independently**, so a slow or unavailable runner never blocks the release, the other assets publish regardless, and any straggler attaches if/when it finishes. (Running the workflow manually builds the bundles as downloadable workflow artifacts without publishing a release.)

## Versioning

The project follows **semantic versioning**, with git tags as the source of truth for releases:

- **Development builds** carry a `-SNAPSHOT` version (see [`pom.xml`](pom.xml)). Every CI build stamps itself with the workflow **run number** and the **commit SHA** into `META-INF/build-info.properties`, surfaced at [`GET /version`](#get-version), so every successful build is uniquely identifiable.
- **Releases** are cut by pushing a `vMAJOR.MINOR.PATCH` tag. The release workflow sets the Maven project version to that tag (via `versions:set`) before packaging, so the published bundles, the jar, and `/version` all report the exact released version.

```bash
git tag v0.2.0
git push origin v0.2.0   # builds + publishes the release; no release is cut on ordinary pushes
```

## License

Distributed under the **GPL-3.0** License. See [`LICENSE`](LICENSE) for details.
