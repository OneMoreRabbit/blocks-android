---
title: Release notes — blocks-android
status: active
updated: 2026-11-13
owner: blocks.component.blocks-android
about: Android release changes and operator action
---

# Release notes — blocks-android

## v0.10.3 — 2026-11-13

**TL;DR:** Explicitly reconnect the same account to resume cloud sync of saved changes; see what is still pending or deferred without interrupting local work.

- Version name 0.10.3; version code 27.
- Opening Blocks still makes no automatic Google identity call. The cached dashboard and local work remain available offline.
- **Settings → Reconnect** requires Service to confirm the previously accepted account and tenant before queued work is sent. A different account locks access rather than switching into another tenant's data.
- A small sync indicator opens a status explanation for cloud-pending, deferred, and local-only NFC changes. Covers and local NFC records are not cloud-synced by this release.
- Backup/export and empty-installation restore remain available. This update does not silently restore cloud authorization after process death or token expiry.

**Action:** Before installing a separately approved custody-signed APK, make a fresh current-account backup in the existing app and verify a private copy using [the backup guide](docs/offline-backup.md). Install only as a same-certificate in-place upgrade: do not uninstall, clear storage, sign out, switch accounts or repoint the Service. After installation, choose Reconnect deliberately with the **same** Google account; preserve the backup and check pending status until independent Service/Web readback confirms convergence. A built APK alone is not proof that the owner's backup exists or that pending work has synchronized.

## v0.10.2 — 2026-11-12

**TL;DR:** Back up the open account while offline, then restore that file into a separate empty Blocks installation.

- Version name 0.10.2; version code 26.
- Settings includes **Export backup**; the sign-in screen includes **Restore backup**. Export needs no network or fresh sign-in.
- The backup includes the open account's projects, notes, links, parks, images, NFC tag locators and unsent changes. Other locked accounts and authentication credentials are not included.
- **The file is not encrypted. Anyone holding it can read its contents.** Keep it in a private location; a checksum detects damage but does not prove ownership.
- Restore refuses a non-empty installation. It opens recovered work locally without granting Service access or automatically sending pending changes.

**Action:** Only after the custody-signed APK is published and installation is approved, install it over the existing app without uninstalling, clearing storage, signing out or switching accounts. Follow [the backup guide](docs/offline-backup.md) to export and verify a private copy. Never test restore on the live source phone. A signed APK alone does not mean the owner's backup exists.

## v0.10.1 — 2026-10-08

**TL;DR:** Project Note and Link actions are quieter and more compact without changing how they work.

- Version name 0.10.1; version code 25.
- Edit, reorder and delete use smaller, neutral outline icons.
- Action groups sit closer together and farther right while keeping separate 48dp touch targets.
- Project data, local persistence, synchronization and API behavior are unchanged.

**Action:** When the custody-signed APK is available, install it over the existing app. Do not uninstall the app or clear its storage.
