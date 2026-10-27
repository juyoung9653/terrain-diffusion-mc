"""Stage, but never install, selected NeoForge Jar-in-Jar libraries on disk.

The plan is deliberately pinned to the inspected Voxy 0.2.12-alpha/1.1.0 and Burnt
1.10.5.2 artifacts.  It removes only the selected JarJar members and their JSON
metadata entries; every other parent member is copied with its original content
and ZipInfo metadata.  The source mods are opened read-only and hash-checked
again after staging.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import io
import json
import shutil
import tomllib
import zipfile
from dataclasses import dataclass
from pathlib import Path


class ExternalizationError(ValueError):
    pass


@dataclass(frozen=True)
class Target:
    owner: str
    artifact: str
    version: str
    nested_path: str
    kind: str  # library or mod
    companion: str
    module_name: str | None = None


VOXY = "voxy-0.2.12-alpha.jar"
VOXY_PROFILES = {
    "0.2.12-alpha": VOXY,
    "1.1.0": "voxy-1.1.0.jar",
}
BURNT = "burnt-1.10.5.2-neoforge-1.21.1.jar"
TARGETS = (
    Target("voxy", "rocksdbjni", "10.2.1", "META-INF/jarjar/rocksdbjni-10.2.1.jar",
           "library", "terrain-diffusion-external-voxy-rocksdbjni-10.2.1.jar",
           "terrain.diffusion.external.voxy.rocksdbjni"),
    Target("voxy", "sqlite-jdbc", "3.49.1.0", "META-INF/jarjar/sqlite-jdbc-3.49.1.0.jar",
           "library", "terrain-diffusion-external-voxy-sqlite-jdbc-3.49.1.0.jar",
           "terrain.diffusion.external.voxy.sqlitejdbc"),
    Target("burnt", "burnt_basic", "1.10.5.2",
           "META-INF/jarjar/burnt_basic-1.10.5.2-neoforge-1.21.1.jar",
           "mod", "burnt_basic-1.10.5.2-neoforge-1.21.1.jar"),
)
METADATA = "META-INF/jarjar/metadata.json"
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for part in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(part)
    return digest.hexdigest()


def signature_entries(archive: zipfile.ZipFile) -> list[str]:
    return [entry.filename for entry in archive.infolist()
            if entry.filename.upper().startswith("META-INF/")
            and (entry.filename.upper().endswith(SIGNATURE_SUFFIXES)
                 or entry.filename.upper().startswith("META-INF/SIG-"))]


def clone_info(info: zipfile.ZipInfo) -> zipfile.ZipInfo:
    cloned = zipfile.ZipInfo(info.filename, info.date_time)
    cloned.comment, cloned.extra = info.comment, info.extra
    cloned.internal_attr, cloned.external_attr = info.internal_attr, info.external_attr
    cloned.create_system, cloned.create_version = info.create_system, info.create_version
    cloned.extract_version, cloned.flag_bits = info.extract_version, info.flag_bits
    return cloned


def write_member(output: zipfile.ZipFile, info: zipfile.ZipInfo, content: bytes) -> None:
    output.writestr(clone_info(info), content, compress_type=info.compress_type)


def manifest_values(raw: bytes) -> dict[str, str]:
    values: dict[str, str] = {}
    key: str | None = None
    for line in raw.decode("utf-8", "strict").replace("\r\n", "\n").split("\n"):
        if line.startswith(" ") and key:
            values[key] += line[1:]
        elif ": " in line:
            key, value = line.split(": ", 1)
            values[key] = value
        elif line:
            key = None
    return values


def library_companion(nested: bytes, target: Target) -> bytes:
    with zipfile.ZipFile(io.BytesIO(nested)) as source:
        if signature_entries(source):
            raise ExternalizationError(f"signed nested library cannot be safely rewritten: {target.nested_path}")
        if "module-info.class" in source.namelist():
            raise ExternalizationError(f"module-info library needs a reviewed module-layer plan: {target.nested_path}")
        if "META-INF/neoforge.mods.toml" in source.namelist():
            raise ExternalizationError(f"nested mod must not be converted to LIBRARY: {target.nested_path}")
        try:
            manifest_info = source.getinfo("META-INF/MANIFEST.MF")
        except KeyError as missing:
            raise ExternalizationError(f"nested library has no manifest: {target.nested_path}") from missing
        values = manifest_values(source.read(manifest_info))
        if values.get("FMLModType") not in (None, "LIBRARY"):
            raise ExternalizationError(f"nested library has incompatible FML type: {target.nested_path}")
        if values.get("Automatic-Module-Name") not in (None, target.module_name):
            raise ExternalizationError(f"nested library has incompatible module name: {target.nested_path}")
        rendered = source.read(manifest_info).decode("utf-8", "strict").replace("\r\n", "\n").rstrip("\n")
        if "FMLModType" not in values:
            rendered += "\nFMLModType: LIBRARY"
        if "Automatic-Module-Name" not in values and target.module_name:
            rendered += f"\nAutomatic-Module-Name: {target.module_name}"
        rendered += "\n\n"
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as output:
            output.comment = source.comment
            for info in source.infolist():
                write_member(output, info, rendered.encode("utf-8") if info.filename == manifest_info.filename
                             else source.read(info))
    return buffer.getvalue()


def jarjar_metadata(archive: zipfile.ZipFile) -> tuple[zipfile.ZipInfo, dict]:
    if signature_entries(archive):
        raise ExternalizationError("signed parent mod cannot be safely rewritten")
    try:
        info = archive.getinfo(METADATA)
        data = json.loads(archive.read(info).decode("utf-8"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise ExternalizationError("missing or malformed JarJar metadata") from failure
    if not isinstance(data, dict) or not isinstance(data.get("jars"), list):
        raise ExternalizationError("JarJar metadata has no jars list")
    return info, data


def voxy_filename(version: str = "0.2.12-alpha") -> str:
    try:
        return VOXY_PROFILES[version]
    except KeyError as error:
        raise ExternalizationError(f"unsupported inspected Voxy version: {version}") from error


def verify_voxy_parent(path: Path, version: str) -> None:
    """Reject a same-named arbitrary JAR before rewriting it."""
    if path.name != voxy_filename(version):
        raise ExternalizationError(f"Voxy profile has unexpected filename: {path.name}")
    try:
        with zipfile.ZipFile(path) as archive:
            metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8", "strict"))
            manifest = manifest_values(archive.read("META-INF/MANIFEST.MF"))
    except (zipfile.BadZipFile, KeyError, UnicodeDecodeError, tomllib.TOMLDecodeError) as error:
        raise ExternalizationError(f"Voxy profile cannot verify parent metadata: {path.name}") from error
    mods = metadata.get("mods")
    if not isinstance(mods, list):
        raise ExternalizationError(f"Voxy profile has no mods metadata: {path.name}")
    voxy = next((entry for entry in mods if isinstance(entry, dict) and entry.get("modId") == "voxy"), None)
    if voxy is None:
        raise ExternalizationError(f"Voxy profile has unexpected mod id: {path.name}")
    declared = voxy.get("version")
    if declared == "${file.jarVersion}":
        declared = manifest.get("Implementation-Version")
    if declared != version:
        raise ExternalizationError(f"Voxy profile has unexpected version: {path.name}")


def select(archive: zipfile.ZipFile, owner: str) -> tuple[zipfile.ZipInfo, dict, list[Target]]:
    info, metadata = jarjar_metadata(archive)
    targets = [target for target in TARGETS if target.owner == owner]
    by_path = {entry.get("path"): entry for entry in metadata["jars"] if isinstance(entry, dict)}
    for target in targets:
        entry = by_path.get(target.nested_path)
        if entry is None or target.nested_path not in archive.namelist():
            raise ExternalizationError(f"missing selected nested library: {target.nested_path}")
        identifier, version = entry.get("identifier"), entry.get("version")
        if not isinstance(identifier, dict) or not isinstance(version, dict) or identifier.get("artifact") != target.artifact \
                or version.get("artifactVersion") != target.version:
            raise ExternalizationError(f"unexpected JarJar identity for {target.nested_path}")
    return info, metadata, targets


def stripped_parent(source_path: Path, destination: Path, owner: str) -> list[tuple[Target, bytes]]:
    with zipfile.ZipFile(source_path) as source:
        metadata_info, metadata, targets = select(source, owner)
        selected_paths = {target.nested_path for target in targets}
        companions = [(target, source.read(target.nested_path)) for target in targets]
        rewritten = copy.deepcopy(metadata)
        rewritten["jars"] = [entry for entry in rewritten["jars"] if entry.get("path") not in selected_paths]
        metadata_bytes = (json.dumps(rewritten, indent=2) + "\n").encode("utf-8")
        with zipfile.ZipFile(destination, "w") as output:
            output.comment = source.comment
            for info in source.infolist():
                if info.filename in selected_paths:
                    continue
                content = metadata_bytes if info.filename == metadata_info.filename else source.read(info)
                write_member(output, info, content)
    return companions


def existing_modules(mods_dir: Path, ignored: set[Path]) -> tuple[set[str], set[str]]:
    names, modules = set(), set()
    if not mods_dir.exists():
        return names, modules
    for jar in mods_dir.glob("*.jar"):
        if jar.resolve() in ignored:
            continue
        names.add(jar.name)
        try:
            with zipfile.ZipFile(jar) as archive:
                if "META-INF/MANIFEST.MF" in archive.namelist():
                    module = manifest_values(archive.read("META-INF/MANIFEST.MF")).get("Automatic-Module-Name")
                    if module:
                        modules.add(module)
                if "META-INF/neoforge.mods.toml" in archive.namelist():
                    text = archive.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace")
                    if 'modId = "burnt"' in text or 'modId="burnt"' in text:
                        modules.add("mod:burnt")
        except zipfile.BadZipFile as failure:
            raise ExternalizationError(f"unreadable existing mod: {jar}") from failure
    return names, modules


def stage(mods_dir: Path, destination: Path, voxy_version: str = "0.2.12-alpha") -> Path:
    voxy_name = voxy_filename(voxy_version)
    voxy, burnt = mods_dir / voxy_name, mods_dir / BURNT
    if not voxy.is_file() or not burnt.is_file():
        raise ExternalizationError("expected inspected Voxy and Burnt jars are missing")
    verify_voxy_parent(voxy, voxy_version)
    if destination.exists() and any(destination.iterdir()):
        raise ExternalizationError(f"stage destination must be empty: {destination}")
    names, modules = existing_modules(mods_dir, {voxy.resolve(), burnt.resolve()})
    required_names = {target.companion for target in TARGETS}
    required_modules = {target.module_name for target in TARGETS if target.module_name} | {"mod:burnt"}
    if names & required_names or modules & required_modules:
        raise ExternalizationError("existing mods already provide an externalized target")
    destination.mkdir(parents=True, exist_ok=False)
    before = {voxy: sha256(voxy), burnt: sha256(burnt)}
    try:
        inventory: list[dict[str, str]] = []
        for source, owner in ((voxy, "voxy"), (burnt, "burnt")):
            parent = destination / source.name
            companions = stripped_parent(source, parent, owner)
            inventory.append({"kind": "rewritten-parent", "source": source.name, "file": parent.name,
                              "sourceSha256": before[source], "sha256": sha256(parent)})
            for target, nested in companions:
                companion = destination / target.companion
                content = library_companion(nested, target) if target.kind == "library" else nested
                companion.write_bytes(content)
                inventory.append({"kind": target.kind, "source": source.name, "file": companion.name,
                                  "nestedPath": target.nested_path, "sha256": sha256(companion)})
        if any(sha256(source) != expected for source, expected in before.items()):
            raise ExternalizationError("source mod changed while staging")
        plan = {
            "formatVersion": 1,
            "mode": "workspace-staged-externalization",
            "sources": {path.name: digest for path, digest in before.items()},
            "inventory": inventory,
            "installPlan": {
                "backup": [voxy_name, BURNT],
                "replace": [voxy_name, BURNT],
                "add": [target.companion for target in TARGETS],
                "note": "Staged only. Copy requires an instance-level backup and a stopped game."
            },
        }
        manifest = destination / "externalization-plan.json"
        manifest.write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
        return manifest
    except Exception:
        shutil.rmtree(destination, ignore_errors=True)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mods-dir", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--voxy-version", choices=sorted(VOXY_PROFILES), default="0.2.12-alpha")
    args = parser.parse_args()
    print(stage(args.mods_dir, args.stage, args.voxy_version))


if __name__ == "__main__":
    main()
