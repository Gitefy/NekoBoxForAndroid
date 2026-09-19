from PIL import Image, ImageDraw, ImageFilter
from pathlib import Path

src = Path(
    r"C:\Users\renos\.cursor\projects\c-Users-renos-Documents-Proxy-NekoBoxForAndroid\assets\c__Users_renos_AppData_Roaming_Cursor_User_workspaceStorage_b989f564ecc3019eb26ffca85a74933a_images_90a63504-8c97-489e-9ce0-ce9c5738db99-db0134b3-c70d-4c88-b3b4-194b93c8b0a1.jpg"
)
res = Path(r"C:\Users\renos\Documents\Proxy\NekoBoxForAndroid\app\src\main\res")
img = Image.open(src).convert("RGBA")

densities = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}
LAUNCHER_FILL = 0.54
ADAPTIVE_FILL = 0.465
MONO_PAD = 0.54


def circle_mask(im: Image.Image) -> Image.Image:
    im = im.copy()
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
    im.putalpha(mask)
    return im


def round_corners(im: Image.Image, radius_ratio=0.22) -> Image.Image:
    im = im.copy()
    r = int(im.size[0] * radius_ratio)
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, im.size[0] - 1, im.size[1] - 1), radius=r, fill=255
    )
    im.putalpha(mask)
    return im


def save_png(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG")
    print("wrote", path, im.size)


def ring_alpha(im: Image.Image) -> Image.Image:
    rgb = im.convert("RGB")
    ww, hh = rgb.size
    p = rgb.load()
    mask = Image.new("L", (ww, hh), 0)
    mp = mask.load()
    for y in range(hh):
        for x in range(ww):
            r, g, b = p[x, y]
            luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if luma > 28:
                mp[x, y] = min(255, int((luma - 28) * 1.6))
    mask = mask.filter(ImageFilter.MaxFilter(3))
    return mask.point(lambda v: 255 if v > 36 else 0)


def centered_ring(im: Image.Image) -> Image.Image:
    alpha = ring_alpha(im)
    color = im.convert("RGBA")
    color.putalpha(alpha)
    bbox = alpha.getbbox()
    if bbox is None:
        return im
    cropped = color.crop(bbox)
    side = max(cropped.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 255))
    canvas.paste(
        cropped,
        ((side - cropped.size[0]) // 2, (side - cropped.size[1]) // 2),
        cropped,
    )
    return canvas


def fit_on_canvas(art: Image.Image, size: int, fill: float, transparent=False) -> Image.Image:
    bg = (0, 0, 0, 0) if transparent else (0, 0, 0, 255)
    canvas = Image.new("RGBA", (size, size), bg)
    inner = max(1, int(size * fill))
    scaled = art.resize((inner, inner), Image.Resampling.LANCZOS)
    canvas.paste(scaled, ((size - inner) // 2, (size - inner) // 2), scaled)
    return canvas


ring = centered_ring(img)

for name, scale in densities.items():
    size = int(48 * scale)
    launcher = fit_on_canvas(ring, size, LAUNCHER_FILL)
    save_png(round_corners(launcher), res / f"mipmap-{name}" / "ic_launcher.png")
    save_png(circle_mask(launcher), res / f"mipmap-{name}" / "ic_launcher_round.png")

    fg_size = int(108 * scale)
    foreground = fit_on_canvas(ring, fg_size, ADAPTIVE_FILL, transparent=False)
    save_png(foreground, res / f"mipmap-{name}" / "ic_launcher_foreground.png")

alpha = ring_alpha(ring)
bbox = alpha.getbbox()
crop = alpha.crop(bbox)
side = max(crop.size)
pad = int(side * MONO_PAD)
sq = Image.new("L", (side + 2 * pad, side + 2 * pad), 0)
sq.paste(crop, ((sq.size[0] - crop.size[0]) // 2, (sq.size[1] - crop.size[1]) // 2))

mono_master = sq.resize((432, 432), Image.Resampling.LANCZOS)
mono = Image.new("RGBA", (432, 432), (255, 255, 255, 0))
mono.putalpha(mono_master)
save_png(mono, res / "drawable-nodpi" / "ic_egox_monochrome.png")

for name, scale in densities.items():
    size = int(24 * scale)
    n = sq.resize((size, size), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(n)
    save_png(out, res / f"drawable-{name}" / "ic_service_active.png")

print("done")
