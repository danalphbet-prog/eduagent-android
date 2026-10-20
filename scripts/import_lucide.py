#!/usr/bin/env python3
"""Mechanically import the Lucide ZIP: full offline SVG catalog + native UI vectors.

Usage: python3 scripts/import_lucide.py /path/to/lucide-main.zip
Only icon SVGs are imported; package tooling, build scripts and unrelated ZIP
contents are deliberately excluded. Retain the upstream ISC/MIT notices.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
ICONS = ROOT / "app/src/main/assets/lucide"
DRAWABLE = ROOT / "app/src/main/res/drawable"
LICENSE = ROOT / "licenses/LUCIDE_LICENSE"
SELECTED = (
    "settings", "send", "plus", "brain", "mic", "copy", "download",
    "external-link", "terminal", "folder-open", "file-code", "play",
    "check", "x", "info", "image", "sparkles", "file-text", "trash",
)
ANDROID_NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID_NS)


def shape_path(element: ET.Element) -> str | None:
    tag = element.tag.split("}")[-1]
    get = element.get
    if tag == "path":
        return get("d")
    if tag == "line":
        return f'M{get("x1")},{get("y1")} L{get("x2")},{get("y2")}'
    if tag in ("polyline", "polygon"):
        points = re.findall(r"-?\d+(?:\.\d+)?", get("points", ""))
        if len(points) < 4 or len(points) % 2:
            raise ValueError(f"Invalid SVG points in {tag}")
        path = "M" + points[0] + "," + points[1]
        path += " ".join("L" + points[i] + "," + points[i + 1] for i in range(2, len(points), 2))
        return path + (" Z" if tag == "polygon" else "")
    if tag == "circle":
        cx, cy, radius = (float(get(name, "0")) for name in ("cx", "cy", "r"))
        return f"M{cx-radius},{cy} a{radius},{radius} 0 1,0 {radius*2},0 a{radius},{radius} 0 1,0 {-radius*2},0"
    if tag == "rect":
        x, y = float(get("x", "0")), float(get("y", "0"))
        w, h = float(get("width", "0")), float(get("height", "0"))
        rx = min(float(get("rx", get("ry", "0"))), w / 2, h / 2)
        return (f"M{x+rx},{y} H{x+w-rx} Q{x+w},{y} {x+w},{y+rx} "
                f"V{y+h-rx} Q{x+w},{y+h} {x+w-rx},{y+h} "
                f"H{x+rx} Q{x},{y+h} {x},{y+h-rx} "
                f"V{y+rx} Q{x},{y} {x+rx},{y} Z")
    if tag == "ellipse":
        cx, cy, rx, ry = (float(get(name, "0")) for name in ("cx", "cy", "rx", "ry"))
        return f"M{cx-rx},{cy} a{rx},{ry} 0 1,0 {rx*2},0 a{rx},{ry} 0 1,0 {-rx*2},0"
    return None


def vector_from_svg(data: bytes) -> bytes:
    svg = ET.fromstring(data)
    vector = ET.Element("vector", {
        f"{{{ANDROID_NS}}}width": "24dp",
        f"{{{ANDROID_NS}}}height": "24dp",
        f"{{{ANDROID_NS}}}viewportWidth": "24",
        f"{{{ANDROID_NS}}}viewportHeight": "24",
    })
    for el in svg.iter():
        shape = shape_path(el)
        if not shape:
            continue
        fill = el.get("fill", svg.get("fill", "none"))
        stroke = el.get("stroke", svg.get("stroke", "currentColor"))
        ET.SubElement(vector, "path", {
            f"{{{ANDROID_NS}}}pathData": shape,
            f"{{{ANDROID_NS}}}fillColor": "#FFFFFFFF" if fill != "none" else "#00000000",
            f"{{{ANDROID_NS}}}strokeColor": "#00000000" if stroke == "none" else "#FFFFFFFF",
            f"{{{ANDROID_NS}}}strokeWidth": el.get("stroke-width", svg.get("stroke-width", "2")),
            f"{{{ANDROID_NS}}}strokeLineCap": el.get("stroke-linecap", svg.get("stroke-linecap", "round")),
            f"{{{ANDROID_NS}}}strokeLineJoin": el.get("stroke-linejoin", svg.get("stroke-linejoin", "round")),
        })
    if not list(vector):
        raise ValueError("SVG icon contains no supported shape")
    return ET.tostring(vector, encoding="utf-8", xml_declaration=True)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: import_lucide.py lucide-main.zip")
    ICONS.mkdir(parents=True, exist_ok=True)
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    LICENSE.parent.mkdir(parents=True, exist_ok=True)
    found: dict[str, bytes] = {}
    with ZipFile(sys.argv[1]) as bundle:
        for name in bundle.namelist():
            match = re.fullmatch(r"lucide-main/icons/([a-z0-9-]+)\.svg", name)
            if not match:
                continue
            icon = match.group(1)
            data = bundle.read(name)
            ET.fromstring(data)
            (ICONS / f"{icon}.svg").write_bytes(data)
            if icon in SELECTED:
                found[icon] = data
        LICENSE.write_bytes(bundle.read("lucide-main/LICENSE"))

    missing = set(SELECTED) - set(found)
    if missing:
        raise ValueError(f"Missing required Lucide icons: {sorted(missing)}")
    for icon, data in found.items():
        vector_name = "ic_lucide_" + icon.replace("-", "_") + ".xml"
        (DRAWABLE / vector_name).write_bytes(vector_from_svg(data))
    print(f"Imported {len(list(ICONS.glob('*.svg')))} offline Lucide SVG icons and {len(found)} Android vectors")


if __name__ == "__main__":
    main()
