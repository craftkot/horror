import json, os, pathlib, sys, time, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
out = ROOT / "client" / "mods"
out.mkdir(parents=True, exist_ok=True)
api_key = os.environ.get("CF_API_KEY", "").strip()

def get_json(url, headers):
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))

def download(url, target, headers):
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=180) as r:
        data = r.read()
    if not data.startswith(b"PK"):
        raise RuntimeError(f"Downloaded payload is not a JAR/ZIP ({len(data)} bytes)")
    target.write_bytes(data)

fails=[]
files=manifest["files"]
print(f"Downloading {len(files)} client mods...")
for i,f in enumerate(files,1):
    pid,fid=f["projectID"],f["fileID"]
    target=out/f"{pid}-{fid}.jar"
    try:
        if api_key:
            meta=get_json(
                f"https://api.curseforge.com/v1/mods/{pid}/files/{fid}/download-url",
                {"x-api-key":api_key,"User-Agent":"EHTID-Horror-Client-CI/1.0"},
            )
            download(meta["data"],target,{"x-api-key":api_key,"User-Agent":"EHTID-Horror-Client-CI/1.0"})
        else:
            download(
                f"https://www.curseforge.com/api/v1/mods/{pid}/files/{fid}/download",
                target,
                {"User-Agent":"Mozilla/5.0 EHTID-Horror-Client-CI"},
            )
        print(f"[{i}/{len(files)}] OK {pid}/{fid} {target.stat().st_size}")
    except Exception as e:
        print(f"[{i}/{len(files)}] FAIL {pid}/{fid}: {e}",file=sys.stderr)
        fails.append((pid,fid,repr(e)))
        if len(fails)>=5:
            break
    time.sleep(0.05)

if fails:
    print("Download failures:",fails,file=sys.stderr)
    sys.exit(2)
