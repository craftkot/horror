# EHTID Horror REBORN v14.2 — Clear Screen + TPS Relief

Minecraft 1.20.1 · Forge 47.4.15 · 109 CurseForge entries.

## Changes from v14.1

- Removed StalkerEntity (project 1475092): its forced emergency alerts,
  fake error screens, EAS broadcasts, glitch/jumpscare screens and the
  associated entities/dimensions are no longer part of this modpack.
- Removed EnhancedVisuals (project 255389): no more blood, dirt, water
  and other first-person HUD overlays from that mod. CreativeCore stays:
  it is shared by other mods (e.g. AmbientSounds).
- Disabled Psychopath vignette/blur/heartbeat visual effects.
- Disabled Backrooms screen shake, jumpscare and sanity red lines
  (preserving entities, dimension and the underlying sanity mechanic).
- Disabled Sound of Fear cave screens (retaining its spatial audio).
- Retained v14.1 performance mitigation and the spark profiler.
- Added both removed mod IDs to the CI exclusion list.

This targets **known screen-obscuring effects**. Other obscure horror mods
may still implement their own custom full-screen visuals. Report any
remaining effect (screenshot + what triggered it) for targeted removal.

### Existing-world upgrade

**BACK UP THE WORLD FIRST.** Updating the manifest is not enough if the
old install is reused. Delete the old JARs for StalkerEntity and EnhancedVisuals
from the existing instance's `mods/` folder, or import into a **new
CurseForge profile**. An old profile may retain removed JARs and old
configs. Verify the mods are missing from the in-game Mods menu.

`overrides/defaultconfigs/` applies mainly when creating a new world.
For existing saves merge the adjusted `backrooms_lifeform-server.toml`
and `sound_of_fear-server.toml` into `saves/<world>/serverconfig/` if
present. For Psychopath, replace the changed settings in
`config/psycho-common.toml`. No need to delete all server configs.

Removing content mods can cause old world entities, items, dimensions,
and blocks from those mods to disappear. Always back up before loading.

## Diagnostic status

The original 2 TPS issue has NOT been proven fixed on the user's world.
GitHub CI is only startup-level validation. For further investigation,
use spark and see [analysis/PERFORMANCE_TPS_RU.md](analysis/PERFORMANCE_TPS_RU.md).

After a push, verify all three workflows:
- Validate Horror Pack
- Forge Server Smoke Test
- Forge Client Smoke Test
