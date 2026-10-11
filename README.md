# EHTID Horror REBORN v5 — Internet Abyss

Minecraft 1.20.1 · Forge 47.4.15

Current manifest: **100 unique CurseForge entries**.

## Direction
The pack intentionally focuses on obscure, Chinese, experimental and low-download horror mods.
The user's previous 46-mod stop-list is enforced by CI and must not return.

## Validation
Every push to main runs `.github/workflows/validate-pack.yml`.

CI checks:
- CurseForge manifest structure;
- duplicate project IDs;
- Minecraft / Forge fields;
- banned horror stop-list;
- required performance/core mods;
- known dependencies for selected horror mods.

## Runtime test
GitHub CI validates the pack statically. Actual Minecraft world creation and runtime
compatibility still require a client launch and latest.log/crash-report review.
