"""Artifact and semantic checks for the Simple Block Physics dimension filter."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
INSTANCE_MODS = Path(r"C:\Users\주영\AppData\Roaming\PrismLauncher\instances\1.21.1 neoforge rpg\minecraft\mods")
SIMPLE_BLOCK_PHYSICS = INSTANCE_MODS / "simpleblockphysics-1.21.1-neoforge-1.2.0.jar"
TICK_HANDLER = "io.github.ferrinember.simpleblockphysics.utils.TickHandler"
HELPER = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/memory/SimpleBlockPhysicsPlayerFilter.java"


def tool(name: str) -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / f"{name}.exe"
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise AssertionError(f"{name} not found; set JAVA_HOME to a JDK")
    return found


def run(*args: str) -> str:
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout


def verify_target_bytecode() -> None:
    bytecode = run(tool("javap"), "-classpath", str(SIMPLE_BLOCK_PHYSICS), "-c", "-p", "-s", TICK_HANDLER)
    start = bytecode.index("public void onLevelTick(")
    end = bytecode.index("public void checkBreak(", start)
    method = bytecode[start:end]
    assert "descriptor: (Lnet/neoforged/neoforge/event/tick/LevelTickEvent$Pre;)V" in method
    target = "Method net/minecraft/server/players/PlayerList.getPlayers:()Ljava/util/List;"
    assert method.count(target) == 1, method
    position_read = "Method net/minecraft/server/level/ServerPlayer.getOnPos:()Lnet/minecraft/core/BlockPos;"
    assert method.index(target) < method.index(position_read), method


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def verify_filter_semantics() -> None:
    with tempfile.TemporaryDirectory(dir=ROOT / ".tmp") as temporary:
        source_root = Path(temporary) / "source"
        write(source_root / "net/minecraft/world/level/Level.java", """
            package net.minecraft.world.level;
            public final class Level {
                private final String dimension;
                public Level(String dimension) { this.dimension = dimension; }
                public String dimension() { return dimension; }
            }
            """)
        write(source_root / "net/minecraft/server/level/ServerPlayer.java", """
            package net.minecraft.server.level;
            import net.minecraft.world.level.Level;
            public final class ServerPlayer {
                private final String name;
                private final Level level;
                public ServerPlayer(String name, Level level) { this.name = name; this.level = level; }
                public Level level() { return level; }
                public String toString() { return name; }
            }
            """)
        write(source_root / "SimpleBlockPhysicsPlayerFilterTest.java", """
            import com.github.xandergos.terraindiffusionmc.compat.memory.SimpleBlockPhysicsPlayerFilter;
            import java.util.List;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.level.Level;
            public final class SimpleBlockPhysicsPlayerFilterTest {
                private static ServerPlayer player(String name, Level level) { return new ServerPlayer(name, level); }
                private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
                public static void main(String[] args) {
                    Level overworld = new Level("overworld");
                    Level nether = new Level("nether");
                    List<ServerPlayer> all = List.of(player("a", overworld), player("b", overworld));
                    check(SimpleBlockPhysicsPlayerFilter.inLevel(all, overworld) == all, "all-match must retain original list");
                    List<ServerPlayer> none = SimpleBlockPhysicsPlayerFilter.inLevel(List.of(player("n", nether)), overworld);
                    check(none.isEmpty(), "none-match must be empty");
                    List<ServerPlayer> mixed = List.of(player("a", overworld), player("n", nether), player("b", overworld));
                    List<ServerPlayer> filtered = SimpleBlockPhysicsPlayerFilter.inLevel(mixed, overworld);
                    check(filtered.size() == 2 && filtered.get(0).toString().equals("a") && filtered.get(1).toString().equals("b"), "mixed list order/filter");
                    check(filtered != mixed, "mixed list must not return foreign-dimension player");
                    check(SimpleBlockPhysicsPlayerFilter.inLevel(List.of(), overworld).isEmpty(), "empty list");
                    System.out.println("SimpleBlockPhysicsPlayerFilterTest: PASS");
                }
            }
            """)
        output = Path(temporary) / "classes"
        sources = [str(path) for path in source_root.rglob("*.java")] + [str(HELPER)]
        run(tool("javac"), "-d", str(output), *sources)
        result = run(tool("java"), "-cp", str(output), "SimpleBlockPhysicsPlayerFilterTest")
        assert result.strip() == "SimpleBlockPhysicsPlayerFilterTest: PASS", result


if __name__ == "__main__":
    assert SIMPLE_BLOCK_PHYSICS.is_file(), SIMPLE_BLOCK_PHYSICS
    assert HELPER.is_file(), HELPER
    verify_target_bytecode()
    verify_filter_semantics()
    print("Simple Block Physics dimension compatibility tests: PASS")
