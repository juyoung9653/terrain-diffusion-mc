"""Compiles the production template-coordinate interner against a tiny BlockPos API stub."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/TemplatePositionInterner.java"
JAVAC = Path(r"C:\Program Files\Java\jdk-21\bin\javac.exe")
JAVA = Path(r"C:\Program Files\Java\jdk-21\bin\java.exe")

BLOCK_POS = """package net.minecraft.core;
public class BlockPos {
  private final int x, y, z;
  public BlockPos(int x, int y, int z) { this.x=x; this.y=y; this.z=z; }
  public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; }
  public static class MutableBlockPos extends BlockPos {
    private int mx, my, mz;
    public MutableBlockPos(int x, int y, int z) { super(0, 0, 0); mx=x; my=y; mz=z; }
    @Override public int getX() { return mx; } @Override public int getY() { return my; }
    @Override public int getZ() { return mz; }
    public void set(int x, int y, int z) { mx=x; my=y; mz=z; }
  }
}
"""

HARNESS = """package probe;
import com.github.xandergos.terraindiffusionmc.world.structure.TemplatePositionInterner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
public final class TemplatePositionInternerHarness {
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    BlockPos first = TemplatePositionInterner.intern(new BlockPos(7, 9, 11));
    BlockPos second = TemplatePositionInterner.intern(new BlockPos(7, 9, 11));
    check(first == second, "equivalent normal coordinates were not shared");
    check(first != TemplatePositionInterner.intern(new BlockPos(8, 9, 11)), "different coordinates aliased");
    BlockPos outside = new BlockPos(128, 0, 0);
    check(TemplatePositionInterner.intern(outside) == outside, "outside coordinate was cached");
    BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(12, 13, 14);
    BlockPos frozen = TemplatePositionInterner.intern(mutable);
    mutable.set(1, 2, 3);
    check(frozen != mutable && frozen.getX() == 12 && frozen.getY() == 13 && frozen.getZ() == 14,
        "mutable input was published or changed a shared coordinate");
    check(frozen == TemplatePositionInterner.intern(new BlockPos(12, 13, 14)),
        "a frozen mutable coordinate was not shared");
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<BlockPos>> futures = new ArrayList<>();
    for (int i = 0; i < 64; i++) futures.add(pool.submit(() -> TemplatePositionInterner.intern(new BlockPos(31, 23, 47))));
    BlockPos shared = futures.getFirst().get();
    for (Future<BlockPos> future : futures) check(shared == future.get(), "concurrent interning returned distinct coordinates");
    pool.shutdown(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "worker pool did not stop");
    System.out.println("PASS actual TemplatePositionInterner compile and identity/concurrency checks");
  }
}
"""


def main() -> None:
    if not JAVAC.is_file() or not JAVA.is_file():
        raise SystemExit("JDK 21 not found; this test has not run")
    scratch = ROOT / ".tmp"
    scratch.mkdir(exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="td-template-position-interner-", dir=scratch))
    try:
        stub = temporary / "net/minecraft/core/BlockPos.java"
        harness = temporary / "probe/TemplatePositionInternerHarness.java"
        stub.parent.mkdir(parents=True)
        harness.parent.mkdir(parents=True)
        stub.write_text(BLOCK_POS, encoding="utf-8")
        harness.write_text(HARNESS, encoding="utf-8")
        subprocess.run([str(JAVAC), "-d", str(temporary), str(stub), str(SOURCE), str(harness)], check=True)
        subprocess.run([str(JAVA), "-cp", str(temporary), "probe.TemplatePositionInternerHarness"], check=True)
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


if __name__ == "__main__":
    main()
