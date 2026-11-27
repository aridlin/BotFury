# BotFury icon

The PNG lives at [assets/botfury/icon.png](../../minecraft-bots/botfury-mod/src/main/resources/assets/botfury/icon.png).
The ICO contains dedicated frames from 16 through 256 px for Windows executables,
shortcuts, and favicons.

Run `python artwork/botfury/render.py` from the repository root with Pillow
installed to regenerate both. The script draws the tonal source and screens it
with the four Dither Press inks and the original Dumb-ass Dither broken-stripes
atlas from [Signal Geometry](https://github.com/aridlin/signal-geometry).
The small frames use simplified shading to keep the lightning cutout readable.

Run `python artwork/botfury/render_readme.py` with Pillow, Roboto Black and
DejaVu Sans Mono Bold installed to regenerate the banner, buttons and stickers
under [readme/](readme/).
