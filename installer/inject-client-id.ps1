$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $RepoRoot ".env"
$AuthJava = Join-Path $RepoRoot "src\main\java\site\kpeclub\launcher\auth\MicrosoftAuth.java"

$ClientId = $env:KPE_CLIENT_ID

if ((-not $ClientId) -and (Test-Path -LiteralPath $EnvFile)) {
    foreach ($Line in (Get-Content -LiteralPath $EnvFile)) {
        $Trimmed = $Line.Trim()
        if ($Trimmed.StartsWith("#") -or (-not $Trimmed.Contains("="))) { continue }
        $Parts = $Trimmed.Split("=", 2)
        $Key = $Parts[0].Trim()
        $Val = $Parts[1].Trim().Trim('"').Trim("'")
        if ($Key -eq "KPE_CLIENT_ID" -and $Val) {
            $ClientId = $Val
            break
        }
    }
}

if (-not $ClientId -or $ClientId -eq "00000000-0000-0000-0000-000000000000") {
    Write-Host "[ClientID] No custom KPE_CLIENT_ID in .env (keeping existing obfuscated key in MicrosoftAuth.java)."
    exit 0
}

if ($ClientId -notmatch "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") {
    Write-Error "Invalid KPE_CLIENT_ID in .env: '$ClientId'. Must be a 36-character UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)."
    exit 1
}

$Hex = $ClientId.Replace("-", "").ToLowerInvariant()
$SeedBytes = [System.Text.Encoding]::UTF8.GetBytes("kpe.oauth2.v2::XboxLive.signin::43110")
$Sha256 = [System.Security.Cryptography.SHA256]::Create()
$Mask = $Sha256.ComputeHash($SeedBytes)

$ObfLiterals = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt 16; $i++) {
    $RawByte = [Convert]::ToByte($Hex.Substring($i * 2, 2), 16)
    $Salt = (($i * 37) + 0x5A) -band 0xFF
    $ObfByte = ($RawByte -bxor $Mask[$i] -bxor $Salt) -band 0xFF
    $ObfLiterals.Add(("(byte) 0x{0:x2}" -f $ObfByte))
}

$Row1 = "            " + ($ObfLiterals[0..3] -join ", ") + ","
$Row2 = "            " + ($ObfLiterals[4..7] -join ", ") + ","
$Row3 = "            " + ($ObfLiterals[8..11] -join ", ") + ","
$Row4 = "            " + ($ObfLiterals[12..15] -join ", ")

$NewArrayBlock = @(
    "    private static final byte[] OBFUSCATED_CID = {",
    $Row1,
    $Row2,
    $Row3,
    $Row4,
    "    };"
) -join "`r`n"

$JavaContent = [System.IO.File]::ReadAllText($AuthJava, [System.Text.Encoding]::UTF8)
$Pattern = "(?s)\s*private static final byte\[\] OBFUSCATED_CID = \{.*?\};"
$UpdatedContent = [System.Text.RegularExpressions.Regex]::Replace($JavaContent, $Pattern, "`r`n" + $NewArrayBlock)
[System.IO.File]::WriteAllText($AuthJava, $UpdatedContent, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[ClientID] Obfuscated KPE_CLIENT_ID from .env into MicrosoftAuth.java (SHA-256 XOR keystream)."
