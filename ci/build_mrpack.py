#!/usr/bin/env python3
"""Build a portable MRPACK using readable original JAR filenames.

Never embed third-party JAR binaries in this pack. CurseForge URLs remain
unchanged, and SHA-1/SHA-512 hashes are always based on downloaded JAR bytes.
"""
from email.message import Message
import hashlib
import json
import os
from pathlib import Path
import re
import tomllib
from urllib.parse import unquote, urlparse
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAN = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
CF_KEY = os.getenv("CF_API_KEY", "").strip()
MODLIST = ROOT / "overrides" / "MODLIST_RU.txt"


def friendly_names_from_modlist():
    result = {}
    if MODLIST.is_file():
        for line in MODLIST.read_text(encoding="utf-8").splitlines():
            match = re.match(r"^\d+\.\s*(.*?)\s+— project (\d+), file (\d+)$", line)
            if match and not match.group(1).startswith("CurseForge project "):
                result[int(match.group(2))] = match.group(1)
    return result


def valid_basename(text):
    """Return a safe readable JAR basename, or None for an opaque ID."""
    if not isinstance(text, str):
        return None
    text = unquote(text).replace("\\", "/").split("/")[-1].strip()
    text = re.sub(r"(?i)\.(jar|zip)$", "", text)
    text = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "-", text)
    text = re.sub(r"\s+", " ", text).strip(" .-_")[:120].strip(" .-_")
    if not text or re.fullmatch(r"\d+(?:[-_]\d+)+", text):
        return None
    if re.fullmatch(r"(?:CurseForge project|Project|File)[-_ ]+\d+", text, flags=re.I):
        return None
    if text.lower() in ("download", "unknown", "mod", "minecraft", "examplemod"):
        return None
    return text + ".jar"


def forge_jar_display_name(local):
    """Derive a real name from Forge's own JAR metadata if HTTP gives no name."""
    with zipfile.ZipFile(local) as z:
        for path in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
            if path not in z.namelist():
                continue
            toml_text = z.read(path).decode("utf-8", errors="replace")
            try:
                entries = tomllib.loads(toml_text).get("mods", [])
            except (ValueError, tomllib.TOMLDecodeError):
                entries = []
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                for field in ("displayName", "modId"):
                    name = entry.get(field)
                    if isinstance(name, str) and not name.startswith("$"):
                        found = valid_basename(name)
                        if found:
                            return found
            # Some MCreator JARs use a TOML dialect not accepted by tomllib.
            for field in ("displayName", "modId"):
                m = re.search(r"(?m)^\s*" + field + r'\s*=\s*["\x27]([^"\x27]+)["\x27]', toml_text)
                if m and not m.group(1).startswith("$"):
                    found = valid_basename(m.group(1))
                    if found:
                        return found
        if "META-INF/MANIFEST.MF" in z.namelist():
            text = z.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
            for key in ("Implementation-Title", "Specification-Title", "Automatic-Module-Name"):
                m = re.search(r"(?mi)^" + re.escape(key) + r":\s*(.+)$", text)
                if m:
                    found = valid_basename(m.group(1))
                    if found:
                        return found
    return None


def original_jar_name(headers, effective_url):
    # Download responses may include the exact original author-supplied name.
    h = Message()
    h["Content-Disposition"] = headers.get("Content-Disposition", "")
    cd = h.get_filename()
    if cd:
        found = valid_basename(cd)
        if found:
            return found
    # Otherwise an actual CDN redirect sometimes contains the original name.
    return valid_basename(urlparse(effective_url).path)


def assign_unique(name, used):
    base, ext = os.path.splitext(name)
    output = name
    number = 2
    while output.casefold() in used:
        output = f"{base}-{number}{ext}"
        number += 1
    used.add(output.casefold())
    return output


friendly = friendly_names_from_modlist()
entries = []
problems = []
used_names = set()
names_audit = []
for i, item in enumerate(MAN["files"], 1):
    pid, fid = item["projectID"], item["fileID"]
    jar = ROOT / "client" / "mods" / f"{pid}-{fid}.jar"
    try:
        if not jar.is_file():
            raise RuntimeError("Downloaded JAR missing")
        if CF_KEY:
            request = Request(
                f"https://api.curseforge.com/v1/mods/{pid}/files/{fid}",
                headers={"x-api-key": CF_KEY, "User-Agent": "EHTID-Horror"},
            )
            with urlopen(request, timeout=35) as resp:
                meta = json.load(resp)["data"]
            author_name = meta.get("fileName")
            url = meta.get("downloadUrl") or (
                f"https://mediafilez.forgecdn.net/files/{fid // 1000}/{fid % 1000}/"
                + unquote(author_name or "")
            )
        else:
            author_name = None
            url = f"https://www.curseforge.com/api/v1/mods/{pid}/files/{fid}/download"

        # Verify that the upstream URL is anonymous and actually serves a JAR.
        # Don't use Range: CurseForge's public endpoint doesn't support it.
        request = Request(url, headers={"User-Agent": "PrismLauncher/1.0"})
        with urlopen(request, timeout=40) as response:
            if response.status not in (200, 206) or response.read(4) != b"PK\x03\x04":
                raise RuntimeError("Source is not a public JAR download")
            downloaded_name = original_jar_name(response.headers, response.geturl())

        # Prefer the actual upstream filename, not the numeric cache filename.
        # For anonymous CF URLs, recover the real mod name from a readable list
        # or Forge JAR metadata; do not include a numeric-ID fallback.
        candidates = [
            ("original", valid_basename(author_name)),
            ("upstream", downloaded_name),
            ("modlist", valid_basename(friendly.get(pid))),
            ("forge", forge_jar_display_name(jar)),
        ]
        good = [(source, name) for source, name in candidates if name]
        if not good:
            raise RuntimeError("No descriptive filename in download or JAR metadata")
        source, selected = good[0]
        name = assign_unique(selected, used_names)
        data = jar.read_bytes()
        if not data.startswith(b"PK\x03\x04"):
            raise RuntimeError("Downloaded JAR is corrupt")

        entries.append({
            "path": "mods/" + name,
            "hashes": {
                "sha1": hashlib.sha1(data).hexdigest(),
                "sha512": hashlib.sha512(data).hexdigest(),
            },
            "downloads": [url],
            "fileSize": len(data),
            "env": {"client": "required", "server": "optional"},
        })
        names_audit.append({"projectID": pid, "fileID": fid, "name": name, "source": source})
        print(f"[{i}/{len(MAN['files'])}] {pid}/{fid} => {name} ({source})", flush=True)
    except Exception as error:
        problems.append(f"{pid}/{fid}: {error}")
        print("UNAVAILABLE " + problems[-1], flush=True)

(ROOT / "mrpack-filenames.json").write_text(
    json.dumps(names_audit, ensure_ascii=False, indent=2), encoding="utf-8"
)
(ROOT / "mrpack-audit.txt").write_text(
    f"Verified {len(entries)}/{len(MAN['files'])}\n"
    + "\n".join(problems)
    + "\n",
    encoding="utf-8",
)
if problems:
    print("Skipping incomplete MRPACK; the CurseForge ZIP will still be released.")
    raise SystemExit(0)

assert len(entries) == len(MAN["files"])
assert len({x["path"].casefold() for x in entries}) == len(entries)
assert all(
    not re.fullmatch(r"mods/\d+-\d+\.jar", x["path"])
    for x in entries
)

index = {
    "formatVersion": 1,
    "game": "minecraft",
    "versionId": MAN["version"],
    "name": MAN["name"],
    "summary": "Horror survival Minecraft 1.20.1 Forge 47.4.15",
    "files": entries,
    "dependencies": {"minecraft": "1.20.1", "forge": "47.4.15"},
}
dist = ROOT / "dist"
dist.mkdir(exist_ok=True)
with zipfile.ZipFile(dist / "EHTID-Horror.mrpack", "w", compression=zipfile.ZIP_DEFLATED) as out:
    out.writestr("modrinth.index.json", json.dumps(index, ensure_ascii=False))
    for path in (ROOT / "overrides").rglob("*"):
        if path.is_file():
            out.write(path, "overrides/" + str(path.relative_to(ROOT / "overrides")).replace("\\", "/"))
print(f"MRPACK_OK: {len(entries)} readable JAR names", flush=True)
