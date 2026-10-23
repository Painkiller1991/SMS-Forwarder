# GitHub release checklist (signed release APKs)

The app checks `GET /repos/Painkiller1991/SMS-Forwarder/releases/latest` on cold start.
Ship **release-signed** APKs (not debug). Debug and release signatures differ — users on an old debug install must **uninstall once**, then install the release APK.

## One-time: create your release keystore

```powershell
.\scripts\create-release-keystore.ps1
```

Or manually:

```powershell
mkdir keystore
keytool -genkeypair -v `
  -keystore keystore/sms-forwarder-release.jks `
  -alias smsforwarder `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass YOUR_STORE_PASS -keypass YOUR_KEY_PASS `
  -dname "CN=Your Name, OU=Mobile, O=SMS Forwarder, L=Unknown, ST=Unknown, C=IR"
```

Copy [`keystore.properties.example`](../keystore.properties.example) → `keystore.properties` and fill passwords.  
**Never commit** `keystore/`, `*.jks`, or `keystore.properties`. Back them up offline — losing the key blocks in-place updates.

## Each release

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Build:

```powershell
$env:JAVA_HOME = "path\to\jdk-17"
.\gradlew.bat assembleRelease
copy app\build\outputs\apk\release\app-release.apk SMS-Forwarder-3.1.3-lan.apk
```

3. Write `update-manifest.json` (exact asset name):

```json
{
  "versionCode": 28,
  "versionName": "3.1.3-lan",
  "apkAsset": "SMS-Forwarder-3.1.3-lan.apk",
  "changelog": [
    "Release-signed APK for GitHub updates",
    "Sender keep-alive when app is closed"
  ]
}
```

4. Publish:

```powershell
gh release create v3.1.3-lan `
  SMS-Forwarder-3.1.3-lan.apk `
  update-manifest.json `
  --title "v3.1.3-lan" `
  --notes "- Release-signed APK`n- Uninstall old debug build once if install fails"
```

`changelog` in the manifest is shown in the update dialog. Repo must stay **public**.

## Play Protect

Release signing removes the “debug / unknown debug key” issue. Play Protect may still warn for apps **not on Play Store**; that needs a Play listing to clear fully.
