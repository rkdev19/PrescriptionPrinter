# Prescription Printer — Setup

## What this is
Android app that watches WhatsApp in the background (Accessibility Service,
no API, no new number) and auto-prints prescriptions 2-per-A4-sheet to
your Epson M1120 / Canon LBP6030W over Wi-Fi.

## Build the APK without Android Studio
1. Create a new GitHub repo, push this whole folder to it.
2. GitHub Actions builds automatically on every push (see
   `.github/workflows/build.yml`).
3. Go to the repo's "Actions" tab → the latest run → download the
   `prescription-printer-debug-apk` artifact. That's your installable APK.
4. Copy it to the Samsung phone and install (allow "install from unknown
   sources" once, since this isn't on the Play Store).

## One-time setup on the phone
1. Open the app → tap "Enable Accessibility Permission" → find
   "Prescription Printer" in the list → turn it on.
2. Settings → Apps → Prescription Printer → Battery → set to
   "Unrestricted" (Samsung's Device Care sometimes calls this
   "Allow background activity").
3. Back in the app, tap "Start Watching WhatsApp" — the persistent
   notification confirms it's running.
4. Install the Epson/Canon printer plugin apps (Mopria Print Service /
   Epson Print Enabler) — needed once so Android knows the printers
   exist on the network, even though our print path talks to them
   directly over IPP.
5. Give both printers a fixed LAN IP (DHCP reservation on the router),
   then edit `EPSON_IP` / `CANON_IP` in `PrintHelper.kt` to match.

## What still needs testing on his real phone/printers (be upfront about this)
- **IPP silent printing** — should bypass the print dialog entirely, but
  needs confirming both printers actually accept a raw PDF over IPP
  without complaint. If one rejects it, `printPdfViaSystemDialog()` is
  the built-in fallback (works everywhere, needs one tap per sheet).
- **Text message + sender-number extraction** — the accessibility node
  reading in `WhatsAppAccessibilityService.maybeQueueText()` is a first
  pass; getting the exact sender number and clean message text will need
  tuning against how WhatsApp actually structures its screen on his
  Samsung's Android version.
- **Image sender attribution** — right now images are queued without
  a sender number (WhatsApp's media folder filenames don't carry it).
  If he needs to know who sent which prescription, this needs a second
  pass reading it from the chat screen at capture time.

## Known constraints (tell him this upfront)
- WhatsApp app updates can occasionally break the accessibility reading —
  expect occasional small fixes needed over time, not "install once,
  forget forever."
- Requires the phone to stay on, charged, and connected to Wi-Fi.
