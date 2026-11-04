"""Render website sharing/icon assets from the existing logo, font and screenshot.

Requires ImageMagick (`magick`). Run from any directory; no website build step.
"""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ASSETS = Path(__file__).resolve().parents[1] / "website/assets"
FONT = str(ASSETS / "dm-sans.ttf")


def render(*args):
    subprocess.run(["magick", *map(str, args)], check=True)


def main():
    with tempfile.TemporaryDirectory(prefix="gamefibre-social-") as directory:
        work = Path(directory)
        logo = work / "logo.png"
        # Draw the existing SVG paths directly, avoiding platform SVG delegates.
        paths = ET.parse(ASSETS / "logo.svg").getroot().findall(".//{http://www.w3.org/2000/svg}path")
        drawing = "translate -74.0923,-89.2308 scale 0.541538,0.541538 " + " ".join(
            f"fill '{path.attrib['fill']}' stroke '{path.attrib['stroke']}' "
            f"stroke-width {path.attrib['stroke-width']} stroke-linecap round stroke-linejoin round "
            f"path '{path.attrib['d']}'" for path in paths)
        large_logo = work / "large-logo.png"
        render("-size", "512x512", "xc:none", "-draw", drawing, large_logo)
        render(large_logo, "-resize", "96x96", logo)
        for name, size in (("favicon-32.png", 32), ("apple-touch-icon.png", 180), ("app-icon-512.png", 512)):
            render("-size", f"{size}x{size}", "xc:white", "(", large_logo, "-resize", f"{size * 4 // 5}x{size * 4 // 5}", ")",
                   "-gravity", "center", "-composite", ASSETS / name)
        mask = work / "mask.png"
        shot = work / "screenshot.png"
        render("-size", "234x520", "xc:none", "-fill", "white", "-draw", "roundrectangle 0,0 233,519 18,18", mask)
        render(ASSETS / "screenshot-01.jpg", "-resize", "234x520!", mask, "-alpha", "on",
               "-compose", "DstIn", "-composite", shot)
        render("-size", "1200x630", "xc:white",
               "-fill", "#f0f4f9", "-draw", "rectangle 800,0 1199,629",
               "-fill", "white", "-stroke", "#dce3ed", "-strokewidth", "2",
               "-draw", "roundrectangle 848,45 1102,585 30,30", "+stroke",
               logo, "-geometry", "+54+58", "-composite",
               "-font", FONT, "-fill", "#0b57d0", "-pointsize", "32", "-annotate", "+164+121", "GameFibre",
               "-fill", "#202124", "-pointsize", "68", "-annotate", "+64+270", "Enjoy your",
               "-annotate", "+64+350", "collection.",
               "-fill", "#5f6368", "-pointsize", "25", "-annotate", "+66+434", "Steam, GOG, Epic and Xbox",
               "-pointsize", "23", "-annotate", "+66+478", "A lightweight game library for Android.",
               "-fill", "#0b57d0", "-pointsize", "21", "-annotate", "+66+558", "gamefibre.com",
               shot, "-geometry", "+858+55", "-composite", "-strip", ASSETS / "social-card.png")


if __name__ == "__main__":
    main()
