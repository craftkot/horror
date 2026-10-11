import json, os, pathlib, sys, time
import urllib.request, urllib.error

ROOT = pathlib.Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
skip = set()
for line in (ROOT / "ci" / "server_skip_ids.txt").read_text().splitlines():
    line = line.strip()
    if line and not line.startswith("#"):
        skip.add(int(line))

out = ROOT / "server" / "mods"
out.mkdir(parents=True, exist_ok=True)
api_key = os.environ.get("CF_API_KEY", "").strip()

def get_json(url, headers):
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))

def download(url, target, headers):
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if not data.startswith(b"PK"):
        raise RuntimeError(f"Downloaded payload is not a JAR/ZIP ({len(data)} bytes) from {url}")
    target.write_bytes(data)

failures = []
files = [f for f in manifest["files"] if f["projectID"] not in skip]
print(f"Server smoke-test will download {len(files)} files; skipped {len(manifest['files'])-len(files)} client-only entries.")

for i, f in enumerate(files, 1):
    pid, fid = f["projectID"], f["fileID"]
    target = out / f"{pid}-{fid}.jar"
    try:
        if api_key:
            meta = get_json(
                f"https://api.curseforge.com/v1/mods/{pid}/files/{fid}/download-url",
                {"x-api-key": api_key, "User-Agent": "EHTID-Horror-CI/1.0"},
            )
            url = meta["data"]
            download(url, target, {"x-api-key": api_key, "User-Agent": "EHTID-Horror-CI/1.0"})
        else:
            # Best-effort public website endpoint. Since July 2026 the CDN may reject
            # unauthenticated automation; if so, the workflow explains how to enable it.
            url = f"https://www.curseforge.com/api/v1/mods/{pid}/files/{fid}/download"
            download(url, target, {"User-Agent": "Mozilla/5.0 EHTID-Horror-CI"})
        print(f"[{i}/{len(files)}] OK {pid}/{fid} ({target.stat().st_size} bytes)")
    except Exception as e:
        failures.append((pid, fid, repr(e)))
        print(f"[{i}/{len(files)}] FAIL {pid}/{fid}: {e}", file=sys.stderr)
        if len(failures) >= 5:
            break
    time.sleep(0.08)

if failures:
    print("\nDownload failures:", file=sys.stderr)
    for x in failures:
        print(x, file=sys.stderr)
    if not api_key:
        print("\nCF_API_KEY is not configured. CurseForge began requiring API-key authentication for automated CDN downloads in 2026.", file=sys.stderr)
        print("Add repository secret CF_API_KEY, then re-run this workflow.", file=sys.stderr)
    sys.exit(2)

print("All server-test mods downloaded successfully.")
