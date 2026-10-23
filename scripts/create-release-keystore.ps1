# Creates keystore/sms-forwarder-release.jks and a local keystore.properties (gitignored).
# Requires keytool on PATH (JDK 17+).

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$keystoreDir = Join-Path $root "keystore"
$jks = Join-Path $keystoreDir "sms-forwarder-release.jks"
$props = Join-Path $root "keystore.properties"

if (Test-Path $jks) {
    Write-Host "Keystore already exists: $jks"
    Write-Host "Aborting so we do not overwrite your release key."
    exit 1
}

New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null

$storePass = Read-Host "Store password (min 6 chars)"
$keyPass = Read-Host "Key password (min 6 chars, can match store)"
$cn = Read-Host "Your name / org for certificate CN (e.g. March)"

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    $candidates = @(
        "$env:JAVA_HOME\bin\keytool.exe",
        "C:\Users\sr136\AppData\Local\Temp\jdk-17\bin\keytool.exe",
        "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $keytool = $c; break }
    }
}
if (-not $keytool) {
    Write-Error "keytool not found. Set JAVA_HOME to a JDK 17+ install."
}

& $keytool -genkeypair `
    -v `
    -keystore $jks `
    -alias smsforwarder `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -storepass $storePass `
    -keypass $keyPass `
    -dname "CN=$cn, OU=Mobile, O=SMS Forwarder, L=Unknown, ST=Unknown, C=IR"

$text = @"
storeFile=keystore/sms-forwarder-release.jks
storePassword=$storePass
keyAlias=smsforwarder
keyPassword=$keyPass
"@
[System.IO.File]::WriteAllText($props, $text.Trim() + "`n")

Write-Host ""
Write-Host "Created:"
Write-Host "  $jks"
Write-Host "  $props"
Write-Host "Back up the .jks and passwords offline. Losing them blocks updates over the same app id."
Write-Host "Build release:  .\gradlew.bat assembleRelease"
