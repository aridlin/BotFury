"""Render the README artwork with Pillow, Roboto Black and DejaVu Sans Mono."""

from html import unescape
from math import cos, pi, sin
import re

from PIL import Image, ImageDraw, ImageFont

from render import FALLING, HERE, HORIZONTAL, PALETTE, RISING, render, screen

INK, RUST, ORANGE, PAPER = PALETTE
OUTPUT = HERE / "readme"


def export(image, name):
    image.resize((image.width * 2, image.height * 2), Image.Resampling.NEAREST).save(
        OUTPUT / f"{name}.png", optimize=True)


def label(pen, text, x, y, color, size):
    font = ImageFont.truetype("DejaVuSansMono-Bold.ttf", size)
    pen.text((x, y), text, fill=color, font=font, anchor="lt")


def banner():
    image = Image.new("RGB", (600, 192), INK)
    pixels = image.load()
    for y in range(6, 186):
        seam = 361 - (y - 6) * 0.29
        for x in range(6, 594):
            run = x - seam
            if run < -9:
                tone, row = 3, RISING
            elif run < 0:
                tone, row = 2, RISING
            elif run < 4:
                tone, row = 0, RISING
            elif y > 158 + 0.15 * (x - 345):
                tone, row = 0.42 + 0.31 * (594 - x) / 270, HORIZONTAL
            else:
                field = (x - 470) * 0.67 - (y - 95)
                tone = 0.72 + 0.44 * (y - 6) / 180
                if -66 < field < -28 or 45 < field < 62:
                    tone += 0.34
                tone, row = tone, FALLING
            pixels[x, y] = screen(tone, row, x, y)
    pen = ImageDraw.Draw(image)
    pen.line((28, 25, 322, 25), fill=INK, width=2)
    pen.line((28, 159, 296, 159), fill=INK, width=2)
    for x in range(28, 87, 8):
        pen.line((x, 169, x + 4, 165), fill=RUST, width=2)

    title_font = ImageFont.truetype("Roboto-Black.ttf", 80)
    title = Image.new("L", (384, 96))
    ImageDraw.Draw(title).text((0, 0), "BOTFURY", fill=255, font=title_font, anchor="lt")
    title = title.crop(title.getbbox()).resize((291, 69), Image.Resampling.LANCZOS)
    image.paste(INK, (27, 39), title)
    label(pen, "MINECRAFT BOT CONTROL", 29, 127, RUST, 14)

    flame = render(158, 79, 0.95)
    # Paper keyline keeps the mark legible even over the darkest ink.
    halo = Image.new("RGBA", flame.size, (*PAPER, 255))
    alpha = flame.getchannel("A")
    for dx, dy in ((0, -2), (0, 2), (-2, 0), (2, 0)):
        image.paste(halo, (385 + dx, 12 + dy), alpha)
    image.paste(flame, (385, 12), flame)
    pen = ImageDraw.Draw(image)
    for x, y in ((378, 80), (550, 48), (564, 112)):
        pen.polygon([(x, y - 5), (x + 3, y), (x, y + 5), (x - 3, y)], fill=PAPER)
    return image


def symbol(pen, name, x, y, color):
    if name == "star":
        points = []
        for index in range(10):
            angle = -pi / 2 + index * pi / 5
            radius = 8 if index % 2 == 0 else 3.5
            points.append((x + cos(angle) * radius, y + sin(angle) * radius))
        pen.polygon(points, fill=color)
    elif name == "releases":
        pen.polygon([(x, y - 8), (x + 8, y - 4), (x + 8, y + 4),
                     (x, y + 8), (x - 8, y + 4), (x - 8, y - 4)], outline=color, width=2)
        pen.line((x - 7, y - 4, x, y, x + 7, y - 4), fill=color, width=2)
        pen.line((x, y, x, y + 7), fill=color, width=2)
    elif name == "setup":
        pen.line((x - 6, y - 6, x + 1, y, x - 6, y + 6), fill=color, width=2)
        pen.line((x + 2, y + 6, x + 8, y + 6), fill=color, width=2)
    elif name == "fabric":
        for dx, dy in ((-8, -8), (0, -8), (-8, 0), (0, 0)):
            pen.polygon([(x + dx, y + dy + 4), (x + dx + 4, y + dy),
                         (x + dx + 8, y + dy + 4), (x + dx + 4, y + dy + 8)], fill=color)
    elif name == "baritone":
        pen.line((x - 7, y + 7, x + 4, y - 4), fill=color, width=3)
        pen.line((x - 6, y - 4, x - 2, y - 8, x + 4, y - 5, x + 8, y + 1),
                 fill=color, width=3)
    elif name == "fleet":
        for dx, dy in ((0, -5), (-6, 3), (6, 3)):
            pen.rectangle((x + dx - 2, y + dy - 2, x + dx + 2, y + dy + 2), fill=color)


def button(name, text, width, fill):
    image = Image.new("RGBA", (width, 32))
    pen = ImageDraw.Draw(image)
    pen.polygon([(0, 0), (width - 8, 0), (width - 1, 7), (width - 1, 31),
                 (7, 31), (0, 24)], fill=INK)
    pen.polygon([(2, 2), (width - 9, 2), (width - 3, 8), (width - 3, 27),
                 (8, 27), (2, 22)], fill=fill)
    pen.rectangle((24, 2, 25, 27), fill=INK)
    symbol(pen, name, 13, 14, INK)
    label(pen, text, 33, 11, INK, 11)
    return image


def sticker(name, text, width, left_tone):
    image = Image.new("RGBA", (width, 38))
    pen = ImageDraw.Draw(image)
    pen.polygon([(7, 0), (width - 1, 0), (width - 1, 31), (width - 8, 37), (0, 37),
                 (0, 7)], fill=PAPER)
    pen.polygon([(8, 2), (width - 3, 2), (width - 3, 30), (width - 9, 35), (2, 35),
                 (2, 8)], fill=INK)
    pixels = image.load()
    for y in range(5, 33):
        for x in range(max(5, 12 - y), 39):
            pixels[x, y] = (*screen(left_tone - (y - 5) / 54, RISING, x, y), 255)
    symbol(pen, name, 22, 19, PAPER)
    label(pen, text, 47, 15, PAPER, 10)
    return image


def chapter_heading(title, name):
    width, height = 600, 62
    image = Image.new("RGB", (width, height), INK)
    pixels = image.load()
    variant = sum(ord(char) for char in name)
    pattern = (RISING, FALLING, HORIZONTAL)[variant % 3]
    for y in range(3, height - 3):
        seam = 510 - y * 0.68
        for x in range(3, width - 3):
            run = x - seam
            if run < -6:
                pixels[x, y] = PAPER
            elif run < 0:
                pixels[x, y] = ORANGE
            elif run > 2:
                band = ((x + (variant % 23) + y * (1 if variant % 2 else -1)) // 21) % 4
                tone = (0.43, 0.84, 1.50, 1.16)[band]
                pixels[x, y] = screen(tone, pattern, x, y)

    font = ImageFont.truetype("Roboto-Black.ttf", 28)
    text_width = font.getlength(title)
    if text_width > 437:
        font = font.font_variant(size=int(28 * 437 / text_width))
    pen = ImageDraw.Draw(image)
    pen.text((18, height / 2), title, font=font, fill=INK, anchor="lm")
    pen.line((19, 50, 55, 50), fill=RUST, width=2)
    return image


def subsection_heading(title):
    font = ImageFont.truetype("Roboto-Black.ttf", 17)
    width, height = round(font.getlength(title)) + 46, 32
    image = Image.new("RGBA", (width, height))
    pen = ImageDraw.Draw(image)
    pen.polygon([(0, 0), (width - 9, 0), (width - 1, 8), (width - 1, height - 1),
                 (0, height - 1)], fill=INK)
    pen.polygon([(2, 2), (width - 10, 2), (width - 3, 9), (width - 3, height - 3),
                 (2, height - 3)], fill=PAPER)
    pixels = image.load()
    for y in range(2, height - 2):
        for x in range(2, 16):
            pixels[x, y] = (*screen(0.91 + 0.93 * (height - y) / height, RISING, x, y), 255)
    pen.text((25, height / 2), title, font=font, fill=INK, anchor="lm")
    return image


def readme_headings():
    readme = (HERE.parents[1] / "README.md").read_text(encoding="utf-8")
    headings = re.findall(
        r'^(##|###) <img src="artwork/botfury/readme/headings/([a-z0-9-]+)\.png" alt="([^"]+)">',
        readme, re.MULTILINE,
    )
    (OUTPUT / "headings").mkdir(exist_ok=True)
    for level, name, title in headings:
        title = unescape(title)
        image = chapter_heading(title, name) if level == "##" else subsection_heading(title)
        # Save at native display size to keep heading images in their natural type hierarchy.
        image.save(OUTPUT / "headings" / f"{name}.png", optimize=True)


if __name__ == "__main__":
    OUTPUT.mkdir(exist_ok=True)
    export(banner(), "banner")
    export(button("star", "STAR ON GITHUB", 132, ORANGE), "button-star")
    export(button("releases", "RELEASES", 94, PAPER), "button-releases")
    export(button("setup", "SETUP", 75, PAPER), "button-setup")
    export(sticker("fabric", "FABRIC 1.20.1", 134, 1.43), "sticker-fabric")
    export(sticker("baritone", "BARITONE", 107, 1.26), "sticker-baritone")
    export(sticker("fleet", "FLEET COMMAND", 135, 1.6), "sticker-fleet")
    readme_headings()
