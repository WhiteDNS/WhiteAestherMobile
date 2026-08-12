"""Generate the res/font TTFs the Compose UI needs.

Inter ships as a variable woff2; Android res/font wants plain TTF, so each
weight is instantiated as a static instance. IBM Plex Mono is already static
and only needs the woff2 container stripped.
"""
import os
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "res", "font")
INTER = os.path.join(HERE, "node_modules", "@fontsource-variable", "inter",
                     "files", "inter-latin-wght-normal.woff2")
PLEX = os.path.join(HERE, "..", "assets", "fonts")

os.makedirs(OUT, exist_ok=True)

for name, weight in [("regular", 400), ("medium", 500), ("semibold", 600), ("bold", 700)]:
    font = TTFont(INTER)
    instancer.instantiateVariableFont(font, {"wght": weight}, inplace=True, updateFontNames=True)
    font.flavor = None
    path = os.path.join(OUT, f"inter_{name}.ttf")
    font.save(path)
    print(f"inter_{name}.ttf  {os.path.getsize(path) // 1024} KB")

for name, weight in [("regular", 400), ("medium", 500)]:
    font = TTFont(os.path.join(PLEX, f"ibm-plex-mono-{weight}.woff2"))
    font.flavor = None
    path = os.path.join(OUT, f"plex_mono_{name}.ttf")
    font.save(path)
    print(f"plex_mono_{name}.ttf  {os.path.getsize(path) // 1024} KB")
