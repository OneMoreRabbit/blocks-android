# Blocks Service API 0.7 contract artifacts

These files are byte-for-byte copies delivered by the current Atlas briefing from
`blocks-service-api` 0.7. The OpenAPI document is the build input for generated client
models; the event schemas are retained for synchronization compatibility tests.

To refresh them after an architecture-approved edge re-pin:

1. run `sh scripts/atlas-context.sh` with the current Atlas component scripts;
2. copy `ATLAS-CONTEXT.d/blocks-service-api/` into this directory without editing it;
3. update `SHA256SUMS` only from the hashes declared by the delivered contract; and
4. run `sha256sum --check contracts/blocks-service-api/SHA256SUMS`.

Never reconstruct a schema from contract prose or observed payloads.
