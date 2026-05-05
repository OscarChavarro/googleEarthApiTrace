#!/usr/bin/env python3
import argparse
import glob
import json
import os
import subprocess
from dataclasses import dataclass
from typing import List, Optional

try:
    from PIL import Image
except ImportError as exc:
    raise SystemExit("Este script requiere Pillow (`python3 -m pip install Pillow`).") from exc


@dataclass
class FrameDetection:
    image: str
    yellow_pixels: int
    best_row: Optional[int]
    best_row_yellow_pixels: int


REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PROGRAM23_DIR = os.path.join(REPO_ROOT, "23_frameTextureNormalizer")


def run_generator(start_frame: int, end_frame: int, output_file: str, clean_dir: bool) -> None:
    out_dir = os.path.dirname(output_file) or "."
    os.makedirs(out_dir, exist_ok=True)
    if clean_dir:
        for path in glob.glob(os.path.join(out_dir, "*.png")):
            os.remove(path)
    cmd = [
        "./gradlew",
        "-q",
        "run",
        f"--args=--offline --start-frame={start_frame} --end-frame={end_frame} --output={output_file}",
    ]
    env = dict(os.environ)
    env.setdefault("DISPLAY", ":1")
    env["GRADLE_USER_HOME"] = "/tmp/gradle-home"
    subprocess.run(cmd, check=True, cwd=PROGRAM23_DIR, env=env)


def detect_yellow_pixels(image_path: str, r_min: int, g_min: int, b_max: int) -> FrameDetection:
    with Image.open(image_path) as img:
        rgb = img.convert("RGB")
        w, h = rgb.size
        px = rgb.load()
        total = 0
        best_row = None
        best_row_count = 0
        for y in range(h):
            row_count = 0
            for x in range(w):
                r, g, b = px[x, y]
                if r >= r_min and g >= g_min and b <= b_max:
                    total += 1
                    row_count += 1
            if row_count > best_row_count:
                best_row_count = row_count
                best_row = y
        return FrameDetection(
            image=os.path.basename(image_path),
            yellow_pixels=total,
            best_row=best_row,
            best_row_yellow_pixels=best_row_count,
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Genera una secuencia con 23_frameTextureNormalizer y detecta línea amarilla por frame."
    )
    parser.add_argument("--start-frame", type=int, required=True)
    parser.add_argument("--end-frame", type=int, required=True)
    parser.add_argument("--output-dir", default="/tmp/output")
    parser.add_argument("--base-name", default="a.png")
    parser.add_argument("--clean-output", action="store_true")
    parser.add_argument("--r-min", type=int, default=200)
    parser.add_argument("--g-min", type=int, default=200)
    parser.add_argument("--b-max", type=int, default=120)
    parser.add_argument("--report", default=None)
    args = parser.parse_args()

    output_dir = os.path.abspath(args.output_dir)
    os.makedirs(output_dir, exist_ok=True)
    output_file = os.path.join(output_dir, args.base_name)

    run_generator(args.start_frame, args.end_frame, output_file, args.clean_output)

    generated = sorted(glob.glob(os.path.join(output_dir, "*.png")))
    if not generated:
        raise RuntimeError(f"No se generaron PNGs en {output_dir}")

    detections: List[FrameDetection] = [
        detect_yellow_pixels(path, args.r_min, args.g_min, args.b_max) for path in generated
    ]
    hits = [d for d in detections if d.best_row is not None and d.best_row_yellow_pixels > 0]

    report_path = args.report or os.path.join(output_dir, "yellow_line_report.json")
    payload = {
        "output_dir": output_dir,
        "images": len(generated),
        "hits": len(hits),
        "detections": [d.__dict__ for d in detections],
    }
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)

    print(f"Imágenes analizadas: {len(generated)}")
    print(f"Frames con posible línea amarilla: {len(hits)}")
    print(f"Reporte: {report_path}")


if __name__ == "__main__":
    main()
