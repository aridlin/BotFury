"""Render the BotFury mark and its Windows icon. Requires Pillow."""

from pathlib import Path
from struct import pack

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
PALETTE = ((25, 24, 21), (148, 56, 35), (238, 101, 60), (244, 237, 218))
MOD_ICON = HERE.parents[1] / "minecraft-bots/botfury-mod/src/main/resources/assets/botfury/icon.png"
SIZES = (16, 20, 24, 32, 40, 48, 64, 96, 128, 256)
HORIZONTAL, VERTICAL, RISING, FALLING = 1, 2, 3, 4
ATLAS = Image.open(HERE / "dumbass-dither-broken-stripes.png").convert("1")
MARK = ATLAS.getpixel((64, 0))


def screen(tone, row, x, y):
    """Sample Signal Geometry's 8×8, nine-density Dumb-ass Dither atlas."""
    lower = int(tone)
    column = int((tone - lower) * 8 + 0.5)
    mark = ATLAS.getpixel((column * 8 + x % 8, row * 8 + y % 8)) == MARK
    return PALETTE[lower + mark]


def render(size, plate, strength):
    # Draw the source plate at a higher resolution so face fills stay inside
    # their outlines when the geometry is sampled on a small icon grid.
    source_size = max(384, size)
    regions = Image.new("L", (source_size, source_size))
    pen = ImageDraw.Draw(regions)

    def draw(region, points):
        pen.polygon([(round(x * source_size / 96), round(y * source_size / 96))
                     for x, y in points], fill=region)

    draw(1, [(6, 28), (40, 8), (90, 24), (90, 68), (56, 88), (6, 72)])
    draw(2, [(10, 28), (40, 11), (85, 25), (56, 42)])
    draw(3, [(9, 32), (54, 46), (54, 84), (9, 70)])
    draw(4, [(58, 45), (87, 28), (87, 66), (58, 83)])

    def face(region, points):
        draw(region, [(9 + 45 * u, 32 + 14 * u + 38 * v) for u, v in points])

    face(1, [(0.10, 0.17), (0.89, 0.17), (0.89, 0.62), (0.79, 0.72),
             (0.10, 0.72)])
    face(5, [(0.19, 0.28), (0.42, 0.37), (0.42, 0.57), (0.19, 0.57)])
    face(5, [(0.56, 0.37), (0.80, 0.28), (0.80, 0.57), (0.56, 0.57)])

    if size >= 40:
        draw(6, [(64, 58), (81, 48), (81, 52), (64, 62)])
        draw(6, [(64, 66), (81, 56), (81, 60), (64, 70)])
    if size >= 64:
        draw(5, [(16, 28), (40, 14), (49, 17), (24, 31)])

    regions = regions.resize((size, size), Image.Resampling.NEAREST)
    result = Image.new("RGBA", (size, size))
    colors = result.load()
    for y in range(size):
        for x in range(size):
            region = regions.getpixel((x, y))
            u, v = (x + 0.5) / size, (y + 0.5) / size
            if region == 0:
                continue
            if region == 2:
                tone, row = 3 - strength * (0.25 + 0.68 * u + 0.2 * v), RISING
            elif region == 3:
                depth = v - (32 + 14 * ((96 * u - 9) / 45)) / 96
                tone, row = 2 - strength * max(0, (depth - 0.22) * 4.5), FALLING
            elif region == 4:
                tone, row = 1 + strength * (0.68 - 0.65 * v), HORIZONTAL
            else:
                tone, row = {1: (0, VERTICAL), 5: (3, VERTICAL), 6: (0, VERTICAL)}[region]
            colors[x, y] = (*screen(tone, row, x * plate // size, y * plate // size), 255)
    return result


def small_icon():
    # At 16 px the two eye marks need a dedicated one-pixel gap.
    pixels = (
        "................",
        "......##........",
        "....##oo###.....",
        "...#ooooooo###..",
        ".##oooooooooo##.",
        ".#+###ooooo##r#.",
        ".#+####o###rrr#.",
        ".#+#o#####rrrr#.",
        ".#+#o#oo#+rrrr#.",
        ".#+###oo#+rrr#..",
        ".#+++####+rrr#..",
        ".###+++++#rr#...",
        "....###++#r#....",
        ".......###r#....",
        ".........##.....",
        "................",
    )
    colors = {".": (0, 0, 0, 0)}
    colors.update({mark: (*rgb, 255) for mark, rgb in zip("#r+o", PALETTE)})
    frame = Image.new("RGBA", (16, 16))
    frame.putdata([colors[mark] for row in pixels for mark in row])
    return frame


def icon_frames():
    for size in SIZES:
        if size == 16:
            yield small_icon()
        elif size <= 24:
            yield render(size, size, 0)
        else:
            yield render(size, min(128, size), 0.95 if size >= 64 else 0.60)


def write_ico(path, frames):
    # Classic 32-bit icon DIBs work with both GNU windres and Microsoft's rc.exe.
    payloads = []
    for frame in frames:
        size = frame.width
        flipped = frame.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        pixels = flipped.tobytes("raw", "BGRA")
        mask_stride = ((size + 31) // 32) * 4
        mask = bytearray(mask_stride * size)
        for y in range(size):
            for x in range(size):
                if pixels[(y * size + x) * 4 + 3] == 0:
                    mask[y * mask_stride + x // 8] |= 0x80 >> (x % 8)
        header = pack("<IiiHHIIiiII", 40, size, size * 2, 1, 32, 0, len(pixels),
                      0, 0, 0, 0)
        payloads.append(header + pixels + mask)

    directory = bytearray(pack("<HHH", 0, 1, len(frames)))
    offset = 6 + 16 * len(frames)
    for frame, payload in zip(frames, payloads):
        size_byte = frame.width % 256
        directory.extend(pack("<BBBBHHII", size_byte, size_byte, 0, 0, 1, 32,
                              len(payload), offset))
        offset += len(payload)
    path.write_bytes(directory + b"".join(payloads))


if __name__ == "__main__":
    render(128, 128, 0.95).resize((512, 512), Image.Resampling.NEAREST).save(
        MOD_ICON, optimize=True)
    write_ico(HERE / "botfury.ico", list(icon_frames()))
