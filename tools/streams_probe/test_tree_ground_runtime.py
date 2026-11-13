import tempfile
import os
import importlib.util
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.streams_probe.tree_ground_runtime import SET_BLOCK_TARGET, METHOD_DESCRIPTOR, validate_tree_ground_runtime, validate_tree_ground_mixin_jar

SOURCE = '''@WrapOperation(method = "%s", at = @At(value = "INVOKE", target = "%s"), require = 1) class X {}''' % (METHOD_DESCRIPTOR, SET_BLOCK_TARGET)
HEADER = "private void placeBlockAt(net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator$Context, net.minecraft.core.BlockPos, net.neoforged.neoforge.event.level.AlterGroundEvent$StateProvider);\n descriptor: (Lnet/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context;Lnet/minecraft/core/BlockPos;Lnet/neoforged/neoforge/event/level/AlterGroundEvent$StateProvider;)V"

class TreeGroundRuntimeTest(unittest.TestCase):
    def run_with(self, output, source=SOURCE):
        scratch = Path.cwd() / "build" / "tree-ground-runtime-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory); jar = root / "runtime.jar"; jar.write_bytes(b"x")
            java = root / "java"; (java / "bin").mkdir(parents=True); (java / "bin" / "javap.exe").write_bytes(b"x")
            mixin = root / "Mixin.java"; mixin.write_text(source, encoding="utf-8")
            with patch("tools.streams_probe.tree_ground_runtime.subprocess.run") as run:
                run.return_value.returncode, run.return_value.stdout, run.return_value.stderr = 0, output, ""
                return validate_tree_ground_runtime(jar, java, mixin)

    def test_exact_event_overload_with_one_write_passes(self):
        result = self.run_with(HEADER + "\n Code:\n  44: invokevirtual #1 // Method net/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context.setBlock:(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V\n  private void other();")
        self.assertEqual(1, result["calls"])

    def test_two_argument_selector_fails_before_javap(self):
        bad = SOURCE.replace(METHOD_DESCRIPTOR, "placeBlockAt")
        with self.assertRaisesRegex(ValueError, "selector"): self.run_with("", bad)

    def test_missing_runtime_overload_fails(self):
        with self.assertRaisesRegex(ValueError, "three-argument"): self.run_with("private void placeBlockAt(Context, BlockPos);")

    def test_two_writes_fail(self):
        with self.assertRaisesRegex(ValueError, "2 Context.setBlock"):
            self.run_with(HEADER + " Method net/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context.setBlock:(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V Method net/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context.setBlock:(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V")

    def test_wrong_descriptor_write_does_not_count(self):
        with self.assertRaisesRegex(ValueError, "0 Context.setBlock"):
            self.run_with(HEADER + " Method net/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context.setBlock:(I)V")

    def test_installed_264_runtime_smoke(self):
        jar = Path(os.environ.get("APPDATA", "~")).expanduser() / "PrismLauncher/libraries/net/neoforged/neoforge/21.1.264/neoforge-21.1.264-client.jar"
        java = Path(r"C:\Program Files\Java\jdk-21")
        source = Path("neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/SubmergedTreeGroundMixin.java")
        if not (jar.is_file() and (java / "bin" / "javap.exe").is_file() and source.is_file()): self.skipTest("local 21.1.264 runtime unavailable")
        self.assertEqual(1, validate_tree_ground_runtime(jar, java, source)["calls"])

    def test_compiled_mixin_annotation_rejects_stale_selector(self):
        scratch = Path.cwd() / "build" / "tree-ground-runtime-tests"; scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory); jar = root / "runtime.jar"; mixin = root / "mixin.jar"; jar.write_bytes(b"r"); mixin.write_bytes(b"m")
            java = root / "java"; (java / "bin").mkdir(parents=True); (java / "bin" / "javap.exe").write_bytes(b"j")
            good = HEADER + " Method net/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context.setBlock:(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"
            stale = "RuntimeVisibleAnnotations:\n method=\"placeBlockAt\" target=\"%s\"\n" % SET_BLOCK_TARGET
            with patch("tools.streams_probe.tree_ground_runtime.run_javap", side_effect=[good, stale]):
                with self.assertRaisesRegex(ValueError, "compiled mixin"):
                    validate_tree_ground_mixin_jar(jar, java, mixin, "com.example.Stale")

    def test_build_preflight_blocks_javac(self):
        build_path = Path("tools/streams_probe/instance-client-probe/build.py")
        spec = importlib.util.spec_from_file_location("tree_ground_build_test", build_path)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory(dir=Path.cwd() / "build" / "tree-ground-runtime-tests") as directory:
            old = sys.argv; sys.argv = ["build.py", "--tree-ground-fix", "--output-dir", directory]
            try:
                with patch.object(module, "validate_tree_ground_runtime", side_effect=ValueError("preflight failed")), patch.object(module.subprocess, "run") as run:
                    with self.assertRaisesRegex(ValueError, "preflight failed"): module.main()
                    run.assert_not_called()
            finally: sys.argv = old

