# Security Policy & Architecture (v1.8.1)

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.8.1   | :white_check_mark: |
| < 1.8.1 | :x:                |

## Security Controls in KPE Club Launcher v1.8.1

### 1. Authenticated & Hash-Verified Auto-Updates (`LauncherUpdater.java`)
- **Mandatory SHA-256 Digest**: `version.json` must provide a valid 64-character hexadecimal `sha256` hash (`isValidSha256Hex`). Missing or malformed digests abort the update check.
- **Isolated Update Staging**: Updates are downloaded into `%APPDATA%\.kpelauncher\updates\` (`FileDownloader.downloadFile` + SHA-256 verification) instead of shared `%TEMP%`, eliminating local temp-file pre-planting and TOCTOU replacement risks.
- **Authenticode Signature Verification**: Before `ProcessBuilder` executes the downloaded installer, `verifyAuthenticodeSignature()` invokes PowerShell `Get-AuthenticodeSignature` and requires `Status -eq 'Valid'`.

### 2. Pinned Supply-Chain Hashes for Mesa3D & 7-Zip (`FpsBoost.java`)
- **Pinned Artifacts**:
  - `mesa3d-26.2.6-release-mingw.7z` (`pal1000/mesa-dist-win` v26.2.6): `da0f71d9181b672c1918f3c342f11118c687f6059a46b16502845160733cc388`
  - `7za920.zip` (7-Zip 9.20 standalone CLI): `2a3afe19c180f8373fa02ff00254d5394fec0349f5804e0ad2f6067854ff28ac`
  - `7zr.exe` (7-Zip standalone console): `a1c56fc63d58c613115c298baca7da311f021e451c4394b08323b95572c2ecb6`
- **No Unprompted Local DLL/Archive Harvesting**: The launcher never automatically scans `Downloads`, `Desktop`, or `Documents` for `.dll` or `.zip` files. Local imports only occur when the user explicitly selects a file through the JavaFX `FileChooser`.
- **System JDK Isolation**: The launcher never writes DLLs into system-wide JDK directories (`C:\Program Files\Java\...`), staging Mesa3D solely inside `.kpelauncher`-managed directories.

### 3. Log4Shell & JVM Process Hardening (`GameLauncher.java`)
- **`-Dlog4j2.formatMsgNoLookups=true`** is injected unconditionally into every Minecraft JVM launch command, and missing Mojang Log4j XML configuration files are automatically downloaded and hash-checked prior to launch.

### 4. Credential & Token Protection (`LauncherConfig.java` & `MicrosoftAuth.java`)
- **DPAPI Token Encryption**: Session tokens stored on Windows are encrypted at rest via Windows Data Protection API (`CryptProtectData` / `CryptUnprotectData` per-user scope).
- **Obfuscated Internal OAuth Client ID**: The developer's Microsoft OAuth Client ID is stored as a SHA-256 keystream XOR payload (`OBFUSCATED_CID`) and reconstructed only in memory during OAuth requests.
- **TLS & Windows Root Store**: Modular `jlink` builds include both `jdk.crypto.ec` (TLS 1.3 ECDHE cipher suites) and `jdk.crypto.mscapi` (Windows Root Certificate Store integration).
