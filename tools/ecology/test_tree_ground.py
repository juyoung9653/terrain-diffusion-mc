"""Compile the production submerged-tree-ground policy against minimal Minecraft stubs."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/ecology/TreeGroundMaterialPolicy.java"
MIXIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/SubmergedTreeGroundMixin.java"

STUBS = {
    "net/minecraft/core/BlockPos.java": r'''
package net.minecraft.core;
public class BlockPos {
  public final int y;
  public BlockPos() { this(80); }
  private BlockPos(int y) { this.y = y; }
  public BlockPos above() { return new BlockPos(y + 1); }
}
''',
    "net/minecraft/tags/FluidTags.java": r'''
package net.minecraft.tags;
public final class FluidTags { public static final Object WATER = new Object(); }
''',
    "net/minecraft/world/level/LevelSimulatedReader.java": r'''
package net.minecraft.world.level;
public interface LevelSimulatedReader { }
''',
    "net/minecraft/world/level/FluidState.java": r'''
package net.minecraft.world.level;
public final class FluidState {
  private final boolean water;
  public FluidState(boolean water) { this.water = water; }
  public boolean is(Object tag) { return water && tag == net.minecraft.tags.FluidTags.WATER; }
}
''',
    "net/minecraft/world/level/Level.java": r'''
package net.minecraft.world.level;
public class Level {
  public static final Object OVERWORLD = new Object();
  private final Object dimension;
  private final ChunkSource chunks;
  public Level(Object dimension, ChunkSource chunks) { this.dimension = dimension; this.chunks = chunks; }
  public Object dimension() { return dimension; }
  public ChunkSource getChunkSource() { return chunks; }
  public static final class ChunkSource {
    private final Generator generator;
    public ChunkSource(Generator generator) { this.generator = generator; }
    public Generator getGenerator() { return generator; }
  }
  public static final class Generator {
    private final Object biomeSource;
    public Generator(Object biomeSource) { this.biomeSource = biomeSource; }
    public Object getBiomeSource() { return biomeSource; }
  }
}
''',
    "net/minecraft/world/level/WorldGenLevel.java": r'''
package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
public class WorldGenLevel implements LevelSimulatedReader {
  private final Level level;
  private final FluidState above;
  public WorldGenLevel(Level level, FluidState above) { this.level = level; this.above = above; }
  public Level getLevel() { return level; }
  public FluidState getFluidState(BlockPos pos) { return pos.y == 81 ? above : new FluidState(false); }
}
''',
    "com/github/xandergos/terraindiffusionmc/world/TerrainDiffusionBiomeSource.java": r'''
package com.github.xandergos.terraindiffusionmc.world;
public final class TerrainDiffusionBiomeSource {
  private final boolean natural;
  public TerrainDiffusionBiomeSource(boolean natural) { this.natural = natural; }
  public boolean naturalBiomes() { return natural; }
}
''',
    "com/github/xandergos/terraindiffusionmc/world/TerrainDiffusionBiomeSources.java": r'''
package com.github.xandergos.terraindiffusionmc.world;
public final class TerrainDiffusionBiomeSources {
  public static TerrainDiffusionBiomeSource find(Object value) {
    return value instanceof TerrainDiffusionBiomeSource source ? source : null;
  }
}
''',
}

HARNESS = r'''
import com.github.xandergos.terraindiffusionmc.compat.ecology.TreeGroundMaterialPolicy;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.FluidState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.WorldGenLevel;

public final class TreeGroundMaterialPolicyTest {
  private static void require(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
  private static WorldGenLevel world(Object dimension, boolean water, Object source) {
    return new WorldGenLevel(new Level(dimension, new Level.ChunkSource(new Level.Generator(source))),
        new FluidState(water));
  }
  public static void main(String[] args) {
    BlockPos ground = new BlockPos();
    Object natural = new TerrainDiffusionBiomeSource(true);
    require(TreeGroundMaterialPolicy.preserveGround(world(Level.OVERWORLD, true, natural), ground),
        "natural TD overworld water above retains substrate");
    require(!TreeGroundMaterialPolicy.preserveGround(world(Level.OVERWORLD, false, natural), ground),
        "dry ground remains eligible for native decoration");
    require(!TreeGroundMaterialPolicy.preserveGround(world(Level.OVERWORLD, true,
        new TerrainDiffusionBiomeSource(false)), ground), "non-natural TD source unchanged");
    require(!TreeGroundMaterialPolicy.preserveGround(world(Level.OVERWORLD, true, new Object()), ground),
        "non-TD source unchanged");
    require(!TreeGroundMaterialPolicy.preserveGround(world(new Object(), true, natural), ground),
        "other dimension unchanged");
    require(!TreeGroundMaterialPolicy.preserveGround(new LevelSimulatedReader() { }, ground),
        "non-worldgen reader unchanged");
    long before = TreeGroundMaterialPolicy.skippedWrites();
    TreeGroundMaterialPolicy.recordSkippedWrite();
    require(TreeGroundMaterialPolicy.skippedWrites() == before + 1, "counter records a skip");
    System.out.println("TreeGroundMaterialPolicyTest: PASS");
  }
}
'''


def java_tool(name: str) -> str:
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    candidates.append(Path(r"C:\Program Files\Java\jdk-21"))
    for home in candidates:
        tool = home / "bin" / f"{name}.exe"
        if tool.is_file():
            return str(tool)
    return shutil.which(name) or ""


class TreeGroundPolicyTest(unittest.TestCase):
    def test_production_policy_preserves_only_submerged_natural_td_ground(self):
        javac, java = java_tool("javac"), java_tool("java")
        self.assertTrue(javac and java, "JDK 21 is required")
        self.assertTrue(POLICY.is_file())
        (ROOT / ".tmp").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="tree-ground-", dir=ROOT / ".tmp") as temporary:
            root = Path(temporary)
            sources = []
            for relative, content in STUBS.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                sources.append(path)
            harness = root / "TreeGroundMaterialPolicyTest.java"
            harness.write_text(HARNESS, encoding="utf-8")
            classes = root / "classes"
            subprocess.run([javac, "-d", str(classes), *map(str, sources), str(POLICY), str(harness)],
                           cwd=ROOT, check=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            result = subprocess.run([java, "-cp", str(classes), "TreeGroundMaterialPolicyTest"],
                                    cwd=ROOT, text=True, capture_output=True, check=True,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            self.assertEqual(result.stdout.strip(), "TreeGroundMaterialPolicyTest: PASS")

    def test_mixin_wraps_only_alter_ground_decorator_block_write(self):
        source = MIXIN.read_text(encoding="utf-8")
        self.assertIn("@Mixin(AlterGroundDecorator.class)", source)
        self.assertIn("method = \"placeBlockAt(", source)
        self.assertIn("AlterGroundEvent$StateProvider;)V", source)
        self.assertIn("TreeGroundMaterialPolicy.preserveGround(context.level(), pos)", source)
        self.assertIn("TreeGroundMaterialPolicy.recordSkippedWrite()", source)


if __name__ == "__main__":
    unittest.main()
