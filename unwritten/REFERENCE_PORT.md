# Reference mechanic audit
Inputs inspected with javap -p -c: The God v1.2.1 (1.20.1), The Broken Script 2.0.5 hotfix build 3850 (1.21.1). Original assets and bytecode are not shipped.
| Original class | Observed behavior | Port |
|---|---|---|
| CarmaeventProcedure | Bonemeal counter reaches 5, then +1 | Per-player persistent counter |
| CarmaEvent3Procedure | Animal tame +1 | Per-player |
| CarmaEvent4Procedure | Farmland trample -5 | Player-caused only |
| CarmaEvent5Procedure | Explosion detonation -3 | Player-attributed only |
| CarmaEvent6Procedure | Karma below 50 displays bad-actions sleep message, above 50 1/10 chance of God message | Pending |
| events.sounds.DoorsEvent | Search 12-block cube for nearest wooden door, toggle OPEN | Reimplemented, loaded chunks only |
| events.misc.BreatheEvent | PLAYER_BREATH targeted sound | Reimplemented with reduced volume/pitch and spatial position |
| events.misc.TextEvent | Select random system message from list | One original localized placeholder message; full variety pending |
| events.world.PlaceRedstoneTorchEvent | Place redstone torch at learned base bottom position | Pending |
| events.misc.BehindYouEvent | Repeated overlay plus glitch audio | Pending |
Original God uses shared MapVariables and gamerule-derived values; our state intentionally remains individual, centered at zero. Removed unverified kill-based karma rules. The scheduler is still ours; reference event scheduling has not yet been ported. Compilation and gameplay verification pending.
