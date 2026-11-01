# Sky Rift / World Fracture — integration work

Target: **EHTID Horror REBORN**, Minecraft **1.20.1**, Forge **47.4.15**.

This repository is a CurseForge modpack, not the source of a custom Forge mod. Do **not** put GLSL files in `overrides/shaderpacks` and call them an integrated addon.

## Current state
- A procedural fracture fragment shader is committed under `addon/src/main/resources/assets/blackfrequency/shaders/program/`.
- It requires an actual Forge client renderer, shader registration and a sky pass. Merely packaging this file does not produce any in-game visual effects.
- No world blocks are modified yet. In particular, bedrock is **not** yet destroyed.

## Implementation contract
1. Build a real Forge 1.20.1 addon with mod id `blackfrequency`, loaded alongside the existing modpack.
2. Register the GLSL program in the Forge client shader registration event and draw a full-sky pass, providing ScreenSize, GameTime, RiftStrength and GreenPhase uniforms.
3. Implement server-owned phases: dormant, foreshadowing, opening, green lull, reopening, collapse.
4. Generate an irregular 2-D fracture graph (Voronoi / warped cell boundaries) dividing the world into many land masses. In the collapse phase remove terrain along multiple fracture corridors through the entire build height, including bedrock. Do this only on explicit activation, not on normal world load.
5. Budget block edits per tick and persist work queues, so unloaded chunks do not get synchronous force-loaded every tick. Ensure that biome/block entity edits and neighbor updates do not stall the server.
6. Provide explicit confirmation, backups and a world-setting gate before any irreversible terrain removal.
7. Add a command to test visuals separately without changing blocks; a separate command may trigger actual terrain destruction after a backup.
8. Run Gradle compilation plus Forge client and server smoke tests before publishing a mod JAR and adding it to the release pipeline.

A shader changes rendered pixels; it cannot by itself split, move or rotate Minecraft block geometry. Fragmentation and moving islands require a separate world mutation / custom mesh implementation.
