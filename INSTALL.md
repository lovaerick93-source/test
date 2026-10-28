# Installing the Remote Control Agent APK

The APK is a **self-signed release build** (signed with `release.jks`). Android does not require Play Store signing to install an APK, but because it is not distributed through the Play Store, Google Play Protect and the "Install unknown apps" block will intervene. Follow the steps below to install successfully.

---

## Before you install: choose the right APK

The project builds several "disguise" flavors (`default`, `instagram`, `twitter`, `facebook`, etc.) that change the app's package name and icon. **Use the `default` flavor** (`android-agent-default-release.apk`) — it does not impersonate another app and is least likely to be flagged by Play Protect.

The other flavors exist only to give the app a different launcher icon/package name; they do not change functionality but can make Play Protect more suspicious because they look like impersonation.

---

## Step 1: Enable "Install unknown apps"

Android blocks APK installation from sources other than the Play Store by default.

1. Download the APK on your phone (via Chrome, Firefox, or transfer from PC).
2. Open the downloaded file (or use a file manager to locate it).
3. When you tap the APK, Android shows: *"For your security, your phone is not allowed to install unknown apps from this source."*
4. Tap **Settings** → this opens **Install unknown apps**.
5. Find the app you used to download the APK (e.g., Chrome, Files, Drive).
6. Toggle **Allow from this source** to **ON**.
7. Return to the APK and tap **Install**.

---

## Step 2: Temporarily disable Google Play Protect

Play Protect scans sideloaded APKs and may block installation with a warning like *"This app is harmful"* or *"Play Protect does not recognize this app."*

1. Open the **Play Store** app.
2. Tap your profile icon (top right) → **Play Protect** → **Settings** (gear icon).
3. Turn **off** "Scan apps with Play Protect" (or "Improve harmful app detection").
4. Install the APK.
5. **After installation**, re-enable Play Protect for ongoing protection.

> If Play Protect still shows a warning during install, tap **"More details"** → **"Install anyway (unsafe)"**. This is safe because you are installing from your own trusted build.

---

## Step 3: Grant runtime permissions the first time you open the app

After installation, open the app. You will be asked for:

| Permission | Why it's needed |
|------------|-----------------|
| **Notifications** | Shows the persistent "agent is running" status |
| **Accessibility** | Lets the paired desktop tap, swipe, and type on your screen (enable in Settings > Accessibility) |
| **Screen capture / Media projection** | Lets the desktop see your phone screen (granted via the system dialog when mirroring starts) |
| **Query all packages** | Lets the desktop see your installed app list (only shared with a controller you approve) |

None of these are granted silently — you must approve each one.

---

## Troubleshooting

### "App not installed"

- Make sure you uninstalled any previous version signed with a **different** key first. Android identifies an app by package name + signing key; mismatched keys block installation.
- Make sure "Install unknown apps" is enabled for the app you're using to open the APK.
- Make sure you have enough free storage.

### Play Protect says "No compatible device" or blocks the app

- Use the **default** flavor APK, not a "disguise" flavor.
- Temporarily disable Play Protect (Step 2 above).
- Ensure "Install unknown apps" is enabled.

### "Install blocked by your administrator"

- This is a work/school device with MDM policies. You cannot sideload without administrator approval. Use a personal device instead.

---

## Verifying the APK signature

The release APK is signed with `release.jks` (keystore password and key password are in `keystore.properties`). If you rebuild the APK yourself, always use the same `release.jks` — losing it means you can never update an installed app in place; you would have to uninstall and reinstall.
