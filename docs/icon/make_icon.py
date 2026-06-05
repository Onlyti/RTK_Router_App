"""Generate RTK Router app icon (matplotlib). Concept: precise location pin + correction
signal arcs. Outputs Android adaptive-icon layers (per density) + a 512 Play icon + preview.
Run: python3 make_icon.py"""
import os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Circle, Polygon, Arc
import matplotlib.colors as mcolors
import numpy as np

BLUE, BLUE_DK = "#0B5FA5", "#063D6E"
CYAN, GREEN, WHITE = "#01A0E9", "#2BD46A", "#FFFFFF"
RES = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res")
# Android: 108dp layer -> px per density
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def gradient_bg(ax):
    ax.imshow(np.linspace(0, 1, 256).reshape(-1, 1), extent=[0, 1024, 0, 1024],
              origin="lower", aspect="auto", zorder=0,
              cmap=mcolors.LinearSegmentedColormap.from_list("bg", [BLUE_DK, BLUE]))


def emblem(ax):
    cx, cy = 512, 470
    for i, r in enumerate((300, 372, 444)):
        ax.add_patch(Arc((cx, cy), 2 * r, 2 * r, theta1=38, theta2=142,
                         lw=34 - i * 6, color=CYAN, alpha=0.95 - i * 0.22, capstyle="round", zorder=3))
    ax.add_patch(Polygon([(cx - 150, cy - 70), (cx + 150, cy - 70), (cx, cy - 300)], fc=WHITE, ec="none", zorder=4))
    ax.add_patch(Circle((cx, cy), 196, fc=WHITE, ec="none", zorder=5))
    ax.add_patch(Circle((cx, cy), 92, fc=GREEN, ec="none", zorder=6))
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        ax.plot([cx + dx * 36, cx + dx * 150], [cy + dy * 36, cy + dy * 150], color=WHITE, lw=16, solid_capstyle="round", zorder=7)


def render(path, size, layer):
    fig = plt.figure(figsize=(size / 100, size / 100), dpi=100)
    ax = fig.add_axes([0, 0, 1, 1])
    if layer == "background":
        gradient_bg(ax); lim = (0, 1024)
    elif layer == "foreground":
        emblem(ax); lim = (-160, 1184)          # inset content into the 66% safe zone
    else:  # full (Play 512)
        gradient_bg(ax); emblem(ax); lim = (0, 1024)
    ax.set_xlim(*lim); ax.set_ylim(*lim); ax.set_aspect("equal"); ax.axis("off")
    fig.savefig(path, transparent=(layer == "foreground"), dpi=100)
    plt.close(fig)


for dens, px in DENSITIES.items():
    d = os.path.join(RES, f"mipmap-{dens}")
    os.makedirs(d, exist_ok=True)
    render(os.path.join(d, "ic_launcher_background.png"), px, "background")
    render(os.path.join(d, "ic_launcher_foreground.png"), px, "foreground")
render(os.path.join(os.path.dirname(__file__), "ic_launcher_512.png"), 512, "full")
print("done: adaptive layers (5 densities) + Play 512")
