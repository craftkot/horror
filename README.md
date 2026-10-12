# EHTID Horror REBORN v14.3 — No Forced Reboot or Countdown

Minecraft **1.20.1** / Forge **47.4.15** / **105 mods**.

## Install from GitHub Releases
- Open the [latest Releases page](https://github.com/craftkot/horror/releases).
- Download **EHTID-Horror-CurseForge.zip** and import it as a *new* CurseForge profile.
- If **EHTID-Horror.mrpack** is also present, it was constructed from verified
  public upstream URLs; use it in a compatible MRPACK launcher (e.g. Prism).
- Third-party JARs are NOT rehosted due to authors' redistribution restrictions.
  Instead the launcher downloads their correct versions.
- Allocate around 8-10 GiB RAM if the system has enough memory.

## Removed intrusive / restart mods
- **The Unseen Entity** (1595083): bytecode audit exposed
  `biosRebootTimer`, `biosDrivesDestroyed` and BIOS-scene HUD logic.
  Strong match for the 30-minute timer / 0-of-3 objectives from screenshot.
- **_Viostt.jar** (1651479): includes a simulated watchdog force-shutdown
  sequence and graphics restart warnings. It is unclear whether an actual
  Minecraft process shutdown is performed; removed as a precaution.
- Previously removed: StalkerEntity (1475092), EnhancedVisuals (255389),
  The Idol (1421612), do_not_join.jar (1484738).
- Existing screen effects disabled in Psychopath, Backrooms and Sound of Fear.
- Previous TPS relief optimizations and spark profiling remain.

## Updating an existing world
**Back up saves BEFORE opening them with removed mods.**
Use a fresh instance, since updating old modpacks can leave deleted JARs in
`mods/` even when they are absent from the new manifest.
For manual upgrades, delete the six excluded JARs from your old
installation. Some existing items/entities/blocks from these mods may
disappear. Server configs in existing worlds can override new settings
from `defaultconfigs`: merge carefully.

The 2 TPS issue has not been measured as fixed on your existing world.
GitHub CI only verifies modpack validation and that the client/server boot.
To profile TPS use `/spark profiler start --timeout 90`.

Documentation: [TPS diagnosis](analysis/PERFORMANCE_TPS_RU.md).
