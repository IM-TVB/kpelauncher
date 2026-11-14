param(
    [Parameter(Mandatory=$false)]
    [string]$InstallerExe = "",
    [Parameter(Mandatory=$false)]
    [switch]$PrepareCertOnly
)

$ErrorActionPreference = "Continue"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$CertOutput = Join-Path $PSScriptRoot "KPEClub-Publisher.cer"
$CustomPfx = if ($env:KPE_PFX_PATH) { $env:KPE_PFX_PATH } else { Join-Path $PSScriptRoot "kpeclub.pfx" }

# Remove Mark-of-the-Web (Zone.Identifier) from project scripts and binaries
Get-ChildItem -Path $RepoRoot -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
    Unblock-File -LiteralPath $_.FullName -ErrorAction SilentlyContinue
}

$SignCert = $null

if (Test-Path -LiteralPath $CustomPfx) {
    Write-Host "Using commercial code-signing certificate: $CustomPfx"
    if ($env:KPE_PFX_PASSWORD) {
        $SecPass = ConvertTo-SecureString $env:KPE_PFX_PASSWORD -AsPlainText -Force
        $SignCert = Get-PfxCertificate -FilePath $CustomPfx -Password $SecPass
    } else {
        $SignCert = Get-PfxCertificate -FilePath $CustomPfx
    }
} else {
    $Subject = "CN=KPE Club, O=KPE Club, C=KH"
    $SignCert = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert -ErrorAction SilentlyContinue |
        Where-Object { $_.Subject -eq $Subject -and $_.NotAfter -gt (Get-Date) } |
        Select-Object -First 1

    if (-not $SignCert) {
        Write-Host "Generating KPE Club Authenticode Publisher Certificate ($Subject)..."
        $SignCert = New-SelfSignedCertificate `
            -Subject $Subject `
            -FriendlyName "KPE Club Official Publisher" `
            -Type CodeSigningCert `
            -KeyUsage DigitalSignature `
            -KeyAlgorithm RSA `
            -KeyLength 2048 `
            -HashAlgorithm SHA256 `
            -CertStoreLocation "Cert:\CurrentUser\My" `
            -NotAfter (Get-Date).AddYears(5)
    }

    if ($SignCert) {
        Export-Certificate -Cert $SignCert -FilePath $CertOutput -Force | Out-Null
        # Trust in CurrentUser Root and TrustedPublisher so Windows recognizes "KPE Club" as a verified publisher
        Import-Certificate -FilePath $CertOutput -CertStoreLocation "Cert:\CurrentUser\Root" -ErrorAction SilentlyContinue | Out-Null
        Import-Certificate -FilePath $CertOutput -CertStoreLocation "Cert:\CurrentUser\TrustedPublisher" -ErrorAction SilentlyContinue | Out-Null
    }
}

if ($PrepareCertOnly) {
    exit 0
}

if ($InstallerExe -and (Test-Path -LiteralPath $InstallerExe) -and $SignCert) {
    Unblock-File -LiteralPath $InstallerExe -ErrorAction SilentlyContinue
    Write-Host "Signing $InstallerExe with publisher 'KPE Club'..."
    $SigResult = Set-AuthenticodeSignature -FilePath $InstallerExe -Certificate $SignCert -HashAlgorithm SHA256 -TimestampServer "http://timestamp.digicert.com" -ErrorAction SilentlyContinue
    if (-not $SigResult -or $SigResult.Status -eq "UnknownError") {
        $SigResult = Set-AuthenticodeSignature -FilePath $InstallerExe -Certificate $SignCert -HashAlgorithm SHA256
    }
    Write-Host "Signature Status: $($SigResult.Status) ($($SigResult.SignerCertificate.Subject))"
}
