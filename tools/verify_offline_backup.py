#!/usr/bin/env python3
"""Offline, independent readback of a Blocks .blocksbackup file; never contacts Service.

Requires Python cryptography. By default prints metadata/counts only. --out is for a
private, empty disposable test directory; it reconstructs JSON and cover bytes there.
It is NOT an importer and never replays queued operations.
"""

import argparse
import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"BLOCKSB1"
ITERATIONS = 600_000
HEADER_BYTES = 40
MAX_BYTES = 64 * 1024 * 1024 + HEADER_BYTES + 16


def read_archive(path: Path, passphrase: str) -> dict:
    if path.stat().st_size > MAX_BYTES:
        raise ValueError("backup exceeds the supported size")
    archive = path.read_bytes()
    if len(archive) < HEADER_BYTES + 16 or archive[:8] != MAGIC:
        raise ValueError("incomplete or unsupported backup")
    if struct.unpack(">I", archive[8:12])[0] != ITERATIONS:
        raise ValueError("unsupported backup key derivation")
    salt, nonce = archive[12:28], archive[28:40]
    key = hashlib.pbkdf2_hmac("sha256", passphrase.encode("utf-8"), salt, ITERATIONS, 32)
    try:
        plaintext = AESGCM(key).decrypt(nonce, archive[HEADER_BYTES:], archive[:HEADER_BYTES])
    except InvalidTag as exc:
        raise ValueError("wrong passphrase or damaged backup") from exc
    payload = json.loads(plaintext)
    if payload.get("formatVersion") != 1 or not payload.get("acceptedAccount", {}).get("tenantId"):
        raise ValueError("invalid backup payload")
    if payload.get("state", {}).get("localBlocks") or payload.get("state", {}).get("localBlockOperations"):
        raise ValueError("protected tag tokens must not be in a portable backup")
    block_metadata = payload.get("protectedBlockMetadata", [])
    operation_metadata = payload.get("protectedBlockOperationMetadata", [])
    if (len(block_metadata) != payload.get("excludedProtectedBlockRecords") or
            len(operation_metadata) != payload.get("excludedProtectedBlockOperations")):
        raise ValueError("protected tag metadata count does not match exclusions")
    block_fields = {
        "clientBlockId", "tokenFingerprint", "label", "state", "intendedProjectId",
        "boundProjectId", "registrationOperationId", "initialBindingOperationId",
        "createdAt", "verifiedAt", "activationMedium", "replacementTokenFingerprint",
        "failure", "serverBlockId",
    }
    operation_fields = {
        "operationId", "type", "clientBlockId", "tokenFingerprint", "label",
        "projectId", "dependsOnOperationId", "createdAt", "contractVersion",
    }
    for item, fields in [(item, block_fields) for item in block_metadata] + [
        (item, operation_fields) for item in operation_metadata
    ]:
        if not set(item) <= fields:
            raise ValueError("protected tag metadata has an unexpected field")
        if "clientBlockId" not in item:
            raise ValueError("protected tag metadata is incomplete")
        fingerprint = item.get("tokenFingerprint")
        if fingerprint is not None and not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
            raise ValueError("protected tag fingerprint is invalid")
    covers = payload.get("coverMedia", [])
    references = set()
    for item in covers:
        ref = item["reference"]
        if not ref or "/" in ref or "\\" in ref or ref in references:
            raise ValueError("invalid or duplicate cover reference")
        references.add(ref)
        raw = base64.b64decode(item["bytesBase64"], validate=True)
        if hashlib.sha256(raw).hexdigest() != item["sha256"]:
            raise ValueError("cover digest mismatch")
    expected = set()
    for encoded in payload["state"].get("projectCovers", []):
        cover = json.loads(encoded)
        expected.add(cover["displayContentRef"])
        expected.add(cover["thumbnailContentRef"])
    if references != expected:
        raise ValueError("cover media set does not match project records")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--out", type=Path, help="empty private disposable readback directory")
    args = parser.parse_args()
    try:
        payload = read_archive(args.archive, getpass.getpass("Backup passphrase: "))
        state = payload["state"]
        pending = sum(len(state.get(name, [])) for name in (
            "commands", "projectCommands", "projectItemOperations", "interactionOperations"
        ))
        print(f"VERIFIED format=1 app={payload['appVersion']} at={payload['exportedAt']}")
        print(f"projects={len(state['activeProjects']) + len(state['archivedProjects'])} "
              f"notes={len(state['projectNoteItems'])} links={len(state['projectResourceLinks'])} "
              f"parking={len(state['parkingRecords'])} pending={pending} "
              f"covers={len(state['projectCovers'])}")
        print(f"TOKEN-FREE TAG METADATA blocks={len(payload['protectedBlockMetadata'])} "
              f"operations={len(payload['protectedBlockOperationMetadata'])}; "
              "raw locators and replayable protected rows excluded")
        print(f"EXCLUDED raw_protected_blocks={payload['excludedProtectedBlockRecords']} "
              f"raw_protected_block_operations={payload['excludedProtectedBlockOperations']} "
              f"known_other_accounts={payload['excludedKnownOtherAccountPartitions']}; "
              "unbound partitions may also exist")
        if args.out:
            if args.out.exists():
                raise ValueError("readback destination must not already exist")
            args.out.mkdir(mode=0o700, parents=False)
            os.chmod(args.out, 0o700)
            manifest = args.out / "manifest.json"
            descriptor = os.open(manifest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as file:
                json.dump(payload, file, indent=2)
            covers_dir = args.out / "covers"
            covers_dir.mkdir(mode=0o700)
            for item in payload["coverMedia"]:
                target = covers_dir / item["reference"]
                descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "wb") as file:
                    file.write(base64.b64decode(item["bytesBase64"], validate=True))
            print("READBACK reconstructed to private disposable directory; no app import or replay")
        return 0
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"NOT VERIFIED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
