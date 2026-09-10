"""Plot measured top-surface water; this is block data, not an in-game screenshot."""
import argparse
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
os.environ["MPLCONFIGDIR"] = str(ROOT / "build/streams-probe/matplotlib")

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
import numpy as np
from compare import load


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("enabled", type=Path)
    parser.add_argument("control", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    _, enabled = load(args.enabled)
    _, control = load(args.control)
    if enabled.keys() != control.keys() or not enabled:
        raise ValueError("matching measured coordinates required")
    xs, zs = zip(*enabled)
    xmin, xmax, zmin, zmax = min(xs), max(xs), min(zs), max(zs)
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.4), constrained_layout=True)
    colors = {"land": (0.77, 0.71, 0.48), "water": (0.15, 0.53, 0.83), "missing": (0.91, 0.92, 0.93)}
    for ax, rows, label in zip(axes, [control, enabled], ["Streams waterways OFF", "Streams waterways ON"]):
        rgb = np.empty((zmax-zmin+1, xmax-xmin+1, 3))
        rgb[:] = colors["missing"]
        count = 0
        for (x, z), row in rows.items():
            water = int(row["topWaterY"]) == int(row["surfaceY"])
            count += water
            rgb[z-zmin, x-xmin] = colors["water" if water else "land"]
        ax.imshow(rgb, origin="upper", interpolation="nearest", extent=[xmin-.5, xmax+.5, zmax+.5, zmin-.5])
        ax.set(title=f"{label}\n{count} surface-water columns", xlabel="Block X", ylabel="Block Z")
    fig.suptitle("Measured savanna surface: same Terrain Diffusion seed and base terrain", fontsize=12)
    axes[1].legend(handles=[Patch(color=colors[k], label=v) for k, v in
                           [("land", "Land"), ("water", "Surface water"), ("missing", "Not sampled")]],
                   loc="lower right", fontsize=8)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.output, dpi=170)
    plt.close(fig)
    print(args.output)


if __name__ == "__main__":
    main()
