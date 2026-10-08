# Blocks Service API 0.7 contract artifacts

These files are the frozen byte-for-byte API 0.7 delivery retained for compatibility
verification after the active edge advanced to API 0.8.

To refresh them after an architecture-approved edge re-pin:

1. run `sh scripts/atlas-context.sh` with the current Atlas component scripts;
2. copy `ATLAS-CONTEXT.d/blocks-service-api/` into the active contract directory without editing it;
3. update `SHA256SUMS` only from the hashes declared by the delivered contract; and
4. run the Gradle contract-verification tasks.

Never reconstruct a schema from contract prose or observed payloads.
