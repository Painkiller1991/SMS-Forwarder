# SMS WiFi Forwarder

Native Kotlin Android app that forwards SMS from one phone to another over the **same Wi‑Fi**, only when an SMS arrives (no polling on the sender). The receiver shows a notification + in-app inbox and can fill OTPs into other apps via **Autofill**.

## What you need

- Android Studio (Ladybug / Koala or newer) with JDK 17
- Two physical Android phones (API 26+)
- Same Wi‑Fi network (not guest Wi‑Fi that blocks device-to-device)
- USB debugging **or** ability to install an APK

Emulators are a poor fit: real SMS delivery needs a SIM on the **sender**.

## Build & install (sideload)

```bash
./gradlew assembleDebug
```

APK path:

`app/build/outputs/apk/debug/app-debug.apk`

Install on **both** phones (Android Studio Run, `adb install`, or share the APK).

## Setup

### Phone B — Receive (shows OTPs)

1. Open the app → **This phone RECEIVES**
2. Grant notification permission
3. Turn **Receive over Wi‑Fi** on (persistent notification = listener running)
4. Open **Autofill settings** and set this app as the Autofill service
5. Keep the pairing QR visible

### Phone A — Send (has the SIM)

1. Open the app → **This phone SENDS**
2. Grant SMS (+ camera for QR)
3. **Scan QR** from Phone B (or enter IP / port / secret manually)
4. Enable **Forwarding**

Send a test SMS to Phone A. Phone B should notify within about a second.

## OTP in banks / WhatsApp

1. Forwarded SMS is parsed for a code (kept ~5 minutes)
2. Open the bank/WhatsApp OTP screen on Phone B
3. Focus the OTP field — Android should suggest the code from this app  
   Or tap **Copy OTP** on the notification and paste

**Limit:** apps that only listen for a *cellular* SMS on Phone B (SMS Retriever) will not see the forward as a radio SMS. Autofill / copy is the supported path.

## Battery

- **Sender:** `SMS_RECEIVED` broadcast only — no foreground service while idle
- **Receiver:** small foreground Wi‑Fi HTTP server only while Receive is on

## Google Play

SMS receive/read permissions are restricted. A forwarder use-case is often rejected. Sideload is the intended distribution for v1.

## Security

Payloads are HMAC-signed with the shared secret from the QR. Only devices that scanned the receiver QR can post successfully. Traffic is local HTTP on your LAN (cleartext by design for simplicity).
