"""Focused, no-Gradle checks for the audited YUNG and Voxy memory hooks."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INSTANCE_MODS = Path(os.environ.get("APPDATA", "")) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods"
DEFAULT_YUNGS_API = INSTANCE_MODS / "YungsApi-1.21.1-NeoForge-5.1.9.jar"
DEFAULT_VOXY = INSTANCE_MODS / "voxy-0.2.12-alpha.jar"
ACTUAL_BUDGET = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/memory/VoxyMemoryBudget.java"
ACTUAL_PLUGIN = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/memory/VoxyMemoryMixinPlugin.java"


def run(*args: str) -> str:
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout


def java_tool(name: str) -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / f"{name}.exe"
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise AssertionError(f"{name} not found; set JAVA_HOME to a JDK")
    return found


def require_ordered(text: str, *tokens: str) -> None:
    cursor = 0
    for token in tokens:
        position = text.find(token, cursor)
        assert position >= 0, f"missing bytecode token {token!r}"
        cursor = position + len(token)


def method_body(bytecode: str, declaration: str) -> str:
    match = re.search(
        rf"(?ms)^{re.escape(declaration)}\n    Code:\n(.*?)(?=^  \S|\Z)",
        bytecode,
    )
    assert match, f"missing method declaration {declaration!r}"
    return match.group(1)


def require_once(body: str, token: str, scope: str) -> None:
    count = body.count(token)
    assert count == 1, f"expected one {token!r} in {scope}, found {count}"


def verify_method_body_parser() -> None:
    synthetic = """  void target();
    Code:
       0: sipush        400
       3: if_icmpge     9
  void packagePrivateFollower();
    Code:
       0: sipush        400
  static {};
    Code:
       0: sipush        400
"""
    target = method_body(synthetic, "  void target();")
    require_once(target, "sipush        400", "synthetic target method")
    require_once(target, "if_icmpge", "synthetic target method")


def verify_yungs_bytecode(yungs_api: Path) -> None:
    yung = run(
        java_tool("javap"),
        "-classpath",
        str(yungs_api),
        "-c",
        "-p",
        "com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.aquiferoverride.AquiferOverrideMask",
    )
    require_ordered(
        yung,
        "public com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.aquiferoverride.AquiferOverrideMask(int, int);",
        "sipush        256",
        "imul",
        'Method java/util/BitSet."<init>":(I)V',
    )
    require_ordered(
        yung,
        "Method java/util/BitSet.set:(I)V",
        "Method java/util/BitSet.get:(I)Z",
    )


def verify_voxy_bytecode(voxy_jar: Path) -> None:
    javap = java_tool("javap")
    engine = run(
        javap,
        "-classpath",
        str(voxy_jar),
        "-c",
        "-p",
        "me.cortex.voxy.common.world.WorldEngine",
    )
    engine_constructor = method_body(
        engine,
        "  public me.cortex.voxy.common.world.WorldEngine("
        "me.cortex.voxy.common.config.section.SectionStorage, "
        "me.cortex.voxy.commonImpl.VoxyInstance);",
    )
    require_once(engine_constructor, "sipush        1024", "WorldEngine cache constructor")
    require_once(engine_constructor, "sipush        2048", "WorldEngine cache constructor")
    require_once(
        engine_constructor,
        'Method me/cortex/voxy/common/world/ActiveSectionTracker."<init>":'
        "(ILme/cortex/voxy/common/world/ActiveSectionTracker$SectionLoader;"
        "ILme/cortex/voxy/common/world/WorldEngine;)V",
        "WorldEngine cache constructor",
    )
    section = run(
        javap,
        "-classpath",
        str(voxy_jar),
        "-c",
        "-p",
        "me.cortex.voxy.common.world.WorldSection",
    )
    release_array = method_body(section, "  void _releaseArray();")
    require_once(release_array, "sipush        400", "WorldSection._releaseArray")
    require_once(release_array, "if_icmpge", "WorldSection._releaseArray")


def write_java(path: Path, source: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")


def verify_budget_and_plugin_behavior() -> None:
    javac = java_tool("javac")
    with tempfile.TemporaryDirectory(dir=ROOT / ".tmp") as temporary:
        root = Path(temporary)
        sources = root / "sources"
        output = root / "classes"
        write_java(sources / "net/neoforged/fml/loading/FMLPaths.java", """
            package net.neoforged.fml.loading;

            import java.nio.file.Path;

            public enum FMLPaths {
                CONFIGDIR;

                public Path get() {
                    return Path.of(System.getProperty("test.config.dir"));
                }
            }
            """)
        write_java(sources / "org/slf4j/Logger.java", """
            package org.slf4j;

            public interface Logger {
                default void warn(String message, Object... arguments) { }
                default void info(String message, Object... arguments) { }
            }
            """)
        write_java(sources / "org/slf4j/LoggerFactory.java", """
            package org.slf4j;

            public final class LoggerFactory {
                private static final Logger LOGGER = new Logger() { };

                public static Logger getLogger(Class<?> ignored) {
                    return LOGGER;
                }
            }
            """)
        write_java(sources / "net/neoforged/fml/loading/FMLLoader.java", """
            package net.neoforged.fml.loading;

            import java.util.List;

            public final class FMLLoader {
                public record Mod(String id, Object version) {
                    public String getModId() { return id; }
                    public Object getVersion() { return version; }
                }

                public static final class ModList {
                    private final List<Mod> mods;
                    public ModList(List<Mod> mods) { this.mods = mods; }
                    public List<Mod> getMods() { return mods; }
                }

                private static ModList loading;

                public static ModList getLoadingModList() { return loading; }
                public static void setLoadingModList(ModList value) { loading = value; }
            }
            """)
        write_java(
            sources / "org/objectweb/asm/tree/ClassNode.java",
            "package org.objectweb.asm.tree; public class ClassNode {}\n",
        )
        write_java(
            sources / "org/spongepowered/asm/mixin/extensibility/IMixinInfo.java",
            "package org.spongepowered.asm.mixin.extensibility; public interface IMixinInfo {}\n",
        )
        write_java(sources / "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin.java", """
            package org.spongepowered.asm.mixin.extensibility;

            import java.util.List;
            import java.util.Set;
            import org.objectweb.asm.tree.ClassNode;

            public interface IMixinConfigPlugin {
                boolean shouldApplyMixin(String target, String mixin);
                void onLoad(String mixinPackage);
                String getRefMapperConfig();
                void acceptTargets(Set<String> mine, Set<String> other);
                List<String> getMixins();
                void preApply(String target, ClassNode node, String mixin, IMixinInfo info);
                void postApply(String target, ClassNode node, String mixin, IMixinInfo info);
            }
            """)
        package = sources / "com/github/xandergos/terraindiffusionmc/compat/memory"
        write_java(package / "VoxyMemoryBudgetTest.java", """
            package com.github.xandergos.terraindiffusionmc.compat.memory;

            import java.util.BitSet;
            import java.util.List;
            import java.util.Properties;
            import net.neoforged.fml.loading.FMLLoader;

            public final class VoxyMemoryBudgetTest {
                private static void require(boolean value, String message) {
                    if (!value) throw new AssertionError(message);
                }

                private static void requirePlugin(String version, boolean expected, String message) {
                    FMLLoader.setLoadingModList(new FMLLoader.ModList(
                            List.of(new FMLLoader.Mod("voxy", version))));
                    require(new VoxyMemoryMixinPlugin().shouldApplyMixin("target", "mixin") == expected,
                            message);
                }

                public static void main(String[] args) {
                    boolean configured = Boolean.getBoolean("test.configured");
                    Properties defaults = new Properties();
                    var normal = VoxyMemoryBudget.parse(defaults);
                    require(normal.secondaryCacheSections() == 2048,
                            "default preserves Voxy secondary budget");
                    require(normal.arrayReuseSections() == 400,
                            "default preserves Voxy reuse budget");
                    require(!VoxyMemoryBudget.isReduction(normal), "default skips Voxy mixins");

                    Properties requested = new Properties();
                    requested.setProperty(VoxyMemoryBudget.SECONDARY_CACHE_PROPERTY, "512");
                    requested.setProperty(VoxyMemoryBudget.ARRAY_REUSE_PROPERTY, "128");
                    var tight = VoxyMemoryBudget.parse(requested);
                    require(tight.secondaryCacheSections() == 512, "configured secondary budget");
                    require(tight.arrayReuseSections() == 128, "configured reuse budget");
                    require(VoxyMemoryBudget.isReduction(tight),
                            "memory-first configuration enables Voxy mixins");

                    requested.setProperty(VoxyMemoryBudget.SECONDARY_CACHE_PROPERTY, "2049");
                    requested.setProperty(VoxyMemoryBudget.ARRAY_REUSE_PROPERTY, "-1");
                    var invalid = VoxyMemoryBudget.parse(requested);
                    require(invalid.secondaryCacheSections() == 2048,
                            "invalid secondary falls back");
                    require(invalid.arrayReuseSections() == 400,
                            "invalid reuse falls back");
                    require(VoxyMemoryBudget.secondaryCacheSections(256) == 256,
                            "never expands an upstream lower secondary cache");
                    require(VoxyMemoryBudget.arrayReuseSections(64) == 64,
                            "never expands an upstream lower reuse pool");

                    BitSet deferred = new BitSet(0);
                    int finalBit = 256 * 384 - 1;
                    require(!deferred.get(finalBit), "empty mask starts clear");
                    deferred.set(finalBit);
                    require(deferred.get(finalBit), "empty BitSet grows on set");

                    requirePlugin("0.2.12-alpha", configured,
                            "legacy Voxy gate follows configured reduction");
                    requirePlugin("1.1.0", configured,
                            "Voxy 1.1.0 gate follows configured reduction");
                    requirePlugin("1.1.1", false, "unknown Voxy version disabled");
                    FMLLoader.setLoadingModList(new FMLLoader.ModList(
                            List.of(new FMLLoader.Mod("another_mod", "1.1.0"))));
                    require(!new VoxyMemoryMixinPlugin().shouldApplyMixin("target", "mixin"),
                            "absent Voxy mod disabled");
                    FMLLoader.setLoadingModList(null);
                    require(!new VoxyMemoryMixinPlugin().shouldApplyMixin("target", "mixin"),
                            "absent loading mod list disabled");
                    System.out.println("VoxyMemoryBudgetTest: PASS configured=" + configured);
                }
            }
            """)
        source_files = [str(path) for path in sources.rglob("*.java")]
        run(
            javac,
            "-d",
            str(output),
            *source_files,
            str(ACTUAL_BUDGET),
            str(ACTUAL_PLUGIN),
        )
        default_config = root / "default-config"
        default_config.mkdir()
        configured_config = root / "configured-config"
        configured_config.mkdir()
        (configured_config / "terrain-diffusion-mc-memory.properties").write_text(
            "voxy.secondary_cache_sections=512\nvoxy.array_reuse_sections=128\n",
            encoding="utf-8",
        )
        default_output = run(
            java_tool("java"),
            f"-Dtest.config.dir={default_config}",
            "-cp",
            str(output),
            "com.github.xandergos.terraindiffusionmc.compat.memory.VoxyMemoryBudgetTest",
        )
        assert default_output.strip() == "VoxyMemoryBudgetTest: PASS configured=false", default_output
        configured_output = run(
            java_tool("java"),
            f"-Dtest.config.dir={configured_config}",
            "-Dtest.configured=true",
            "-cp",
            str(output),
            "com.github.xandergos.terraindiffusionmc.compat.memory.VoxyMemoryBudgetTest",
        )
        assert configured_output.strip() == "VoxyMemoryBudgetTest: PASS configured=true", configured_output


def existing_or_explicit(path: Path, label: str) -> Path:
    assert path.is_file(), f"{label} does not exist: {path}"
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--voxy-jar",
        type=Path,
        action="append",
        default=[],
        help="Voxy JAR to inspect; repeat to validate multiple versions",
    )
    parser.add_argument(
        "--yungs-api-jar",
        type=Path,
        help="YUNG's API JAR to inspect; defaults to the legacy Prism instance when available",
    )
    args = parser.parse_args()
    verify_method_body_parser()
    if args.yungs_api_jar is not None:
        verify_yungs_bytecode(existing_or_explicit(args.yungs_api_jar, "YUNG's API"))
    elif DEFAULT_YUNGS_API.is_file():
        verify_yungs_bytecode(DEFAULT_YUNGS_API)
    else:
        print(f"SKIP: default YUNG's API JAR not found: {DEFAULT_YUNGS_API}")
    if args.voxy_jar:
        for voxy_jar in args.voxy_jar:
            verify_voxy_bytecode(existing_or_explicit(voxy_jar, "Voxy JAR"))
    elif DEFAULT_VOXY.is_file():
        verify_voxy_bytecode(DEFAULT_VOXY)
    else:
        print(f"SKIP: default Voxy JAR not found: {DEFAULT_VOXY}")
    assert ACTUAL_BUDGET.is_file(), ACTUAL_BUDGET
    assert ACTUAL_PLUGIN.is_file(), ACTUAL_PLUGIN
    verify_budget_and_plugin_behavior()
    print("Compatibility bytecode and memory-budget tests: PASS")


if __name__ == "__main__":
    main()
