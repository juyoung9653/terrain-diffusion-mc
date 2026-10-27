#!/usr/bin/env python3
"""Read a small, FPS-relevant subset of NVIDIA DRS settings without mutating it.

Uses NvAPI_QueryInterface plus only Initialize/CreateSession/LoadSettings/
Get*/Find*/Destroy/Unload entry points.  In particular it deliberately does
not resolve any Set*, Save*, Restore*, Delete*, or Create* profile function.
The structure layouts and interface IDs are from NVIDIA's public nvapi headers.
"""
from __future__ import annotations

import argparse
import ctypes as c
import json
from pathlib import Path
from typing import Any


U16_2048 = c.c_uint16 * 2048


class Binary(c.Structure):
    _pack_ = 4
    _fields_ = [("valueLength", c.c_uint32), ("value", c.c_ubyte * 4096)]


class Value(c.Union):
    _pack_ = 4
    _fields_ = [("u32", c.c_uint32), ("binary", Binary),
                ("wide", U16_2048), ("u64", c.c_uint64)]


class Setting(c.Structure):
    _pack_ = 4
    _fields_ = [("version", c.c_uint32), ("name", U16_2048),
                ("setting_id", c.c_uint32), ("setting_type", c.c_uint32),
                ("setting_location", c.c_uint32),
                ("is_current_predefined", c.c_uint32),
                ("is_predefined_valid", c.c_uint32),
                ("predefined", Value), ("current", Value)]


class Application(c.Structure):
    _fields_ = [("version", c.c_uint32), ("is_predefined", c.c_uint32),
                ("app_name", U16_2048), ("friendly_name", U16_2048),
                ("launcher", U16_2048), ("file_in_folder", U16_2048),
                ("flags", c.c_uint32), ("command_line", U16_2048)]


def version(struct: type[c.Structure], revision: int) -> int:
    return c.sizeof(struct) | (revision << 16)


# Public nvapi_interface.h IDs.  Never add mutating DRS function IDs here.
IDS = {
    "Initialize": 0x0150E828, "Unload": 0xD22BDD7E,
    "CreateSession": 0x0694D52E, "DestroySession": 0xDAD9CFF8,
    "LoadSettings": 0x375DBD6B, "GetCurrentGlobalProfile": 0x617BFF9F,
    "FindApplicationByName": 0xEEE566B2, "GetSetting": 0x73BF8338,
}
SETTINGS = {
    "Vertical Sync": 0x00A879CF,
    "OpenGL default swap interval": 0x206A6582,
    "OpenGL swap interval fraction": 0x206C4581,
    "OpenGL swap interval sign": 0x20655CFA,
    "Vulkan/OpenGL present method": 0x20D690F8,
    "Buffer-flipping mode": 0x201F619F,
    "Maximum frames allowed": 0x208E55E3,
    "Triple buffering": 0x20FDD1F9,
    "Frame Rate Limiter": 0x10835002,
    "Idle Application Max FPS Limit": 0x10835016,
    "Battery Boost Application FPS": 0x10115C8C,
    "External Quiet Mode": 0x10115C8D,
    "Preferred refresh rate": 0x0064B541,
}
LOCATIONS = {0: "current-profile", 1: "global-profile", 2: "base-profile", 3: "driver-default"}
VALUES = {
    "Vertical Sync": {0x60925292: "use-application-setting", 0x08416747: "force-off",
                      0x47814940: "force-on", 0x32610244: "half-refresh", 0x71271021: "third-refresh"},
    "OpenGL default swap interval": {0: "tear/no-sync", 1: "vsync-one", 0xFFFFFFFF: "disabled"},
    "Vulkan/OpenGL present method": {0: "prefer-disabled", 1: "prefer-enabled", 2: "auto"},
    "Buffer-flipping mode": {0: "off", 1: "on"}, "Triple buffering": {0: "disabled", 1: "enabled"},
    "Frame Rate Limiter": {0: "disabled"}, "Idle Application Max FPS Limit": {0: "disabled"},
    "Battery Boost Application FPS": {0: "no-override"}, "External Quiet Mode": {0: "off"},
    "Preferred refresh rate": {0: "application-controlled", 1: "highest-available"},
}


def wide(text: str) -> U16_2048:
    encoded = text.encode("utf-16-le")
    if len(encoded) >= c.sizeof(U16_2048):
        raise ValueError("application name is too long")
    result = U16_2048()
    c.memmove(result, encoded, len(encoded))
    return result


def status(call: Any, *args: Any) -> None:
    result = call(*args)
    if result != 0:
        raise RuntimeError(f"NVAPI status {result} (0x{result & 0xffffffff:08X})")


def api_function(query: Any, ident: int, restype: Any, *args: Any) -> Any:
    address = query(ident)
    if not address:
        raise RuntimeError(f"NVAPI interface 0x{ident:08X} unavailable")
    return c.CFUNCTYPE(restype, *args)(address)


def setting(get_setting: Any, session: c.c_void_p, profile: c.c_void_p, label: str, ident: int) -> dict[str, Any]:
    item = Setting()
    item.version = version(Setting, 1)
    try:
        status(get_setting, session, profile, ident, c.byref(item))
    except RuntimeError as exc:
        return {"id": f"0x{ident:08X}", "status": str(exc)}
    value = item.current.u32
    decoded = VALUES.get(label, {}).get(value)
    return {"id": f"0x{ident:08X}", "raw_u32": f"0x{value:08X}",
            "decimal": value, "decoded": decoded,
            "source": LOCATIONS.get(item.setting_location, f"unknown-{item.setting_location}"),
            "current_is_predefined": bool(item.is_current_predefined),
            "predefined_valid": bool(item.is_predefined_valid)}


def profile_settings(get_setting: Any, session: c.c_void_p, profile: c.c_void_p) -> dict[str, Any]:
    return {name: setting(get_setting, session, profile, name, ident) for name, ident in SETTINGS.items()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--application", action="append", default=["javaw.exe"],
                        help="DRS executable name to inspect; may repeat")
    parser.add_argument("--output", type=Path, help="Optional JSON output")
    args = parser.parse_args()
    if c.sizeof(c.c_void_p) != 8:
        raise SystemExit("This helper requires 64-bit Python for nvapi64.dll")
    nvapi = c.CDLL("nvapi64.dll")
    nvapi.nvapi_QueryInterface.argtypes = [c.c_uint32]
    nvapi.nvapi_QueryInterface.restype = c.c_void_p
    query = nvapi.nvapi_QueryInterface
    initialize = api_function(query, IDS["Initialize"], c.c_int)
    unload = api_function(query, IDS["Unload"], c.c_int)
    create = api_function(query, IDS["CreateSession"], c.c_int, c.POINTER(c.c_void_p))
    destroy = api_function(query, IDS["DestroySession"], c.c_int, c.c_void_p)
    load = api_function(query, IDS["LoadSettings"], c.c_int, c.c_void_p)
    global_profile = api_function(query, IDS["GetCurrentGlobalProfile"], c.c_int, c.c_void_p, c.POINTER(c.c_void_p))
    find_app = api_function(query, IDS["FindApplicationByName"], c.c_int, c.c_void_p, U16_2048, c.POINTER(c.c_void_p), c.POINTER(Application))
    get_setting = api_function(query, IDS["GetSetting"], c.c_int, c.c_void_p, c.c_void_p, c.c_uint32, c.POINTER(Setting))
    session = c.c_void_p()
    try:
        status(initialize)
        status(create, c.byref(session))
        status(load, session)
        global_handle = c.c_void_p()
        status(global_profile, session, c.byref(global_handle))
        report: dict[str, Any] = {
            "read_only": True,
            "provenance": "NVIDIA public nvapi.h, nvapi_interface.h, NvApiDriverSettings.h (queried via NvAPI DRS read APIs only)",
            "layouts": {"NVDRS_SETTING_V1_bytes": c.sizeof(Setting), "NVDRS_APPLICATION_V4_bytes": c.sizeof(Application)},
            "global": profile_settings(get_setting, session, global_handle), "applications": {},
        }
        for name in dict.fromkeys(args.application):
            profile = c.c_void_p()
            app = Application(); app.version = version(Application, 4)
            try:
                status(find_app, session, wide(name), c.byref(profile), c.byref(app))
                report["applications"][name] = {"found": True, "settings": profile_settings(get_setting, session, profile)}
            except RuntimeError as exc:
                report["applications"][name] = {"found": False, "status": str(exc)}
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
    finally:
        if session.value:
            destroy(session)
        unload()


if __name__ == "__main__":
    main()
