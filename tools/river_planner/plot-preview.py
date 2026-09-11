"""Plot planned rivers and actual audited water; not a Minecraft screenshot."""
import argparse
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    def rows(name):
        with (args.world / name).open(encoding="utf-8") as source:
            return list(csv.DictReader(source))
    dem = rows("td-river-preview-dem.csv")
    segments = rows("td-river-preview-plan.csv")
    xs = sorted({int(r["x"]) for r in dem})
    zs = sorted({int(r["z"]) for r in dem})
    heights = np.array([float(r["ground"]) for r in dem]).reshape(len(zs), len(xs))
    lines = [[(int(r["x1"]), int(r["z1"])), (int(r["x2"]), int(r["z2"]))] for r in segments]
    widths = [max(.7, float(r["halfWidth1"]) / 2) for r in segments]
    if (args.world / "td-river-preview-curves.csv").is_file():
        from collections import defaultdict
        paths = defaultdict(list)
        for row in rows("td-river-preview-curves.csv"):
            paths[row["source"]].append(row)
        lines, widths = [], []
        for path in paths.values():
            for a, b in zip(path, path[1:]):
                lines.append([(float(a["x"]), float(a["z"])), (float(b["x"]), float(b["z"]))])
                widths.append(max(.7, float(a["halfWidth"]) / 2))
    audited = [r for p in sorted(args.world.glob("td-river-preview-audit-*.csv")) for r in rows(p.name)]
    fig, axes = plt.subplots(1, 2, figsize=(13, 6))
    for ax in axes:
        ax.imshow(heights, extent=[xs[0], xs[-1], zs[-1], zs[0]], cmap="gist_earth", vmin=40, vmax=140)
        ax.set_xlabel("X (blocks)")
        ax.set_ylabel("Z (blocks)")
    axes[0].add_collection(LineCollection(lines, linewidths=widths, colors="#36c8ff"))
    axes[0].set_title(f"Finite terrain-based plan: {len(segments)} reaches")
    if audited:
        x = np.array([int(r["x"]) for r in audited])
        z = np.array([int(r["z"]) for r in audited])
        wet = np.array([r["actualFluidAtExpectedWater"] == "true" and int(r["expectedWaterY"]) > int(r["expectedGroundY"]) for r in audited])
        # Show one measured 2x2 chunk cluster rather than empty space between
        # distant diagnostic sites. All sampled water remains in the CSVs.
        from collections import Counter
        joins = Counter(int(r["target"]) for r in segments)
        centers = [r for r in segments if joins[int(r["source"])] >= 2]
        centers.sort(key=lambda r: (int(r["water1"]) > 62, float(r["halfWidth1"])), reverse=True)
        selected = None
        for r in centers:
            cx, cz = int(r["x1"]), int(r["z1"])
            inside = (x >= cx - 16) & (x < cx + 16) & (z >= cz - 16) & (z < cz + 16)
            if inside.sum() == 1024:
                selected = inside
                break
        if selected is None:
            selected = np.ones(len(x), dtype=bool)
        axes[1].scatter(x[selected], z[selected], c=np.array([int(r["expectedGroundY"]) for r in audited])[selected],
                        cmap="terrain", marker="s", s=42, linewidths=0)
        axes[1].scatter(x[wet & selected], z[wet & selected], s=42, marker="s", color="#36c8ff", linewidths=0)
        axes[1].set_xlim(x[selected].min() - .5, x[selected].max() + .5)
        axes[1].set_ylim(z[selected].max() + .5, z[selected].min() - .5)
        axes[1].set_title(f"Measured confluence: {int((wet & selected).sum())} wet columns\n({int(wet.sum())} verified across all test chunks)")
    else:
        axes[1].set_title("No completed block audit")
    for ax in axes:
        ax.set_aspect("equal")
    fig.tight_layout()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.output, dpi=170)


if __name__ == "__main__":
    main()
