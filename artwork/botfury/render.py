"""Render the BotFury mark and its Windows icon. Requires Pillow."""

from pathlib import Path
from struct import pack

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
PALETTE = ((25, 24, 21), (148, 56, 35), (238, 101, 60), (244, 237, 218))
MOD_ICON = HERE.parents[1] / "minecraft-bots/botfury-mod/src/main/resources/assets/botfury/icon.png"
SIZES = (16, 20, 24, 32, 40, 48, 64, 96, 128, 256)
HORIZONTAL, RISING, FALLING = 1, 3, 4
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

    flame = [(48, 5), (72, 25), (69, 41), (79, 33), (90, 49), (92, 65),
             (84, 80), (71, 89), (52, 93), (32, 90), (16, 81), (7, 67),
             (7, 55), (22, 35), (25, 47), (39, 28)]
    draw(1, flame)
    draw(2, [(48 + (x - 48) * 0.89, 53 + (y - 53) * 0.89) for x, y in flame])
    if size >= 32:
        draw(3, [(48, 11), (68, 27), (64, 44), (55, 35), (38, 39), (45, 27)])
        draw(4, [(11, 57), (25, 46), (31, 55), (35, 69), (57, 82), (75, 81),
                 (69, 86), (51, 89), (33, 86), (18, 77), (11, 66)])
    draw(1, [(50, 28), (68, 28), (57, 46), (72, 46), (39, 83), (45, 60),
             (32, 60)])
    draw(5, [(52, 32), (61, 32), (50, 50), (63, 50), (47, 69), (51, 56),
             (39, 56)])

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
                tone, row = 2 - strength * max(0, (v + 0.22 * u - 0.46) * 1.6), RISING
            elif region == 3:
                tone, row = 2 + strength * (0.84 - 0.85 * v), FALLING
            elif region == 4:
                tone, row = 1 - strength * max(0, (v - 0.56) * 1.3), HORIZONTAL
            else:
                tone, row = (0 if region == 1 else 3), RISING
            colors[x, y] = (*screen(tone, row, x * plate // size, y * plate // size), 255)
    return result


def small_icon():
    # Keep a clear, uninterrupted lightning stroke at 16 px.
    pixels = (
        "................",
        "........#.......",
        ".......#+#......",
        ".......#++#.....",
        "......#+++#.....",
        "...#..#+o+#..#..",
        "...#.#+o+#..#+#.",
        "..#+##+o+###++#.",
        ".#++++ooo++++++#",
        ".#+++++o+++++++#",
        ".#+++++o++++++r#",
        ".#++++o++++++r#.",
        "..#++r++++rrr#..",
        "...#rrrrrrrr#...",
        "....########....",
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
