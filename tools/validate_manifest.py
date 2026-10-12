import json
from pathlib import Path

SCREEN_BLOCKING_EXCLUSIONS = {255389, 1475092, 1421612, 1484738}  # Visuals, StalkerEntity, Idol, do_not_join

BANNED = {
890565,686437,940557,1082195,1454646,839197,971482,852533,1279376,349597,
531188,695021,1455237,1380564,1001299,923133,970277,1495006,304719,1043571,
1426040,1558499,1197678,1563349,1515056,1337928,1492061,1680016,1275792,
1123475,1305685,1475234,1394764,1565240,1672412,501766,829752,538529,
237754,561625,1225054,1566054,1240540,496520,1111374,1085414
}
CORE = {388172,546670,908741,790626,429235,448233,967848}
DEPENDENCY_RULES = {
    974401: {237749},                 # Mobtimizations -> CoroUtil
    1484738: {388172,419699,1425144}, # do_not_join -> GeckoLib, Architectury, WallClimber API
    1475092: {388172},                # StalkerEntity -> GeckoLib
    1715713: {388172},                # Pale Watcher -> GeckoLib
    1248459: {388172},                # Dwerden -> GeckoLib
    1709569: {388172},                # Otherworld -> GeckoLib
    1233947: {388172},                # Cataclysms -> GeckoLib
}

p = Path("manifest.json")
assert p.exists(), "manifest.json missing"
m = json.loads(p.read_text(encoding="utf-8"))
assert m.get("manifestType") == "minecraftModpack"
assert m.get("manifestVersion") == 1
assert m["minecraft"]["version"] == "1.20.1"
loaders = m["minecraft"].get("modLoaders", [])
assert len(loaders) == 1 and loaders[0].get("primary") is True
assert loaders[0]["id"].startswith("forge-")

files = m.get("files", [])
assert files, "files is empty"
ids = []
for i, f in enumerate(files):
    assert isinstance(f.get("projectID"), int) and f["projectID"] > 0, f"bad projectID at {i}"
    assert isinstance(f.get("fileID"), int) and f["fileID"] > 0, f"bad fileID at {i}"
    assert f.get("required") is True, f"non-required entry at {i}"
    ids.append(f["projectID"])

s = set(ids)
assert len(ids) == len(s), "duplicate projectID detected"
assert not (s & BANNED), f"banned horror mods returned: {sorted(s & BANNED)}"
assert not (s & SCREEN_BLOCKING_EXCLUSIONS), f"screen-obscuring mods returned: {sorted(s & SCREEN_BLOCKING_EXCLUSIONS)}"
assert CORE <= s, f"missing core projects: {sorted(CORE - s)}"

for mod, deps in DEPENDENCY_RULES.items():
    if mod in s:
        missing = deps - s
        assert not missing, f"project {mod} missing dependencies: {sorted(missing)}"

print(f"PASS: {len(files)} unique manifest entries")
print("Minecraft:", m["minecraft"]["version"])
print("Loader:", loaders[0]["id"])
print("Stop-list: clean")
print("Known dependency rules: satisfied")
