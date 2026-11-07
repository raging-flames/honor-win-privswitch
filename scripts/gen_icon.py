"""生成应用图标（需 Pillow）：python scripts/gen_icon.py"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "res" / "drawable" / "ic_launcher.png"

SIZE = 512
BG = (27, 94, 32, 255)        # 深绿
FG = (255, 255, 255, 255)     # 白
HOLE = (27, 94, 32, 255)      # 钥匙孔


def shield_points(cx, top, w, h):
    half = w / 2
    return [
        (cx, top),
        (cx + half, top + h * 0.16),
        (cx + half, top + h * 0.52),
        (cx, top + h),
        (cx - half, top + h * 0.52),
        (cx - half, top + h * 0.16),
    ]


def main():
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([8, 8, SIZE - 8, SIZE - 8], radius=112, fill=BG)

    pts = shield_points(SIZE / 2, SIZE * 0.20, SIZE * 0.52, SIZE * 0.60)
    d.polygon(pts, fill=FG)

    cx, cy = SIZE / 2, SIZE * 0.47
    r = SIZE * 0.055
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=HOLE)
    d.polygon(
        [
            (cx - r * 0.55, cy + r * 0.4),
            (cx + r * 0.55, cy + r * 0.4),
            (cx + r * 0.9, cy + r * 2.6),
            (cx - r * 0.9, cy + r * 2.6),
        ],
        fill=HOLE,
    )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    img.resize((192, 192), Image.LANCZOS).save(ROOT / "docs" / "screenshots" / "icon.png")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
