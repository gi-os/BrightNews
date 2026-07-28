#!/usr/bin/env python3
"""Draw the launcher icon: a single white letter on black, matching the other Light tools.

    python3 scripts/generate_icon.py            # defaults to R, for RSS
    python3 scripts/generate_icon.py --letter S --font /path/to/PublicSans-Regular.ttf

Writes plain bitmap mipmaps into tool/src/main/res. Deliberately no adaptive icon: tools that
read an app's icon out of the package, Obtainium among them, hand back nothing when the icon
resolves to an AdaptiveIconDrawable rather than a bitmap. Public Sans is the face used
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

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
]


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

    print(f"Wrote '{letter}' icons into {args.res}")


if __name__ == "__main__":
    main()
