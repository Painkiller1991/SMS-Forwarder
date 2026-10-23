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
copy app\build\outputs\apk\release\app-release.apk SMS-Forwarder-3.1.8-lan.apk
```

3. Write `update-manifest.json` (exact asset name):

```json
{
  "versionCode": 33,
  "versionName": "3.1.8-lan",
  "apkAsset": "SMS-Forwarder-3.1.8-lan.apk",
  "forceUpdate": false,
  "changelog": [
    "Update dialog buttons fully visible",
    "Fix receiver EADDRINUSE bind"
  ]
}
```

- `forceUpdate: false` — user can Later / Skip; features keep working.
- `forceUpdate: true` — clients on **3.1.6+** must install; app turns off sender/receive and blocks the UI until updated.

4. Publish:

```powershell
gh release create v3.1.8-lan `
  SMS-Forwarder-3.1.8-lan.apk `
  update-manifest.json `
  --title "v3.1.8-lan" `
  --notes "- Update dialog layout`n- Receiver EADDRINUSE fix"
```

`changelog` in the manifest is shown in the update dialog. Repo must stay **public**.

## Play Protect

Release signing removes the “debug / unknown debug key” issue. Play Protect may still warn for apps **not on Play Store**; that needs a Play listing to clear fully.
