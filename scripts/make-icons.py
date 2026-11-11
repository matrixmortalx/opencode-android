#!/usr/bin/env python3
"""OpenCode for Android — launcher ikonlarını üretir.

Gereksinim: pip install pillow
Kullanım:   python3 scripts/make-icons.py
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "res")

WHITE = (240, 246, 252, 255)
CYAN = (45, 212, 191, 255)

# Büyük tuvalde çizip küçültüyoruz (daha pürüzsüz sonuç için).
BIG = 768

# Yoğunluk -> ikon boyutu (piksel)
SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def rounded_gradient_background(size: int) -> Image.Image:
    """Koyu, hafif gradyanlı yuvarlatılmış kare arka plan."""
    top, bottom = (13, 20, 33), (4, 46, 51)
    gradient = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(gradient)
    for y in range(size):
        t = y / max(size - 1, 1)
        color = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        draw.line([(0, y), (size, y)], fill=color)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * 0.225), fill=255
    )
    background = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    background.paste(gradient, (0, 0), mask)
    return background


def draw_glyph(img: Image.Image, size: int) -> None:
    """Merkeze "</>" biçiminde bir glif çizer."""
    draw = ImageDraw.Draw(img)
    lw = max(int(size * 0.075), 3)
    half = lw // 2

    def dot(x: int, y: int, color) -> None:
        draw.ellipse([x - half, y - half, x + half, y + half], fill=color)

    def rline(p1, p2, color) -> None:
        a = (int(p1[0] * size), int(p1[1] * size))
        b = (int(p2[0] * size), int(p2[1] * size))
        draw.line([a, b], fill=color, width=lw)
        dot(a[0], a[1], color)
        dot(b[0], b[1], color)

    # "<"
    rline((0.407, 0.370), (0.305, 0.500), WHITE)
    rline((0.305, 0.500), (0.407, 0.630), WHITE)
    # "/"
    rline((0.556, 0.324), (0.463, 0.676), CYAN)
    # ">"
    rline((0.593, 0.370), (0.694, 0.500), WHITE)
    rline((0.694, 0.500), (0.593, 0.630), WHITE)


def main() -> None:
    for density, size in SIZES.items():
        img = rounded_gradient_background(BIG)
        draw_glyph(img, BIG)
        out_dir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        out_path = os.path.join(out_dir, "ic_launcher.png")
        img.resize((size, size), Image.LANCZOS).save(out_path)
        print(f"  ✔ {out_path}")


if __name__ == "__main__":
    main()
