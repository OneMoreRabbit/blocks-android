# Offline backup and restore (Android 0.10.2)

Blocks can export the currently open account without a network connection or fresh Google sign-in. This is not sync and does not send anything to Blocks Service. **The backup is not encrypted. Anyone holding the file can read its projects, notes, images, NFC tag locators and unsent changes.** Save it only in a private place you control; do not share or automatically upload it.

These steps apply only after the custody-signed 0.10.2 APK is published and its in-place installation is approved. Until then, keep the owner's installed app and data unchanged. Do not use a test or unsigned APK on the owner's phone.

## Back up the existing phone

1. Leave the existing account open. Do not sign out, switch accounts, uninstall Blocks, clear its storage or point it at another Service.
2. On the dashboard, choose **Settings → Export backup**. Read the warning, then choose a private file location with Android's document picker.
3. Keep Blocks open until it says **Verified current-account export**. Record the displayed counts, format, app version and time. A cancelled picker, failed storage, or interrupted write is **not** a verified backup. Blocks tries to remove an incomplete file; if it cannot, delete that incomplete file at the chosen location.
4. Make a separate private copy of the verified `.blocksbackup` file. Do not discard the source phone's data.

Format 2 includes the open account's active and archived Projects, Notes, Links, Park history and tombstones, ordering, drafts, project identity mappings, local/server versions, sync cursor, pending and retired operations with original replay identities, every referenced cover variant, and full NFC/block records and pending operations including raw tag locators. It reads the tenant database in one consistent SQLite transaction including committed WAL pages, then captures related draft and cover stores after foreground work is quiescent. Missing required data fails export rather than being silently omitted. Google/Service credentials, session tokens and private keys are never included. Other locked accounts are not unlocked or included; do not switch to them during this incident.

The archive has a version and SHA-256 checksum for its complete payload; each image has its own checksum. Checksums detect corruption, **not** forgery or ownership. Format 1 encrypted/passphrase archives from the earlier unshipped candidate are unsupported by this restore. The current format has a 128 MiB payload limit and fails clearly if exceeded.

## Verify a copy on a trusted computer

With Python 3, run:

```sh
python3 tools/verify_offline_backup.py /path/to/Blocks-backup.blocksbackup
```

The reader reports format and counts without contacting Service. To reconstruct JSON and image bytes in a **new private disposable directory**, add `--out /path/to/new-empty-directory`. These files are unencrypted. Protect and remove test copies after verification.

## Restore on a different, empty installation

On a separate fresh device or fresh disposable installation, open Blocks' sign-in screen and choose **Restore backup**. Select the verified file using Android's document picker, inspect the account/record counts and confirm. Restore is refused if that installation already contains retained Blocks account data; it never overwrites it. Do **not** clear or uninstall the original phone to make room.

The restored account opens locally offline. Blocks recreates its local Keystore protection on the destination device. It does not import a Google credential, treat the archived identity as Service authorization, automatically sync/replay the pending queue, or program a physical tag. To resume cross-device sync later, explicitly sign in to the **same** Google account and let Service independently confirm the exact account and tenant. A mismatch locks the restored data without exposing it to the different account.

You can make another current-account export from that locally restored account while offline; that does not grant it Service authority or start synchronization.

A verified archive and a tested restore on a disposable installation are not proof that the owner's phone has been backed up. The owner must complete and verify their own export after a separately approved signed in-place update. Never test restore on the live source phone.
