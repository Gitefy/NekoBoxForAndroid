from PIL import Image, ImageDraw, ImageFilter, ImageFont
from pathlib import Path

root = Path(__file__).resolve().parent
src = Path(
    r"C:\Users\renos\.cursor\projects\c-Users-renos-Documents-Proxy-NekoBoxForAndroid\assets\c__Users_renos_AppData_Roaming_Cursor_User_workspaceStorage_b989f564ecc3019eb26ffca85a74933a_images_90a63504-8c97-489e-9ce0-ce9c5738db99-db0134b3-c70d-4c88-b3b4-194b93c8b0a1.jpg"
)
old_path = Path(
    r"C:\Users\renos\Documents\Proxy\NekoBoxForAndroid\app\src\main\res\mipmap-xxxhdpi\ic_launcher.png"
)

new_img = Image.open(src).convert("RGBA")
old_img = Image.open(old_path).convert("RGBA")


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
    ImageDraw.Draw(mask).ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
    im.putalpha(mask)
    return im


def try_font(size, bold=False):
    candidates = [
        r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf",
        r"C:\Windows\Fonts\arial.ttf",
    ]
    for p in candidates:
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            continue
    return ImageFont.load_default()


def ring_mask(im):
    rgb = im.convert("RGB")
    w, h = rgb.size
    px = rgb.load()
    mask = Image.new("L", (w, h), 0)
    mp = mask.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if luma > 28:
                mp[x, y] = 255
    mask = mask.filter(ImageFilter.MaxFilter(3))
    bbox = mask.getbbox()
    crop = mask.crop(bbox)
    s = max(crop.size)
    pad = int(s * 0.18)
    sq = Image.new("L", (s + 2 * pad, s + 2 * pad), 0)
    sq.paste(crop, ((sq.size[0] - crop.size[0]) // 2, (sq.size[1] - crop.size[1]) // 2))
    return sq


square = round_corners(new_img, 0.22)
roundi = circle_mask(new_img)
old_sq = round_corners(old_img, 0.22)
notif = ring_mask(new_img)

# --- Home screen mockup ---
W, H = 1080, 1920
home = Image.new("RGB", (W, H), (10, 12, 18))
d = ImageDraw.Draw(home)
# wallpaper gradient
for y in range(H):
    t = y / H
    c = (
        int(10 + 18 * t),
        int(12 + 8 * t),
        int(22 + 28 * t),
    )
    d.line([(0, y), (W, y)], fill=c)

# status bar
d.rectangle((0, 0, W, 72), fill=(0, 0, 0, 40))
font_sb = try_font(28)
font_title = try_font(42, bold=True)
font_label = try_font(28)
font_small = try_font(24)
d.text((48, 22), "9:41", fill=(255, 255, 255), font=font_sb)
d.text((W - 220, 22), "5G  100%", fill=(255, 255, 255), font=font_sb)

d.text((64, 140), "更换后桌面效果", fill=(255, 255, 255), font=font_title)

# app grid
names = ["相机", "相册", "电话", "NekoBox", "设置", "文件", "时钟", "日历"]
icon_size = 168
gap_x = 210
gap_y = 250
origin_x = 120
origin_y = 280
new_icon = square.resize((icon_size, icon_size), Image.Resampling.LANCZOS)
old_icon = old_sq.resize((icon_size, icon_size), Image.Resampling.LANCZOS)

placeholder_colors = [
    (70, 130, 180),
    (90, 90, 110),
    (60, 160, 110),
    None,
    (120, 120, 130),
    (90, 110, 160),
    (180, 140, 70),
    (150, 90, 90),
]

for i, name in enumerate(names):
    col, row = i % 4, i // 4
    x = origin_x + col * gap_x
    y = origin_y + row * gap_y
    if name == "NekoBox":
        home.paste(new_icon, (x, y), new_icon)
    else:
        ph = Image.new("RGBA", (icon_size, icon_size), (0, 0, 0, 0))
        pd = ImageDraw.Draw(ph)
        pd.rounded_rectangle((0, 0, icon_size - 1, icon_size - 1), radius=38, fill=placeholder_colors[i])
        home.paste(ph, (x, y), ph)
    tw = d.textlength(name, font=font_label)
    d.text((x + (icon_size - tw) / 2, y + icon_size + 16), name, fill=(230, 230, 235), font=font_label)

# dock
dock_y = 1680
d.rounded_rectangle((80, dock_y, W - 80, dock_y + 180), radius=48, fill=(20, 22, 30))
dock_icon = new_icon.resize((140, 140), Image.Resampling.LANCZOS)
home.paste(dock_icon, ((W - 140) // 2, dock_y + 20), dock_icon)

home.save(root / "mockup_homescreen.png", quality=95)

# --- Notification mockup ---
notif_bg = Image.new("RGB", (W, H), (16, 18, 24))
nd = ImageDraw.Draw(notif_bg)
for y in range(H):
    t = y / H
    nd.line([(0, y), (W, y)], fill=(int(12 + 8 * t), int(14 + 6 * t), int(20 + 10 * t)))
nd.text((48, 22), "9:41", fill=(255, 255, 255), font=font_sb)
nd.text((64, 140), "通知栏效果", fill=(255, 255, 255), font=font_title)

# status-bar tiny icon row
nd.rounded_rectangle((48, 240, W - 48, 340), radius=24, fill=(28, 30, 38))
tiny = Image.new("RGBA", (48, 48), (0, 0, 0, 0))
tm = notif.resize((48, 48), Image.Resampling.LANCZOS)
white = Image.new("RGBA", (48, 48), (255, 255, 255, 0))
white.putalpha(tm)
notif_bg.paste(white, (80, 266), white)
nd.text((148, 268), "状态栏小图标  ·  白色圆环", fill=(220, 220, 225), font=font_small)

# expanded notification card
nd.rounded_rectangle((48, 400, W - 48, 700), radius=32, fill=(32, 34, 44))
large = square.resize((96, 96), Image.Resampling.LANCZOS)
notif_bg.paste(large, (80, 440), large)
nd.text((200, 448), "NekoBox", fill=(255, 255, 255), font=try_font(34, True))
nd.text((200, 500), "VPN 运行中", fill=(180, 185, 195), font=font_small)
nd.text((80, 560), "通知展开后左侧是彩色应用图标，", fill=(200, 200, 208), font=font_small)
nd.text((80, 600), "状态栏里仍是白色剪影（系统强制）。", fill=(200, 200, 208), font=font_small)
notif_bg.paste(white.resize((40, 40), Image.Resampling.LANCZOS), (W - 140, 456), white.resize((40, 40), Image.Resampling.LANCZOS))

notif_bg.save(root / "mockup_notification.png", quality=95)

# --- Before / after ---
cmp = Image.new("RGB", (1400, 820), (10, 12, 18))
cd = ImageDraw.Draw(cmp)
font_h = try_font(36, True)
cd.text((80, 40), "更换前", fill=(200, 200, 210), font=font_h)
cd.text((780, 40), "更换后", fill=(200, 200, 210), font=font_h)
old_big = old_sq.resize((520, 520), Image.Resampling.LANCZOS)
new_big = square.resize((520, 520), Image.Resampling.LANCZOS)
cmp.paste(old_big, (80, 120), old_big)
cmp.paste(new_big, (780, 120), new_big)
cd.text((80, 680), "当前 C 形图标", fill=(160, 165, 175), font=font_label)
cd.text((780, 680), "新毛笔圆环图标", fill=(160, 165, 175), font=font_label)
cmp.save(root / "mockup_before_after.png", quality=95)

print("ok")
