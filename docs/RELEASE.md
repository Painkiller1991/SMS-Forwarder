# Release flow (develop → main → GitHub Actions)

Day-to-day work lives on **`develop`**. Shipping a build means bumping the version on develop, merging to **`main`**, and letting CI publish a signed GitHub Release.

```text
develop commits → bump versionCode/versionName + update-manifest.json → merge to main → Actions release
```

## Branches

| Branch | Role |
|--------|------|
| `develop` | Feature work and commits |
| `main` | Release line only; every push runs [`.github/workflows/release.yml`](../.github/workflows/release.yml) |

### Ship a release

1. On `develop`, bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Sync `update-manifest.json` so `versionName`, `versionCode`, and `apkAsset` (`SMS-Forwarder-{versionName}.apk`) match. Set `forceUpdate` as needed.
3. Merge `develop` → `main` and push `main`.
4. GitHub Actions builds the signed release APK and creates `v{versionName}` with the APK + `update-manifest.json`.

CI **fails** if the manifest does not match Gradle, or if tag `v{versionName}` already exists — bump before re-merging.

### `forceUpdate`

- `forceUpdate: false` — user can Later / Skip; features keep working.
- `forceUpdate: true` — clients on **3.1.6+** must install; app turns off sender/receive and blocks the UI until updated.

`changelog` in the manifest is shown in the update dialog. Repo must stay **public** so devices can read `releases/latest`.

## One-time: GitHub Actions secrets

Repo → **Settings → Secrets and variables → Actions**. Add before the first CI release:

| Secret | Value |
|--------|--------|
| `RELEASE_KEYSTORE_BASE64` | Base64 of `keystore/sms-forwarder-release.jks` |
| `RELEASE_STORE_PASSWORD` | Keystore store password |
| `RELEASE_KEY_ALIAS` | Usually `smsforwarder` |
| `RELEASE_KEY_PASSWORD` | Key password |

`GITHUB_TOKEN` is provided by Actions; the workflow uses `permissions: contents: write` for releases.

Encode the keystore (PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\sms-forwarder-release.jks")) | Set-Clipboard
```

Then paste into the `RELEASE_KEYSTORE_BASE64` secret.

## One-time: create your release keystore (local)

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

Local `assembleRelease` still uses `keystore.properties`. CI uses env vars (`SMS_STORE_*`) when that file is absent.

## Manual release (optional fallback)

If you need to publish without Actions:

```powershell
$env:JAVA_HOME = "path\to\jdk-17"
.\gradlew.bat assembleRelease
copy app\build\outputs\apk\release\app-release.apk SMS-Forwarder-3.1.8-lan.apk
gh release create v3.1.8-lan `
  SMS-Forwarder-3.1.8-lan.apk `
  update-manifest.json `
  --title "v3.1.8-lan" `
  --notes "- Manual release notes"
```

## Play Protect

Release signing removes the “debug / unknown debug key” issue. Play Protect may still warn for apps **not on Play Store**; that needs a Play listing to clear fully.
