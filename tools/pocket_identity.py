"""Export the two PICO launcher layers, their signed distances and the Android icon."""
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import distance_transform_edt

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ART = ROOT / "art/identity"
SIZE = 1024


def sdf(image):
    inside = np.asarray(image.getchannel("A")) >= 128
    signed = distance_transform_edt(inside) - distance_transform_edt(~inside)
    values = np.clip(127.5 + signed * 5, 0, 255).astype(np.uint8)
    return Image.fromarray(np.stack([values] * 4, axis=-1))


def main():
    y, x = np.mgrid[:SIZE, :SIZE]
    base = Image.open(ART / "sprint-ridges.png").convert("RGBA").resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    disc = np.clip((502 - np.hypot(x - 512, y - 512)) * 255, 0, 255).astype(np.uint8)
    base.putalpha(Image.fromarray(disc))
    runner = Image.open(ART / "sprint-runner.png").convert("RGBA")
    runner = runner.crop(runner.getchannel("A").point(lambda a: 255 if a >= 4 else 0).getbbox())
    runner.thumbnail((800, 802), Image.Resampling.LANCZOS)
    front = Image.new("RGBA", base.size)
    front.alpha_composite(runner, ((SIZE-runner.width)//2 + 20, (SIZE-runner.height)//2 - 8))
    for index, layer in enumerate((base, front)):
        layer.save(RES / f"drawable/icon_3d_layer_{index}.png")
        sdf(layer).save(RES / f"drawable/icon_3d_sdf_{index}.png")
    Image.alpha_composite(base, front).save(RES / "mipmap-xxxhdpi/ic_spatial_launcher.png")


if __name__ == "__main__":
    main()
