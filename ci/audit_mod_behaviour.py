#!/usr/bin/env python3
"""Offline audit of timer / forced-exit signatures in downloaded Forge JARs."""
import collections
import json
from pathlib import Path
import re
import zipfile

root = Path(__file__).resolve().parents[1]
man = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
scores = []
patterns = {
    "system.exit": (12, re.compile(rb"(?i)system\.exit|runtime\.getruntime|shutdownhook|shutdown\b")),
    "forced_quit": (10, re.compile(rb"(?i)force.?quit|force.?close|close.?the.?game|exit.?game|restart.?game|restart.?minecraft|shut.?down.?game")),
    "time_objective": (12, re.compile(rb"(?i)0\s*/\s*3|30:00|1800\s*(?:seconds|secs)|36000\s*(?:ticks)")),
    "restart": (6, re.compile(rb"(?i)restart|relaunch|reboot")),
    "countdown": (7, re.compile(rb"(?i)countdown|time.?left|remaining.?time|timer")),
    "three_objectives": (5, re.compile(rb"(?i)objective|mission|challenge|collect.?three|find.?three")),
}
for mod in man["files"]:
    pid, fid = mod["projectID"], mod["fileID"]
    fn = root / "client" / "mods" / f"{pid}-{fid}.jar"
    if not fn.is_file():
        continue
    hits = collections.Counter()
    proof = collections.defaultdict(list)
    try:
        with zipfile.ZipFile(fn) as z:
            for member in z.infolist():
                name = member.filename
                if not name.endswith((".class", ".json", ".toml", ".mcmeta", ".txt", ".properties")):
                    continue
                if member.file_size > 2_000_000:
                    continue
                if name.startswith(("META-INF/jarjar/", "assets/minecraft/")):
                    continue
                buf = z.read(member)
                for label, (_, rx) in patterns.items():
                    match = rx.search(buf)
                    if match:
                        hits[label] += 1
                        if len(proof[label]) < 4:
                            a, b = max(0, match.start()-40), min(len(buf), match.end()+65)
                            excerpt = re.sub(rb"[^\x20-\x7e]", b" ", buf[a:b]).decode("ascii", "replace")
                            proof[label].append({"entry": name, "text": excerpt})
    except Exception as exc:
        proof["ERROR"] = [str(exc)]
    # Score only evidence in this JAR, not external reputation.
    raw = sum(weight * min(3, hits[label]) for label, (weight, _) in patterns.items())
    scores.append({"projectID": pid, "fileID": fid, "score": raw, "hits": dict(hits), "examples": dict(proof)})

scores.sort(key=lambda x: x["score"], reverse=True)
report = root / "timer-audit.json"
report.write_text(json.dumps(scores, ensure_ascii=False, indent=2), encoding="utf-8")
print("=== TIMER/RESTART JAR FORENSICS ===", flush=True)
for entry in scores[:32]:
    if entry["score"] == 0: continue
    print("PROJECT", entry["projectID"], "SCORE", entry["score"], "HITS", entry["hits"], flush=True)
    for k in ("time_objective", "forced_quit", "system.exit", "restart", "countdown"):
        for sample in entry["examples"].get(k, [])[:2]:
            print("   ", k, sample["entry"], "=>", sample["text"][:135], flush=True)
print("Written", report.name, "mods", len(scores), flush=True)
