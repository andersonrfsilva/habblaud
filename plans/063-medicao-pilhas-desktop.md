# Zaplaud desktop footprint spike

Measured on Linux, 2026-11-09. This is a UI-only benchmark, not a feature-complete Zaplaud port.

## Results

Three fresh process launches per variant; each cell is the median of those three runs. Memory and artifact sizes use MiB (1,048,576 bytes). CPU is percent of one logical core, not percent of the entire machine.

| Stack | Launch to mapped window (ms) | Idle RSS (MiB) | Idle PSS (MiB) | Post-scroll RSS (MiB) | Post-scroll PSS (MiB) | Idle CPU (%) | Artifact (MiB) |
|---|---:|---:|---:|---:|---:|---:|---:|
| Compose Desktop, default JVM | 993.8 | 239.68 | 221.92 | 251.48 | 233.72 | 0.100 | 139.93 |
| Compose Desktop, tuned JVM | 875.5 | 208.58 | 190.81 | 230.71 | 212.89 | 0.100 | 139.93 |
| Rust + Slint | 118.9 | 24.91 | 19.55 | 25.08 | 19.71 | 0.000 | 17.78 |
| Go + Fyne | 115.4 | 165.90 | 152.06 | 174.24 | 160.40 | 1.200 | 23.46 |

Artifact sizes: Compose is the complete `createDistributable` application directory, including its jlink runtime (`du -sb`: 146,723,004 bytes). Rust and Go are stripped release executables, excluding system shared libraries. Compose default and tuned use the same artifact. Binary sizes are deterministic build measurements, not three runtime samples.

## Exact versions and environment

- Host: Linux 7.2.11-arch1-1, x86_64; Intel Core i7-10700 @ 2.90 GHz; 16 logical CPUs; 15,821 MiB RAM.
- Compose: Kotlin/JVM and Kotlin Compose compiler plugin 2.4.21; `org.jetbrains.compose` 1.12.2; Material3 desktop 1.9.0; Skiko 0.150.2.
- Gradle 9.3.1, matching the read-only repository wrapper properties. JDK/jlink runtime: OpenJDK 21.0.13.0.2, build date 2026-10-22.
- Rust: rustc 1.96.1 (31fca3adb 2026-06-26); Cargo 1.96.1 (356927216 2026-06-26); Slint 1.18.1, latest stable resolved from crates.io with `version = "*"`; exact dependencies preserved in `rust/Cargo.lock`.
- Slint uses `std`, `backend-winit`, `renderer-software`, `compat-1-2`; release uses `strip = true` and `lto = "thin"`. Slint succeeded; egui fallback was unnecessary.
- Go: go1.27.3-X:nodwarf5 linux/amd64; Fyne 2.8.2, latest resolved by `go get ...@latest`. `CGO_ENABLED=1`, `GOAMD64=v1`. Exact dependencies preserved in `go/go.mod` and `go/go.sum`.
- Fyne was chosen because its stock virtualized `widget.List` and `Entry` cover the required prototype directly. No claim is made that Fyne is lighter than Gio.
- Display: no session DISPLAY/WAYLAND_DISPLAY was set. Downloaded Arch Xvfb 21.1.25-2 and its missing libraries into `tools/`. Xvfb ran at 1280×800×24, over localhost TCP, without a window manager.

## Equivalent workload and measurement method

All three prototypes mapped a 1100×720 window in all measured runs. Each has a left chat list with 300 rows, avatar circles, titles, and one-line previews. Each has a right conversation with 200 alternating message bubbles, mixed short/long text, pt-BR accents, and a text-entry widget. Lists use Compose `LazyColumn`, Slint `ListView`, or Fyne `widget.List` virtualization.

`measure.py` starts each executable directly and polls X11 `XQueryTree`/`XGetWindowAttributes` every 5 ms. A title-matching window with `map_state == IsViewable` ends the startup measurement. This is the requested window-mapped proxy, not a verified first painted frame.

After mapping, the harness waits 10 seconds and reads `/proc/<pid>/smaps_rollup` for RSS and PSS. It then measures `/proc/<pid>/stat` user+system CPU tick deltas over 30 seconds. After that, it moves the pointer into the conversation and sends 80 XTest wheel-down clicks, 25 ms apart. It waits 3 seconds and reads RSS/PSS again. Each process exits before the next run; variants execute serially. No prototype builds ran during the final measurement series.

`screenshots.py` independently checked the rendered UI and scrolling. Before/after PNGs show conversation movement from message 1 to approximately message 90 (Compose), 124 (Slint), and 30 (Fyne). Scroll distance differs because each toolkit interprets wheel events differently. The post-scroll comparison is therefore equal input, not equal message distance. Layouts are logically equivalent, not pixel-identical; row heights, text metrics, and widget chrome differ.

## Commands used

All prototype sources, isolated dependency caches, logs, measurement scripts, screenshots, raw JSON, and retained artifacts are under `/tmp/zaplaud-desktop-spike`. Repository code was not modified.

```bash
ROOT=/tmp/zaplaud-desktop-spike
GRADLE=/home/anderson/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle

# Seed isolated Gradle cache from existing cache, then try offline first.
cp -a --reflink=auto ~/.gradle/caches/modules-2 "$ROOT/gradle-home/caches/"
cd "$ROOT/compose"
HOME="$ROOT/home" GRADLE_USER_HOME="$ROOT/gradle-home"   "$GRADLE" --offline --no-daemon --max-workers=2 createDistributable
# Offline resolution lacked the Kotlin/JVM 2.4.21 plugin; online succeeded.
HOME="$ROOT/home" GRADLE_USER_HOME="$ROOT/gradle-home"   "$GRADLE" --no-daemon --max-workers=2 createDistributable

cd "$ROOT/rust"
CARGO_HOME="$ROOT/cargo-home" CARGO_TARGET_DIR="$ROOT/rust/target"   CARGO_BUILD_JOBS=3 cargo build --release

cd "$ROOT/go"
export HOME="$ROOT/home" GOCACHE="$ROOT/go-cache"
export GOMODCACHE="$ROOT/go-mod" GOPATH="$ROOT/go-path"
go get fyne.io/fyne/v2@latest
go mod tidy
go build -p 3 -trimpath -ldflags="-s -w" -o zaplaud-fyne .

# GUI tools downloaded from official Arch package download URLs:
# xorg-server-xvfb, libxfont2, libfontenc, xorg-xkbcomp, libxkbcommon-x11.
# Extracted with: tar -xf <downloaded-package> -C "$ROOT/tools"
# tools/usr/bin also contains a copied bash and a sh -> bash symlink.
bwrap --ro-bind / /   --bind "$ROOT/temp" /tmp --bind "$ROOT" "$ROOT"   --ro-bind "$ROOT/tools/usr/bin" /usr/bin   --setenv LD_LIBRARY_PATH "$ROOT/tools/usr/lib"   --setenv HOME "$ROOT/home"   /usr/bin/Xvfb :91 -screen 0 1280x800x24   -nolisten unix -listen tcp -ac -noreset

cd "$ROOT"
LD_LIBRARY_PATH="$ROOT/tools/usr/lib" python screenshots.py
bash run-measurements.sh
python make_report.py
```

The harness launches the packaged Compose executable with `JAVA_TOOL_OPTIONS=''` for default JVM settings. The tuned variant sets exactly `JAVA_TOOL_OPTIONS='-Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'`. It sets `DISPLAY=localhost:91`, `LIBGL_ALWAYS_SOFTWARE=1`, `SLINT_BACKEND=winit-software`, and redirects HOME/XDG paths/TMPDIR into the workspace for every measured application.

## Caveats and unmeasured scope

- “Cold start” means a fresh process, not cold filesystem caches. Prior builds and smoke launches warmed the OS cache. Cache-flushed startup is NOT MEASURED.
- Rendering is headless/software, not a physical desktop GPU. Compose logged failure to create a Linux GL context and fell back; Slint explicitly uses its software renderer; Fyne uses OpenGL with `LIBGL_ALWAYS_SOFTWARE=1`. This is not a controlled same-renderer comparison.
- RSS/PSS cover the application process, not Xvfb, a compositor, the OS, or GPU driver memory. Swap/per-process swap and peak RSS are NOT MEASURED.
- A reported CPU value of 0.000% means no CPU tick delta in the median sample, not proof of literally zero work.
- First painted frame, frame rate, scroll latency, CPU during scrolling, text-entry interaction, typing latency, and accessibility are NOT MEASURED. Mapped-window startup may precede visible content.
- macOS, Apple Silicon, native Wayland, hardware acceleration, installers, code signing, and notarization are NOT MEASURED.
- Real chat networking, sockets, synchronization, storage, image decoding/caching, media, notifications, Markdown, cryptography, and long-lived usage are NOT MEASURED. Do not read these toy figures as full Zaplaud memory predictions.
- Rust/Go binary sizes exclude the installed Linux shared-library footprint. Compose includes its JRE. Compressed downloads and macOS bundles are NOT MEASURED.
- The tuned 128 MiB JVM heap succeeded for this workload only. Suitability for full Zaplaud, out-of-memory behavior, GC pauses, and reduced-tier JIT throughput are NOT MEASURED.
- Initial smoke failures were resolved: missing Xvfb `xkbcomp`, missing Slint `libxkbcommon-x11.so`, and a Fyne avatar sizing API mismatch. An initial unsandboxed Xvfb attempt failed; final Xvfb used a workspace-backed `/tmp` mount. Tool-managed transient paths during initial setup were not audited, so strict absence of transient writes outside the workspace is NOT VERIFIED.

## Raw runs

Memory values below are exact KiB from `smaps_rollup`; startup/CPU are rounded for display. Full precision and actual CPU sampling durations remain in `*-measurements.json`.

| Variant | Run | Startup (ms) | Idle RSS / PSS (KiB) | Post-scroll RSS / PSS (KiB) | Idle CPU (%) |
|---|---:|---:|---:|---:|---:|
| compose-default | 1 | 974.1 | 245432 / 227245 | 257520 / 239327 | 0.100 |
| compose-default | 2 | 993.8 | 251272 / 233044 | 269496 / 251267 | 0.100 |
| compose-default | 3 | 995.7 | 229372 / 211047 | 252500 / 234175 | 0.100 |
| compose-tuned | 1 | 882.2 | 213584 / 195394 | 225596 / 207400 | 0.100 |
| compose-tuned | 2 | 875.5 | 208348 / 190102 | 256252 / 238006 | 0.133 |
| compose-tuned | 3 | 872.7 | 224612 / 206366 | 236244 / 217998 | 0.100 |
| slint | 1 | 118.9 | 25784 / 20289 | 25908 / 20398 | 0.000 |
| slint | 2 | 123.2 | 25376 / 19940 | 25500 / 20057 | 0.033 |
| slint | 3 | 87.2 | 25512 / 20017 | 25680 / 20179 | 0.000 |
| fyne | 1 | 111.8 | 169196 / 155019 | 171476 / 157293 | 1.167 |
| fyne | 2 | 117.6 | 169884 / 155708 | 178424 / 164248 | 1.200 |
| fyne | 3 | 115.4 | 181432 / 167211 | 201000 / 186779 | 1.233 |

## Retained evidence and cleanup

`REPORT.md`, prototype sources, dependency lockfiles, `measure.py`, `screenshots.py`, `run-measurements.sh`, `make_report.py`, build/runtime logs, raw measurement JSON, and before/after screenshots remain in this directory. Packaged executables are retained under `artifacts/`. Large build/dependency caches and intermediate outputs were removed after measurement; the final cleanup size is recorded in `cleanup.txt`.
