param(
    [Parameter(Mandatory=$false)]
    [switch]$PreJlink,
    [Parameter(Mandatory=$false)]
    [switch]$PostJlink
)

$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ModulesDir = Join-Path $RepoRoot "target\modules"
$JfxNativesDir = Join-Path $RepoRoot "target\jfx-natives"
$RuntimeDir = Join-Path $RepoRoot "target\runtime"

# Essential JavaFX native DLLs (excludes 4.5 MB d3dcompiler_47.dll, prism_es2.dll, and duplicate MSVC/UCRT DLLs)
$NeededJfxDlls = @(
    "glass.dll",
    "javafx_font.dll",
    "javafx_iio.dll",
    "prism_common.dll",
    "prism_d3d.dll",
    "prism_sw.dll",
    "decora_sse.dll",
    "concrt140.dll"
)

if ($PreJlink) {
    if (Test-Path -LiteralPath $JfxNativesDir) {
        Remove-Item -LiteralPath $JfxNativesDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $JfxNativesDir -Force | Out-Null

    Get-ChildItem -Path $ModulesDir -Filter "javafx-*.jar" -File -ErrorAction SilentlyContinue | ForEach-Object {
        $JarPath = $_.FullName
        $Zip = [System.IO.Compression.ZipFile]::Open($JarPath, [System.IO.Compression.ZipArchiveMode]::Update)
        try {
            $EntriesToDelete = New-Object System.Collections.Generic.List[System.IO.Compression.ZipArchiveEntry]
            foreach ($Entry in $Zip.Entries) {
                $Name = $Entry.Name
                if ($Name.EndsWith(".dll", [System.StringComparison]::OrdinalIgnoreCase)) {
                    if ($NeededJfxDlls -contains $Name.ToLowerInvariant()) {
                        $DestFile = Join-Path $JfxNativesDir $Name
                        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($Entry, $DestFile, $true)
                    }
                    $EntriesToDelete.Add($Entry)
                } elseif ($Entry.FullName.StartsWith("META-INF/maven/", [System.StringComparison]::OrdinalIgnoreCase)) {
                    $EntriesToDelete.Add($Entry)
                }
            }
            foreach ($Entry in $EntriesToDelete) {
                $Entry.Delete()
            }
        } finally {
            $Zip.Dispose()
        }
    }
    exit 0
}

if ($PostJlink) {
    $BinDir = Join-Path $RuntimeDir "bin"
    $LibDir = Join-Path $RuntimeDir "lib"
    $ConfDir = Join-Path $RuntimeDir "conf"
    $LegalDir = Join-Path $RuntimeDir "legal"

    # Copy deduplicated JavaFX native DLLs directly into target\runtime\bin so JavaFX never extracts to ~/.openjfx/cache
    if (Test-Path -LiteralPath $JfxNativesDir) {
        Get-ChildItem -Path $JfxNativesDir -Filter "*.dll" -File | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $BinDir $_.Name) -Force
        }
    }

    if (Test-Path -LiteralPath $LegalDir) {
        Remove-Item -LiteralPath $LegalDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    # Prune unused JDK executables and AWT/Swing/2D/JMX/debug DLLs
    $UnusedBinFiles = @(
        "java.exe", "keytool.exe", "kinit.exe", "klist.exe", "ktab.exe",
        "jabswitch.exe", "jaccessinspector.exe", "jaccesswalker.exe", "rmiregistry.exe", "jrunscript.exe",
        "awt.dll", "fontmanager.dll", "freetype.dll", "javajpeg.dll", "lcms.dll", "mlib_image.dll",
        "prefs.dll", "management.dll", "management_ext.dll", "management_agent.dll", "instrument.dll",
        "j2pkcs11.dll", "j2pcsc.dll", "jaas.dll", "sspi_bridge.dll", "w2k_lsa_auth.dll",
        "jsound.dll", "splashscreen.dll", "jawt.dll", "rmi.dll", "dt_shmem.dll", "dt_socket.dll",
        "jdwp.dll", "javaaccessbridge.dll", "windowsaccessbridge-64.dll", "windowsaccessbridge-32.dll",
        "le.dll", "extnet.dll", "attach.dll", "saproc.dll", "syslookup.dll"
    )
    foreach ($F in $UnusedBinFiles) {
        $P = Join-Path $BinDir $F
        if (Test-Path -LiteralPath $P) {
            Remove-Item -LiteralPath $P -Force -ErrorAction SilentlyContinue
        }
    }

    # Prune unused compiler/font/debug files in lib\ and conf\
    $UnusedLibFiles = @(
        "jrt-fs.jar", "src.zip", "ct.sym", "classlist",
        "psfont.properties.ja", "psfontj2d.properties",
        "fontconfig.bfc", "fontconfig.properties.src"
    )
    foreach ($F in $UnusedLibFiles) {
        $P = Join-Path $LibDir $F
        if (Test-Path -LiteralPath $P) {
            Remove-Item -LiteralPath $P -Force -ErrorAction SilentlyContinue
        }
    }

    foreach ($F in @("sound.properties", "logging.properties", "management")) {
        $P = Join-Path $ConfDir $F
        if (Test-Path -LiteralPath $P) {
            Remove-Item -LiteralPath $P -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    exit 0
}
