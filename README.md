# KPE Club Launcher v1.8.1

A lightweight, secure, and modern custom Minecraft Java Edition launcher built with **Java 21** and **JavaFX 21**, designed for the **KPE Club** community and optimized for both modern gaming PCs and legacy hardware (Windows 7 / 8.1 / 10 / 11, x64 and x86).

---

## Key Features in v1.8.1

### 1. Security & Integrity Hardening
- **Pinned SHA-256 & Authenticode Auto-Updater (`LauncherUpdater`)**:
  - Requires a valid 64-character hex `sha256` digest in `version.json` before any update is offered.
  - Downloads updates exclusively into the user-private `%APPDATA%\.kpelauncher\updates\` directory (never shared `%TEMP%`).
  - Verifies both the **SHA-256 digest** and the **Windows Authenticode signature** (`Get-AuthenticodeSignature`) of the installer before launching.
  - Properly orders pre-release versions (`1.8.1` > `1.8.1-beta`, `1.8.1.1` > `1.8.1`).
- **Pinned SHA-256 Mesa3D & 7-Zip Verification (`FpsBoost`)**:
  - Uses the official [`pal1000/mesa-dist-win` 26.2.6 release (`mesa3d-26.2.6-release-mingw.7z`)](https://github.com/pal1000/mesa-dist-win/releases/download/26.2.6/mesa3d-26.2.6-release-mingw.7z) and standalone `7za920.zip` / `7zr.exe` with pinned SHA-256 verification prior to extraction or execution.
  - Never scans or auto-imports unverified archives from `Downloads`, `Desktop`, or `Documents` unless explicitly selected by the user via the file chooser.
- **Log4Shell Mitigation (`GameLauncher`)**:
  - Always passes `-Dlog4j2.formatMsgNoLookups=true` on every launch and automatically downloads missing Mojang client logging XML configurations before starting the game.
- **Protected OAuth Client ID (`MicrosoftAuth`)**:
  - Reconstructs the internal Microsoft OAuth Client ID at runtime via a SHA-256 keystream XOR cipher with zero plaintext UUIDs in source or compiled bytecode.

### 2. Smart Legacy GPU Support & Stability
- **Native Hardware OpenGL by Default (`oldGpuBypass = false`)**:
  - Dedicated and modern GPUs use full native hardware acceleration by default.
  - If an older GPU (e.g., Intel GMA 4500 / HD 2000 / 3000) crashes with `GLFW error 65542` (`WGL: The driver does not appear to support OpenGL`), the crash dialog offers one-click automatic enablement of the **Old PC GPU Driver Bypass (Mesa3D)**.
- **Non-Invasive JRE Shim Isolation**:
  - Never copies DLLs into the user's system JDK installation (`C:\Program Files\Java\...`). Instead, creates an isolated `.kpelauncher\mesa\jre-shim-<arch>` directory using NTFS directory junctions and stages Mesa3D only inside `.kpelauncher`-owned paths.
- **Resilient Process Log Streaming & Modded Memory Safety**:
  - Game stdout/stderr continues draining even if disk space runs low while writing `latest.log`, preventing pipe-buffer deadlocks.
  - Omits `-XX:+DisableExplicitGC` so Netty and LWJGL direct byte buffers in heavily modded setups never trigger `OutOfMemoryError: Direct buffer memory`.
  - Uses `wmic.exe` when present and automatically falls back to PowerShell `Get-Process` on newer Windows 11 builds to set high process priority.
- **Atomic Configuration Persistence (`LauncherConfig`)**:
  - Writes `settings.json` and `modpacks.json` to `.tmp` files first and moves them into place atomically (`ATOMIC_MOVE` with `REPLACE_EXISTING` fallback), preserving existing settings if a corrupt file is encountered.

---

## Building & Packaging

### Compact Modular Runtime (`jlink`)
Both `build-win64.bat` and `build-win32.bat` build a stripped, ZIP-9 compressed custom runtime image (`target\runtime`) containing only the required Java SE and JavaFX modules:

```text
java.base,java.desktop,java.logging,java.Management,java.net.http,java.prefs,java.scripting,java.sql,java.xml,jdk.crypto.ec,jdk.crypto.mscapi,jdk.unsupported,javafx.base,javafx.controls,javafx.fxml,javafx.graphics
```

### Build Commands
1. **64-Bit Windows Installer (`win64`)**:
   ```bat
   build-win64.bat
   ```
2. **32-Bit Legacy Windows Installer (`win32`)**:
   ```bat
   build-win32.bat
   ```
