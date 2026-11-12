# Offline backup (Android 0.10.2 candidate)

Blocks can export the **currently open local account** without a network connection or a fresh Google sign-in. This is a recovery archive, not a sync command and not an APK. Export does not send data to Blocks Service.

## Make a backup on the phone

1. Open the cached account in Blocks. In the dashboard, open **Settings → Export backup**.
2. Enter and confirm a passphrase of at least 12 characters. Keep it separately from the backup file: it cannot be recovered or reset.
3. Choose a location and name in Android's document picker. Keep Blocks open until it reports **Backup verified**. Check the counts, time, app version and excluded-data warning shown there.
4. Keep a second copy of the verified `.blocksbackup` file in a location you control. Do not share the file or passphrase in chat or issue trackers.

If the picker is cancelled, storage fails, or Blocks is interrupted, there is **no verified backup**. Blocks attempts to remove an incomplete document; if it cannot, delete that document yourself. The phone's original local data is not removed or replayed by export. Try again after the failure is resolved.

## What is in format 1

The authenticated-encrypted archive contains one open tenant's current Project database projection: active/archived Projects, Notes, Links, Park history and tombstones, ordering, local/server identity mappings, row versions, sync cursor, pending and retired operations with their original idempotency identities, UI preferences, drafts, accepted account identity binding, and decrypted cover media bytes with digests. A consistent read transaction includes committed SQLite WAL data. The UI stops its own background sync and waits for in-flight local work while capturing related draft/media stores.

It does **not** contain a Google or Service credential, private key, Android Keystore ciphertext, or raw NFC tag tokens. Protected local block records and block operations are excluded, with counts shown; their local tag associations may therefore need to be recreated. Other retained accounts remain locked and are excluded; make a separate backup while each account is deliberately open. A partition no longer present in the accepted-account index might exist and cannot be enumerated safely by this export. If there is no verified current local database or a required cover cannot be read, export fails rather than omitting it. Format 1 has a 64 MiB plaintext limit.

The archive is encrypted with PBKDF2-HMAC-SHA256 (600,000 iterations, per-file random salt) and AES-256-GCM (random nonce, authenticated header and content). Its decryption does not depend on the original phone's Keystore. The passphrase is not stored by Blocks.

## Verify and read back on a separate trusted computer

On a computer with Python 3 and the `cryptography` package, obtain this repository's `tools/verify_offline_backup.py` and run:

```sh
python3 tools/verify_offline_backup.py /path/to/Blocks-backup.blocksbackup
```

It prompts for the passphrase without putting it in shell history and reports verified format, counts and exclusions. For a **disposable private directory** containing the reconstructed JSON and cover bytes, add `--out /path/to/new-empty-directory`. The tool refuses an existing destination. Those readback files are plaintext; protect them and remove them when finished. No source-device key, network connection, Service credential, queue replay or app import is used.

This release has **no in-app restore/import action**. On a replacement device, authenticate to the rightful Blocks account first. Do not feed the archive's tenant mapping or pending operations into a live app or Service without a separately reviewed restore procedure that verifies identity and replay safety. A verified file plus independent readback protects the information; it does not by itself resume the app on a new phone. Do not uninstall, clear storage, sign out, or replace the installed signed app while protecting unsynced work.
