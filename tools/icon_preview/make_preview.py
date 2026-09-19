from PIL import Image, ImageDraw, ImageFilter
from pathlib import Path

src = Path(
    r"C:\Users\renos\.cursor\projects\c-Users-renos-Documents-Proxy-NekoBoxForAndroid\assets\c__Users_renos_AppData_Roaming_Cursor_User_workspaceStorage_b989f564ecc3019eb26ffca85a74933a_images_90a63504-8c97-489e-9ce0-ce9c5738db99-db0134b3-c70d-4c88-b3b4-194b93c8b0a1.jpg"
)
out_dir = Path(__file__).resolve().parent
out_dir.mkdir(parents=True, exist_ok=True)

img = Image.open(src).convert("RGBA")
w, h = img.size
print("source", w, h, img.mode)


def round_corners(im, radius_ratio=0.22):
    im = im.copy()
    r = int(im.size[0] * radius_ratio)
    mask = Image.new("L", im.size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle((0, 0, im.size[0] - 1, im.size[1] - 1), radius=r, fill=255)
    im.putalpha(mask)
    return im


def circle_mask(im):
    im = im.copy()
    mask = Image.new("L", im.size, 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
    im.putalpha(mask)
    return im


def squircle_mask(im):
    im = im.copy()
    r = int(im.size[0] * 0.32)
    mask = Image.new("L", im.size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle((0, 0, im.size[0] - 1, im.size[1] - 1), radius=r, fill=255)
    im.putalpha(mask)
    return im


def on_dark(im, size=640):
    bg = Image.new("RGBA", (size, size), (12, 14, 20, 255))
    im2 = im.resize((512, 512), Image.Resampling.LANCZOS)
    bg.paste(im2, ((size - 512) // 2, (size - 512) // 2), im2)
    return bg.convert("RGB")


square = round_corners(img, 0.22)
roundi = circle_mask(img)
squir = squircle_mask(img)

on_dark(square).save(out_dir / "preview_launcher_square.png")
on_dark(roundi).save(out_dir / "preview_launcher_round.png")
on_dark(squir).save(out_dir / "preview_launcher_adaptive.png")

rgb = img.convert("RGB")
px = rgb.load()
mask = Image.new("L", img.size, 0)
mp = mask.load()
for y in range(h):
    for x in range(w):
        r, g, b = px[x, y]
        luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        if luma > 28:
            mp[x, y] = min(255, int((luma - 28) * 1.4))

mask = mask.filter(ImageFilter.MaxFilter(3))
mask = mask.point(lambda v: 255 if v > 40 else 0)
bbox = mask.getbbox()
print("mask bbox", bbox)
cx1, cy1, cx2, cy2 = bbox
cw, ch = cx2 - cx1, cy2 - cy1
side = max(cw, ch)
pad = int(side * 0.18)
nx1 = max(0, cx1 - (side - cw) // 2 - pad)
ny1 = max(0, cy1 - (side - ch) // 2 - pad)
nx2 = min(w, nx1 + side + 2 * pad)
ny2 = min(h, ny1 + side + 2 * pad)
crop = mask.crop((nx1, ny1, nx2, ny2))
s = max(crop.size)
sq = Image.new("L", (s, s), 0)
sq.paste(crop, ((s - crop.size[0]) // 2, (s - crop.size[1]) // 2))
notif = sq.resize((256, 256), Image.Resampling.LANCZOS)
white = Image.new("RGBA", (256, 256), (255, 255, 255, 0))
white.putalpha(notif)
bar = Image.new("RGB", (640, 200), (16, 16, 18))
d = ImageDraw.Draw(bar)
small = white.resize((72, 72), Image.Resampling.LANCZOS)
bar.paste(small.convert("RGB"), (40, 64), small)
d.text((130, 70), "VPN running", fill=(255, 255, 255))
d.text((130, 108), "notification small icon preview", fill=(160, 160, 170))
bar.save(out_dir / "preview_notification_statusbar.png")
white.save(out_dir / "preview_notification_silhouette.png")

sheet = Image.new("RGB", (1700, 720), (8, 9, 14))
d = ImageDraw.Draw(sheet)
items = [
    (on_dark(square), "Launcher square"),
    (on_dark(roundi), "Launcher round"),
    (on_dark(squir), "Adaptive / squircle"),
]
for i, (im, title) in enumerate(items):
    x = 40 + i * 540
    sheet.paste(im.resize((480, 480), Image.Resampling.LANCZOS), (x, 80))
    d.text((x + 20, 30), title, fill=(220, 220, 230))
sheet.save(out_dir / "preview_sheet.png")
print("wrote", [p.name for p in out_dir.iterdir()])
