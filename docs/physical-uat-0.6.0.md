# Blocks Android 0.6.0 physical NFC and QR UAT

This manual is for the signed 0.6.0 pilot only. Emulator and fake-tag results are useful
development evidence, but they do not establish that a physical Android device can program
and rediscover a real NFC tag.

## What you need

- An Android phone with NFC and camera support, unlocked and signed in to the accepted pilot
  Google account.
- The Orchestrator-produced, architecture-accepted signed `0.6.0` APK. Record its filename,
  SHA-256, source commit, version code, and signing-certificate fingerprints in the result
  table below.
- Two writable NDEF-compatible NFC tags. Keep a third read-only, too-small, malformed, or
  non-Blocks tag available for negative tests if practical.
- Two active test projects and one archived test project containing no personal or sensitive
  information.
- A printed copy or photograph of the generated QR for the camera test. A phone cannot point
  its own camera at its own screen.

Do not uninstall the existing pilot or clear its data. For an adb upgrade that retains local
data, use:

```sh
adb install -r /path/to/blocks-android-0.6.0.apk
```

After installation, open **Settings → About** in Blocks and verify `Version 0.6.0 (8)`.

## 1. Program and verify a writable NFC tag

1. Open an active project from Dashboard.
2. Open **Project settings → Set tag**.
3. Enter a non-sensitive block label and choose **Continue**.
4. Hold the first writable tag to the phone until Blocks reports **Tag ready**.
5. Move the tag away, return to Dashboard, then tap it again.
6. Confirm Blocks opens exactly the project used in step 1 and creates no note, parking item,
   project, or other domain mutation.

The **Tag ready** result is shown only after Android reads back and exactly verifies the NDEF
locator it wrote. Never record or paste the raw locator/token into the result table.

## 2. Force-stop and prove offline local resolution

1. Turn on airplane mode and ensure Wi-Fi is off.
2. Force-stop Blocks from Android App info, or run:

   ```sh
   adb shell am force-stop dev.blocks.android
   ```

3. Leave the device screen unlocked, then tap the programmed tag once.
4. Confirm Blocks launches once with one back stack and opens the correct cached project.
5. Press **Main** and confirm Dashboard remains usable with no sync prompt, failure banner,
   or foreground Service dependency.
6. Press Back once from Dashboard and confirm Blocks exits rather than revealing a duplicate
   Blocks activity.

## 3. Multiple tags, reassignment, unbind, and archive

1. Reconnect only if needed for account continuity; the tag operations themselves must remain
   locally usable without Service.
2. In the same project, use **Set tag** to program the second writable tag. Confirm both tags
   independently open that project.
3. Open the other active project, choose **Set tag**, enter a label, and present the first tag.
4. Confirm Blocks recognizes the known tag and asks before reassignment. Accept it. Confirm the
   tag now opens the second project without being rewritten.
5. In **Project settings → Manage tags**, choose **Unbind** for that tag and confirm the action.
6. Tap the unbound tag. Confirm Blocks offers a contained project assignment instead of
   guessing or creating data. Assign it and verify the chosen project opens.
7. Bind a tag to the archived test project, or archive an already-bound project. Tap the tag
   and confirm its archived Project opens with the normal contained **Reopen** action.

## 4. QR fallback and scan

1. Open a project and choose **Project settings → Set tag**.
2. Enter a label, choose **Continue**, then **Use QR instead**.
3. Confirm a QR is displayed without a textual raw token. Photograph or print the QR without
   sharing or uploading it.
4. Return to Dashboard and choose **Settings → Scan QR**. Grant camera permission if Android
   asks; the permission must not have been requested before this explicit action.
5. Scan the photographed/printed QR and confirm the correct local project opens while offline.
6. Verify manual Dashboard browsing still works and that Dashboard and Project are still the
   only persistent destinations.

## 5. Safe failure and payload handling

Run each available case. Existing tag contents must remain unchanged unless the explicit
two-step replacement flow is completed.

1. Start **Set tag**, then close the prompt before presenting a tag. Start **Set tag** again:
   Blocks must offer to resume the saved setup instead of silently creating a new locator.
2. Interrupt a write by moving the tag away. Reopen **Manage tags → Resume NFC** and present
   the same tag. Blocks must verify/resume the saved operation; it must not mint a new token.
3. Present a read-only, too-small, or non-NDEF tag. Confirm Blocks reports a contained failure,
   claims no active binding, and leaves the project unchanged.
4. Present a tag containing a foreign URL or multi-record payload. Confirm normal tapping is
   ignored and programming does not overwrite it.
5. Present a valid Blocks tag unknown to the current retained account during **Set tag**.
   Confirm the first tap does not overwrite it. Replacement must require an explicit warning
   followed by another tap of that same tag. Cancel instead unless this is a disposable tag.
6. With a different retained account active, tap a tag known only to the first account. Confirm
   the result is indistinguishable from an unknown tag and reveals no other-account project or
   block information.
7. Scan a malformed QR, an unsupported-version Blocks URI, and an unrelated QR. Confirm each is
   rejected safely and Dashboard remains usable.

## Result record

Do not include raw NFC/QR locators, tokens, credentials, personal data, project text, or block
labels in this record.

| Item | Result |
|---|---|
| Device model / Android version | |
| APK filename / SHA-256 | |
| Source commit / version | |
| Signing certificate SHA-1 / SHA-256 | |
| Upgrade retained existing local data | PASS / FAIL |
| Writable tag program and exact read-back | PASS / FAIL |
| Force-stop, offline tap, correct project | PASS / FAIL |
| Single launch/back stack | PASS / FAIL |
| Multiple tags | PASS / FAIL |
| Reassign without rewrite | PASS / FAIL |
| Unbind and contained assignment | PASS / FAIL |
| Archived project behavior | PASS / FAIL |
| QR generation and camera scan | PASS / FAIL |
| Interrupted/failed write recovery | PASS / FAIL |
| Foreign/malformed/unknown handling | PASS / FAIL |
| Cross-account non-disclosure | PASS / FAIL |
| Notes | No sensitive values |

If any row fails, stop the pilot gate and report only a concise symptom, source build identity,
and non-sensitive reproduction step to `blocks-arch`. Do not merge, tag, or request signing
from this development branch.
