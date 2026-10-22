"""Starts Fabric 1.21.11 or 26.3 the way the Dove launcher does, for testing the agent.

The game, its libraries and assets come from the launcher's .minecraft; everything a run writes
(mods, options, worlds, logs) goes to its own game folder, so the launcher's profiles are never
touched. JVM flags match the launcher's for Java 25 (jvm.rs gc_flags, modern_runtime.rs).

    python tools/mcrun.py --version 1.21.11 --java <home> --dove <doveclient jar> \
        [--agent build/libs/dove-agent.jar[=options]] [--pack <folder of FPS Boost jars>] \
        [--mode bench|hud|blockentities|title|none] [--jfr <file>] [--game-dir <dir>]

Prints one JSON line at the end: exit code, time to the title screen and into the world, and the
bench's frame rates when --mode bench.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

import ctypes
from ctypes import wintypes

DATA = Path(os.environ["APPDATA"]) / "DoveClient" / "DoveLauncher" / "data"
MC = DATA / ".minecraft"
LOADER = "0.19.5"

# Benchmark settings (see Dove's BenchSmoke): no frame cap from vsync or idle input.
OPTIONS = {
    "renderDistance": "16",
    "simulationDistance": "12",
    "maxFps": "260",
    "enableVsync": "false",
    "inactivityFpsLimit": '"minimized"',
    "pauseOnLostFocus": "false",
    "fullscreen": "false",
    "onboardAccessibility": "false",
    "tutorialStep": "none",
    "skipMultiplayerWarning": "true",
    "joinedFirstServer": "true",
    "narrator": "0",
    "soundCategory_master": "0.0",
}


def close_window(pid):
    """Closes the game's window as a player would (WM_CLOSE): the game saves and stops."""
    user32 = ctypes.windll.user32
    found = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def each(hwnd, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid and user32.IsWindowVisible(hwnd):
            found.append(hwnd)
        return True

    user32.EnumWindows(each, 0)
    for hwnd in found:
        user32.PostMessageW(hwnd, 0x0010, 0, 0)
    return bool(found)


def rules_allow(rules):
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_rule = rule.get("os") or {}
        if rule.get("features"):
            matches = False
        else:
            matches = (os_rule.get("name") in (None, "windows")) and (os_rule.get("arch") in (None, "x86_64"))
        if matches:
            allowed = rule["action"] == "allow"
    return allowed


def maven_path(name):
    parts = name.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = "-" + parts[3] if len(parts) > 3 else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{classifier}.jar"


def library_key(name):
    parts = name.split(":")
    return ":".join([parts[0], parts[1]] + parts[3:])


def classpath(version):
    fabric = json.loads((MC / "versions" / f"fabric-loader-{LOADER}-{version}" / f"fabric-loader-{LOADER}-{version}.json").read_text())
    vanilla = json.loads((MC / "versions" / version / f"{version}.json").read_text())
    entries, seen = [], set()
    for lib in fabric["libraries"] + vanilla["libraries"]:
        if not rules_allow(lib.get("rules")):
            continue
        key = library_key(lib["name"])
        if key in seen:
            continue
        seen.add(key)
        artifact = ((lib.get("downloads") or {}).get("artifact") or {}).get("path") or maven_path(lib["name"])
        path = MC / "libraries" / artifact
        if not path.is_file():
            sys.exit(f"Missing library {path}")
        entries.append(str(path))
    entries.append(str(MC / "versions" / version / f"{version}.jar"))
    return entries, vanilla


def prepare_game_dir(game_dir, version, dove, pack, fabric_api, fresh, max_fps):
    game_dir.mkdir(parents=True, exist_ok=True)
    mods = game_dir / "mods"
    mods.mkdir(exist_ok=True)
    wanted = [Path(dove), Path(fabric_api)] + (sorted(Path(pack).glob("*.jar")) if pack else [])
    names = {jar.name for jar in wanted}
    for old in mods.glob("*.jar"):
        if old.name not in names:
            old.unlink()
    # Copied only when changed: a new file gets scanned by Windows Defender when the game opens it,
    # which made startup times jump by 10 s from one run to the next.
    for jar in wanted:
        target = mods / jar.name
        source = jar.stat()
        if not target.exists() or target.stat().st_size != source.st_size or int(target.stat().st_mtime) != int(source.st_mtime):
            shutil.copy2(jar, target)
    if fresh:
        shutil.rmtree(game_dir / "saves", ignore_errors=True)
    # Dove's own settings from its dev run, so no first-start screens get in the way.
    dev_config = Path(__file__).resolve().parents[2] / "Dove-Client-Mod" / "fabric" / "versions" / version / "run" / "config" / "doveclient"
    if dev_config.is_dir() and not (game_dir / "config" / "doveclient").exists():
        shutil.copytree(dev_config, game_dir / "config" / "doveclient")
    options_file = game_dir / "options.txt"
    lines = options_file.read_text().splitlines() if options_file.exists() else []
    values = dict(line.split(":", 1) for line in lines if ":" in line)
    values.update(OPTIONS)
    values["maxFps"] = str(max_fps)
    options_file.write_text("\n".join(f"{k}:{v}" for k, v in values.items()) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True, choices=["1.21.11", "26.3"])
    parser.add_argument("--java", required=True, help="Java home")
    parser.add_argument("--dove", required=True)
    parser.add_argument("--fabric-api", required=True)
    parser.add_argument("--agent", help="agent jar, optionally =options")
    parser.add_argument("--pack", help="folder with the FPS Boost jars")
    parser.add_argument("--mode", default="bench")
    parser.add_argument("--jfr", help="Java Flight Recorder output file")
    parser.add_argument("--game-dir", required=True)
    parser.add_argument("--jvm", action="append", default=[], help="extra JVM argument")
    parser.add_argument("--no-launcher-flags", action="store_true", help="leave out the Java 25 flags the launcher adds")
    parser.add_argument("--memory", default="4")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--keep-world", action="store_true")
    parser.add_argument("--rejoin", action="store_true", help="join the newest world in saves/ (Quick Play), play, close the window")
    parser.add_argument("--play-seconds", type=int, default=20)
    parser.add_argument("--max-fps", type=int, default=260, help="the game's frame limit (260 = unlimited)")
    parser.add_argument("--quit-at-title", action="store_true", help="close the window once the title screen shows")
    args = parser.parse_args()

    game_dir = Path(args.game_dir).resolve()
    prepare_game_dir(game_dir, args.version, args.dove, args.pack, args.fabric_api, not (args.keep_world or args.rejoin), args.max_fps)
    cp, vanilla = classpath(args.version)
    natives = MC / "versions" / args.version / "natives"
    java = Path(args.java) / "bin" / "java.exe"
    feature = int(re.search(r'JAVA_VERSION="(\d+)', (Path(args.java) / "release").read_text()).group(1))

    jvm = [f"-Xms{args.memory}G", f"-Xmx{args.memory}G"]
    if feature >= 25:
        jvm += ["-XX:+UseZGC"]
    else:
        jvm += ["-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions",
                "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20",
                "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1MaxNewSizePercent=40",
                "-XX:G1NewSizePercent=20"]
    jvm += ["-XX:+DisableExplicitGC", "-XX:+PerfDisableSharedMem"]
    if args.agent:
        jar, _, opts = args.agent.partition("=")
        log = game_dir / "logs" / "dove-agent.log"
        jvm.append(f"-javaagent:{Path(jar).resolve()}=log={log}" + ("," + opts if opts else ""))
    if feature >= 25 and not args.no_launcher_flags:
        jvm += ["--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow"]
    jvm += ["-Dfabric.noGui=true"]
    if args.mode != "none":
        jvm.append(f"-Ddove.smokeTest={args.mode}")
    if args.jfr:
        jvm.append(f"-XX:StartFlightRecording=filename={args.jfr},settings=profile")
    jvm += args.jvm
    # The version's own JVM arguments (natives, launcher brand), as the launcher passes them.
    for arg in vanilla["arguments"]["jvm"]:
        values = [arg] if isinstance(arg, str) else (arg["value"] if isinstance(arg["value"], list) else [arg["value"]]) if rules_allow(arg.get("rules")) else []
        for value in values:
            if value in ("-cp", "${classpath}"):
                continue
            jvm.append(value.replace("${natives_directory}", str(natives))
                       .replace("${launcher_name}", "dove").replace("${launcher_version}", "bench"))
    jvm += ["-cp", os.pathsep.join(cp), "net.fabricmc.loader.impl.launch.knot.KnotClient"]
    game = ["--username", "DoveBench", "--version", f"fabric-loader-{LOADER}-{args.version}",
            "--gameDir", str(game_dir), "--assetsDir", str(MC / "assets"), "--assetIndex", vanilla["assetIndex"]["id"],
            "--uuid", str(uuid.uuid3(uuid.NAMESPACE_OID, "DoveBench")).replace("-", ""), "--accessToken", "0",
            "--userType", "msa", "--versionType", "release", "--width", "1600", "--height", "900"]
    if args.rejoin:
        worlds = sorted((game_dir / "saves").iterdir(), key=lambda p: p.stat().st_mtime)
        game += ["--quickPlaySingleplayer", worlds[-1].name]
        result_world = worlds[-1].name

    (game_dir / "logs").mkdir(exist_ok=True)
    console = (game_dir / "logs" / "console.log").open("w", encoding="utf-8")
    console.write(" ".join(str(java) if i == 0 else a for i, a in enumerate([str(java)] + jvm)) + "\n")
    started = time.monotonic()
    marks = {}
    result = {"version": args.version, "java": feature, "agent": args.agent, "pack": bool(args.pack), "mode": args.mode}
    proc = subprocess.Popen([str(java)] + jvm + game, cwd=game_dir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace")
    killer = threading.Timer(args.timeout, proc.kill)
    killer.start()
    for line in proc.stdout:
        now = time.monotonic() - started
        console.write(f"{now:9.3f} {line}")
        if "Replacing the title screen with Dove's" in line:
            marks.setdefault("title_s", round(now, 2))
            if args.quit_at_title:
                threading.Timer(3, lambda: marks.setdefault("closed", close_window(proc.pid))).start()
        if "joined the game" in line:
            marks.setdefault("joined_s", round(now, 2))
            if args.rejoin:
                threading.Timer(args.play_seconds, lambda: marks.setdefault("closed", close_window(proc.pid))).start()
        if re.search(r"/(ERROR|FATAL)\]|Exception|WARNING:", line) and "authlib" not in line and "Realms" not in line:
            marks.setdefault("problems", []).append(line.strip()[:200])
        m = re.search(r"bench (still|turning) (.*)", line)
        if m:
            result[m.group(1)] = m.group(2).strip()
        if "[DoveSmoke] OK" in line:
            result["ok"] = True
    proc.wait()
    killer.cancel()
    console.close()
    result.update(marks)
    if args.rejoin:
        result["world"] = result_world
    result["exit"] = proc.returncode
    result["total_s"] = round(time.monotonic() - started, 2)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
