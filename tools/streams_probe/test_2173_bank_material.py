"""Fail-closed bytecode/source audit for the Streams 2.17.3 bank-cap repair."""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from test_2162_bed_hook import java_tool, javap, method_for_descriptor

ROOT = Path(__file__).resolve().parents[2]
MIXIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsBankMaterial2173Mixin.java"
POLICY = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsBankTop2173Policy.java"
CARVER = "dev.streamsreflowing.worldgen.RiverCarverFeature"
PLACE = "(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z"
TOP = "Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos$MutableBlockPos;IIILnet/minecraft/world/level/block/state/BlockState;I)V"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    tool = java_tool("javap", args.java_home)
    assert tool and args.jar.is_file(), "JDK21 javap and the Streams JAR are required"
    text = javap(tool, args.jar, CARVER, "-p", "-s", "-c")
    place = method_for_descriptor(text, PLACE)
    assert place.count("WorldGenLevel.setBlock") == 18, "2.17.3 place write layout changed"
    assert place.count("Method proprietary1:(" + TOP) == 5, "place top-helper layout changed"
    assert "13352: invokeinterface" in place and "Blocks.GRASS_BLOCK" in place, "direct grass recap changed"
    assert text.count("Method proprietary1:(" + TOP) == 12, "global top-helper call count changed"
    mixin = MIXIN.read_text(encoding="utf-8")
    for required in ('@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)',
                     'method = "*"', 'require = 12, expect = 12', 'ordinal = 12',
                     'ordinal = 13', 'ordinal = 14', 'ordinal = 15',
                     'StreamsBankTop2173Policy.suppressUnnecessaryRaisedCap',
                     'StreamsBankMaterialPolicy.climateBankTop'):
        assert required in mixin, "mixin contract missing: " + required
    policy = POLICY.read_text(encoding="utf-8")
    for required in ('StreamsNaturalRiverAdapter.version(config) != 14',
                     'Access.IN_CORRIDOR.invoke(plan)', 'NaturalRiverRaisedCapPolicy.suppress',
                     'Access.IN_CHANNEL.invoke(plan)', 'originalGround, water, y',
                     'isAir()'):
        assert required in policy, "raised-cap guard missing: " + required
    print("StreamsBankMaterial2173HookTest: PASS", args.jar.name)


if __name__ == "__main__":
    main()
