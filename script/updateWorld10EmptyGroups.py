#!/usr/bin/env python3
"""Freeze the coverage-43 mostly-empty selection for the next superpipeline run."""

import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import struct

FOLDER = Path(__file__).resolve().parent
PYRAMID = Path("/samples/datasets/googleEarth/toplevel")
CACHE = Path("/media/ramdisk/43_pyramidalImageCoverage-toplevel-level-10.flags")


def inventory():
    present = set()
    if CACHE.exists():
        data = CACHE.read_bytes()
        magic, version, depth, count = struct.unpack(">4I", data[:16])
        if (magic, version, depth, len(data)) != (0x50494346, 1, 10, 16 + count * 8):
            raise ValueError("Invalid coverage-43 level-10 cache")
        present.update(struct.unpack(f">{count}Q", data[16:]))
    stack = [(PYRAMID, 0)]
    folders = 0
    while stack:
        folder, depth = stack.pop()
        folders += 1
        with os.scandir(folder) as entries:
            for entry in entries:
                if re.fullmatch(r"0[0-3]{10}\.png", entry.name, re.IGNORECASE) and entry.is_file():
                    key = entry.name[:-4]
                    parts = Path(entry.path).relative_to(PYRAMID).parts
                    digit_layout = tuple(key[1:]) + (entry.name,)
                    legacy_layout = tuple(key[:i] for i in range(2, len(key) + 1)) + (entry.name,)
                    if parts not in (digit_layout, legacy_layout):
                        continue
                    column = row = 0
                    for digit in key[1:]:
                        column = column * 2 + (digit in "12")
                        row = row * 2 + (digit in "23")
                    present.add(row * 1024 + column)
                elif depth < 10 and entry.is_dir(follow_symlinks=False):
                    stack.append((Path(entry.path), depth + 1))
        if folders % 50000 == 0:
            print(f"Scanned {folders} folders; {len(present)} level-10 cells", flush=True)
    return present


def select_groups(present, minimum, northern_band_only=False):
    groups = []
    span = 360 / 1024
    bands = [(latitude, 8) for latitude in range(-60, 60, 8)]
    if northern_band_only:
        bands = []
    bands.append((59, 9))
    for latitude, height in bands:
        r0 = math.ceil((latitude + 180) / span - .5)
        r1 = math.floor((latitude + height + 180) / span - .5)
        for longitude in range(-180, 180, 8):
            c0 = math.ceil((longitude + 180) / span - .5)
            c1 = math.floor((longitude + 188) / span - .5)
            total = (r1 - r0 + 1) * (c1 - c0 + 1)
            missing = sum(row * 1024 + col not in present
                          for row in range(r0, r1 + 1) for col in range(c0, c1 + 1))
            forced = northern_band_only and latitude == 59
            if forced or missing * 100 >= total * minimum:
                groups.append(dict(latitude=latitude, longitude=longitude,
                    latitudeSpan=height, longitudeSpan=8, completionLevel=10, mergeLevelOnly=10,
                    center=f"{latitude + height / 2:g},{longitude + 4}",
                    routeCommand=f"./run.sh zigzag {latitude} {longitude} 180000 3200 {90000 if latitude == 59 else 100000} {height} 8",
                    captureStep=180000, captureHeight=90000 if latitude == 59 else 100000,
                    force=forced,
                    missingTiles=missing, checkedTiles=total,
                    missingPercent=round(missing * 100 / total, 6)))
    return groups


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minimum-missing-percent", type=int, default=95, choices=range(1, 101))
    parser.add_argument("--northern-band-only", action="store_true",
                        help="Recapture all 45 global groups at 59–68 N, including existing coverage")
    args = parser.parse_args()
    present = inventory()
    jobs = select_groups(present, args.minimum_missing_percent, args.northern_band_only)
    now = datetime.now(timezone.utc)
    batch = f"{args.minimum_missing_percent}-{now.strftime('%Y%m%dT%H%M%SZ')}"
    if args.northern_band_only:
        batch = "north59-68-" + batch
    for sequence, job in enumerate(jobs, 1):
        job.update(sequence=sequence, total=len(jobs),
            jobId=f"world10-empty-groups-{batch}-{sequence:06d}-{job['latitude']}-{job['longitude']}")
    metadata = dict(createdAt=now.isoformat(), batchId=batch, pyramid=str(PYRAMID),
        selection="NORTHERN_RECAPTURE" if args.northern_band_only else "SHOW_MOSTLY_EMPTY_GROUPS", depth=10,
        minimumMissingPercent=args.minimum_missing_percent, groupDegrees=8,
        latitudeRange=[59, 68] if args.northern_band_only else [-60, 68],
        northernBand=dict(latitude=59, latitudeSpan=9, longitudeSpan=8, forced=args.northern_band_only),
        longitudeRange=[-180, 180],
        source="Level-10 PNG index plus affirmative viewer-43 cache; ancestors excluded",
        presentLevel10Pngs=len(present), selectedGroups=len(jobs),
        criterion="Forced northern recapture, including existing PNGs" if args.northern_band_only
            else f"missingTiles * 100 >= checkedTiles * {args.minimum_missing_percent}")
    for name, content in (("world10EmptyGroups.jsonl", "".join(json.dumps(j, separators=(",", ":")) + "\n" for j in jobs)),
                          ("world10EmptyGroups.selection.json", json.dumps(metadata, indent=2) + "\n")):
        target = FOLDER / name
        temporary = target.with_suffix(target.suffix + ".partial")
        temporary.write_text(content)
        temporary.replace(target)
    print(f"Selected {len(jobs)} groups; northern recapture={args.northern_band_only}; "
          f"inventory={len(present)} level-10 cells")


if __name__ == "__main__":
    main()
