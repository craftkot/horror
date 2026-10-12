# EHTID Horror REBORN v14.1 — TPS Relief

Minecraft 1.20.1 · Forge 47.4.15 · 111 CurseForge entries (including spark)

This branch contains the actual playable modpack manifest (`manifest.json`) and
configuration overrides (`overrides/`). The older numbered mod list in
`overrides/MODLIST_RU.txt` is a historical reference and is NOT the source of truth.

## Performance hotfix

The v14 All Encounters profile increased simultaneous horror encounter pressure.
v14.1 reduces the *frequency*, ambient scan overhead and active entity load of
several event-driven mods, without removing those mods or their entities.

Added spark 1.10.53 (Forge 1.20.1) for real profiling.

**Important:** These edits are *mitigations*, not a verified fix for reported
2 TPS; profiling an affected game world is still required to identify the bottleneck.
The GitHub Forge CI smoke tests validate startup, not gameplay TPS/FPS.

See [analysis/PERFORMANCE_TPS_RU.md](analysis/PERFORMANCE_TPS_RU.md)
for installing this build, applying configs to existing worlds, spark commands,
and what logs/profiles to share for further investigation.

## CI

- Validate Horror Pack
- Forge Server Smoke Test
- Forge Client Smoke Test

Do not claim client-side stability until all applicable CI jobs succeed.
