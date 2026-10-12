#!/usr/bin/env python3
"""MRPACK from verified public original-download URLs; never redistribute JARs."""
import os, json, hashlib, zipfile, pathlib, urllib.request, urllib.parse
root = pathlib.Path(__file__).resolve().parents[1]
man = json.loads((root/"manifest.json").read_text())
key = os.getenv("CF_API_KEY", "").strip()
if not key:
    print("No CurseForge API key; verifying public CurseForge download endpoints.", flush=True)
files = []
problems = []
for i, entry in enumerate(man["files"], 1):
    pid, fid = entry["projectID"], entry["fileID"]
    jar = root/"client"/"mods"/(str(pid)+"-"+str(fid)+".jar")
    try:
        if key:
            req = urllib.request.Request(
                "https://api.curseforge.com/v1/mods/"+str(pid)+"/files/"+str(fid),
                headers={"x-api-key":key,"User-Agent":"EHTID-Horror"})
            with urllib.request.urlopen(req, timeout=35) as response:
                meta = json.load(response)["data"]
            fname = meta["fileName"]
            url = meta.get("downloadUrl") or (
              "https://mediafilez.forgecdn.net/files/"+str(fid//1000)+"/"+str(fid%1000)+"/"+urllib.parse.quote(fname))
        else:
            fname = str(pid)+"-"+str(fid)+".jar"
            url = "https://www.curseforge.com/api/v1/mods/"+str(pid)+"/files/"+str(fid)+"/download"
        probe = urllib.request.Request(url, headers={"Range":"bytes=0-0","User-Agent":"Mozilla/5.0"})
        with urllib.request.urlopen(probe, timeout=30) as r:
            if r.status not in (200,206):
                raise RuntimeError("HTTP "+str(r.status))
        data = jar.read_bytes()
        files.append({"path":"mods/"+fname,
                      "hashes":{"sha1":hashlib.sha1(data).hexdigest(),
                                "sha512":hashlib.sha512(data).hexdigest()},
                      "downloads":[url],"fileSize":len(data),
                      "env":{"client":"required","server":"optional"}})
        print(str(i)+"/"+str(len(man["files"]))+" OK "+str(pid), flush=True)
    except Exception as exc:
        problems.append(str(pid)+"/"+str(fid)+": "+str(exc))
        print("UNAVAILABLE "+problems[-1], flush=True)
(root/"mrpack-audit.txt").write_text("Verified "+str(len(files))+"/"+str(len(man["files"]))+"\n"+"\n".join(problems), encoding="utf-8")
if problems:
    print("Skipping incomplete MRPACK; CurseForge installer will be released.")
    raise SystemExit(0)
index = {"formatVersion":1,"game":"minecraft","versionId":man["version"],"name":man["name"],
         "summary":"Horror survival Minecraft 1.20.1 Forge 47.4.15",
         "files":files,"dependencies":{"minecraft":"1.20.1","forge":"47.4.15"}}
dist = root/"dist"
dist.mkdir(exist_ok=True)
with zipfile.ZipFile(dist/"EHTID-Horror.mrpack","w",compression=zipfile.ZIP_DEFLATED) as z:
    z.writestr("modrinth.index.json",json.dumps(index,ensure_ascii=False))
    for p in (root/"overrides").rglob("*"):
        if p.is_file():
            z.write(p,"overrides/"+str(p.relative_to(root/"overrides")).replace("\\","/"))
print("MRPACK_OK", flush=True)
