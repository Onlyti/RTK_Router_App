"""Generate RTK Router app icon (matplotlib). Concept: precise location pin + correction
signal arcs. Run: python3 make_icon.py  ->  ic_launcher_512.png / _foreground.png"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Circle, Polygon, Arc
import numpy as np

BLUE = "#0B5FA5"
BLUE_DK = "#063D6E"
CYAN = "#01A0E9"
GREEN = "#2BD46A"
WHITE = "#FFFFFF"


def draw(ax, bg=True):
    if bg:
        # rounded-square background with a subtle vertical two-tone
        ax.imshow(
            np.linspace(0, 1, 256).reshape(-1, 1),
            extent=[0, 1024, 0, 1024], origin="lower",
            cmap=matplotlib.colors.LinearSegmentedColormap.from_list("bg", [BLUE_DK, BLUE]),
            aspect="auto", zorder=0,
        )
        ax.add_patch(FancyBboxPatch(
            (8, 8), 1008, 1008, boxstyle="round,pad=0,rounding_size=220",
            ec="none", fc="none", zorder=1))

    cx, cy = 512, 470          # pin head centre
    # correction signal arcs radiating from the pin head (top)
    for i, r in enumerate((300, 372, 444)):
        ax.add_patch(Arc((cx, cy), 2 * r, 2 * r, angle=0, theta1=38, theta2=142,
                         lw=34 - i * 6, color=CYAN, alpha=0.95 - i * 0.22, zorder=3,
                         capstyle="round"))

    # location pin (teardrop): white head + pointed base
    head_r = 196
    ax.add_patch(Polygon([(cx - 150, cy - 70), (cx + 150, cy - 70), (cx, cy - 300)],
                         closed=True, fc=WHITE, ec="none", zorder=4))
    ax.add_patch(Circle((cx, cy), head_r, fc=WHITE, ec="none", zorder=5))
    # RTK-fixed green centre dot + crosshair ticks (precision)
    ax.add_patch(Circle((cx, cy), 92, fc=GREEN, ec="none", zorder=6))
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        ax.plot([cx + dx * 36, cx + dx * 150], [cy + dy * 36, cy + dy * 150],
                color=WHITE, lw=16, solid_capstyle="round", zorder=7)

    ax.set_xlim(0, 1024); ax.set_ylim(0, 1024)
    ax.set_aspect("equal"); ax.axis("off")


def save(name, bg=True, pad=0.0):
    fig = plt.figure(figsize=(5.12, 5.12), dpi=200)
    ax = fig.add_axes([pad, pad, 1 - 2 * pad, 1 - 2 * pad])
    draw(ax, bg=bg)
    fig.savefig(name, transparent=not bg, dpi=200)
    plt.close(fig)
    print("wrote", name)


save("ic_launcher_512.png", bg=True)               # full icon (Play 512)
save("ic_launcher_foreground.png", bg=False, pad=0.16)  # adaptive foreground (safe zone)
