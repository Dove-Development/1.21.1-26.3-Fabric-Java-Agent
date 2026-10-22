"""Summarises a Java Flight Recorder file: where each thread spent its samples, up to a moment.

    python tools/jfrsum.py <recording.jfr> [--until <seconds after JVM start>] [--thread "Render thread"]
        [--depth 40] [--top 25] [--focus <package prefix>,...]

Uses the JDK's `jfr` tool (JAVA_HOME or PATH). For each thread it prints the share of execution
samples (Java code running) and native samples (in native code: file system, GL driver, ...),
then the most common frames: "self" is the top frame, "focus" the first frame in one of the given
packages (by default the game and its mods), which says which part of the game asked for the work.
Waits (ThreadPark, monitor waits, sleeps longer than 20 ms) are summed per thread and stack too.
"""
import argparse
import collections
import json
import os
import re
import subprocess
import sys
from datetime import datetime


def jfr_tool():
    home = os.environ.get("JAVA_HOME")
    return os.path.join(home, "bin", "jfr") if home else "jfr"


def load(path, events, depth):
    out = subprocess.run([jfr_tool(), "print", "--json", "--stack-depth", str(depth), "--events", ",".join(events), path],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    if out.returncode != 0:
        sys.exit(out.stderr)
    return json.loads(out.stdout)["recording"]["events"]


def when(event):
    return datetime.fromisoformat(event["values"]["startTime"].replace("Z", "+00:00")).timestamp()


def duration(text):
    """ISO 8601 duration as jfr prints it: PT0.012S, PT1M2.5S."""
    m = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?", text)
    return int(m.group(1) or 0) * 3600 + int(m.group(2) or 0) * 60 + float(m.group(3) or 0)


def frame_name(frame):
    method = frame["method"]
    return f'{method["type"]["name"]}.{method["name"]}'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("recording")
    parser.add_argument("--until", type=float)
    parser.add_argument("--since", type=float, default=0.0)
    parser.add_argument("--thread")
    parser.add_argument("--depth", type=int, default=48)
    parser.add_argument("--top", type=int, default=25)
    parser.add_argument("--stacks", help="print the most common stacks (top --depth frames) whose frames contain this text")
    parser.add_argument("--timeline", type=float, help="seconds per row: what --thread did over time")
    parser.add_argument("--focus", default="net.minecraft,com.mojang,net.fabricmc,org.spongepowered,com.doveclient,net.caffeinemc,me.jellysquid,ferritecore,dev.tr7zw,net.raphimc,ca.fxco")
    args = parser.parse_args()
    focus = tuple(p.strip() for p in args.focus.split(","))

    info = load(args.recording, ["jdk.JVMInformation"], 1)
    start = datetime.fromisoformat(info[0]["values"]["jvmStartTime"].replace("Z", "+00:00")).timestamp()

    samples = load(args.recording, ["jdk.ExecutionSample", "jdk.NativeMethodSample"], args.depth)
    waits = load(args.recording, ["jdk.ThreadPark", "jdk.JavaMonitorWait", "jdk.ThreadSleep", "jdk.JavaMonitorEnter"], args.depth)

    stacks = collections.Counter()
    timeline = collections.defaultdict(lambda: {"java": 0, "native": 0, "focus": collections.Counter(), "self": collections.Counter()})
    per_thread = collections.defaultdict(lambda: {"java": 0, "native": 0, "self": collections.Counter(), "focus": collections.Counter()})
    for event in samples:
        t = when(event) - start
        if t < args.since or (args.until is not None and t > args.until):
            continue
        values = event["values"]
        thread = (values.get("sampledThread") or {}).get("javaName") or "?"
        if args.thread and thread != args.thread:
            continue
        row = per_thread[thread]
        row["native" if event["type"] == "jdk.NativeMethodSample" else "java"] += 1
        frames = (values.get("stackTrace") or {}).get("frames") or []
        if args.stacks and any(args.stacks in frame_name(f) for f in frames):
            stacks[" <- ".join(frame_name(f).split("/")[-1] for f in frames[:14])] += 1
        if args.timeline:
            slot = timeline[int(t // args.timeline)]
            slot["native" if event["type"] == "jdk.NativeMethodSample" else "java"] += 1
            if frames:
                slot["self"][frame_name(frames[0])] += 1
                slot["focus"][next((frame_name(f) for f in frames if f["method"]["type"]["name"].replace("/", ".").startswith(focus) and "$$Lambda" not in f["method"]["type"]["name"]), "-")] += 1
        if frames:
            row["self"][frame_name(frames[0])] += 1
            for frame in frames:
                if frame["method"]["type"]["name"].replace("/", ".").startswith(focus):
                    row["focus"][frame_name(frame)] += 1
                    break

    waited = collections.defaultdict(lambda: collections.Counter())
    for event in waits:
        t = when(event) - start
        if t < args.since or (args.until is not None and t > args.until):
            continue
        values = event["values"]
        thread = (values.get("eventThread") or {}).get("javaName") or "?"
        if args.thread and thread != args.thread:
            continue
        seconds = duration(values["duration"])
        frames = (values.get("stackTrace") or {}).get("frames") or []
        where = next((frame_name(f) for f in frames if f["method"]["type"]["name"].replace("/", ".").startswith(focus)), frame_name(frames[0]) if frames else "?")
        waited[thread][where] += seconds

    if args.stacks:
        print(f"{sum(stacks.values())} samples with {args.stacks}")
        for stack, n in stacks.most_common(args.top):
            print(f"{n:5d}  {stack}")
        return
    if args.timeline:
        for slot in sorted(timeline):
            row = timeline[slot]
            top = "; ".join(f"{n} {name}" for name, n in row["focus"].most_common(2))
            print(f"{slot * args.timeline:6.1f}s j{row['java']:3d} n{row['native']:3d}  self: {row['self'].most_common(1)[0][0] if row['self'] else '-'}  | {top}")
        return

    # Samples are taken every 10-20 ms per running thread (profile settings), so counts are relative.
    for thread, row in sorted(per_thread.items(), key=lambda kv: -(kv[1]["java"] + kv[1]["native"]))[:12]:
        total = row["java"] + row["native"]
        print(f"\n### {thread}: {total} samples ({row['java']} java, {row['native']} native); waited {sum(waited[thread].values()):.1f} s")
        if args.thread or thread in ("Render thread", "main"):
            print("  top frames (self):")
            for name, n in row["self"].most_common(args.top):
                print(f"    {n:6d} {100 * n / total:5.1f}%  {name}")
            print("  first game/mod frame:")
            for name, n in row["focus"].most_common(args.top):
                print(f"    {n:6d} {100 * n / total:5.1f}%  {name}")
            print("  waits (s):")
            for name, s in waited[thread].most_common(10):
                print(f"    {s:7.2f}  {name}")


if __name__ == "__main__":
    main()
