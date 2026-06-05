"""Google Play feature graphic (1024x500) for RTK Router."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
import numpy as np
from make_icon import emblem  # reuse the icon emblem

BLUE, BLUE_DK, WHITE, CYAN = "#0B5FA5", "#063D6E", "#FFFFFF", "#9BD7F5"

fig = plt.figure(figsize=(10.24, 5.0), dpi=100)
ax = fig.add_axes([0, 0, 1, 1])
ax.imshow(np.linspace(0, 1, 256).reshape(1, -1), extent=[0, 1024, 0, 500], origin="lower",
          aspect="auto", cmap=mcolors.LinearSegmentedColormap.from_list("bg", [BLUE_DK, BLUE]))

# emblem on the left (drawn in 0..1024 space -> scale/translate into a 360px box)
sub = fig.add_axes([0.04, 0.12, 0.30, 0.76])
emblem(sub)
sub.set_xlim(-30, 1054); sub.set_ylim(70, 1024); sub.set_aspect("equal"); sub.axis("off")

ax.text(380, 312, "RTK Router", color=WHITE, fontsize=52, fontweight="bold", va="center")
ax.text(382, 228, "NTRIP RTK corrections, phone to receiver",
        color=CYAN, fontsize=19, va="center")
ax.text(382, 178, "Multi-network failover · VRS · no GPS permission",
        color=CYAN, fontsize=15, va="center", alpha=0.9)
ax.set_xlim(0, 1024); ax.set_ylim(0, 500); ax.axis("off")
fig.savefig("feature_graphic_1024x500.png", dpi=100)
print("wrote feature_graphic_1024x500.png")
