# Frozen Blocks Service API 0.2 compatibility artifacts

These are the previously verified API 0.2 bytes used only to compile the installed-pilot
compatibility adapter. That adapter may replay already-persisted 0.2 operations with their
original endpoint, body, concurrency value, and idempotency identity; new product actions
must never enter the legacy queue.

This directory is frozen for the compatibility window. Do not refresh it from the active
edge. The active API 0.8 artifacts live in `../blocks-service-api/`; frozen API 0.7, 0.6 and 0.4
artifacts live in `../blocks-service-api-legacy-v0_6/` and
`../blocks-service-api-legacy-v0_4/`.

Run `sha256sum --check contracts/blocks-service-api-legacy-v0_2/SHA256SUMS` before
building the generated compatibility model set.

Never reconstruct a schema from contract prose or observed payloads.
