#!/usr/bin/env python3
"""Create and later remove a narrowly-scoped temporary NVIDIA FRL exception.

This is deliberately separate from ``nvapi_readonly.py``.  It is *not* run by
the FPS launcher.  ``apply`` writes only one custom DRS profile for the exact
Java executable used by the probe and only one setting (FRL=0).  ``restore``
deletes only that profile after revalidating its name, application, setting and
counts.  It never reads or writes the global profile.

The API names, IDs and layouts come from NVIDIA's public nvapi.h and
nvapi_interface.h.  An interrupted operation leaves the state JSON in place so
that restore can fail closed rather than guessing what to remove.
"""
from __future__ import annotations

import argparse
import ctypes as c
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import nvapi_readonly as ro


JAVA = Path(r"C:\Program Files\Java\jdk-21\bin\javaw.exe")
PROFILE_NAME = "TD-FPS-PROBE-FRL-TEMPORARY"
FRL_ID = 0x10835002


class Profile(c.Structure):
    _fields_ = [("version", c.c_uint32), ("profile_name", ro.U16_2048),
                ("gpu_support", c.c_uint32), ("is_predefined", c.c_uint32),
                ("number_of_apps", c.c_uint32), ("number_of_settings", c.c_uint32)]


MUTATING_IDS = {
    "CreateProfile": 0xCC176068, "DeleteProfile": 0x17093206,
    "CreateApplication": 0x4347A9DE, "SetSetting": 0x577DD202,
    "SaveSettings": 0xFCBC7E14,
}
READ_IDS = {"FindProfileByName": 0x7E4A9A0B, "GetProfileInfo": 0x61CD6FD6}


def timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def array_text(value: ro.U16_2048) -> str:
    raw = bytes(value)
    return raw.decode("utf-16-le", errors="strict").split("\0", 1)[0]


def set_array(target: c.Structure, field: str, value: str) -> None:
    encoded = value.encode("utf-16-le")
    field_type = dict(target._fields_)[field]
    if len(encoded) >= c.sizeof(field_type):
        raise ValueError(f"{field} is too long")
    c.memset(c.addressof(target) + getattr(type(target), field).offset, 0, c.sizeof(field_type))
    c.memmove(c.addressof(target) + getattr(type(target), field).offset, encoded, len(encoded))


def java_is_running() -> bool:
    # Do not change driver matching while its target executable is live.
    try:
        import psutil
        for process in psutil.process_iter(["name", "exe"]):
            name = (process.info["name"] or "").lower()
            exe = process.info.get("exe")
            if name == "javaw.exe" and (not exe or Path(exe).resolve() == JAVA.resolve()):
                return True
    except Exception:
        # The safety check must not turn a permissions issue into a bypass.
        return True
    return False


class DRS:
    """Small allow-list of the exact DRS APIs needed by this reversible trial."""
    def __init__(self) -> None:
        self.nvapi = c.CDLL("nvapi64.dll")
        self.nvapi.nvapi_QueryInterface.argtypes = [c.c_uint32]
        self.nvapi.nvapi_QueryInterface.restype = c.c_void_p
        query = self.nvapi.nvapi_QueryInterface
        self.initialize = ro.api_function(query, ro.IDS["Initialize"], c.c_int)
        self.unload = ro.api_function(query, ro.IDS["Unload"], c.c_int)
        self.create_session = ro.api_function(query, ro.IDS["CreateSession"], c.c_int, c.POINTER(c.c_void_p))
        self.destroy_session = ro.api_function(query, ro.IDS["DestroySession"], c.c_int, c.c_void_p)
        self.load = ro.api_function(query, ro.IDS["LoadSettings"], c.c_int, c.c_void_p)
        self.find_application = ro.api_function(query, ro.IDS["FindApplicationByName"], c.c_int, c.c_void_p, ro.U16_2048, c.POINTER(c.c_void_p), c.POINTER(ro.Application))
        self.get_setting = ro.api_function(query, ro.IDS["GetSetting"], c.c_int, c.c_void_p, c.c_void_p, c.c_uint32, c.POINTER(ro.Setting))
        self.find_profile = ro.api_function(query, READ_IDS["FindProfileByName"], c.c_int, c.c_void_p, ro.U16_2048, c.POINTER(c.c_void_p))
        self.get_profile_info = ro.api_function(query, READ_IDS["GetProfileInfo"], c.c_int, c.c_void_p, c.c_void_p, c.POINTER(Profile))
        self.create_profile = ro.api_function(query, MUTATING_IDS["CreateProfile"], c.c_int, c.c_void_p, c.POINTER(Profile), c.POINTER(c.c_void_p))
        self.create_application = ro.api_function(query, MUTATING_IDS["CreateApplication"], c.c_int, c.c_void_p, c.c_void_p, c.POINTER(ro.Application))
        self.set_setting = ro.api_function(query, MUTATING_IDS["SetSetting"], c.c_int, c.c_void_p, c.c_void_p, c.POINTER(ro.Setting))
        self.delete_profile = ro.api_function(query, MUTATING_IDS["DeleteProfile"], c.c_int, c.c_void_p, c.c_void_p)
        self.save = ro.api_function(query, MUTATING_IDS["SaveSettings"], c.c_int, c.c_void_p)
        self.session = c.c_void_p()

    def __enter__(self) -> "DRS":
        ro.status(self.initialize)
        try:
            ro.status(self.create_session, c.byref(self.session))
            ro.status(self.load, self.session)
            return self
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        if self.session.value:
            self.destroy_session(self.session)
            self.session = c.c_void_p()
        self.unload()

    def __exit__(self, *_: object) -> None:
        self.close()

    def find_app(self, path: str) -> tuple[c.c_void_p, ro.Application] | None:
        profile = c.c_void_p(); app = ro.Application(); app.version = ro.version(ro.Application, 4)
        try:
            ro.status(self.find_application, self.session, ro.wide(path), c.byref(profile), c.byref(app))
            return profile, app
        except RuntimeError as exc:
            if "FFFFFF5A" in str(exc):  # NVAPI_APPLICATION_NOT_FOUND (-166)
                return None
            raise

    def find_named_profile(self, name: str) -> c.c_void_p | None:
        profile = c.c_void_p()
        try:
            ro.status(self.find_profile, self.session, ro.wide(name), c.byref(profile))
            return profile
        except RuntimeError as exc:
            if "FFFFFF5D" in str(exc):  # NVAPI_PROFILE_NOT_FOUND (-163)
                return None
            raise

    def profile_info(self, profile: c.c_void_p) -> Profile:
        info = Profile(); info.version = ro.version(Profile, 1)
        ro.status(self.get_profile_info, self.session, profile, c.byref(info))
        return info

    def frl(self, profile: c.c_void_p) -> ro.Setting:
        value = ro.Setting(); value.version = ro.version(ro.Setting, 1)
        ro.status(self.get_setting, self.session, profile, FRL_ID, c.byref(value))
        return value


def verified_target() -> str:
    if not JAVA.is_file():
        raise RuntimeError(f"Expected FPS launcher executable is missing: {JAVA}")
    return str(JAVA.resolve())


def profile_identity(drs: DRS, handle: c.c_void_p, app_path: str, *, restore: bool) -> Profile:
    info = drs.profile_info(handle)
    if array_text(info.profile_name) != PROFILE_NAME or info.is_predefined:
        raise RuntimeError("Refusing profile: temporary profile identity does not match")
    if info.number_of_apps != 1 or info.number_of_settings != 1:
        raise RuntimeError("Refusing profile: it no longer contains exactly one app and one setting")
    found = drs.find_app(app_path)
    if found is None or found[0].value != handle.value or Path(array_text(found[1].app_name)).resolve() != Path(app_path).resolve():
        raise RuntimeError("Refusing profile: exact Java application identity does not match")
    frl = drs.frl(handle)
    if frl.current.u32 != 0 or frl.setting_location != 0:
        raise RuntimeError("Refusing profile: it does not contain current-profile FRL=0")
    return info


def apply(state_path: Path) -> None:
    if state_path.exists():
        raise RuntimeError("Refusing to overwrite an existing driver backup receipt")
    if java_is_running():
        raise RuntimeError("Refusing apply while javaw.exe is running")
    app_path = verified_target()
    state: dict[str, Any] = {"schema": 1, "created_utc": datetime.now(timezone.utc).isoformat(),
        "operation": "temporary-javaw-frl-disable", "status": "planned", "profile_name": PROFILE_NAME,
        "application_path": app_path, "setting": {"id": "0x10835002", "value": 0},
        "safety": "profile may be deleted only after exact name/app/one-app/one-setting/FRL=0 validation"}
    write_json(state_path, state)  # durable backup/identity before any driver mutation
    with DRS() as drs:
        if drs.find_app(app_path) is not None:
            raise RuntimeError("Refusing apply: an existing DRS application matches this exact Java path")
        if drs.find_named_profile(PROFILE_NAME) is not None:
            raise RuntimeError("Refusing apply: temporary profile name already exists; restore it first")
        profile_data = Profile(); profile_data.version = ro.version(Profile, 1); set_array(profile_data, "profile_name", PROFILE_NAME)
        handle = c.c_void_p()
        ro.status(drs.create_profile, drs.session, c.byref(profile_data), c.byref(handle))
        state["status"] = "profile-created-unsaved"; write_json(state_path, state)
        try:
            app = ro.Application(); app.version = ro.version(ro.Application, 4); set_array(app, "app_name", app_path)
            ro.status(drs.create_application, drs.session, handle, c.byref(app))
            state["status"] = "application-created-unsaved"; write_json(state_path, state)
            setting = ro.Setting(); setting.version = ro.version(ro.Setting, 1); setting.setting_id = FRL_ID; setting.setting_type = 0; setting.current.u32 = 0
            ro.status(drs.set_setting, drs.session, handle, c.byref(setting))
            state["status"] = "setting-created-unsaved"; write_json(state_path, state)
            ro.status(drs.save, drs.session)
            profile_identity(drs, handle, app_path, restore=False)
            state["status"] = "applied"; state["applied_utc"] = datetime.now(timezone.utc).isoformat(); write_json(state_path, state)
        except BaseException:
            state["status"] = "apply-failed-requires-manual-restore"; write_json(state_path, state)
            raise
    print(state_path)


def restore(state_path: Path) -> None:
    if java_is_running():
        raise RuntimeError("Refusing restore while javaw.exe is running")
    state = json.loads(state_path.read_text(encoding="utf-8"))
    app_path = verified_target()
    if state.get("schema") != 1 or state.get("status") not in ("applied", "apply-failed-requires-manual-restore") or state.get("profile_name") != PROFILE_NAME or state.get("application_path") != app_path or state.get("setting") != {"id": "0x10835002", "value": 0}:
        raise RuntimeError("Refusing restore: state file is not the expected applied temporary override")
    with DRS() as drs:
        handle = drs.find_named_profile(PROFILE_NAME)
        if handle is None:
            raise RuntimeError("Refusing restore: temporary profile is absent")
        profile_identity(drs, handle, app_path, restore=True)
        ro.status(drs.delete_profile, drs.session, handle)
        ro.status(drs.save, drs.session)
        if drs.find_app(app_path) is not None or drs.find_named_profile(PROFILE_NAME) is not None:
            raise RuntimeError("Restore verification failed: temporary profile/application still resolves")
    state["status"] = "restored"; state["restored_utc"] = datetime.now(timezone.utc).isoformat(); write_json(state_path, state)
    print(state_path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check", help="read-only preflight; does not change NVIDIA settings")
    check.add_argument("--state-dir", type=Path, default=Path("build/fps-probe/driver-trials"))
    apply_cmd = commands.add_parser("apply", help="create the one-setting temporary application profile")
    apply_cmd.add_argument("--state", type=Path, required=True)
    restore_cmd = commands.add_parser("restore", help="delete only the validated profile made by apply")
    restore_cmd.add_argument("--state", type=Path, required=True)
    args = parser.parse_args()
    if c.sizeof(c.c_void_p) != 8:
        raise SystemExit("requires 64-bit Python")
    if args.command == "check":
        target = verified_target()
        if java_is_running():
            raise SystemExit("preflight: javaw.exe is currently running")
        with DRS() as drs:
            existing_app = drs.find_app(target)
            existing_profile = drs.find_named_profile(PROFILE_NAME)
        print(json.dumps({"safe_to_apply": existing_app is None and existing_profile is None,
            "application_path": target, "profile_name": PROFILE_NAME,
            "existing_exact_application": existing_app is not None, "existing_temp_profile": existing_profile is not None,
            "planned_state": str(args.state_dir / f"frl-override-{timestamp()}.json")}, indent=2))
    elif args.command == "apply":
        apply(args.state)
    else:
        restore(args.state)


if __name__ == "__main__":
    main()
