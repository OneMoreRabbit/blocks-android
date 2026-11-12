#!/usr/bin/env python3
"""Independent offline readback of a Blocks unencrypted .blocksbackup file.

The archive contains private project text, images, raw NFC locators and pending
operations. Do not upload or share it. --out writes a private disposable directory.
This reader never imports, signs in, synchronizes or programs tags.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys

MAGIC = b"BLOCKSB2"
HEADER_BYTES = 44
MAX_PAYLOAD_BYTES = 128 * 1024 * 1024


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def read_archive(path: Path) -> dict:
    if path.stat().st_size > MAX_PAYLOAD_BYTES + HEADER_BYTES:
        raise ValueError("backup exceeds the supported size")
    archive = path.read_bytes()
    if len(archive) < HEADER_BYTES + 1 or archive[:8] != MAGIC:
        raise ValueError("incomplete or unsupported backup")
    length = struct.unpack(">I", archive[8:12])[0]
    if length < 1 or length > MAX_PAYLOAD_BYTES or len(archive) != HEADER_BYTES + length:
        raise ValueError("backup length does not match")
    raw = archive[HEADER_BYTES:]
    if hashlib.sha256(raw).digest() != archive[12:44]:
        raise ValueError("backup checksum does not match")
    envelope = json.loads(raw, object_pairs_hook=no_duplicate_keys)
    if set(envelope) != {"manifest", "payload"}:
        raise ValueError("backup envelope is invalid")
    manifest = envelope["manifest"]
    payload = envelope["payload"]
    separator = b',"payload":'
    if raw.count(separator) != 1 or not raw.endswith(b"}"):
        raise ValueError("backup payload encoding is invalid")
    payload_raw = raw.split(separator, 1)[1][:-1]
    if (manifest.get("formatVersion") != 2 or
            manifest.get("appVersion") != payload.get("appVersion") or
            manifest.get("stateSchemaVersion") != payload.get("state", {}).get("schemaVersion") or
            manifest.get("payloadSha256") != hashlib.sha256(payload_raw).hexdigest()):
        raise ValueError("backup manifest does not match payload")
    row_counts = {name: len(value) for name, value in payload["state"].items() if isinstance(value, list)}
    row_counts["coverMedia"] = len(payload.get("coverMedia", []))
    if manifest.get("rowCounts") != row_counts:
        raise ValueError("backup row counts do not match")
    if set(payload) != {
        "formatVersion", "appVersion", "exportedAt", "acceptedAccount", "state",
        "drafts", "coverMedia", "excludedKnownOtherAccountPartitions",
    } or payload["formatVersion"] != 2:
        raise ValueError("unsupported backup payload")
    if not payload.get("acceptedAccount", {}).get("tenantId"):
        raise ValueError("missing account partition")
    covers = payload.get("coverMedia", [])
    references = set()
    for item in covers:
        ref = item["reference"]
        if not re.fullmatch(r"[0-9a-fA-F-]{36}-r[1-9][0-9]*-(display|thumbnail)\.enc", ref):
            raise ValueError("invalid cover reference")
        if ref in references:
            raise ValueError("duplicate cover reference")
        references.add(ref)
        image = base64.b64decode(item["bytesBase64"], validate=True)
        if hashlib.sha256(image).hexdigest() != item["sha256"]:
            raise ValueError("cover digest mismatch")
    expected = set()
    for encoded in payload["state"].get("projectCovers", []):
        cover = json.loads(encoded, object_pairs_hook=no_duplicate_keys)
        expected.add(cover["displayContentRef"])
        expected.add(cover["thumbnailContentRef"])
    if references != expected:
        raise ValueError("cover media set does not match project records")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--out", type=Path, help="new private disposable readback directory")
    args = parser.parse_args()
    try:
        payload = read_archive(args.archive)
        state = payload["state"]
        pending = sum(len(state.get(name, [])) for name in (
            "commands", "projectCommands", "projectItemOperations",
            "interactionOperations", "localBlockOperations",
        ))
        print(f"VERIFIED format=2 app={payload['appVersion']} at={payload['exportedAt']}")
        print(f"projects={len(state['activeProjects']) + len(state['archivedProjects'])} "
              f"notes={len(state['projectNoteItems'])} links={len(state['projectResourceLinks'])} "
              f"parking={len(state['parkingRecords'])} pending={pending} "
              f"covers={len(state['projectCovers'])} nfc={len(state['localBlocks'])}")
        print(f"known_other_accounts_not_included={payload['excludedKnownOtherAccountPartitions']}")
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
            print("READBACK reconstructed in private disposable directory; no app import or replay")
        return 0
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"NOT VERIFIED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
