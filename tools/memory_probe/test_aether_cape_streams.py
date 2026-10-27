"""Bytecode and semantic checks for the Aether cape stream compatibility hook."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
INSTANCE_MODS = Path(r"C:\Users\주영\AppData\Roaming\PrismLauncher\instances\1.21.1 neoforge rpg\minecraft\mods")
AETHER = INSTANCE_MODS / "aether-1.21.1-1.5.10-neoforge.jar"
CLASS = "com.aetherteam.aether.mixin.AetherMixinHooks"


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


def require_in_order(text: str, *tokens: str) -> None:
    cursor = 0
    for token in tokens:
        position = text.find(token, cursor)
        assert position >= 0, f"missing bytecode token {token!r}"
        cursor = position + len(token)


def verify_aether_bytecode() -> None:
    bytecode = run(tool("javap"), "-classpath", str(AETHER), "-c", "-p", "-s", CLASS)
    method_start = bytecode.index("public static net.minecraft.world.item.ItemStack isCapeVisible(")
    method_end = bytecode.index("public static net.minecraft.resources.ResourceLocation getCapeTexture", method_start)
    method = bytecode[method_start:method_end]
    target = "Method java/util/stream/StreamSupport.stream:(Ljava/util/Spliterator;Z)Ljava/util/stream/Stream;"
    assert method.count(target) == 2, f"expected two cape stream calls, found {method.count(target)}"
    assert method.count("iconst_1") == 2, "expected exactly two parallel flags in isCapeVisible"
    require_in_order(method, "iconst_1", target, "InterfaceMethod java/util/stream/Stream.findFirst:()Ljava/util/Optional;",
                     "iconst_1", target, "InterfaceMethod java/util/stream/Stream.findFirst:()Ljava/util/Optional;")


def verify_selection_parity() -> None:
    with tempfile.TemporaryDirectory(dir=ROOT / ".tmp") as temporary:
        root = Path(temporary)
        source = root / "CapeStreamSelectionParity.java"
        source.write_text(
            """
            import java.util.List;
            import java.util.Objects;
            import java.util.Spliterator;
            import java.util.stream.StreamSupport;
            public final class CapeStreamSelectionParity {
                private static <T> T first(List<T> values, boolean parallel) {
                    Spliterator<T> spliterator = values.spliterator();
                    return StreamSupport.stream(spliterator, parallel).findFirst().orElse(null);
                }
                private static void check(List<Integer> values) {
                    if (!Objects.equals(first(values, true), first(values, false))) {
                        throw new AssertionError("parallel/sequential findFirst differ: " + values);
                    }
                }
                public static void main(String[] args) {
                    check(List.of());
                    check(List.of(7));
                    check(List.of(7, 3, 9));
                    check(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
                    System.out.println("CapeStreamSelectionParity: PASS");
                }
            }
            """,
            encoding="utf-8",
        )
        output = root / "classes"
        run(tool("javac"), "-d", str(output), str(source))
        result = run(tool("java"), "-cp", str(output), "CapeStreamSelectionParity")
        assert result.strip() == "CapeStreamSelectionParity: PASS", result


if __name__ == "__main__":
    assert AETHER.is_file(), AETHER
    verify_aether_bytecode()
    verify_selection_parity()
    print("Aether cape stream compatibility tests: PASS")
