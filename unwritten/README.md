# The Unwritten
Original standalone adaptive horror mod for Minecraft 26.3, NeoForge 26.3.0.118, Java 25. Existing modpack files are untouched.
## Implemented code
- Per-player director with tension, karma-dependent event probability and cooldowns.
- Bounded region visit history and per-world, per-player versioned state files under unwritten/. Periodic, disconnect and shutdown saves.
- Karma changes from killing monsters (+1), passive land or water creatures (-3).
- Targeted spatial sounds and temporary client-only watcher using a vanilla enderman placeholder; false presence currently combines both effects.
- Private temporary obsidian apparition restored from authoritative world state, without changing actual blocks.
## Not yet verified
The full mod build and in-game packet behavior must be confirmed by CI and smoke testing. No custom watcher model, true player mimic, custom dimension or story yet.
## Build
From repository root: gradle -p unwritten build (Gradle 9.2.1, JDK 25). CI uploads the JAR only on success.
