"""A/B comparison through mcrun.py, alternating A B B A so drift on a hot laptop hits both sides.

    python tools/ab.py --rounds 3 --common "<mcrun arguments>" --a "<A's extra arguments>" --b "<B's>" [--out results.jsonl]

Prints every run, then each side's mean and spread for the startup times and, in bench mode,
the frame rates (avg / 1% / 0.1% lows, still and turning). A run that does not exit 0 is reported
and left out of the averages.
"""
import argparse
import json
import re
import shlex
import statistics
import subprocess
import sys
from pathlib import Path

MCRUN = Path(__file__).with_name("mcrun.py")


def metrics(result):
    values = {}
    for key in ("title_s", "joined_s"):
        if key in result:
            values[key] = result[key]
    for phase in ("still", "turning"):
        m = re.search(r"avg=([\d.]+) low1=([\d.]+) low01=([\d.]+)", result.get(phase, ""))
        if m:
            values[f"{phase}_avg"], values[f"{phase}_1%"], values[f"{phase}_0.1%"] = map(float, m.groups())
    return values


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--common", required=True)
    parser.add_argument("--a", default="")
    parser.add_argument("--b", default="")
    parser.add_argument("--out")
    args = parser.parse_args()

    order = []
    for i in range(args.rounds):
        order += ["A", "B"] if i % 2 == 0 else ["B", "A"]
    results = {"A": [], "B": []}
    out = open(args.out, "a", encoding="utf-8") if args.out else None
    for side in order:
        extra = args.a if side == "A" else args.b
        command = [sys.executable, str(MCRUN)] + shlex.split(args.common, posix=False) + shlex.split(extra, posix=False)
        command = [c.strip('"') for c in command]
        done = subprocess.run(command, capture_output=True, text=True)
        line = done.stdout.strip().splitlines()[-1] if done.stdout.strip() else "{}"
        try:
            result = json.loads(line)
        except json.JSONDecodeError:
            result = {"exit": "harness", "error": (done.stdout + done.stderr)[-500:]}
        result["side"] = side
        if out:
            out.write(json.dumps(result) + "\n")
            out.flush()
        ok = result.get("exit") == 0
        print(side, "ok" if ok else f"FAILED exit={result.get('exit')}", metrics(result), flush=True)
        if ok:
            results[side].append(metrics(result))

    keys = sorted({k for side in results.values() for r in side for k in r})
    sys.stdout.reconfigure(encoding="utf-8")
    # Medians too: one run spoiled by a busy background app moves a mean a lot on this laptop.
    print(f"\n{'':14s} {'A mean':>16s} {'B mean':>16s} {'B-A':>8s} {'A median':>9s} {'B median':>9s} {'B-A':>8s}")
    for key in keys:
        a = [r[key] for r in results["A"] if key in r]
        b = [r[key] for r in results["B"] if key in r]
        if not a or not b:
            continue
        ma, mb = statistics.mean(a), statistics.mean(b)
        sa = statistics.stdev(a) if len(a) > 1 else 0
        sb = statistics.stdev(b) if len(b) > 1 else 0
        da, db = statistics.median(a), statistics.median(b)
        print(f"{key:14s} {ma:9.2f} ±{sa:5.2f} {mb:9.2f} ±{sb:5.2f} {mb - ma:+8.2f} {da:9.2f} {db:9.2f} {db - da:+8.2f}  (n={len(a)},{len(b)})")


if __name__ == "__main__":
    main()
