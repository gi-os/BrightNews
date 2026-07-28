#!/usr/bin/env python3
"""Draw the launcher icon: a single white letter on black, matching the other Light tools.

    python3 scripts/generate_icon.py            # defaults to R, for RSS
    python3 scripts/generate_icon.py --letter S --font /path/to/PublicSans-Regular.ttf

Writes legacy mipmaps plus an adaptive icon into tool/src/main/res. Public Sans is the face used
by the sibling tools; it is not vendored here, so pass --font to reproduce exactly. Without it the
script falls back to whatever DejaVu Sans the system has, which is close enough to re-render.
"""

import argparse
import os
from PIL import Image, ImageDraw, ImageFont

# Density buckets Android expects, and the launcher icon size in each.
LEGACY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# An adaptive icon is drawn at 108dp and masked down to roughly the middle 72dp.
ADAPTIVE_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
]

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

COLORS_XML = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#000000</color>
</resources>
"""


def load_font(path, size):
    for candidate in [path] + FONT_CANDIDATES:
        if candidate and os.path.exists(candidate):
            return ImageFont.truetype(candidate, size)
    raise SystemExit("No usable TrueType font found; pass --font")


def draw(letter, size, font_path, transparent, cap_fraction):
    """One square icon. `cap_fraction` is the letter height as a share of the canvas."""
    background = (0, 0, 0, 0) if transparent else (0, 0, 0, 255)
    image = Image.new("RGBA", (size, size), background)
    canvas = ImageDraw.Draw(image)

    # Size the face so the letter's own bounding box, not its line height, fills the target.
    target = size * cap_fraction
    points = max(8, int(target * 1.35))
    for _ in range(64):
        font = load_font(font_path, points)
        box = canvas.textbbox((0, 0), letter, font=font)
        height = box[3] - box[1]
        if height <= target or points <= 8:
            break
        points -= max(1, int(points * 0.04))

    font = load_font(font_path, points)
    box = canvas.textbbox((0, 0), letter, font=font)
    x = (size - (box[2] - box[0])) / 2 - box[0]
    y = (size - (box[3] - box[1])) / 2 - box[1]
    canvas.text((x, y), letter, font=font, fill=(255, 255, 255, 255))
    return image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--letter", default="R", help="single character to draw")
    parser.add_argument("--font", default=None, help="TrueType font to use")
    parser.add_argument("--res", default="tool/src/main/res", help="resource directory")
    args = parser.parse_args()

    letter = args.letter.strip().upper()[:1] or "R"

    for bucket, size in LEGACY_SIZES.items():
        directory = os.path.join(args.res, bucket)
        os.makedirs(directory, exist_ok=True)
        icon = draw(letter, size, args.font, transparent=False, cap_fraction=0.62)
        icon.save(os.path.join(directory, "ic_launcher.png"))
        icon.save(os.path.join(directory, "ic_launcher_round.png"))

    # Adaptive foreground: same letter, transparent field, sized for the mask's safe zone.
    for bucket, size in ADAPTIVE_SIZES.items():
        directory = os.path.join(args.res, bucket)
        os.makedirs(directory, exist_ok=True)
        draw(letter, size, args.font, transparent=True, cap_fraction=0.40).save(
            os.path.join(directory, "ic_launcher_foreground.png")
        )

    anydpi = os.path.join(args.res, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w") as handle:
            handle.write(ADAPTIVE_XML)

    values = os.path.join(args.res, "values")
    os.makedirs(values, exist_ok=True)
    with open(os.path.join(values, "ic_launcher_background.xml"), "w") as handle:
        handle.write(COLORS_XML)

    print(f"Wrote '{letter}' icons into {args.res}")


if __name__ == "__main__":
    main()
