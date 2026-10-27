"""Verify and install the opt-in NeoForge disk-backed ONNX Runtime package.

The portable artifact continues to use Jar-in-Jar.  This tool only accepts the
receipt emitted by ``-PneoforgeDiskLibraries=true`` and prevents a partial copy
from leaving a mod jar without its FML LIBRARY companion.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import tempfile
import zipfile
from pathlib import Path


REQUIRED = {
    "formatVersion", "mode", "module", "sourceSha256", "libraryFile",
    "librarySha256", "modFile", "modSha256",
}


def properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise ValueError(f"invalid receipt line in {path}: {line!r}")
        values[key] = value
    if set(values) != REQUIRED:
        raise ValueError(f"receipt schema mismatch in {path}: {sorted(values)}")
    if values["formatVersion"] != "1" or values["mode"] != "neoforge-disk-library":
        raise ValueError(f"unsupported disk-library receipt in {path}")
    if not values["module"].startswith("com.microsoft.onnxruntime:"):
        raise ValueError(f"unexpected library module in {path}: {values['module']}")
    return values


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest(jar: Path) -> dict[str, str]:
    with zipfile.ZipFile(jar) as archive:
        try:
            raw = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        except KeyError as failure:
            raise ValueError(f"missing manifest: {jar}") from failure
    values: dict[str, str] = {}
    for line in raw.replace("\r\n", "\n").split("\n"):
        key, separator, value = line.partition(": ")
        if separator:
            values[key] = value
    return values


def verify(receipt_path: Path, library: Path, mod: Path) -> dict[str, str]:
    receipt = properties(receipt_path)
    if library.name != receipt["libraryFile"] or mod.name != receipt["modFile"]:
        raise ValueError("artifact filename does not match receipt")
    if sha256(library) != receipt["librarySha256"]:
        raise ValueError("disk ONNX library hash does not match receipt")
    if sha256(mod) != receipt["modSha256"]:
        raise ValueError("NeoForge mod hash does not match receipt")
    library_manifest = manifest(library)
    if library_manifest.get("FMLModType") != "LIBRARY":
        raise ValueError("disk ONNX library is not an FML LIBRARY candidate")
    if library_manifest.get("Implementation-Version") != receipt["module"].rsplit(":", 1)[1]:
        raise ValueError("disk ONNX library manifest version does not match receipt")
    with zipfile.ZipFile(library) as archive:
        if "META-INF/neoforge.mods.toml" in archive.namelist():
            raise ValueError("disk ONNX library must be a standalone FML LIBRARY, not a second mod")
        if "ai/onnxruntime/OrtEnvironment.class" not in archive.namelist():
            raise ValueError("disk ONNX library lacks OrtEnvironment")
        if receipt["module"].split(":", 2)[1] == "onnxruntime_gpu":
            cuda = "ai/onnxruntime/native/win-x64/onnxruntime_providers_cuda.dll"
            if cuda not in archive.namelist():
                raise ValueError("disk ONNX GPU library lacks the Windows CUDA native provider")
    with zipfile.ZipFile(mod) as archive:
        nested = [name for name in archive.namelist() if "onnxruntime" in name.lower()]
    if nested:
        raise ValueError(f"disk mode mod still embeds ONNX: {nested[0]}")
    return receipt


def atomic_copy(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=target.name + ".", suffix=".tmp", dir=target.parent,
                                     delete=False) as temporary:
        temporary_path = Path(temporary.name)
        with source.open("rb") as input_file:
            shutil.copyfileobj(input_file, temporary)
        temporary.flush()
    try:
        temporary_path.replace(target)
    finally:
        temporary_path.unlink(missing_ok=True)


def install(receipt_path: Path, library: Path, mod: Path, mods_dir: Path) -> None:
    verify(receipt_path, library, mod)
    # The library goes first: an interrupted install cannot leave a freshly
    # copied slim mod without its required runtime jar.
    atomic_copy(library, mods_dir / library.name)
    atomic_copy(mod, mods_dir / mod.name)
    atomic_copy(receipt_path, mods_dir / (mod.name + ".onnx-receipt.properties"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("receipt", type=Path)
    parser.add_argument("library", type=Path)
    parser.add_argument("mod", type=Path)
    parser.add_argument("--install-mods", type=Path, metavar="DIRECTORY")
    args = parser.parse_args()
    if args.install_mods:
        install(args.receipt, args.library, args.mod, args.install_mods)
    else:
        verify(args.receipt, args.library, args.mod)


if __name__ == "__main__":
    main()
