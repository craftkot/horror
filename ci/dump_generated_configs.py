import pathlib, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
roots = [ROOT/"server"/"config", ROOT/"server"/"world"/"serverconfig"]
keywords = (
    "horror","fear","psych","disturb","masked","grin","fluffy","clown",
    "cursed","stalker","unseen","dngo","gotham","idol","entity303",
    "skinwalker","creepy","paranormal","mirror","rot","viostt","night",
    "coroutil","mobtim","otherworld","death","backroom"
)
print("===== GENERATED CONFIG INVENTORY =====")
for base in roots:
    if not base.exists():
        continue
    for p in sorted(base.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(ROOT)
        print(rel)

print("\n===== HORROR-RELATED CONFIG CONTENT =====")
total = 0
for base in roots:
    if not base.exists():
        continue
    for p in sorted(base.rglob("*")):
        if not p.is_file() or p.suffix.lower() not in {".toml",".json",".json5",".cfg",".properties",".txt"}:
            continue
        low = p.name.lower()
        if not any(k in low for k in keywords):
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except Exception as e:
            print(f"--- {p.relative_to(ROOT)} (read error: {e})")
            continue
        # Keep Actions log readable.
        if len(text) > 12000:
            text = text[:12000] + "\n... [truncated]"
        print(f"\n--- {p.relative_to(ROOT)} ---\n{text}")
        total += len(text)
        if total > 90000:
            print("\n[config dump capped]")
            raise SystemExit(0)
