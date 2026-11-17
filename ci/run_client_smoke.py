import os, pathlib, subprocess, sys, time, threading, re, shutil, math
import minecraft_launcher_lib

ROOT=pathlib.Path(__file__).resolve().parents[1]
MC=ROOT/"client"
MOUSE_SENSITIVITY=0.6
MIN_GROUND_MESH_SEGMENTS=8
MIN_GROUND_MESH_ARMS=2
MAX_GROUND_MESH_ARMS=4
GROUND_MESH_SAMPLES=96
FISSURE_PARTICLE_SAMPLES=48
MAX_GROUND_MESH_SEGMENTS=MAX_GROUND_MESH_ARMS*(GROUND_MESH_SAMPLES//MAX_GROUND_MESH_ARMS-1)
FISSURE_PARTICLE_EMISSION_RE=re.compile(
    r"BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLES_EMITTED samples=(\d+) accentDust=(\d+) violetDust=(\d+) edgeDust=(\d+) total=(\d+) heightmap=motion-blocking-no-leaves"
)

def fissure_target_view_score(target_y, player_y, horizontal_distance_squared):
    values=(float(target_y),float(player_y),float(horizontal_distance_squared))
    if not all(math.isfinite(value) for value in values) or values[2]<=0.0:
        raise ValueError("fissure target view score requires finite coordinates and positive horizontal distance")
    vertical_delta=values[0]-(values[1]+1.62)
    return abs(math.degrees(math.atan2(abs(vertical_delta),math.sqrt(values[2])))-45.0)

def verify_fissure_target_view_score_profile():
    fixtures=(
        (68.9125,70,7.272919386951269,0.11306757125331757),
        (66.1,70,32.98037496303177,1.1335440888873975),
    )
    for target_y,player_y,horizontal_squared,expected in fixtures:
        actual=fissure_target_view_score(target_y,player_y,horizontal_squared)
        if not math.isclose(actual,expected,rel_tol=0.0,abs_tol=1.0E-6):
            raise RuntimeError("BLACK FREQUENCY balanced fissure view-score fixture regression")
    for invalid in (
            (float("nan"),70,4.0),
            (66.0,70,float("inf")),
            (66.0,70,0.0),
            (66.0,70,-1.0)):
        try:
            fissure_target_view_score(*invalid)
        except ValueError:
            continue
        raise RuntimeError("BLACK FREQUENCY fissure view-score accepted invalid coordinates")
    print("WORLD_CAPTURE: balanced fissure target score fixtures and invalid-input guards verified",flush=True)

def parse_fissure_particle_emission(log_text):
    match=FISSURE_PARTICLE_EMISSION_RE.search(log_text)
    return tuple(int(value) for value in match.groups()) if match else None


def verify_beacon_aura_source():
    root=ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"
    network=(root/"RiftNetwork.java").read_text(encoding="utf-8")
    client=(root/"client"/"RiftClientState.java").read_text(encoding="utf-8")
    sky=(root/"client"/"RiftSkyRenderer.java").read_text(encoding="utf-8")
    controller=(root/"RiftController.java").read_text(encoding="utf-8")
    if ("private static final String VERSION = \"4\";" not in network
            or "buf.writeLong(p.beaconPos); buf.writeBoolean(p.restorationActive)" not in network
            or "buf.readLong(), buf.readBoolean()" not in network
            or "packet.beaconPos" not in network
            or "packet.restorationActive" not in network
            or "public final long beaconPos;" not in network
            or "public final boolean restorationActive;" not in network):
        raise RuntimeError("BLACK FREQUENCY physical beacon position packet contract is incomplete")
    if ("public static void renderBeaconAura(RenderLevelStageEvent event)" not in client
            or "level.hasChunk(beacon.getX()>>4,beacon.getZ()>>4)" not in client
            or "Blocks.BEACON" not in client
            or "VertexFormat.Mode.QUADS" not in client
            or "BEACON_AURA_RENDERED cages=2 edgeRibbons=24 rotation=counterphase loadedOnly=true" not in client
            or "shouldShowBeaconAura(collapse,progress,restorationActive)" not in client
            or "BEACON_AURA_RESTORATION_VISIBILITY_VERIFIED zeroProgressRestoring=true completed=false" not in client
            or "hasActiveTimedClientState(progress,collapse,finaleTicks,restorationActive)" not in client
            or "isClientPacketStateStale(++ticksSincePacket)" not in client
            or "BEACON_AURA_STALE_PACKET_EXPIRY_VERIFIED restorationOnly=true idle=false timeoutTicks=100" not in client
            or "nextBeaconAuraCompletionFadeTicks(" not in client
            or "beaconAuraCompletionFadeTicks,wasRestoring,worldRestorationActive,collapsing,safeProgress)" not in client
            or "BEACON_AURA_COMPLETION_FADE_PROFILE_VERIFIED ticks=16 curve=smoothstep partialTick=true" not in client
            or "BEACON_AURA_COMPLETION_FADE_RENDERED" not in client
            or "BEACON_AURA_COMPLETION_FADE_MIDPOINT_RENDERED" not in client
            or "BEACON_AURA_COMPLETION_FADE_TAIL_RENDERED" not in client
            or "BEACON_AURA_COMPLETION_FADE_MILESTONES_VERIFIED midpointInRange=true tailVisible=true" not in client
            or "BEACON_AURA_RESTORATION_ACTIVE_RENDERED progressZero=true loadedOnly=true" not in client
            or "BEACON_AURA_DISTANCE_FADE_PROFILE_VERIFIED start=40.0 end=48.0 curve=smoothstep monotonic=true invalidRejected=true" not in client
            or "beaconAuraDistanceFade(distanceSquared)" not in client
            or "distanceSquared<=BEACON_AURA_FADE_START_DISTANCE_SQR" not in client
            or "BEACON_AURA_RIBBON_PROFILE_VERIFIED outerHalfWidth=0.022 innerHalfWidth=0.012 cameraFacing=true allocationFree=true" not in client
            or "private static void beaconAuraRibbon(" not in client
            or "BEACON_AURA_ROTATION_PROFILE_VERIFIED periodTicks=1200 outerTurns=1 innerTurns=-2 wrapContinuous=true partialBounded=true" not in client
            or "BEACON_AURA_ROTATION_PERIOD=1200L" not in client
            or "beaconAuraRotationAngle(level.getGameTime(),partial,1)" not in client
            or "beaconAuraRotationAngle(level.getGameTime(),partial,-2)" not in client
            or "RiftClientState.renderBeaconAura(event);" not in sky
            or "RiftNetwork.send(player,remaining,false,0,s.x,s.z,s.naturalBeaconPos,true);" not in controller
            or "s.x,s.z,s.naturalBeaconPos,s.restoring)" not in controller):
        raise RuntimeError("BLACK FREQUENCY loaded-only violet beacon aura source contract is incomplete")
    aura_start=client.find("public static void renderBeaconAura(")
    aura_end=client.find("\n    }",aura_start)
    aura_body=client[aura_start:aura_end] if aura_start>=0 and aura_end>aura_start else ""
    if any(token in aura_body for token in ("getChunkAt(","getChunk(",
            "getChunkSource().getChunk","forceChunk")):
        raise RuntimeError("BLACK FREQUENCY beacon aura must never request chunk loading")
    print("WORLD_CAPTURE: loaded-only physical beacon aura packet and render contract verified",flush=True)

def verify_restoration_particle_source():
    source_path=ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"RiftController.java"
    source=source_path.read_text(encoding="utf-8")
    geometry_contract=(ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"RiftGeometry.java").read_text(encoding="utf-8")
    if ("double zone=gravityAnomalyField(testX,testZ,tick);" not in source
            or "double zone=gravityAnomalyField(px,pz,s.finaleTicks);" not in source
            or "double zone=gravityAnomalyField(player.getX(),player.getZ(),s.finaleTicks);" not in source
            or "double base=Math.sin(x*.115D+gravityFieldBasePhase)" not in source
            or "double cross=Math.sin((x+z)*.047D+gravityFieldCrossPhase-gravityFieldCrossTimeOffset);" not in source
            or "double turbulence=Math.sin((x-z)*.083D+gravityFieldTurbulencePhase+gravityFieldTurbulenceTimeOffset);" not in source
            or "double microTurbulence=Math.sin((x*.17D+z*.13D)" not in source
            or "double field=base+.12D*cross+.06D*turbulence+.035D*microTurbulence;" not in source
            or "private static void updateGravityFieldPhaseCache(int ticks)" not in source
            or "if(gravityFieldCachedTick==time) return;" not in source
            or "gravityFieldBasePhase=Math.sin(time*.011D)*1.8D;" not in source
            or "gravityFieldBaseZOffset=time*.008D;" not in source
            or "gravityFieldCrossPhase=Math.sin(time*.013D)*.9D;" not in source
            or "gravityFieldCrossTimeOffset=time*.009D;" not in source
            or "gravityFieldTurbulencePhase=Math.sin(time*.017D)*1.3D;" not in source
            or "gravityFieldTurbulenceTimeOffset=time*.021D;" not in source
            or "gravityFieldMicroPhase=Math.sin(time*.027D)*.8D;" not in source
            or "gravityFieldMicroTimeOffset=time*.031D;" not in source
            or "gravityFieldCacheRefreshCount<Long.MAX_VALUE" not in source
            or "refreshCountAfterSameTick!=refreshCountAt85" not in source
            or "refreshCountAfterRewind!=refreshCountAt85+1L" not in source
            or "refreshCountAfterSecondRewind!=refreshCountAt85+2L" not in source
            or "ANOMALY_FIELD_TIME_PHASE_CACHE_VERIFIED cachedOffsets=8 sameTickReuse=true tickRewind=true" not in source
            or "Math.sin(player.getX()*.115" in source
            or "Math.sin(px*.115+Math.sin(s.finaleTicks*.011)*1.8)" in source):
        raise RuntimeError("BLACK FREQUENCY gravity consumers must share the bounded multi-scale anomaly field")
    field_start=source.find("private static double gravityAnomalyField(")
    field_end=source.find("\n    private static void verifyGravityAnomalyField",field_start)
    field_body=source[field_start:field_end] if field_start>=0 and field_end>field_start else ""
    if not field_body or any(term in field_body for term in ("time*.008D","time*.009D","time*.021D","time*.031D")):
        raise RuntimeError("BLACK FREQUENCY gravity field must reuse all cached time offsets")
    client_source=(ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"client"/"RiftClientState.java").read_text(encoding="utf-8")
    fissure_start=source.find("private static FissureEmission emitSurfaceFissure(")
    fissure_end=source.find("\n    private static",fissure_start+1)
    fissure_emitter=source[fissure_start:fissure_end] if fissure_start>=0 and fissure_end>fissure_start else ""
    ground_sampler_start=source.find("private static int findFissureGroundY(")
    ground_sampler_end=source.find("\n    /**",ground_sampler_start+1)
    ground_sampler=source[ground_sampler_start:ground_sampler_end] if ground_sampler_start>=0 and ground_sampler_end>ground_sampler_start else ""
    particle_height_start=source.find("private static double fissureParticleY(")
    particle_height_end=source.find("\n    private static void verifyFissureSurfaceParticleHeight",particle_height_start)
    particle_height=source[particle_height_start:particle_height_end] if particle_height_start>=0 and particle_height_end>particle_height_start else ""
    target_start=source.find("private static FissureTarget findVisibleFissureTarget(")
    target_end=source.find("\n    // Run after ordinary explosion modifiers",target_start+1)
    target_body=source[target_start:target_end] if target_start>=0 and target_end>target_start else ""
    pad_start=source.find('if(playerInteractionTest && Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest"))')
    pad_end=source.find("} else if(playerInteractionTest)",pad_start+1)
    pad_body=source[pad_start:pad_end] if pad_start>=0 and pad_end>pad_start else ""
    completion_start=source.find("if(s.restoreIndex<0 && s.restoreTicks>=100)")
    completion_end=source.find("\n        s.setDirty();",completion_start)
    completion_body=source[completion_start:completion_end] if completion_start>=0 and completion_end>completion_start else ""
    completion_animation=completion_body.find("ClientboundSetTitlesAnimationPacket(")
    completion_title=completion_body.find("ClientboundSetTitleTextPacket(")
    if ("RESTORE_TITLE_FADE_IN_TICKS=10;" not in source
            or "RESTORE_TITLE_STAY_TICKS=80;" not in source
            or "RESTORE_TITLE_FADE_OUT_TICKS=20;" not in source
            or completion_animation<0 or completion_title<0 or completion_animation>completion_title
            or 'private static final String RESTORE_SUCCESS_TITLE="МИР СПАСЁН";' not in source
            or 'private static final String RESTORE_PARTIAL_TITLE="ЕСТЬ ПРОПУСКИ";' not in source
            or "s.skippedOccupied==0 && s.skippedInvalid==0 ? RESTORE_SUCCESS_TITLE : RESTORE_PARTIAL_TITLE" not in completion_body
            or "RESTORE_COMPLETION_TITLE_WIDTH_VERIFIED successChars={} partialChars={} maxChars=13" not in source
            or "RESTORATION_COMPLETION_TITLE_TIMING_VERIFIED" not in source):
        raise RuntimeError("BLACK FREQUENCY completion title must set and verify explicit fade timing before the title packet")
    if ("public static final double FISSURE_BEND_AMPLITUDE=.24D;" not in geometry_contract
            or source.count("RiftGeometry.FISSURE_BEND_AMPLITUDE")<5
            or client_source.count("RiftGeometry.FISSURE_BEND_AMPLITUDE")<4
            or "CI_BEACON_PAD_FLOOR_Y_OFFSET=1;" not in source
            or "CI_BEACON_PAD_PLAYER_Y_OFFSET=2;" not in source
            or "CI_BEACON_PAD_STANDOFF=3.25D;" not in source
            or "CI_BEACON_PAD_GAP_ROW=2;" not in source
            or "CI_BEACON_PAD_FIRST_SOLID_ROW=3;" not in source
            or "CI_BEACON_PAD_LAST_SOLID_ROW=4;" not in source
            or "CI_BEACON_PLAYER_EYE_HEIGHT=1.62D;" not in source
            or "ciBeaconSightlineHeightAtBeaconFace()" not in source
            or "ciBeaconSightlineHeightAtPadInnerFace()" not in source
            or "Math.abs(distanceSquared-12.8125D)>1.0E-9D" not in source
            or "beaconEntryY>0.0D && beaconEntryY<1.0D" not in source
            or "padInnerFaceY>CI_BEACON_PAD_FLOOR_Y_OFFSET+1.0D" not in source
            or "BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_BLOCKER_DIAGNOSTIC" not in source
            or "CI_BEACON_PAD_FIRST_SOLID_ROW!=CI_BEACON_PAD_GAP_ROW+1" not in source
            or "ciBeaconPadAlongX(int anchorX,int anchorZ,int beaconX,int beaconZ)" not in source
            or "ciBeaconPadAlongX(s.x,s.z,demoPos.getX(),demoPos.getZ())" not in pad_body
            or "chooseCiBeaconPadSign(level,demoPlayer,s,demoPos,padAlongX)" not in pad_body
            or "BEACON_APPROACH_SIDE_SELECTED" not in pad_body
            or "padSign*offset" not in pad_body
            or "ciBeaconPadApproachClear(ServerLevel level,net.minecraft.server.level.ServerPlayer player,State s,BlockPos beacon" not in source
            or "ClipContext.Fluid.NONE,player" not in source
            or "findVisibleFissureTarget(level,s,player,eye)!=null" not in source
            or "fissureVisibilityRequired=true" not in source
            or "verifyCiBeaconPadSignSelection();" not in source
            or "for(int offset=CI_BEACON_PAD_FIRST_SOLID_ROW;offset<=CI_BEACON_PAD_LAST_SOLID_ROW;offset++)" not in pad_body
            or "ciBeaconSightlineHitsBeacon(level,demoPlayer,demoPos)" not in pad_body
            or "BEACON_SIGHTLINE_VERIFIED" not in pad_body
            or "demoPos.offset(dx,CI_BEACON_PAD_FLOOR_Y_OFFSET,dz)" not in pad_body
            or "Math.abs(padDx)>RADIUS" not in pad_body
            or "Math.abs(padDz)>RADIUS" not in pad_body
            or "level.getWorldBorder().isWithinBounds(floor)" not in pad_body
            or "level.hasChunk(floor.getX()>>4,floor.getZ()>>4)" not in pad_body
            or "demoPos.getY()+CI_BEACON_PAD_PLAYER_Y_OFFSET" not in pad_body
            or "demoPos.getX()+.5D+padDirectionX*CI_BEACON_PAD_STANDOFF" not in pad_body
            or "demoPos.getZ()+.5D+padDirectionZ*CI_BEACON_PAD_STANDOFF" not in pad_body
            or "PLAYER_BEACON_SAFE_PAD_PROFILE_VERIFIED" not in pad_body):
        raise RuntimeError("BLACK FREQUENCY elevated perpendicular beacon pad must stay loaded, bounded, visible and within interaction reach")
    if ("BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();" not in target_body
            or "findFissureGroundY(level,blockX,blockZ,cursor)" not in target_body
            or "fissureParticleY(level,blockX,surfaceY,blockZ,cursor)" not in target_body
            or "fissureRayChunksLoaded(eye,target,(chunkX,chunkZ)->level.hasChunk(chunkX,chunkZ))" not in target_body
            or "LoadedChunkLookup" not in source
            or "Math.floor(startX)" not in source
            or "BEACON_RAY_CHUNK_TRAVERSAL_VERIFIED" not in source
            or "new BlockPos(" in target_body
            or "findFissureGroundY(level,s.x,s.z)" in source):
        raise RuntimeError("BLACK FREQUENCY visible fissure target must reuse its loaded-only sample cursor")
    if ("BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();" not in fissure_emitter
            or "findFissureGroundY(level,blockX,blockZ,cursor)" not in fissure_emitter
            or "fissureParticleY(level,blockX,surfaceY,blockZ,cursor)" not in fissure_emitter
            or "new BlockPos(" in fissure_emitter
            or "new BlockPos(" in ground_sampler
            or "cursor.set(x,y,z)" not in ground_sampler
            or "cursor.set(x,y-1,z)" not in ground_sampler
            or "new BlockPos(" in particle_height
            or "cursor.set(x,surfaceY,z)" not in particle_height):
        raise RuntimeError("BLACK FREQUENCY server fissure sampling must reuse one mutable block-position cursor")
    if ("private static final DustParticleOptions FISSURE_VIOLET_EDGE_DUST" not in source
            or "level.sendParticles(FISSURE_VIOLET_EDGE_DUST," not in fissure_emitter
            or "private static final DustParticleOptions SURFACE_FISSURE_ACCENT_DUST" not in source
            or "level.sendParticles(SURFACE_FISSURE_ACCENT_DUST," not in fissure_emitter
            or "level.sendParticles(FISSURE_VIOLET_ACCENT_DUST," in fissure_emitter
            or ("FISSURE_"+"OBSIDIAN_SHARD") in source
            or ("Blocks.CRYING_"+"OBSIDIAN.defaultBlockState()") in source
            or "new DustParticleOptions(" in fissure_emitter):
        raise RuntimeError("BLACK FREQUENCY fissure outer tips must use one cached compact violet Dust option")
    if ("private record FissureSample(" not in source
            or "private static final FissureSample[] FISSURE_SAMPLES=createFissureSamples();" not in source
            or "FISSURE_SAMPLES[arm*FISSURE_SEGMENTS_PER_ARM+segment-1]" not in fissure_emitter
            or "Math.sin(" in fissure_emitter
            or "Math.cos(" in fissure_emitter):
        raise RuntimeError("BLACK FREQUENCY server fissure geometry must use its bit-verified static sample cache")
    if ("private static boolean isOutermostValidFissureSample(int segment,int previousSegment)" not in source
            or "if(isOutermostValidFissureSample(segment,edgeCandidateSegment))" not in fissure_emitter
            or "if(hasEdgeCandidate)" not in fissure_emitter
            or "edgeFallbackArms++" not in fissure_emitter
            or "segment<FISSURE_SEGMENTS_PER_ARM" not in source):
        raise RuntimeError("BLACK FREQUENCY must emit one compact edge mote at each arm's furthest valid sample")
    start=source.find("    private static void restore(")
    end=source.find("\n    @SubscribeEvent",start)
    if start<0 or end<0:
        raise RuntimeError("BLACK FREQUENCY restoration method source boundary regression")
    body=source[start:end]
    state_start=source.find("private static final class State extends SavedData")
    state_end=source.find("static State load",state_start)
    state_fields=source[state_start:state_end] if state_start>=0 and state_end>state_start else ""
    save_start=source.find("@Override public CompoundTag save",state_end)
    save_end=source.find("return n;",save_start)
    save_body=source[save_start:save_end] if save_start>=0 and save_end>save_start else ""
    if ("final BlockPos.MutableBlockPos restoreCursor = new BlockPos.MutableBlockPos();" not in state_fields
            or 'long packedPosition=entry.getLong("pos");' not in body
            or "BlockPos.MutableBlockPos pos=s.restoreCursor;" not in body
            or "pos.set(BlockPos.getX(packedPosition),BlockPos.getY(packedPosition),BlockPos.getZ(packedPosition));" not in body
            or 'BlockPos.of(entry.getLong("pos"))' in body
            or "restoreCursor" in save_body
            or "RESTORE_POSITION_CURSOR_REUSE_VERIFIED sameInstance=true packedCoordinates=true transient=true" not in source):
        raise RuntimeError("BLACK FREQUENCY restoration must reuse a transient mutable snapshot-position cursor without serializing it")
    snapshot_load_start=source.find("static State load(CompoundTag n)")
    snapshot_load_end=source.find("if(snapshotCount>s.edits)",snapshot_load_start)
    snapshot_load=source[snapshot_load_start:snapshot_load_end] if snapshot_load_start>=0 and snapshot_load_end>snapshot_load_start else ""
    runtime_snapshot_start=source.find("long candidateSnapshotEstimate=boundedSnapshotPayloadEstimate(snapshot);")
    runtime_snapshot_end=source.find("if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),2|16))",runtime_snapshot_start)
    runtime_snapshot=source[runtime_snapshot_start:runtime_snapshot_end] if runtime_snapshot_start>=0 and runtime_snapshot_end>runtime_snapshot_start else ""
    if ("MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES=64L*1024L*1024L" not in source
            or "boundedSnapshotPayloadEstimate(entry)" not in snapshot_load
            or "withinSnapshotRetentionBudget(s.retainedSnapshotEstimateBytes,candidateEstimate)" not in snapshot_load
            or "RECOVERY_SNAPSHOT_RETENTION_BUDGET_VERIFIED" not in source
            or "RECOVERY_SNAPSHOT_AGGREGATE_BUDGET_QUARANTINED" not in source
            or "withinSnapshotRetentionBudget(s.retainedSnapshotEstimateBytes,candidateSnapshotEstimate)" not in runtime_snapshot
            or "s.retainedSnapshotEstimateBytes+=candidateSnapshotEstimate" not in runtime_snapshot
            or "RECOVERY_SNAPSHOT_RUNTIME_BUDGET_GUARD_VERIFIED" not in source):
        raise RuntimeError("BLACK FREQUENCY runtime and loaded recovery snapshots must share one bounded retention budget")
    scan_interval=re.search(r"RESTORE_PLAYER_SCAN_INTERVAL_TICKS\s*=\s*(\d+)",source)
    ring_policy_start=source.find("private static boolean shouldEmitRestoreAudiencePulse")
    ring_policy_end=source.find("private static boolean shouldScanRestorePlayers",ring_policy_start)
    scan_policy_end=source.find("private static final class RestorePlayerScanState",ring_policy_end)
    ring_policy=source[ring_policy_start:ring_policy_end] if ring_policy_start>=0 and ring_policy_end>ring_policy_start else ""
    scan_policy=source[ring_policy_end:scan_policy_end] if ring_policy_end>=0 and scan_policy_end>ring_policy_end else ""
    tick_advance=body.find("s.restoreTicks=nextNonNegativeTick(s.restoreTicks)")
    cadence_derive=body.find("int cadenceTick=restorationCadenceTick(s.restoreTicks,level.getGameTime())")
    scan_gate=body.find("if(shouldScanRestorePlayers(cadenceTick,beaconPresent))")
    beacon_anchor_guard=body.find("boolean beaconPositionValid=s.beaconPosPresent")
    beacon_chunk_lookup=body.find("&& level.hasChunkAt(activatedBeacon)",beacon_anchor_guard)
    cadence_policy_start=source.find("private static int restorationCadenceTick")
    cadence_policy_end=source.find("private static long restorationAnimationTick",cadence_policy_start)
    cadence_policy=source[cadence_policy_start:cadence_policy_end] if cadence_policy_start>=0 and cadence_policy_end>cadence_policy_start else ""
    player_loop=body.find("for(var player:level.players())")
    menu_guard=body.find("player.containerMenu instanceof net.minecraft.world.inventory.BeaconMenu")
    scan_end=body.find("\n        // A visible, bounded stabilization pulse",player_loop)
    scan_body=body[player_loop:scan_end] if scan_end>player_loop else ""
    if (not scan_interval or int(scan_interval.group(1))!=4
            or not (0<=tick_advance<cadence_derive<scan_gate<player_loop<=menu_guard)
            or "private static boolean isRecoveryBeaconVisualAnchorValid(" not in source
            or not (0<=beacon_anchor_guard<beacon_chunk_lookup)
            or "boolean beaconPosPresent;" not in state_fields
            or 'boolean beaconPositionPresent=n.contains("beaconPos");' not in snapshot_load
            or 'boolean beaconPositionValid=n.contains("beaconPos",Tag.TAG_LONG);' not in snapshot_load
            or 'if(beaconPositionPresent && !beaconPositionValid)' not in snapshot_load
            or 'quarantined malformed recovery beacon position' not in snapshot_load
            or 's.setDirty();' not in snapshot_load[snapshot_load.find("if(beaconPositionPresent && !beaconPositionValid)"):]
            or "if(beaconPosPresent) n.putLong(" not in save_body
            or 'else n.remove("beaconPos");' not in save_body
            or "BlockPos activatedBeacon=recoveryVisualAnchor(s,level.getSeaLevel());" not in body
            or source.count("s.beaconPosPresent=true")<2
            or "verifyRecoveryBeaconPositionPresence();" not in source
            or "wrongTypeTag.putString(" not in source
            or "RECOVERY_BEACON_POSITION_PRESENCE_VERIFIED" not in source
            or "level.getWorldBorder().isWithinBounds(activatedBeacon)" not in body
            or "int cadenceTick=restorationCadenceTick(s.restoreTicks,level.getGameTime())" not in body
            or "Math.floorMod(gameTime,(long)RESTORE_CADENCE_PERIOD_TICKS)" not in cadence_policy
            or "restoreTicks<Integer.MAX_VALUE" not in cadence_policy
            or "if(beaconPresent) {\n            for(var player:level.players())" in body
            or not scan_body or "player.closeContainer()" not in scan_body
            or "playerScan.observe(" not in scan_body or "break;" in scan_body
            or "restoreTicks==1" not in ring_policy
            or "RESTORE_RING_INTERVAL_TICKS" not in ring_policy
            or "restoreTicks==1" not in scan_policy
            or "RESTORE_PLAYER_SCAN_INTERVAL_TICKS" not in scan_policy
            or "RESTORE_INITIAL_BEACON_PULSE_EMITTED" not in body):
        raise RuntimeError("BLACK FREQUENCY cadence-aware shared player scan must close all beacon menus before finishing")
    packet_range=re.search(r"RESTORE_PARTICLE_PACKET_RANGE\s*=\s*([0-9.]+)D",source)
    effect_envelope=re.search(r"RESTORE_PARTICLE_EFFECT_ENVELOPE\s*=\s*([0-9.]+)D",source)
    particle_distance_expression=re.search(
        r"RESTORE_PARTICLE_VIEW_DISTANCE\s*=\s*RESTORE_PARTICLE_PACKET_RANGE\s*\+\s*RESTORE_PARTICLE_EFFECT_ENVELOPE",
        source)
    audience_start=source.find("private static boolean withinRestoreParticleAudience(")
    audience_end=source.find("\n    private static boolean shouldScanRestorePlayers",audience_start)
    audience_body=source[audience_start:audience_end] if audience_start>=0 and audience_end>audience_start else ""
    if (not packet_range or float(packet_range.group(1))!=32.0
            or not effect_envelope or float(effect_envelope.group(1))!=5.0
            or not particle_distance_expression
            or "Double.isFinite(distanceSquared)" not in audience_body
            or "distanceSquared<RESTORE_PARTICLE_VIEW_DISTANCE_SQR" not in audience_body
            or "Math.nextDown(RESTORE_PARTICLE_VIEW_DISTANCE_SQR)" not in source
            or "Math.nextUp(RESTORE_PARTICLE_VIEW_DISTANCE_SQR)" not in source
            or body.count("withinRestoreParticleAudience(")!=2
            or "verifyRestoreParticleAudienceBounds();" not in source
            or "RESTORE_PARTICLE_AUDIENCE_BOUNDS_VERIFIED" not in source):
        raise RuntimeError("BLACK FREQUENCY beacon particle audience must match the bounded vanilla packet radius")
    forbidden=("ParticleTypes.REVERSE_PORTAL","ParticleTypes.DRAGON_BREATH","ParticleTypes.END_ROD")
    found=[token for token in forbidden if token in body]
    if found:
        raise RuntimeError("BLACK FREQUENCY legacy restoration particles remain: "+", ".join(found))
    if "FISSURE_VIOLET_" in body:
        raise RuntimeError("BLACK FREQUENCY restoration still uses oversized ground-fissure Dust")
    if "§d" in source or "§5" in source:
        raise RuntimeError("BLACK FREQUENCY recovery UI still uses legacy magenta formatting codes")
    if ("private static Component recoveryViolet(String text)" not in source
            or "TextColor.fromRgb(RECOVERY_UI_VIOLET_RGB)" not in source
            or not re.search(r"RECOVERY_UI_VIOLET_RGB\s*=\s*0x5714C7",source)):
        raise RuntimeError("BLACK FREQUENCY recovery UI must use explicit deep-violet RGB")
    base_scale=re.search(r"RESTORE_VIOLET_DUST_SCALE\s*=\s*([0-9.]+)F",source)
    accent_scale=re.search(r"RESTORE_VIOLET_ACCENT_DUST_SCALE\s*=\s*([0-9.]+)F",source)
    spread_xz=re.search(r"RESTORE_AUDIENCE_SPREAD_XZ\s*=\s*([0-9.]+)D",source)
    spread_y=re.search(r"RESTORE_AUDIENCE_SPREAD_Y\s*=\s*([0-9.]+)D",source)
    if not all((base_scale,accent_scale,spread_xz,spread_y)):
        raise RuntimeError("BLACK FREQUENCY restoration Dust size/spread constants are missing")
    base,accent,xz,y=map(lambda m:float(m.group(1)),
                         (base_scale,accent_scale,spread_xz,spread_y))
    if not (.52<=base<=.62 and .70<=accent<=.82 and base<accent and 0<xz<=1.0 and 0<y<=.75):
        raise RuntimeError("BLACK FREQUENCY restoration Dust size/spread exceeds visual bounds")
    anomaly_start=source.find("// Lightweight visual signatures of the same drifting gravity field.")
    anomaly_end=source.find("// Recovery must remain available",anomaly_start)
    if anomaly_start<0 or anomaly_end<0:
        raise RuntimeError("BLACK FREQUENCY gravity anomaly particle source boundary regression")
    anomaly_body=source[anomaly_start:anomaly_end]
    legacy_anomaly_particles=("ParticleTypes.REVERSE_PORTAL","ParticleTypes.DRAGON_BREATH","ParticleTypes.END_ROD")
    legacy_found=[token for token in legacy_anomaly_particles if token in anomaly_body]
    if (legacy_found
            or anomaly_body.count("level.sendParticles(ANOMALY_VIOLET_DUST")!=2
            or anomaly_body.count("level.sendParticles(ANOMALY_VIOLET_ACCENT_DUST")!=1
            or "new DustParticleOptions" in anomaly_body
            or "ANOMALY_VIOLET_DUST = RESTORE_VIOLET_DUST" not in source
            or "ANOMALY_VIOLET_ACCENT_DUST = RESTORE_VIOLET_ACCENT_DUST" not in source):
        raise RuntimeError("BLACK FREQUENCY gravity anomaly cues must reuse cached violet Dust")
    print("WORLD_CAPTURE: gravity anomaly cues use cached deep/bright violet Dust",flush=True)
    print("WORLD_CAPTURE: compact violet fissure edge Dust is cached across pulses",flush=True)
    print("WORLD_CAPTURE: server fissure sampling reuses one mutable block-position cursor",flush=True)
    print("WORLD_CAPTURE: surface fissure uses a smaller cached accent than recovery cues",flush=True)
    print("WORLD_CAPTURE: restoration uses smaller cached violet Dust, bounded halo and dark-purple UI",flush=True)

def verify_real_beacon_break_source():
    controller=(ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"RiftController.java").read_text(encoding="utf-8")
    handler_start=controller.find("public void onRecoveryBeaconBreak(")
    handler_end=controller.find("\n    /**",handler_start)
    handler=controller[handler_start:handler_end] if handler_start>=0 and handler_end>handler_start else ""
    verify_start=controller.find("private static void verifyCiNaturalBeaconBreakNoDrop(")
    verify_end=controller.find("\n    @SubscribeEvent",verify_start)
    verify=controller[verify_start:verify_end] if verify_start>=0 and verify_end>verify_start else ""
    completion_start=controller.find("if(s.restoreIndex<0 && s.restoreTicks>=100)")
    probe_start=controller.find('if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPostRestoreBeaconBreakTest"))',completion_start)
    probe_end=controller.find("\n            for(var player:level.players())",probe_start)
    probe=controller[probe_start:probe_end] if probe_start>=0 and probe_end>probe_start else ""
    script=pathlib.Path(__file__).read_text(encoding="utf-8")
    if (not handler or "event.setCanceled(true);" not in handler
            or "level.setBlock(pos,Blocks.AIR.defaultBlockState(),3)" not in handler
            or "ciPendingNaturalBeaconBreakVerificationPos=pos.immutable();" not in handler
            or "NATURAL_BEACON_MINED_NO_DROP" not in handler
            or "private static void verifyCiNaturalBeaconBreakNoDrop(" not in controller
            or "verifyCiNaturalBeaconBreakNoDrop(level,s);" not in controller
            or "getEntitiesOfClass(" not in verify
            or "countItem(net.minecraft.world.item.Items.BEACON)" not in verify
            or "loadedOnly=true" not in verify
            or probe.count("new net.minecraftforge.event.level.BlockEvent.BreakEvent(")!=1
            or "testEvent" in probe
            or "POST_RESTORE_BEACON_BREAK_CONSUMED" in probe
            or 'subprocess.run(["xdotool","mousedown","1"]' not in script
            or "REAL_PLAYER_BEACON_BREAK_NO_DROP_VERIFIED" not in script
            or "beacon-after-left-click.png" not in script):
        raise RuntimeError("BLACK FREQUENCY post-restoration beacon mining must use real player input and verify zero drops")
    print("WORLD_CAPTURE: post-restoration beacon mining uses real left-click input and checks inventory/entity drops",flush=True)

verify_fissure_target_view_score_profile()
verify_restoration_particle_source()
verify_beacon_aura_source()

def valid_fissure_particle_emission(counts):
    if counts is None:
        return False
    samples,accent_dust,violet_dust,edge_dust,total=counts
    return (32<=samples<=FISSURE_PARTICLE_SAMPLES
            and 0<=accent_dust<=8
            and 0<=violet_dust==samples-accent_dust
            and 0<=edge_dust<=4
            and total==samples+edge_dust and total<=52
            and (samples!=FISSURE_PARTICLE_SAMPLES
                 or (accent_dust==8 and violet_dust==40 and edge_dust==4)))

_fissure_fixture=(
    "BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLES_EMITTED "
    "samples=48 accentDust=8 violetDust=40 edgeDust=4 total=52 "
    "heightmap=motion-blocking-no-leaves"
)
if (parse_fissure_particle_emission(_fissure_fixture)!=(48,8,40,4,52)
        or not valid_fissure_particle_emission((48,8,40,4,52))
        or not valid_fissure_particle_emission((32,4,28,2,34))
        or valid_fissure_particle_emission((48,8,40,5,53))
        or parse_fissure_particle_emission(_fissure_fixture.replace("violetDust","reversePortal")) is not None
        or parse_fissure_particle_emission(_fissure_fixture.replace("accentDust","dragonBreath")) is not None
        or parse_fissure_particle_emission(_fissure_fixture.replace("edgeDust","sh"+"ards")) is not None):
    raise RuntimeError("BLACK FREQUENCY fissure particle log/budget parser regression")
print("WORLD_CAPTURE: fissure particle log schema and budget verified",flush=True)
MC.mkdir(parents=True,exist_ok=True)

def status(x): print("[install]",x,flush=True)
cb={"setStatus":status,"setProgress":lambda x:None,"setMax":lambda x:None}
def _is_transient_forge_download_error(error):
    # urllib3 may wrap a broken socket write in ProtocolError/ChunkedEncodingError.
    # Walk the exception chain so the retry policy does not hide unrelated failures.
    pending=[error]
    visited=set()
    retryable_names={"ProtocolError","ChunkedEncodingError","ReadTimeoutError",
                     "ConnectTimeoutError","NewConnectionError","MaxRetryError"}
    while pending:
        current=pending.pop()
        if id(current) in visited:
            continue
        visited.add(id(current))
        if isinstance(current,(BrokenPipeError,ConnectionResetError,TimeoutError)):
            return True
        if type(current).__name__ in retryable_names:
            return True
        for attribute in ("__cause__","__context__"):
            nested=getattr(current,attribute,None)
            if isinstance(nested,BaseException):
                pending.append(nested)
    return False


def install_forge_with_retries(installer,version,directory,callback,sleep=time.sleep,max_attempts=3):
    if max_attempts<1:
        raise ValueError("max_attempts must be positive")
    for attempt in range(1,max_attempts+1):
        try:
            installer(version,directory,callback=callback)
            return
        except Exception as error:
            if not _is_transient_forge_download_error(error) or attempt==max_attempts:
                raise
            delay=min(8,2**attempt)
            print(f"[install] transient Forge library download interruption; retry {attempt+1}/{max_attempts} in {delay}s: {type(error).__name__}",flush=True)
            sleep(delay)


def verify_forge_download_retry_policy():
    class ProtocolError(Exception):
        pass

    def broken_pipe_error():
        error=ProtocolError("connection broken during library stream")
        error.__cause__=BrokenPipeError("simulated truncated artifact stream")
        return error

    calls=[]
    delays=[]
    def flaky_installer(version,directory,callback=None):
        calls.append(version)
        if len(calls)==1:
            raise broken_pipe_error()

    install_forge_with_retries(flaky_installer,"test-forge","test-minecraft",{},
                               sleep=delays.append)
    if calls!=["test-forge","test-forge"] or delays!=[2]:
        raise RuntimeError("BLACK FREQUENCY Forge download retry regression")

    calls.clear()
    delays.clear()
    def permanent_failure(version,directory,callback=None):
        calls.append(version)
        raise ValueError("invalid Forge metadata")

    try:
        install_forge_with_retries(permanent_failure,"test-forge","test-minecraft",{},
                                   sleep=delays.append)
    except ValueError:
        pass
    else:
        raise RuntimeError("BLACK FREQUENCY Forge retry must preserve permanent errors")
    if calls!=["test-forge"] or delays:
        raise RuntimeError("BLACK FREQUENCY permanent Forge error was retried")

    calls.clear()
    delays.clear()
    def persistent_network_failure(version,directory,callback=None):
        calls.append(version)
        raise ConnectionResetError("simulated network reset")

    try:
        install_forge_with_retries(persistent_network_failure,"test-forge","test-minecraft",{},
                                   sleep=delays.append,max_attempts=3)
    except ConnectionResetError:
        pass
    else:
        raise RuntimeError("BLACK FREQUENCY Forge retry must stop after its attempt budget")
    if calls!=["test-forge"]*3 or delays!=[2,4]:
        raise RuntimeError("BLACK FREQUENCY Forge retry attempt budget regression")
    print("WORLD_CAPTURE: Forge installer retries transient library-download interruptions only",flush=True)


verify_forge_download_retry_policy()


print("Installing Minecraft 1.20.1...",flush=True)
minecraft_launcher_lib.install.install_minecraft_version("1.20.1",str(MC),callback=cb)
forge="1.20.1-47.4.15"
print("Installing Forge",forge,flush=True)
install_forge_with_retries(minecraft_launcher_lib.forge.install_forge_version,forge,str(MC),cb)

versions=minecraft_launcher_lib.utils.get_installed_versions(str(MC))
ids=[v["id"] for v in versions]
cands=[x for x in ids if "47.4.15" in x and "1.20.1" in x]
if not cands:
    print("Installed versions:",ids,file=sys.stderr)
    raise SystemExit("Could not find installed Forge client version id")
version_id=cands[0]
print("Launching version:",version_id,flush=True)

opts=minecraft_launcher_lib.utils.generate_test_options()
opts.update({
    "username":"EHTID_CI",
    "executablePath":"java",
    "defaultExecutablePath":"java",
    "gameDirectory":str(MC),
    "jvmArguments":["-Xms1G","-Xmx6G","-Djava.awt.headless=false","-Dblackfrequency.ciTestMode=true","-Dblackfrequency.ciTickStep=2400"] + (["-Dblackfrequency.ciClientSmokeTest=true"] if os.environ.get("BF_TEST_RESTORATION")=="1" else []) + (["-Dblackfrequency.ciExerciseRestoration=true"] if os.environ.get("BF_TEST_RESTORATION")=="1" else []) + (["-Dblackfrequency.ciPlayerBeaconTest=true"] if os.environ.get("BF_TEST_PLAYER_BEACON")=="1" else []) + (["-Dblackfrequency.ciNaturalBeaconTest=true"] if os.environ.get("BF_TEST_NATURAL_BEACON")=="1" else []) + (["-Dblackfrequency.ciPostRestoreBeaconBreakTest=true"] if os.environ.get("BF_TEST_NATURAL_BEACON")=="1" else []) + (["-Dblackfrequency.ciSparseRecoveryTest=true"] if os.environ.get("BF_TEST_SPARSE_RECOVERY")=="1" else []) + (["-Dblackfrequency.ciSnowBeaconTest=true"] if os.environ.get("BF_TEST_SNOW_BEACON")=="1" else []),
    "customResolution":True,
    "resolutionWidth":"1280",
    "resolutionHeight":"720",
    "launcherName":"EHTID-GitHub-Client-Smoke",
    "launcherVersion":"1.0",
})
# X11/XTest cannot steer GLFW while raw mouse input is enabled. This is a
# disposable CI client, so use the regular cursor path for deterministic camera capture.
options_file=MC/"options.txt"
option_lines=options_file.read_text(encoding="utf-8",errors="replace").splitlines() if options_file.exists() else []
for option,value in (("rawMouseInput","false"),("mouseSensitivity",str(MOUSE_SENSITIVITY)),
                     ("pauseOnLostFocus","false")):
    prefix=option+":"
    for index,line in enumerate(option_lines):
        if line.startswith(prefix):
            option_lines[index]=prefix+value
            break
    else:
        option_lines.append(prefix+value)
options_file.write_text("\n".join(option_lines)+"\n",encoding="utf-8")
option_values={line.split(":",1)[0]:line.split(":",1)[1] for line in option_lines if ":" in line}
if option_values.get("pauseOnLostFocus")!="false":
    raise SystemExit("Could not disable Minecraft pauseOnLostFocus for deterministic integrated-server ticks")
print("WORLD_CAPTURE: raw mouse input disabled for deterministic X11 camera control",flush=True)
print("WORLD_CAPTURE: pauseOnLostFocus=false to keep restoration ticking during capture",flush=True)
# Xvfb pointer acceleration makes inverse relative moves drift away from the
# server-aimed beacon. Disable acceleration so the same pixel delta is reversible.
xset_path=shutil.which("xset")
xset_disabled=False
if xset_path:
    xset_result=subprocess.run([xset_path,"m","0","0"],check=False)
    xset_disabled=xset_result.returncode==0
print("WORLD_CAPTURE: X11 pointer acceleration disabled",xset_disabled,flush=True)

cmd=minecraft_launcher_lib.command.get_minecraft_command(version_id,str(MC),opts)
print("Command built; starting client under DISPLAY="+os.environ.get("DISPLAY",""),flush=True)

env=os.environ.copy()
env["LIBGL_ALWAYS_SOFTWARE"]="1"
env["MESA_LOADER_DRIVER_OVERRIDE"]="llvmpipe"

console=ROOT/"client-console.log"
p=subprocess.Popen(cmd,cwd=MC,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1,env=env)

def tee():
    with console.open("w",encoding="utf-8",errors="replace") as f:
        if p.stdout:
            for line in p.stdout:
                print(line,end="")
                f.write(line); f.flush()
threading.Thread(target=tee,daemon=True).start()

def capture_test_world():
    """Best-effort UI automation; confirm world entrance from the actual client log."""
    game_window=None
    def focus_game():
        if game_window:
            subprocess.run(["xdotool","windowactivate","--sync",game_window],
                           stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
    def key(k):
        focus_game()
        subprocess.run(["xdotool","key","--clearmodifiers",k],check=False)
    def click(x,y):
        # Hold/release explicitly: some GLFW clients ignore xdotool's fast click.
        focus_game()
        subprocess.run(["xdotool","mousemove","--sync",str(x),str(y)],check=False)
        time.sleep(0.25)
        subprocess.run(["xdotool","mousedown","1"],check=False)
        time.sleep(0.18)
        subprocess.run(["xdotool","mouseup","1"],check=False)
        print("WORLD_CAPTURE_INPUT: mouse click",x,y,flush=True)
    shots=ROOT/"client-smoke-artifacts"
    os.makedirs(shots,exist_ok=True)
    window=subprocess.run(["xdotool","search","--onlyvisible","--name","Minecraft"],capture_output=True,text=True).stdout.splitlines()
    game_window=window[-1] if window else None
    if game_window:
        focus_game()
        subprocess.run(["xdotool","windowsize",game_window,"1280","720"],check=False)
        subprocess.run(["xdotool","windowmove",game_window,"0","0"],check=False)
    def snap(name):
        path=shots/name
        # scrot creates a suffixed file instead of overwriting an existing path.
        # Remove the previous attempt so the artifact always contains the latest frame.
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        subprocess.run(["scrot","-a","0,0,1280,720",str(path)],check=False)
        focus_game()
    def move_camera(delta_y):
        focus_game()
        # GLFW recenters the hidden cursor while the game has mouse capture.
        # getmouselocation then reports the warp, not the XTest motion delivered
        # to Minecraft. Track successful relative-input requests and undo that
        # exact sum; the real beacon interaction remains the final aim check.
        direction=1 if delta_y>0 else -1
        remaining=abs(delta_y)
        moved=0
        while remaining:
            step=min(80,remaining)*direction
            result=subprocess.run(["xdotool","mousemove_relative","--sync","--","0",str(step)],check=False)
            if result.returncode!=0:
                break
            moved+=step
            remaining-=abs(step)
            time.sleep(0.08)
        print("WORLD_CAPTURE_INPUT: camera pitch mouse delta",delta_y,"moved",moved,flush=True)
        return moved
    def move_camera_horizontal(delta_x):
        focus_game()
        # Keep yaw motion separate from pitch so both can be restored exactly.
        direction=1 if delta_x>0 else -1
        remaining=abs(delta_x)
        moved=0
        while remaining:
            step=min(80,remaining)*direction
            result=subprocess.run(["xdotool","mousemove_relative","--sync","--",str(step),"0"],check=False)
            if result.returncode!=0:
                break
            moved+=step
            remaining-=abs(step)
            time.sleep(0.08)
        print("WORLD_CAPTURE_INPUT: camera yaw mouse delta",delta_x,"moved",moved,flush=True)
        return moved
    def camera_delta_for_pitch(current_pitch,target_pitch):
        # Minecraft 1.20.1 applies (sensitivity*0.6+0.2)^3*8*0.15 degrees per pixel.
        factor=MOUSE_SENSITIVITY*0.6+0.2
        degrees_per_pixel=factor*factor*factor*8.0*0.15
        return round((target_pitch-current_pitch)/degrees_per_pixel)
    def camera_delta_for_yaw(current_yaw,target_yaw):
        # Positive X mouse motion increases Minecraft yaw; use the shortest
        # signed correction across the -180/180 boundary.
        factor=MOUSE_SENSITIVITY*0.6+0.2
        degrees_per_pixel=factor*factor*factor*8.0*0.15
        delta=(target_yaw-current_yaw+180.0)%360.0-180.0
        return round(delta/degrees_per_pixel)

    def wrapped_angle_delta(current,target):
        return (current-target+180.0)%360.0-180.0

    def read_client_camera_rotation(log_text=None):
        if log_text is None:
            log_text=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
        matches=list(re.finditer(
            r"BLACK_FREQUENCY_TEST CLIENT_CAMERA_ROTATION yaw=([-+0-9.eE]+) pitch=([-+0-9.eE]+)",
            log_text))
        if not matches:
            return None
        yaw,pitch=map(float,matches[-1].groups())
        if not math.isfinite(yaw) or not math.isfinite(pitch):
            return None
        return yaw,pitch

    def read_beacon_aim(log_text=None):
        if log_text is None:
            log_text=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
        matches=list(re.finditer(
            r"BLACK_FREQUENCY_TEST BEACON_AIM_SET yaw=([-+0-9.eE]+) pitch=([-+0-9.eE]+)",
            log_text))
        if not matches:
            return None
        yaw,pitch=map(float,matches[-1].groups())
        if not math.isfinite(yaw) or not math.isfinite(pitch):
            return None
        return yaw,pitch

    def wait_for_camera_rotation(target_pitch=None,tolerance=2.5,timeout=1.5):
        deadline=time.monotonic()+timeout
        last=read_client_camera_rotation()
        while time.monotonic()<deadline:
            current=read_client_camera_rotation()
            if current is not None:
                last=current
                if target_pitch is None or abs(current[1]-target_pitch)<=tolerance:
                    return current
            time.sleep(0.05)
        return last

    def wait_for_camera_orientation(target_yaw,target_pitch,tolerance=3.0,timeout=2.0):
        deadline=time.monotonic()+timeout
        last=read_client_camera_rotation()
        while time.monotonic()<deadline:
            current=read_client_camera_rotation()
            if current is not None:
                last=current
                yaw_error=wrapped_angle_delta(current[0],target_yaw)
                pitch_error=current[1]-target_pitch
                if abs(yaw_error)<=tolerance and abs(pitch_error)<=tolerance:
                    return current
            time.sleep(0.05)
        return last

    def restore_camera_orientation(target_yaw,target_pitch):
        # Input sums are not a reliable camera state: server teleports, cursor
        # recentering and pitch clamping can change the view outside XTest input.
        # Re-read the rendered camera and close the remaining error instead.
        last=None
        for attempt in range(3):
            current=read_client_camera_rotation()
            if current is None:
                time.sleep(0.1)
                continue
            last=current
            yaw_error=wrapped_angle_delta(current[0],target_yaw)
            pitch_error=current[1]-target_pitch
            if abs(yaw_error)<=3.0 and abs(pitch_error)<=3.0:
                return current
            yaw_delta=camera_delta_for_yaw(current[0],target_yaw)
            pitch_delta=camera_delta_for_pitch(current[1],target_pitch)
            if yaw_delta==0 and pitch_delta==0:
                break
            if yaw_delta:
                moved=move_camera_horizontal(yaw_delta)
                if moved!=yaw_delta:
                    break
            if pitch_delta:
                moved=move_camera(pitch_delta)
                if moved!=pitch_delta:
                    break
            last=wait_for_camera_orientation(target_yaw,target_pitch,tolerance=3.0,timeout=2.0)
            if (last is not None
                    and abs(wrapped_angle_delta(last[0],target_yaw))<=3.0
                    and abs(last[1]-target_pitch)<=3.0):
                return last
        return last if last is not None else read_client_camera_rotation()

    def aim_camera_at_beacon(log_text=None):
        target=read_beacon_aim(log_text)
        if target is None:
            print("WORLD_CAPTURE: beacon aim telemetry is not ready; deferring click",flush=True)
            return False
        current=read_client_camera_rotation()
        if current is None:
            print("WORLD_CAPTURE: rendered camera telemetry is not ready; deferring beacon click",flush=True)
            return False
        if (abs(wrapped_angle_delta(current[0],target[0]))>3.0
                or abs(current[1]-target[1])>3.0):
            current=restore_camera_orientation(target[0],target[1])
        if (current is None
                or abs(wrapped_angle_delta(current[0],target[0]))>3.0
                or abs(current[1]-target[1])>3.0):
            print("WORLD_CAPTURE_FAIL: rendered camera did not reach the physical beacon",
                  current,"expected",target,flush=True)
            return False
        print("WORLD_CAPTURE: physical beacon camera aim verified",
              round(current[0],2),round(current[1],2),flush=True)
        return True

    camera_sample="BLACK_FREQUENCY_TEST CLIENT_CAMERA_ROTATION yaw=179.0 pitch=26.5"
    if (read_client_camera_rotation(camera_sample)!=(179.0,26.5)
            or abs(wrapped_angle_delta(-179.0,179.0)-2.0)>1.0E-9):
        raise RuntimeError("BLACK FREQUENCY client camera telemetry parser regression")
    beacon_aim_sample="BLACK_FREQUENCY_TEST BEACON_AIM_SET yaw=-7.6293945E-6 pitch=54.20249"
    parsed_beacon_aim=read_beacon_aim(beacon_aim_sample)
    if (parsed_beacon_aim is None
            or abs(parsed_beacon_aim[0]+7.6293945E-6)>1.0E-9
            or abs(parsed_beacon_aim[1]-54.20249)>1.0E-6):
        raise RuntimeError("BLACK FREQUENCY beacon aim telemetry parser regression")
    camera_direction_cases=(
        camera_delta_for_pitch(26.46307,0.0)<0,
        camera_delta_for_pitch(0.0,-65.0)<0,
        camera_delta_for_pitch(-65.0,26.46307)>0,
        camera_delta_for_yaw(0.0,-178.0)<0,
        camera_delta_for_yaw(-178.0,0.0)>0,
        camera_delta_for_yaw(179.0,-179.0)>0,
    )
    if not all(camera_direction_cases):
        raise RuntimeError("BLACK FREQUENCY camera correction direction regression")
    print("WORLD_CAPTURE: client camera telemetry, beacon aim parser and correction directions verified",flush=True)
    def is_rift_violet_pixel(r,g,b):
        # Reject pale lavender UI/death-screen skies; the rift must be saturated violet.
        return r>55 and b>80 and b>g*1.5 and r>g*1.5 and b-r<150
    def is_ground_violet_dust_pixel(r,g,b):
        # Base Dust has a low green/red ratio; keep it separate from the brighter accent.
        return (25 <= r <= 125 and g <= r*.32 and b >= 130
                and b-r >= 60 and b > r*1.7)
    def is_ground_shaded_violet_dust_pixel(r,g,b):
        # In-game fog/particle lighting darkens the same cached Dust option.
        # Keep this lower-intensity range separate from the ground-frame classifier.
        return (25 <= r <= 90 and g <= r*.60 and b >= 115
                and b-r >= 60 and b > r*1.7)
    def is_ground_fissure_accent_pixel(r,g,b):
        # Accent Dust uses more green relative to red and a higher blue channel.
        return (25 <= r <= 105 and g >= r*.36 and g <= 80 and b >= 145
                and b-r >= 70 and b > r*1.7)
    def is_ground_fissure_pixel(r,g,b):
        return is_ground_violet_dust_pixel(r,g,b) or is_ground_fissure_accent_pixel(r,g,b)
    def is_ground_mesh_pixel(r,g,b):
        # The mesh edge is a restrained purple, distinct from both cached Dust colors.
        return (105 <= r <= 155 and 40 <= g <= 100 and b >= 165
                and b-r >= 45 and b > r*1.25)
    # Samples include deep-violet Dust and the darker mesh edge; reject legacy magenta.
    if (is_rift_violet_pixel(142,127,155)
            or not is_rift_violet_pixel(134,44,150)
            or not is_rift_violet_pixel(117,46,209)
            or is_ground_fissure_pixel(142,127,155)
            or is_ground_fissure_pixel(134,44,150)
            or not is_ground_violet_dust_pixel(87,20,199)
            or not is_ground_shaded_violet_dust_pixel(42,18,125)
            or not is_ground_shaded_violet_dust_pixel(53,24,130)
            or not is_ground_shaded_violet_dust_pixel(59,26,161)
            or is_ground_shaded_violet_dust_pixel(42,30,125)
            or is_ground_shaded_violet_dust_pixel(42,18,100)
            or is_ground_violet_dust_pixel(77,36,224)
            or is_ground_violet_dust_pixel(207,0,239)
            or not is_ground_fissure_accent_pixel(77,36,224)
            or is_ground_fissure_accent_pixel(87,20,199)
            or is_ground_fissure_accent_pixel(201,66,214)
            or is_ground_fissure_pixel(201,66,214)
            or not is_ground_mesh_pixel(117,46,209)
            or not is_ground_mesh_pixel(130,55,220)
            or is_ground_mesh_pixel(158,26,250)
            or is_ground_mesh_pixel(198,0,229)
            or is_ground_mesh_pixel(201,66,214)
            or is_ground_mesh_pixel(142,127,155)):
        raise RuntimeError("BLACK FREQUENCY pixel classifier regression")
    print("WORLD_CAPTURE: saturated violet pixel classifier verified",flush=True)

    def has_continuous_sky_band(longest_runs, minimum_run, minimum_rows=6):
        return (max(longest_runs,default=0)>=minimum_run
                and sum(1 for run in longest_runs if run>=minimum_run)>=minimum_rows)

    # Isolated portal particles can be vivid violet, but they are not a sky tear.
    # Require a continuous shader-colored band across several rows.
    if (not has_continuous_sky_band([64]*6,64)
            or has_continuous_sky_band([32]*129,64)
            or has_continuous_sky_band([55]+[0]*128,64)):
        raise RuntimeError("BLACK FREQUENCY continuous sky band classifier regression")
    print("WORLD_CAPTURE: continuous sky band classifier verified",flush=True)
    def has_sky_background(dark_coverage):
        return math.isfinite(dark_coverage) and dark_coverage>=.08

    # Measured from the real 20e05e3 client artifacts: the upper-sky frame
    # contained 11.8% dark sky pixels; the false-positive ground frame 4.8%.
    if not has_sky_background(.117) or has_sky_background(.048):
        raise RuntimeError("BLACK FREQUENCY sky-background classifier regression")
    print("WORLD_CAPTURE: dark sky background classifier verified",flush=True)

    def smoothstep01(edge0,edge1,value):
        x=max(0.0,min(1.0,(value-edge0)/(edge1-edge0)))
        return x*x*(3.0-2.0*x)

    shader_path=ROOT/"addon"/"src"/"main"/"resources"/"assets"/"blackfrequency"/"shaders"/"core"/"sky_rift.fsh"
    shader_source=shader_path.read_text(encoding="utf-8")
    def lightning_power_twelve(value):
        base=max(0.0,min(1.0,value))
        squared=base*base
        fourth=squared*squared
        eighth=fourth*fourth
        return eighth*fourth

    lightning_samples=(-0.5,0.0,0.125,0.5,0.9,1.0,1.5)
    lightning_equivalent=all(
        math.isclose(lightning_power_twelve(value),
                     max(0.0,min(1.0,value))**12,
                     rel_tol=1.0e-12,abs_tol=1.0e-15)
        for value in lightning_samples)
    lightning_chain=(
        "float lightningBase=clamp(lightningWave,0.,1.);",
        "float lightningSquared=lightningBase*lightningBase;",
        "float lightningFourth=lightningSquared*lightningSquared;",
        "float lightningEighth=lightningFourth*lightningFourth;",
        "float lightningFlash=lightningEighth*lightningFourth;",
    )
    if (not lightning_equivalent
            or any(token not in shader_source for token in lightning_chain)
            or "pow(" in shader_source):
        raise RuntimeError("BLACK FREQUENCY sky lightning multiply-chain regression")
    print("WORLD_CAPTURE: bounded sky lightning x^12 multiply chain verified",
          {"samples": len(lightning_samples), "genericPowCalls": 0},flush=True)
    if "vec3 edge=vec3(.46,.18,.82);" not in shader_source:
        raise RuntimeError("BLACK FREQUENCY sky edge palette regression")
    filament_mask_samples=[
        smoothstep01(.025,.18,0.0),
        smoothstep01(.025,.18,.025),
        smoothstep01(.025,.18,.10),
        smoothstep01(.025,.18,.18),
        smoothstep01(.025,.18,1.0),
    ]
    gate_start=shader_source.find("if(fractureField>.025){")
    gate_end=shader_source.find("\n }",gate_start)
    filament_gate=shader_source[gate_start:gate_end+3] if gate_start>=0 and gate_end>=0 else ""
    cell_call_count=shader_source.count("cells(n.")
    if (not all(token in shader_source for token in (
            "float fractureField=max(majorGlow,zenithGlow);",
            "float filaments=0.;",
            "if(fractureField>.025){",
            "float filamentMask=smoothstep(.025,.18,fractureField);",
            "filaments=(1.-smoothstep(.008,.023,cellDistance))*filamentMask;"))
            or cell_call_count!=3
            or filament_gate.count("cells(n.")!=3
            or "float filaments=0.;" not in shader_source[:gate_start]
            or filament_mask_samples[0]!=0.0
            or filament_mask_samples[1]!=0.0
            or not (0.0<filament_mask_samples[2]<1.0)
            or filament_mask_samples[3]!=1.0
            or filament_mask_samples[4]!=1.0):
        raise RuntimeError("BLACK FREQUENCY fracture-gated sky filament regression")
    print("WORLD_CAPTURE: fracture-gated sky filaments verified",
          {"mask": [round(value,4) for value in filament_mask_samples],
           "voronoiSearches": cell_call_count,
           "gateThreshold": 0.025},flush=True)

    def sky_capture_summary(horizon_visible, upper_visible):
        if horizon_visible and upper_visible:
            return "horizon+upper"
        if upper_visible:
            return "upper-only"
        if horizon_visible:
            return "horizon-only"
        return "none"

    expected_summaries=[
        (True,True,"horizon+upper"),
        (False,True,"upper-only"),
        (True,False,"horizon-only"),
        (False,False,"none"),
    ]
    if any(sky_capture_summary(horizon,upper)!=expected
           for horizon,upper,expected in expected_summaries):
        raise RuntimeError("BLACK FREQUENCY sky capture summary regression")
    print("WORLD_CAPTURE: separate horizon/upper visibility summary verified",flush=True)

    def measure_ground_visibility(frame):
        width,height=frame.size
        pixels=frame.load()
        left,right=int(width*.20),int(width*.80)
        top,bottom=int(height*.40),int(height*.90)
        dust_pixels=accent_pixels=particle_pixels=mesh_pixels=combined_pixels=0
        dust_tiles=accent_tiles=particle_tiles=mesh_tiles=combined_tiles=0
        tile_size=32
        for tile_y in range(top,bottom,tile_size):
            for tile_x in range(left,right,tile_size):
                dust_hits=accent_hits=particle_hits=mesh_hits=combined_hits=0
                for y in range(tile_y,min(tile_y+tile_size,bottom)):
                    for x in range(tile_x,min(tile_x+tile_size,right)):
                        r,g,b=pixels[x,y]
                        dust=is_ground_violet_dust_pixel(r,g,b)
                        accent=is_ground_fissure_accent_pixel(r,g,b)
                        particle=dust or accent
                        mesh=is_ground_mesh_pixel(r,g,b)
                        if dust:
                            dust_pixels+=1
                            dust_hits+=1
                        if accent:
                            accent_pixels+=1
                            accent_hits+=1
                        if particle:
                            particle_pixels+=1
                            particle_hits+=1
                        if mesh:
                            mesh_pixels+=1
                            mesh_hits+=1
                        if particle or mesh:
                            combined_pixels+=1
                            combined_hits+=1
                if dust_hits>=3:
                    dust_tiles+=1
                if accent_hits>=3:
                    accent_tiles+=1
                if particle_hits>=4:
                    particle_tiles+=1
                if mesh_hits>=3:
                    mesh_tiles+=1
                if combined_hits>=4:
                    combined_tiles+=1
        return {
            "dustPixels":dust_pixels,
            "dustTiles":dust_tiles,
            "accentPixels":accent_pixels,
            "accentTiles":accent_tiles,
            "particlePixels":particle_pixels,
            "particleTiles":particle_tiles,
            "meshPixels":mesh_pixels,
            "meshTiles":mesh_tiles,
            "combinedPixels":combined_pixels,
            "combinedTiles":combined_tiles,
        }

    def ground_visibility_passes(metrics):
        # Normal terrain can hide one or more outer arms behind slopes or plants.
        # Accept a distributed base-Dust signal across two 32px tiles, but only
        # when the mesh independently spans multiple tiles; mesh-only and
        # particle-only captures remain invalid.
        visible_mesh=(metrics["combinedPixels"]>=160 and metrics["combinedTiles"]>=6
                      and metrics["meshPixels"]>=24 and metrics["meshTiles"]>=2)
        distributed_dust=(metrics["dustPixels"]>=96 and metrics["dustTiles"]>=2
                          and metrics["particlePixels"]>=96 and metrics["particleTiles"]>=2)
        edge_accent=(metrics["accentPixels"]>=3 and metrics["accentTiles"]>=1
                     and metrics["particlePixels"]>=8 and metrics["particleTiles"]>=1)
        return visible_mesh and (distributed_dust or edge_accent)

    _ground_visibility_distributed_dust_no_accent = {
        "dustPixels":96, "dustTiles":2,
        "accentPixels":0, "accentTiles":0,
        "particlePixels":96, "particleTiles":2,
        "combinedPixels":160, "combinedTiles":6,
        "meshPixels":24, "meshTiles":2,
    }
    # Regression fixture from the latest Normal capture: uneven terrain leaves
    # the visible base-Dust plume in two tiles while the ribbon spans twelve.
    _ground_visibility_normal_fallback_capture = {
        "dustPixels":205, "dustTiles":2,
        "accentPixels":0, "accentTiles":0,
        "particlePixels":205, "particleTiles":2,
        "combinedPixels":673, "combinedTiles":12,
        "meshPixels":468, "meshTiles":10,
    }
    # A real normal-terrain capture may show only the four sparse edge accents
    # plus the continuous ribbon; require both spatially, not the whole Dust plume.
    _ground_visibility_edge_only = {
        "dustPixels":0, "dustTiles":0,
        "accentPixels":10, "accentTiles":2,
        "particlePixels":10, "particleTiles":1,
        "combinedPixels":6186, "combinedTiles":27,
        "meshPixels":6176, "meshTiles":27,
    }
    _ground_visibility_below_distributed_dust_pixel_floor = dict(_ground_visibility_distributed_dust_no_accent)
    _ground_visibility_below_distributed_dust_pixel_floor["dustPixels"] = 95
    _ground_visibility_below_distributed_dust_pixel_floor["particlePixels"] = 95
    _ground_visibility_below_distributed_dust_tile_floor = dict(_ground_visibility_distributed_dust_no_accent)
    _ground_visibility_below_distributed_dust_tile_floor["dustTiles"] = 1
    _ground_visibility_below_distributed_dust_tile_floor["particleTiles"] = 1
    _ground_visibility_below_edge_floor = dict(_ground_visibility_edge_only)
    _ground_visibility_below_edge_floor["particlePixels"] = 7
    _ground_visibility_below_edge_floor["particleTiles"] = 0
    _ground_visibility_without_accent_tile = dict(_ground_visibility_edge_only)
    _ground_visibility_without_accent_tile["accentTiles"] = 0
    _ground_visibility_without_mesh = dict(_ground_visibility_edge_only)
    _ground_visibility_without_mesh["meshPixels"] = 23
    _ground_visibility_without_mesh["meshTiles"] = 1
    if (not ground_visibility_passes(_ground_visibility_distributed_dust_no_accent)
            or not ground_visibility_passes(_ground_visibility_normal_fallback_capture)
            or not ground_visibility_passes(_ground_visibility_edge_only)
            or ground_visibility_passes(_ground_visibility_below_distributed_dust_pixel_floor)
            or ground_visibility_passes(_ground_visibility_below_distributed_dust_tile_floor)
            or ground_visibility_passes(_ground_visibility_below_edge_floor)
            or ground_visibility_passes(_ground_visibility_without_accent_tile)
            or ground_visibility_passes(_ground_visibility_without_mesh)):
        raise RuntimeError("BLACK FREQUENCY ground fissure sparse visibility regression")
    print("WORLD_CAPTURE: two-tile distributed-Dust and edge-accent ground visibility profiles verified",flush=True)

    def is_compact_restoration_dust_component(component_size,width,height):
        # Fog and the sparse-world camera can render a real Dust mote only 6-7
        # pixels wide. Keep the 24px noise floor, but reject broad sky-rift ribbons
        # by shape instead of requiring an unnecessarily wide particle sprite.
        return (24<=component_size<=512 and 6<=width<=32 and 6<=height<=32)

    if (not is_compact_restoration_dust_component(300,20,25)
            or not is_compact_restoration_dust_component(44,7,11)
            or not is_compact_restoration_dust_component(39,7,10)
            or is_compact_restoration_dust_component(18,6,6)
            or is_compact_restoration_dust_component(1130,41,51)
            or is_compact_restoration_dust_component(300,33,24)
            or is_compact_restoration_dust_component(300,24,33)
            or is_compact_restoration_dust_component(23,20,25)
            or is_compact_restoration_dust_component(513,26,25)):
        raise RuntimeError("BLACK FREQUENCY restoration Dust component classifier regression")

    def measure_restoration_palette(frame):
        width,height=frame.size
        pixels=frame.load()
        # The capture camera is aimed at the activated physical beacon. Measure
        # a central, bounded window and count only small connected violet groups:
        # broad sky-rift ribbons must not masquerade as restoration Dust.
        left,right=int(width*.30),int(width*.70)
        top,bottom=int(height*.18),int(height*.70)
        legacy_magenta=0
        violet_points=set()
        for y in range(top,bottom):
            for x in range(left,right):
                r,g,b=pixels[x,y]
                if r>150 and b>150 and g<100 and abs(r-b)<90:
                    legacy_magenta+=1
                if (is_ground_violet_dust_pixel(r,g,b)
                        or is_ground_shaded_violet_dust_pixel(r,g,b)
                        or is_ground_fissure_accent_pixel(r,g,b)):
                    violet_points.add((x,y))
        # Dust sprites form small clusters; connected ribbons from the sky
        # renderer are larger and are deliberately excluded from this metric.
        violet_dust=0
        while violet_points:
            pending=[violet_points.pop()]
            component_size=0
            min_x=min_y=10**9
            max_x=max_y=-(10**9)
            while pending:
                x,y=pending.pop()
                component_size+=1
                min_x=min(min_x,x)
                min_y=min(min_y,y)
                max_x=max(max_x,x)
                max_y=max(max_y,y)
                for neighbor_y in range(max(top,y-1),min(bottom,y+2)):
                    for neighbor_x in range(max(left,x-1),min(right,x+2)):
                        point=(neighbor_x,neighbor_y)
                        if point in violet_points:
                            violet_points.remove(point)
                            pending.append(point)
            component_width=max_x-min_x+1
            component_height=max_y-min_y+1
            if is_compact_restoration_dust_component(
                    component_size,component_width,component_height):
                violet_dust+=component_size
        return {"legacyMagentaPixels":legacy_magenta,"violetDustPixels":violet_dust}

    def restoration_palette_passes(metrics):
        return (metrics["legacyMagentaPixels"]<=200
                and 24<=metrics["violetDustPixels"]<=35000)

    def restoration_palette_evidence_passes(active_metrics,finished_metrics):
        # The active frame can be occluded by the disposable safe pad or nearby
        # village foliage. The completion frame is captured from the same player
        # view after the bounded recovery pulses have had time to render.
        # Require real violet Dust in at least one frame; never accept magenta-only.
        return (restoration_palette_passes(active_metrics)
                or restoration_palette_passes(finished_metrics))

    def measure_completion_title_width(frame):
        width,height=frame.size
        top,bottom=int(height*.28),int(height*.55)
        pixels=frame.load()
        column_counts=[0]*width
        for y in range(top,bottom):
            for x in range(width):
                red,green,blue=pixels[x,y]
                if red>=65 and green<=45 and blue>=145 and blue-red>=70:
                    column_counts[x]+=1
        active=[x for x,count in enumerate(column_counts) if count>=6]
        if not active:
            return {"left":0,"right":0,"width":0,"fraction":0.0,"centerOffset":float("inf")}
        left,right=active[0],active[-1]
        title_width=right-left+1
        return {"left":left,"right":right,"width":title_width,
                "fraction":title_width/width,
                "centerOffset":abs((left+right)/2-width/2)}

    def completion_title_width_passes(metrics,frame_width):
        return (200<=metrics["width"]<=frame_width*.78
                and metrics["centerOffset"]<=frame_width*.08)

    from PIL import Image,ImageDraw
    _compact_title_fixture=Image.new("RGB",(1280,720),(0,0,0))
    ImageDraw.Draw(_compact_title_fixture).rectangle((300,240,980,320),fill=(86,20,197))
    _wide_title_fixture=Image.new("RGB",(1280,720),(0,0,0))
    ImageDraw.Draw(_wide_title_fixture).rectangle((50,240,1230,320),fill=(86,20,197))
    _blank_title_fixture=Image.new("RGB",(1280,720),(0,0,0))
    if (not completion_title_width_passes(
            measure_completion_title_width(_compact_title_fixture),1280)
            or completion_title_width_passes(
                measure_completion_title_width(_wide_title_fixture),1280)
            or completion_title_width_passes(
                measure_completion_title_width(_blank_title_fixture),1280)):
        raise RuntimeError("BLACK FREQUENCY compact completion-title width classifier regression")
    print("WORLD_CAPTURE: completion title width classifier verified",flush=True)


    _palette_valid={"legacyMagentaPixels":0,"violetDustPixels":1000}
    _palette_missing={"legacyMagentaPixels":0,"violetDustPixels":0}
    if (not restoration_palette_passes(_palette_valid)
            or not restoration_palette_passes({"legacyMagentaPixels":0,"violetDustPixels":24})
            or restoration_palette_passes({"legacyMagentaPixels":0,"violetDustPixels":23})
            or restoration_palette_passes({"legacyMagentaPixels":0,"violetDustPixels":35001})
            or restoration_palette_passes({"legacyMagentaPixels":201,"violetDustPixels":1000})
            or not restoration_palette_evidence_passes(_palette_valid,_palette_missing)
            or not restoration_palette_evidence_passes(_palette_missing,_palette_valid)
            or restoration_palette_evidence_passes(_palette_missing,_palette_missing)):
        raise RuntimeError("BLACK FREQUENCY restoration palette density/evidence guard regression")

    def verify_sky_pixels(name, region, minimum, max_green=None):
        from PIL import Image
        path=shots/name
        if not path.is_file():
            print("WORLD_CAPTURE_FAIL: missing sky frame",name,flush=True)
            return False
        with Image.open(path) as source:
            frame=source.convert("RGB")
        width,height=frame.size
        # Spawn terrain and tree crowns vary between Normal and Sparse worlds.
        # Search overlapping sky windows instead of hard-coding one screen strip.
        window_width=max(1,int(width*.22))
        window_height=max(1,int(height*.18))
        step_x=max(1,int(width*.05))
        step_y=max(1,int(height*.05))
        search_bottom=int(height*.42)
        best=None
        best_valid=None
        for top in range(0,max(1,search_bottom-window_height+1),step_y):
            for left in range(0,max(1,width-window_width+1),step_x):
                crop=frame.crop((left,top,left+window_width,top+window_height))
                pixels=list(crop.get_flattened_data())
                purple=0
                green=0
                dark_sky=0
                purple_rows=[]
                purple_longest_runs=[]
                for y in range(crop.height):
                    row_purple=0
                    row_longest=0
                    current_run=0
                    start=y*crop.width
                    for r,g,b in pixels[start:start+crop.width]:
                        if is_rift_violet_pixel(r,g,b):
                            purple+=1
                            row_purple+=1
                            current_run+=1
                            row_longest=max(row_longest,current_run)
                        else:
                            current_run=0
                        if g>r*1.15 and g>b*1.15 and g>25:
                            green+=1
                        # Ground cracks can contain the same violet pixels as the sky shader.
                        # Require a real dark sky field behind the tear, not just purple geometry.
                        if max(r,g,b)<50 and b>=g:
                            dark_sky+=1
                    purple_rows.append(row_purple)
                    purple_longest_runs.append(row_longest)
                coverage=purple/(crop.width*crop.height)
                green_coverage=green/(crop.width*crop.height)
                dark_sky_coverage=dark_sky/(crop.width*crop.height)
                score=coverage-.35*green_coverage
                row_threshold=max(6,int(crop.width*.01))
                peak_threshold=max(12,int(crop.width*.025))
                min_contiguous_run=max(64,int(crop.width*.22))
                line_rows=sum(1 for count in purple_rows if count>=row_threshold)
                peak_row=max(purple_rows,default=0)
                continuous_rows=sum(1 for run in purple_longest_runs if run>=min_contiguous_run)
                max_contiguous_run=max(purple_longest_runs,default=0)
                candidate=(score,coverage,green_coverage,dark_sky_coverage,line_rows,peak_row,
                           continuous_rows,max_contiguous_run,left,top,peak_threshold,min_contiguous_run)
                line_visible=(line_rows>=8 and peak_row>=peak_threshold
                              and has_continuous_sky_band(purple_longest_runs,min_contiguous_run))
                valid=(coverage>=minimum and dark_sky_coverage>=.08 and line_visible
                       and (max_green is None or green_coverage<=max_green))
                if best is None or candidate[0]>best[0]:
                    best=candidate
                # The highest-scoring window may contain too much foliage even
                # when a lower-scoring window satisfies every visibility rule.
                # Prefer the best qualifying window; retain the overall best only
                # for diagnostics when no window actually passes.
                if valid and (best_valid is None or candidate[0]>best_valid[0]):
                    best_valid=candidate
        if best is None:
            print("WORLD_CAPTURE_FAIL: no sky sample window",name,flush=True)
            return False
        selected=best_valid if best_valid is not None else best
        (score,coverage,green_coverage,dark_sky_coverage,line_rows,peak_row,continuous_rows,
         max_contiguous_run,left,top,peak_threshold,min_contiguous_run)=selected
        line_visible=(line_rows>=8 and peak_row>=peak_threshold
                       and continuous_rows>=6 and max_contiguous_run>=min_contiguous_run)
        visible=best_valid is not None
        selection="valid-window" if visible else "best-diagnostic-window"
        print(f"WORLD_CAPTURE: SKY_PIXEL_COVERAGE {name}={coverage:.4f} green={green_coverage:.4f} darkSky={dark_sky_coverage:.4f} lineRows={line_rows} peakRow={peak_row} continuousRows={continuous_rows} maxRun={max_contiguous_run} minRun={min_contiguous_run} window={left},{top} required={minimum:.4f} minDarkSky=0.0800 maxGreen={max_green} visible={visible} selection={selection}",flush=True)
        return visible
    beacon_attempts=0
    beacon_ready_at=None
    last_beacon_attempt=0
    beacon_aim_verified=False
    def try_beacon_click(logs):
        nonlocal beacon_attempts,beacon_ready_at,last_beacon_attempt,beacon_aim_verified
        if os.environ.get("BF_TEST_PLAYER_BEACON")!="1":
            return
        # Do not begin mouse automation until the server confirms that the
        # disposable player is protected from random gravity deaths.
        if ("BLACK_FREQUENCY_TEST PLAYER_RIFT_IMPULSES_SUPPRESSED_FOR_BEACON_TEST" not in logs
                or "BLACK_FREQUENCY_TEST PLAYER_VANILLA_FALL_SUPPRESSED_FOR_BEACON_TEST" not in logs
                or "BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT" not in logs
                or "BLACK_FREQUENCY_TEST PLAYER_VANILLA_FALL_RESTORED_AFTER_SAFE_PAD" not in logs):
            return
        if "BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION" in logs:
            return
        if "BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER" not in logs:
            return
        if "BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_VERIFIED hit=beacon loadedChunksOnly=true" not in logs:
            return
        if (os.environ.get("BF_TEST_NATURAL_BEACON")=="1"
                and "BLACK_FREQUENCY_TEST BEACON_SKY_CLEARANCE_VERIFIED" not in logs):
            return
        now=time.monotonic()
        if beacon_ready_at is None:
            beacon_ready_at=now
            print("WORLD_CAPTURE: beacon ready; waiting for client teleport",flush=True)
        # Both Normal and Sparse must capture the real ground fissure before
        # the physical beacon is activated by the player's right-click.
        if not terrain_shot:
            return
        retry_delay=0.75 if "BLACK_FREQUENCY_TEST PLAYER_BEACON_AIM_RECOVERED_AFTER_MISS" in logs else 2.0
        if now-beacon_ready_at<1.5 or now-last_beacon_attempt<retry_delay or beacon_attempts>=8:
            return
        # Capture the active rift before clicking, then aim back at the server's
        # measured beacon rotation. Restoring the fissure/sky view is not a valid
        # substitute for aiming at the physical block.
        if not sky_capture_attempted:
            capture_sky_before_beacon()
        fresh_logs=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else logs
        if not aim_camera_at_beacon(fresh_logs):
            return
        beacon_aim_verified=True
        if beacon_attempts==0:
            snap("beacon-before-right-click.png")
        snap("beacon-aim-restored.png")
        # Actual mouse input, not a synthetic server event.
        focus_game()
        subprocess.run(["xdotool","mousedown","3"],check=False)
        time.sleep(0.25)
        subprocess.run(["xdotool","mouseup","3"],check=False)
        beacon_attempts+=1
        last_beacon_attempt=now
        print("WORLD_CAPTURE_INPUT: player right-click attempt",beacon_attempts,flush=True)
        # The old capture raced the integrated server and often saved the prompt
        # frame before the real interaction packet reached the server. Wait briefly
        # for accepted activation and its action-bar response, but keep the sparse
        # restoration window bounded.
        interaction_deadline=time.monotonic()+3.0
        interaction_confirmed=False
        while time.monotonic()<interaction_deadline and p.poll() is None:
            interaction_logs=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            if ("BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION" in interaction_logs
                    and "BLACK_FREQUENCY_TEST BEACON_START_ACTIONBAR_ONLY" in interaction_logs
                    and "BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED" in interaction_logs):
                interaction_confirmed=True
                time.sleep(0.35)
                break
            time.sleep(0.1)
        if interaction_confirmed:
            print("WORLD_CAPTURE: physical beacon activation confirmed before screenshot",flush=True)
            snap("beacon-after-right-click.png")
        else:
            # A missed click is recoverable; never publish its stale prompt frame as
            # evidence of activation. The outer loop will retry after aim recovery.
            print("WORLD_CAPTURE: after-click screenshot deferred until physical activation is confirmed",flush=True)
        # The final world check still independently requires the server's real
        # interaction marker; the screenshot wait is not a substitute for it.
    def mine_beacon_after_restoration(log_text):
        if os.environ.get("BF_TEST_NATURAL_BEACON")!="1":
            return True
        protection_deadline=time.monotonic()+2.0
        while ("BLACK_FREQUENCY_TEST CANCELED_BEACON_BREAK_PRESERVED" not in log_text
               and time.monotonic()<protection_deadline and p.poll() is None):
            time.sleep(.1)
            log_text=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else log_text
        if "BLACK_FREQUENCY_TEST CANCELED_BEACON_BREAK_PRESERVED" not in log_text:
            print("WORLD_CAPTURE_FAIL: protection-canceled break did not preserve the recovery beacon",flush=True)
            return False
        if ("BLACK_FREQUENCY_TEST NATURAL_BEACON_MINED_NO_DROP" in log_text
                or "BLACK_FREQUENCY_TEST REAL_PLAYER_BEACON_BREAK_NO_DROP_VERIFIED" in log_text):
            print("WORLD_CAPTURE_FAIL: beacon was consumed before the real left-click test",flush=True)
            return False
        if not aim_camera_at_beacon(log_text):
            return False
        snap("beacon-before-left-click.png")
        if not (shots/"beacon-before-left-click.png").is_file():
            print("WORLD_CAPTURE_FAIL: pre-mining beacon screenshot was not captured",flush=True)
            return False
        log_before=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else log_text
        marker_offset=len(log_before)
        focus_game()
        down=subprocess.run(["xdotool","mousedown","1"],check=False)
        if down.returncode!=0:
            print("WORLD_CAPTURE_FAIL: could not hold the left mouse button for beacon mining",flush=True)
            return False
        print("WORLD_CAPTURE_INPUT: player left-click hold started",flush=True)
        deadline=time.monotonic()+10.0
        observed=False
        try:
            while time.monotonic()<deadline and p.poll() is None:
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                delta=current[marker_offset:]
                if ("BLACK_FREQUENCY_TEST NATURAL_BEACON_MINED_NO_DROP" in delta
                        and "BLACK_FREQUENCY_TEST REAL_PLAYER_BEACON_BREAK_NO_DROP_VERIFIED" in delta):
                    observed=True
                    break
                time.sleep(.1)
        finally:
            subprocess.run(["xdotool","mouseup","1"],check=False)
        if not observed:
            current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            delta=current[marker_offset:]
            print("WORLD_CAPTURE_FAIL: real left-click did not remove the beacon and verify zero drops",
                  {"minedMarker":"BLACK_FREQUENCY_TEST NATURAL_BEACON_MINED_NO_DROP" in delta,
                   "noDropMarker":"BLACK_FREQUENCY_TEST REAL_PLAYER_BEACON_BREAK_NO_DROP_VERIFIED" in delta},
                  flush=True)
            return False
        time.sleep(.25)
        snap("beacon-after-left-click.png")
        if not (shots/"beacon-after-left-click.png").is_file():
            print("WORLD_CAPTURE_FAIL: post-mining beacon screenshot was not captured",flush=True)
            return False
        print("WORLD_CAPTURE: real player left-click removed the owned beacon without an item drop",flush=True)
        return True

    time.sleep(2)
    print("WORLD_CAPTURE_INPUT: active window",
        subprocess.run(["xdotool","getactivewindow","getwindowname"],
                       capture_output=True,text=True).stdout.strip(),flush=True)
    print("WORLD_CAPTURE_INPUT: mouse position",
        subprocess.run(["xdotool","getmouselocation","--shell"],
                       capture_output=True,text=True).stdout.strip(),flush=True)
    snap("01-main-menu.png")
    if os.environ.get("BF_ISOLATED_TEST")=="1":
        # Fresh vanilla install displays a first-run accessibility welcome overlay.
        # It must be dismissed before the underlying Singleplayer button can receive clicks.
        click(640,665)
        time.sleep(6)
        snap("01b-after-accessibility-continue.png")
    # EHTID/STALKER custom title screen: Singleplayer sits on the LEFT, not center.
    click(640,353) if os.environ.get("BF_ISOLATED_TEST")=="1" else click(225,328)
    time.sleep(8)
    snap("02-after-singleplayer.png")
    # EHTID title screen opens world creation directly; bottom-left button at x400.
    if os.environ.get("BF_ISOLATED_TEST")=="1":
        # Singleplayer may open Create World directly on a fresh installation.
        snap("02b-world-selection.png")
        # A random seed can place the disposable player on a tiny island, leaving
        # the four-arm ground mesh with only one supported segment. Pin the client
        # fixture to a verified Java 1.20 plains spawn so the render test measures
        # the mesh rather than unrelated random world-generation variance.
        click(640,35)  # World tab
        time.sleep(0.8)
        click(640,245)  # Seed field
        key("CTRL+A")
        seed="5589746799300216064"
        subprocess.run(["xdotool","type","--clearmodifiers",seed],check=False)
        time.sleep(0.5)
        snap("02c-world-seed-configured.png")
        print("WORLD_CAPTURE: deterministic Java 1.20.1 plains seed entered",seed,flush=True)
        click(270,35)  # Return to Game tab
        time.sleep(0.5)
        click(400,665)
    else:
        click(400,665)
    time.sleep(10)
    snap("03-after-create-world.png")
    print("WORLD_CAPTURE: requested new disposable singleplayer world",flush=True)
    deadline_world=time.time()+150
    while time.time()<deadline_world and p.poll() is None:
        logs=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
        if "Starting integrated minecraft server" in logs or "Starting integrated minecraft server version" in logs:
            print("WORLD_CAPTURE: integrated server detected",flush=True)
            # The integrated server starting is NOT proof of gameplay: world creation can
            # fail at 0% and still leave a visible client window.
            spawned=False
            spawn_deadline=time.time()+150
            while time.time()<spawn_deadline and p.poll() is None:
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                if ("logged in with entity id" in current or
                    "joined the game" in current or
                    "Starting to track player" in current):
                    spawned=True
                    break
                time.sleep(2)
            if not spawned:
                print("WORLD_CAPTURE_FAIL: integrated server started but player never spawned",flush=True)
                snap("world-spawn-failure.png")
                # Collect JVM state when world creation stalls at 0%.
                dump=shots/"world-spawn-jvm-threads.txt"
                with dump.open("w",encoding="utf-8") as f:
                    subprocess.run(["jcmd",str(p.pid),"Thread.print","-l"],
                                   stdout=f,stderr=subprocess.STDOUT,timeout=25,check=False)
                print("WORLD_CAPTURE: JVM thread dump saved",flush=True)
                return False
            print("WORLD_CAPTURE: player spawn verified in log",flush=True)
            # Capture gameplay immediately: restoration can begin before the
            # initial frame series ends, so waiting until afterward misses it.
            restoration_shot=False
            terrain_shot=False
            terrain_capture_attempted=False
            terrain_capture_failed=False
            sky_shot=False
            sky_capture_attempted=False
            def capture_terrain_fracture(logs):
                nonlocal terrain_shot,terrain_capture_attempted,terrain_capture_failed
                # A server particle marker alone is not proof that the client can
                # see the crack. Wait for the safe-pad teleport, then aim at the
                # original surface coordinates before taking the screenshot.
                if (terrain_shot or terrain_capture_attempted or terrain_capture_failed
                        or "BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION" in logs):
                    return
                if "BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET_UNAVAILABLE" in logs:
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: the safe beacon pad occluded every loaded fissure sample",flush=True)
                    return
                marker=re.search(
                    r"BLACK_FREQUENCY_TEST SURFACE_FRACTURE_CUE_EMITTED x=(-?\d+) y=(-?\d+) z=(-?\d+)",
                    logs)
                particles=parse_fissure_particle_emission(logs)
                target=re.search(
                    r"BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET x=([-+0-9.eE]+) y=([-+0-9.eE]+) z=([-+0-9.eE]+) distanceSquared=([-+0-9.eE]+) horizontalDistanceSquared=([-+0-9.eE]+) viewScore=([-+0-9.eE]+) selection=balanced-view",
                    logs)
                safe_pad=re.search(
                    r"BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT x=(-?\d+) y=(-?\d+) z=(-?\d+) support=solid fallDistance=0\.0",
                    logs)
                pad_profile=re.search(
                    r"BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_PAD_PROFILE_VERIFIED floorOffset=1 playerOffset=2 axis=([XZ]) firstSolidRow=3 gapRow=2 lastSolidRow=4 standoff=3\.25 distanceSquared=([-+0-9.eE]+) padBlocks=(\d+) withinReach=true beamColumnUntouched=true withinRiftRadius=true perpendicular=true",
                    logs)
                aim=re.search(
                    r"BLACK_FREQUENCY_TEST BEACON_AIM_SET yaw=([-+0-9.eE]+) pitch=([-+0-9.eE]+)",
                    logs)
                mesh=re.search(
                    r"BLACK_FREQUENCY_GROUND_MESH_RENDERED segments=(\d+) arms=(\d+) anchorX=(-?\d+) anchorZ=(-?\d+) sampledColumns=(\d+) cacheHits=(\d+)",
                    logs)
                pending_mesh=re.search(
                    r"BLACK_FREQUENCY_GROUND_MESH_PENDING segments=(\d+) arms=(\d+) requiredSegments=(\d+) requiredArms=(\d+)",
                    logs)
                if pending_mesh:
                    pending_segments,pending_arms,required_segments,required_arms=map(int,pending_mesh.groups())
                    if pending_segments>=required_segments and pending_arms>=required_arms:
                        terrain_capture_failed=True
                        print("WORLD_CAPTURE_FAIL: ready ground mesh was incorrectly marked pending",
                              "segments",pending_segments,"arms",pending_arms,flush=True)
                        return
                if (not marker or not particles or not target or not safe_pad or not pad_profile or not aim or not mesh
                        or "BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER" not in logs
                        or "BLACK_FREQUENCY_TEST BEACON_APPROACH_SIDE_SELECTED" not in logs
                        or "fissureVisible=true loadedOnly=true" not in logs
                        or "BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_VERIFIED hit=beacon loadedChunksOnly=true" not in logs):
                    return
                samples,accent_dust,violet_dust,edge_dust_count,total_particles=particles
                mesh_segments=int(mesh.group(1))
                mesh_arms=int(mesh.group(2))
                mesh_anchor_x,mesh_anchor_z=map(int,mesh.groups()[2:4])
                mesh_columns=int(mesh.group(5))
                mesh_cache_hits=int(mesh.group(6))
                if not (0<mesh_columns<GROUND_MESH_SAMPLES
                        and mesh_cache_hits>0
                        and mesh_columns+mesh_cache_hits==GROUND_MESH_SAMPLES):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: per-build ground-column cache accounting is invalid",
                          "columns",mesh_columns,"cacheHits",mesh_cache_hits,flush=True)
                    return
                if (mesh_segments<MIN_GROUND_MESH_SEGMENTS
                        or mesh_segments>MAX_GROUND_MESH_SEGMENTS
                        or mesh_arms<MIN_GROUND_MESH_ARMS or mesh_arms>MAX_GROUND_MESH_ARMS):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: client ground mesh is outside the bounded segment/arm budget",
                          "segments",mesh_segments,"arms",mesh_arms,flush=True)
                    return
                if not valid_fissure_particle_emission(particles):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: fissure particle composition/budget mismatch",
                          samples,accent_dust,violet_dust,edge_dust_count,total_particles,flush=True)
                    return
                pad_axis=pad_profile.group(1)
                pad_distance_squared=float(pad_profile.group(2))
                pad_block_count=int(pad_profile.group(3))
                if (pad_axis not in ("X","Z")
                        or not math.isfinite(pad_distance_squared)
                        or pad_distance_squared<0.0
                        or pad_distance_squared>4.5*4.5
                        or pad_block_count!=6):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: perpendicular beacon pad is outside interaction reach or incomplete",
                          pad_axis,pad_distance_squared,pad_block_count,flush=True)
                    return
                player_x,player_y,player_z=map(int,safe_pad.groups())
                target_horizontal_squared=float(target.group(5))
                target_view_score=float(target.group(6))
                minimum_target_horizontal_squared=2.25*2.25
                if (not math.isfinite(target_horizontal_squared)
                        or target_horizontal_squared<minimum_target_horizontal_squared-1.0E-9
                        or target_horizontal_squared>36.0+1.0E-9):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: fissure camera target is too close or outside the bounded standoff",
                          target_horizontal_squared,flush=True)
                    return
                expected_view_score=fissure_target_view_score(
                    float(target.group(2)),player_y,target_horizontal_squared)
                if (not math.isfinite(target_view_score)
                        or target_view_score<0.0 or target_view_score>20.0
                        or not math.isclose(target_view_score,expected_view_score,
                                            rel_tol=0.0,abs_tol=1.0E-6)):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: fissure target is not a bounded 45-degree camera candidate",
                          target_view_score,expected_view_score,flush=True)
                    return
                terrain_capture_attempted=True
                if samples<32:
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: fewer than 32 of 48 loaded ground-fissure samples",samples,flush=True)
                    return
                # The server log is emitted before the client necessarily applies
                # its teleport packet. Wait for several client ticks before steering.
                time.sleep(2.0)
                # Aim at a server-verified emitted sample with an unobstructed
                # ray from the safe pad, not at the anchor's possibly hidden cliff face.
                target_x,target_y,target_z=map(float,target.groups()[:3])
                current_orientation=read_client_camera_rotation()
                if current_orientation is None:
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: current camera rotation telemetry is missing",flush=True)
                    return
                current_yaw,current_pitch=current_orientation
                print("WORLD_CAPTURE: using fresh camera rotation before fissure aim",
                      round(current_yaw,2),round(current_pitch,2),flush=True)
                eye_x,eye_y,eye_z=player_x+.5,player_y+1.62,player_z+.5
                dx,dy,dz=target_x-eye_x,target_y-eye_y,target_z-eye_z
                horizontal=math.hypot(dx,dz)
                target_yaw=math.degrees(math.atan2(-dx,dz))
                target_pitch=-math.degrees(math.atan2(dy,max(.01,horizontal)))
                yaw_delta=camera_delta_for_yaw(current_yaw,target_yaw)
                pitch_delta=camera_delta_for_pitch(current_pitch,target_pitch)
                key("F1")
                subprocess.run(["xdotool","mousemove","--sync","640","640"],check=False)
                time.sleep(.2)
                moved_yaw=move_camera_horizontal(yaw_delta)
                moved_pitch=move_camera(pitch_delta)
                restored=True
                try:
                    # Do not trust requested mouse deltas: verify the camera that
                    # Minecraft actually rendered before accepting the fissure frame.
                    actual_orientation=wait_for_camera_orientation(
                        target_yaw,target_pitch,tolerance=5.0,timeout=3.0)
                    if (actual_orientation is None
                            or abs(wrapped_angle_delta(actual_orientation[0],target_yaw))>5.0
                            or abs(actual_orientation[1]-target_pitch)>5.0):
                        terrain_capture_failed=True
                        print("WORLD_CAPTURE_FAIL: client camera did not reach the fissure target",
                              "target",round(target_yaw,2),round(target_pitch,2),
                              "actual",actual_orientation,flush=True)
                        return
                    print("WORLD_CAPTURE: fissure camera orientation verified",
                          "target",round(target_yaw,2),round(target_pitch,2),
                          "actual",tuple(round(v,2) for v in actual_orientation),flush=True)
                    # Narrative titles last up to six seconds. Let them clear while
                    # the server keeps emitting the fissure, then capture the actual view.
                    time.sleep(6.5)
                    snap("terrain-fracture-near-player.png")
                finally:
                    if moved_pitch:
                        restored_pitch=move_camera(-moved_pitch)
                        if restored_pitch!=-moved_pitch:
                            restored=False
                    if moved_yaw:
                        restored_yaw=move_camera_horizontal(-moved_yaw)
                        if restored_yaw!=-moved_yaw:
                            restored=False
                    key("F1")
                if not restored:
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: fissure camera could not be restored exactly",flush=True)
                    return
                from PIL import Image
                path=shots/"terrain-fracture-near-player.png"
                with Image.open(path) as source:
                    frame=source.convert("RGB")
                metrics=measure_ground_visibility(frame)
                # The target-aimed sample frame can miss a narrow seam on irregular
                # Normal terrain even though the mesh is visible from the restored
                # player view. Capture that view before the real beacon click and
                # apply the identical particle+mesh thresholds; never accept logs alone.
                if not ground_visibility_passes(metrics):
                    fallback_path=shots/"recovery-beacon-ground-context.png"
                    snap("recovery-beacon-ground-context.png")
                    fallback_metrics=None
                    if fallback_path.is_file():
                        with Image.open(fallback_path) as source:
                            fallback_metrics=measure_ground_visibility(source.convert("RGB"))
                    if fallback_metrics is not None and ground_visibility_passes(fallback_metrics):
                        terrain_shot=True
                        print("WORLD_CAPTURE: visible ground fissure mesh captured",
                              "view=restored-beacon-context",fallback_metrics,flush=True)
                        return
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: ground fissure was not visible in either player view",
                          "targetView",metrics,"restoredBeaconView",fallback_metrics,flush=True)
                    return
                terrain_shot=True
                print("WORLD_CAPTURE: visible ground fissure mesh captured",
                      *marker.groups(),"target",*target.groups(),"samples",samples,
                      "meshSegments",mesh_segments,"meshArms",mesh_arms,
                      "meshAnchor",mesh_anchor_x,mesh_anchor_z,
                      "visibility",metrics,
                      "aim",round(target_yaw,1),round(target_pitch,1),flush=True)
            def capture_sky_before_beacon():
                nonlocal sky_shot,sky_capture_attempted
                if sky_capture_attempted:
                    return
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                if not ("BLACK_FREQUENCY_RENDER_ACTIVE" in current
                        and "BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED" in current
                        and "BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER" in current
                        and "BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION" not in current):
                    return
                key("F1")
                # Keep enough vertical pointer travel for the full camera sweep.
                subprocess.run(["xdotool","mousemove","--sync","640","640"],check=False)
                time.sleep(0.2)
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                initial_rotation=read_client_camera_rotation(current)
                if initial_rotation is None:
                    print("WORLD_CAPTURE: actual client camera telemetry not ready; will retry",flush=True)
                    key("F1")
                    return
                sky_capture_attempted=True
                initial_yaw,initial_pitch=initial_rotation
                print("WORLD_CAPTURE: actual client camera rotation",
                      "yaw",round(initial_yaw,2),"pitch",round(initial_pitch,2),flush=True)
                try:
                    # The server's BEACON_AIM_SET value is only a requested rotation.
                    # Level the camera from the rotation that the client actually rendered.
                    horizon_delta=camera_delta_for_pitch(initial_pitch,0.0)
                    move_camera(horizon_delta)
                    horizon_rotation=wait_for_camera_rotation(0.0,tolerance=4.0,timeout=2.0)
                    for correction_attempt in range(2):
                        if horizon_rotation is not None and abs(horizon_rotation[1])<=4.0:
                            break
                        measured=read_client_camera_rotation()
                        if measured is None:
                            break
                        correction=camera_delta_for_pitch(measured[1],0.0)
                        if correction==0:
                            break
                        moved=move_camera(correction)
                        if moved!=correction:
                            break
                        horizon_rotation=wait_for_camera_rotation(0.0,tolerance=4.0,timeout=2.0)
                    horizon_camera_confirmed=(
                        horizon_rotation is not None and abs(horizon_rotation[1])<=4.0)
                    if horizon_camera_confirmed:
                        print("WORLD_CAPTURE: horizon camera pitch verified",
                              round(horizon_rotation[1],2),flush=True)
                    else:
                        measured=read_client_camera_rotation()
                        print("WORLD_CAPTURE_FAIL: camera did not reach the horizon",
                              None if measured is None else round(measured[1],2),flush=True)
                    horizon_visible=False
                    last_candidate=None
                    for index in range(10 if horizon_camera_confirmed else 0):
                        if index:
                            move_camera_horizontal(170)
                            wait_for_camera_rotation(timeout=0.8)
                        time.sleep(0.45)
                        candidate_name=f"sky-rift-horizon-candidate-{index:02d}.png"
                        snap(candidate_name)
                        last_candidate=shots/candidate_name
                        if verify_sky_pixels(candidate_name,"horizon",.08,.20):
                            shutil.copyfile(last_candidate,shots/"sky-rift-hudless.png")
                            horizon_visible=True
                            print("WORLD_CAPTURE: adaptive horizon frame selected",candidate_name,flush=True)
                            break
                    if not horizon_visible and last_candidate and last_candidate.is_file():
                        shutil.copyfile(last_candidate,shots/"sky-rift-hudless.png")
                    # Use the measured pitch after the horizon sweep. This avoids
                    # accumulating assumptions about how much X11 input Minecraft accepted.
                    measured=read_client_camera_rotation()
                    upper_camera_confirmed=False
                    if measured is not None:
                        upper_delta=camera_delta_for_pitch(measured[1],-65.0)
                        move_camera(upper_delta)
                        upper_rotation=wait_for_camera_rotation(-65.0,tolerance=5.0,timeout=2.0)
                        for correction_attempt in range(2):
                            if upper_rotation is not None and abs(upper_rotation[1]+65.0)<=5.0:
                                break
                            measured=read_client_camera_rotation()
                            if measured is None:
                                break
                            correction=camera_delta_for_pitch(measured[1],-65.0)
                            if correction==0:
                                break
                            moved=move_camera(correction)
                            if moved!=correction:
                                break
                            upper_rotation=wait_for_camera_rotation(-65.0,tolerance=5.0,timeout=2.0)
                        upper_camera_confirmed=(
                            upper_rotation is not None and abs(upper_rotation[1]+65.0)<=5.0)
                    if upper_camera_confirmed:
                        print("WORLD_CAPTURE: upper-sky camera pitch verified",
                              round(upper_rotation[1],2),flush=True)
                    else:
                        measured=read_client_camera_rotation()
                        print("WORLD_CAPTURE_FAIL: camera did not reach the upper-sky angle",
                              None if measured is None else round(measured[1],2),flush=True)
                    upper_visible=False
                    last_upper_candidate=None
                    for index in range(10 if upper_camera_confirmed else 0):
                        if index:
                            move_camera_horizontal(170)
                            wait_for_camera_rotation(timeout=0.8)
                        time.sleep(0.45)
                        candidate_name=f"sky-rift-upper-candidate-{index:02d}.png"
                        snap(candidate_name)
                        last_upper_candidate=shots/candidate_name
                        if verify_sky_pixels(candidate_name,"upper",.08,.25):
                            shutil.copyfile(last_upper_candidate,shots/"sky-rift-upper.png")
                            upper_visible=True
                            print("WORLD_CAPTURE: adaptive upper-sky frame selected",candidate_name,flush=True)
                            break
                    if not upper_visible and last_upper_candidate and last_upper_candidate.is_file():
                        shutil.copyfile(last_upper_candidate,shots/"sky-rift-upper.png")
                    sky_shot=upper_visible
                    if upper_visible and not horizon_visible:
                        shutil.copyfile(shots/"sky-rift-upper.png",shots/"sky-rift-hudless.png")
                        print("WORLD_CAPTURE: upper-sky frame accepted; horizon did not meet the continuous-band threshold",
                              flush=True)
                finally:
                    key("F1")
                restored_rotation=restore_camera_orientation(initial_yaw,initial_pitch)
                if (restored_rotation is None
                        or abs(restored_rotation[1]-initial_pitch)>3.0
                        or abs(wrapped_angle_delta(restored_rotation[0],initial_yaw))>3.0):
                    sky_shot=False
                    print("WORLD_CAPTURE_FAIL: rendered camera rotation was not restored",
                          None if restored_rotation is None else
                          (round(restored_rotation[0],2),round(restored_rotation[1],2)),
                          "expected",(round(initial_yaw,2),round(initial_pitch,2)),flush=True)
                else:
                    print("WORLD_CAPTURE: rendered camera rotation restored",
                          round(restored_rotation[0],2),round(restored_rotation[1],2),flush=True)
                if horizon_visible and upper_visible:
                    print("WORLD_CAPTURE: SKY_RIFT_HORIZON_AND_UPPER_SKY_VERIFIED_BEFORE_BEACON_CLICK",flush=True)
                elif upper_visible:
                    print("WORLD_CAPTURE: SKY_RIFT_UPPER_SKY_ONLY_VERIFIED_BEFORE_BEACON_CLICK",flush=True)
                elif horizon_visible:
                    print("WORLD_CAPTURE: SKY_RIFT_HORIZON_ONLY_VERIFIED_BEFORE_BEACON_CLICK",flush=True)
                else:
                    print("WORLD_CAPTURE_FAIL: neither horizon nor upper-sky frame passed visibility checks; still attempting real interaction",flush=True)
            for stage in range(32):
                if p.poll() is not None:
                    break
                snap(f"world-stage-{stage:02d}.png")
                if os.environ.get("BF_TEST_RESTORATION")=="1" and not restoration_shot:
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                    capture_terrain_fracture(current)
                    if terrain_capture_failed:
                        break
                    try_beacon_click(current)
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                capture_terrain_fracture(latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else "")
                capture_sky_before_beacon()
                time.sleep(0.1)
            subprocess.run(["scrot","-a","0,0,1280,720",
                str(ROOT/"client-smoke-artifacts"/"world.png")],check=False)
            # Ground fracture starts after finale tick 260. Early 16-second frames
            # mostly show the sky; wait for actual terrain edits before final proof.
            fracture_deadline=time.time()+90
            fracture_seen=False
            sparse_test=os.environ.get("BF_TEST_SPARSE_RECOVERY")=="1"
            while time.time()<fracture_deadline and p.poll() is None:
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                capture_terrain_fracture(current)
                if terrain_capture_failed:
                    break
                try_beacon_click(current)
                capture_sky_before_beacon()
                if "BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED" in current and not fracture_seen:
                    fracture_seen=True
                    print("WORLD_CAPTURE: first physical terrain fracture confirmed",flush=True)
                # Both modes must wait for the bounded fissure particle pass and
                # its actual player-view capture before the recovery beacon is clicked.
                if fracture_seen and terrain_shot:
                    break
                time.sleep(0.1)
            if not fracture_seen:
                print("WORLD_CAPTURE_FAIL: no terrain edit marker during capture window",flush=True)
                snap("terrain-fracture-not-seen.png")
                return False
            if not terrain_shot:
                final_log=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                if "BLACK_FREQUENCY_GROUND_MESH_EVENT_ACTIVE" not in final_log:
                    print("WORLD_CAPTURE_FAIL: AFTER_PARTICLES ground renderer was never dispatched",flush=True)
                elif "BLACK_FREQUENCY_GROUND_MESH_EMPTY" in final_log:
                    print("WORLD_CAPTURE_FAIL: client sampler found no valid loaded surface segments",flush=True)
                elif "BLACK_FREQUENCY_GROUND_MESH_RENDERED" not in final_log:
                    print("WORLD_CAPTURE_FAIL: ground renderer ran but did not reach its mesh readiness threshold",flush=True)
                else:
                    print("WORLD_CAPTURE_FAIL: mesh marker exists but no player-visible fissure frame was captured",flush=True)
                print("WORLD_CAPTURE_FAIL: no player-visible ground fissure was captured",flush=True)
                # Preserve the earlier frame that was actually measured; keep the
                # post-failure diagnostic view under a separate filename.
                snap("recovery-beacon-ground-context-final-failure.png")
                return False
            final_log=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            if "BLACK_FREQUENCY_GROUND_MESH_SURFACE_OUTLINE_HEIGHT_VERIFIED" not in final_log:
                print("WORLD_CAPTURE_FAIL: outline-based ground surface height self-check was not executed",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_PLANT_OUTLINE_HEIGHT_VERIFIED" not in final_log:
                print("WORLD_CAPTURE_FAIL: real tall-grass outline height was not verified",flush=True)
                return False
            camera_source_path=ROOT/"addon/src/main/java/com/blackfrequency/rift/client/RiftClientState.java"
            camera_source=camera_source_path.read_text(encoding="utf-8",errors="replace")
            color_profile_marker="BLACK_FREQUENCY_GROUND_MESH_COLOR_CHANNEL_PROFILE_VERIFIED zero=0 half=128 one=255 lowClamp=0 highClamp=255 nonFinite=true"
            if color_profile_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: per-ribbon color conversion profile was not executed",flush=True)
                return False
            glow_alpha_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_GLOW_ALPHA_VERIFIED multiplier=([0-9.]+) fullStrengthPulseMin=([0-9.]+) fullStrengthPulseMax=([0-9.]+) minAlpha=([0-9.]+) maxAlpha=([0-9.]+)",
                final_log)
            expected_glow_alpha=(.66,.56,1.0,.3696,.66)
            if (not glow_alpha_match
                    or any(not math.isclose(float(glow_alpha_match.group(i)),expected_glow_alpha[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-6)
                           for i in range(1,6))
                    or "GROUND_GLOW_ALPHA=.66F;" not in camera_source
                    or "alpha*GROUND_GLOW_ALPHA" not in camera_source):
                print("WORLD_CAPTURE_FAIL: violet ground halo alpha profile is missing or outside its tested bounds",flush=True)
                return False
            vertex_start=camera_source.find("private static void vertex(")
            vertex_end=camera_source.find("private static void ribbon(",vertex_start+1)
            ribbon_start=camera_source.find("private static void ribbon(",vertex_end)
            ribbon_end=camera_source.find("private static void clearGeometryCache()",ribbon_start)
            vertex_source=(camera_source[vertex_start:vertex_end]
                           if vertex_start>=0 and vertex_end>vertex_start else "")
            ribbon_source=(camera_source[ribbon_start:ribbon_end]
                           if ribbon_start>=0 and ribbon_end>ribbon_start else "")
            build_start=camera_source.find("private static GroundMesh buildSegments(")
            build_end=camera_source.find("// Use the nearest actually sampled surface point",build_start)
            build_source=(camera_source[build_start:build_end]
                          if build_start>=0 and build_end>build_start else "")
            sample_offsets_marker=("BLACK_FREQUENCY_GROUND_MESH_SAMPLE_OFFSETS_VERIFIED "
                                   "samples=96 trigPerRebuild=0 bounded=true phaseCached=true")
            if sample_offsets_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: precomputed ground sample offsets were not verified",flush=True)
                return False
            if ("private record GroundSample(" not in camera_source
                    or "private static final GroundSample[] GROUND_SAMPLES=createGroundSamples();" not in camera_source
                    or "private static GroundSample[] createGroundSamples()" not in camera_source
                    or "private static void verifyGroundSampleCacheProfile()" not in camera_source
                    or "verifyGroundSampleCacheProfile();" not in build_source
                    or "GroundSample sample=GROUND_SAMPLES[arm*SEGMENTS_PER_ARM+segment-1];" not in build_source
                    or "sample.forwardX()" not in build_source
                    or "sample.bendX()" not in build_source
                    or "sample.forwardZ()" not in build_source
                    or "sample.bendZ()" not in build_source
                    or "Math.sin(" in build_source or "Math.cos(" in build_source):
                print("WORLD_CAPTURE_FAIL: ground mesh rebuild still calculates fixed sample trigonometry",flush=True)
                return False
            print("WORLD_CAPTURE: all 96 ground mesh sample offsets/phases are cached outside the rebuild loop",flush=True)
            animation_phase_marker=("BLACK_FREQUENCY_GROUND_MESH_ANIMATION_PHASE_CACHE_VERIFIED "
                                    "samples=96 perFrameTrig=6 legacyPerSegmentTrig=3 equivalent=true")
            if animation_phase_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: cached animation phase profile was not verified",flush=True)
                return False
            render_loop_start=camera_source.find("for(GroundSegment segment:cachedSegments)")
            render_loop_end=camera_source.find("BufferUploader.drawWithShader(builder.end());",render_loop_start)
            render_loop_source=(camera_source[render_loop_start:render_loop_end]
                                if render_loop_start>=0 and render_loop_end>render_loop_start else "")
            render_preamble_start=camera_source.find("double time=(double)tick+boundedRenderPartialTick")
            render_preamble=(camera_source[render_preamble_start:render_loop_start]
                             if render_preamble_start>=0 and render_loop_start>render_preamble_start else "")
            if ("double phaseSin,double phaseCos,double flickerPhaseSin,double flickerPhaseCos" not in camera_source
                    or "GroundSample sample,double taperA,double taperB" not in camera_source
                    or "Math.sin(phase),Math.cos(phase)" not in camera_source
                    or "Math.sin(phase*1.9D),Math.cos(phase*1.9D)" not in camera_source
                    or render_preamble.count("Math.sin(")!=3
                    or render_preamble.count("Math.cos(")!=3
                    or "segment.sample()" not in render_loop_source
                    or "sample.phaseSin()" not in render_loop_source
                    or "sample.phaseCos()" not in render_loop_source
                    or "sample.flickerPhaseSin()" not in render_loop_source
                    or "sample.flickerPhaseCos()" not in render_loop_source
                    or "Math.sin(" in render_loop_source or "Math.cos(" in render_loop_source):
                print("WORLD_CAPTURE_FAIL: ground animation still performs trigonometry per segment",flush=True)
                return False
            print("WORLD_CAPTURE: ground animation uses six shared time-base trig calls per frame",flush=True)
            ribbon_cache_marker=("BLACK_FREQUENCY_GROUND_MESH_RIBBON_COLOR_CACHE_VERIFIED "
                                 "staticRgbChannels=12 dynamicAlphaChannels=4 edgeAlphaShared=true")
            if ribbon_cache_marker in final_log:
                expected_static_channels=(
                    "private static final int GROUND_GLOW_RED_CHANNEL=colorChannel(.20F);",
                    "private static final int GROUND_GLOW_GREEN_CHANNEL=colorChannel(.025F);",
                    "private static final int GROUND_GLOW_BLUE_CHANNEL=colorChannel(.43F);",
                    "private static final int GROUND_CORE_RED_CHANNEL=colorChannel(.008F);",
                    "private static final int GROUND_CORE_GREEN_CHANNEL=colorChannel(.001F);",
                    "private static final int GROUND_CORE_BLUE_CHANNEL=colorChannel(.022F);",
                    "private static final int GROUND_FILAMENT_RED_CHANNEL=colorChannel(.28F);",
                    "private static final int GROUND_FILAMENT_GREEN_CHANNEL=colorChannel(.015F);",
                    "private static final int GROUND_FILAMENT_BLUE_CHANNEL=colorChannel(.62F);",
                    "private static final int GROUND_EDGE_RED_CHANNEL=colorChannel(GROUND_EDGE_RED);",
                    "private static final int GROUND_EDGE_GREEN_CHANNEL=colorChannel(GROUND_EDGE_GREEN);",
                    "private static final int GROUND_EDGE_BLUE_CHANNEL=colorChannel(GROUND_EDGE_BLUE);")
                dynamic_alpha_calls=(
                    "int glowAlphaChannel=colorChannel(alpha*GROUND_GLOW_ALPHA);",
                    "int coreAlphaChannel=colorChannel(alpha*.94F);",
                    "int filamentAlphaChannel=colorChannel(alpha*corePulse);",
                    "int edgeAlphaChannel=colorChannel(edgeAlpha);")
                ribbon_calls=(
                    "GROUND_GLOW_RED_CHANNEL,GROUND_GLOW_GREEN_CHANNEL,GROUND_GLOW_BLUE_CHANNEL,glowAlphaChannel",
                    "GROUND_CORE_RED_CHANNEL,GROUND_CORE_GREEN_CHANNEL,GROUND_CORE_BLUE_CHANNEL,coreAlphaChannel",
                    "GROUND_FILAMENT_RED_CHANNEL,GROUND_FILAMENT_GREEN_CHANNEL,GROUND_FILAMENT_BLUE_CHANNEL,filamentAlphaChannel",
                    "GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,edgeAlphaChannel")
                render_start=camera_source.find("for(GroundSegment segment:cachedSegments)")
                render_end=camera_source.find("BufferUploader.drawWithShader(builder.end());",render_start)
                render_source=(camera_source[render_start:render_end]
                               if render_start>=0 and render_end>render_start else "")
                if (not vertex_source or ".color(red,green,blue,alpha)" not in vertex_source
                        or "colorChannel(" in vertex_source or not ribbon_source
                        or "int redChannel,int greenChannel,int blueChannel,int alphaChannel" not in ribbon_source
                        or "colorChannel(" in ribbon_source
                        or ribbon_source.count("redChannel,greenChannel,blueChannel,alphaChannel")!=4
                        or any(field not in camera_source for field in expected_static_channels)
                        or any(render_source.count(call)!=1 for call in dynamic_alpha_calls)
                        or render_source.count("colorChannel(")!=4
                        or any(call not in render_source for call in ribbon_calls)
                        or render_source.count("GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,edgeAlphaChannel")!=2
                        or "verifyPackedColorProfile();" not in build_source
                        or "verifyRibbonColorCacheProfile();" not in build_source):
                    print("WORLD_CAPTURE_FAIL: ground ribbon must cache 12 static RGB channels and convert only four dynamic alpha channels per segment",flush=True)
                    return False
                print("WORLD_CAPTURE: ground ribbon reuses 12 static RGB channels and computes four alpha channels per segment",flush=True)
            else:
                channel_calls=("int redChannel=colorChannel(red);",
                               "int greenChannel=colorChannel(green);",
                               "int blueChannel=colorChannel(blue);",
                               "int alphaChannel=colorChannel(alpha);")
                if (not vertex_source or ".color(red,green,blue,alpha)" not in vertex_source
                        or "colorChannel(" in vertex_source or not ribbon_source
                        or any(ribbon_source.count(call)!=1 for call in channel_calls)
                        or "redChannel,greenChannel,blueChannel,alphaChannel" not in ribbon_source
                        or "verifyPackedColorProfile();" not in build_source):
                    print("WORLD_CAPTURE_FAIL: ground ribbon must convert each RGBA channel once per quad and reuse it for all four vertices",flush=True)
                    return False
                print("WORLD_CAPTURE: legacy ground ribbon RGBA conversion profile verified",flush=True)
            if "BLACK_FREQUENCY_TEST GROUND_MESH_INACTIVE_CACHE_CLEANUP_VERIFIED levelReference=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: inactive ground mesh cache did not prove ClientLevel reference release",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_RANGE_CACHE_RELEASE_VERIFIED levelReference=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: vertical range rejection did not release the cached ClientLevel",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_3D_RANGE_PROFILE_VERIFIED radius=64 horizontalBoundary=true verticalBoundary=true invalidRejected=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: 3D ground mesh render-range boundaries were not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_LATCH_VERIFIED stable=true returnRebuild=true anchorChangeRebuild=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: vertically culled ground mesh rebuilt or failed to resume at the expected boundary",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_CAMERA_CULL_PROFILE_VERIFIED radius=64 cameraPosition=true playerCameraIndependent=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: ground mesh culling did not verify camera-relative horizontal bounds",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_CAMERA_POSITION_GUARD_VERIFIED nonFiniteRejected=true worldLimitRejected=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: invalid camera coordinates were not rejected before terrain sampling",flush=True)
                return False
            ground_render_start=camera_source.find("public static void render(RenderLevelStageEvent event) {")
            ground_render_end=camera_source.find("\n    }\n",ground_render_start+1)
            ground_render=(camera_source[ground_render_start:ground_render_end]
                           if ground_render_start>=0 and ground_render_end>ground_render_start else "")
            cursor_set=ground_render.find("SURFACE_CURSOR.set(worldAnchorX,level.getMinBuildHeight(),worldAnchorZ);")
            border_check=ground_render.find("isWithinBounds(SURFACE_CURSOR)")
            if ("private static final BlockPos.MutableBlockPos SURFACE_CURSOR=" not in camera_source
                    or not ground_render or cursor_set<0 or border_check<cursor_set
                    or "new BlockPos(" in ground_render):
                print("WORLD_CAPTURE_FAIL: ground render anchor guard must reuse its render-thread cursor without per-frame BlockPos allocation",flush=True)
                return False
            print("WORLD_CAPTURE: ground render anchor guard reuses its mutable cursor; no per-frame BlockPos allocation",flush=True)
            if ("long cameraHorizontalDistanceSquared=horizontalDistanceSquared(" not in camera_source
                    or "cameraBlockX,cameraBlockZ,worldAnchorX,worldAnchorZ);" not in camera_source
                    or "int surfaceReferenceY=cameraBlockY;" not in camera_source
                    or "double dy=camera.y-cachedAnchorSurfaceY;" not in camera_source):
                print("WORLD_CAPTURE_FAIL: ground mesh render range is not consistently camera-relative",flush=True)
                return False
            if ("private static double boundedRenderPartialTick(double partialTick)" not in camera_source
                    or "double time=(double)tick+boundedRenderPartialTick(event.getPartialTick());" not in camera_source):
                print("WORLD_CAPTURE_FAIL: ground fissure animation does not clamp the renderer partial tick",flush=True)
                return False
            taper_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_TAPER_VERIFIED start=([0-9.eE+-]+) first=([0-9.eE+-]+) second=([0-9.eE+-]+) middle=([0-9.eE+-]+) secondLast=([0-9.eE+-]+) last=([0-9.eE+-]+) end=([0-9.eE+-]+) symmetric=(true|false) invalidClamped=(true|false)",
                final_log)
            expected_taper=(.5,.5+1.0/27.0,17.0/27.0,1.0,17.0/27.0,.5+1.0/27.0,.5)
            if (not taper_match
                    or any(not math.isclose(float(taper_match.group(i)),expected_taper[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,8))
                    or taper_match.group(8)!="true" or taper_match.group(9)!="true"
                    or "double tail=Math.min(1.0,(SEGMENTS_PER_ARM-boundedSample)/(double)GROUND_ENDPOINT_TAPER_SAMPLES);" not in camera_source
                    or "double progress=Math.min(ramp,tail);" not in camera_source
                    or "double eased=progress*progress*(3.0D-2.0D*progress);" not in camera_source
                    or "BLACK_FREQUENCY_GROUND_MESH_TAPER_EASING_VERIFIED curve=smoothstep zeroSlopeAtEnds=true sameSpan=true" not in final_log):
                print("WORLD_CAPTURE_FAIL: ground fissure endpoint taper is not symmetric or bounded",flush=True)
                return False
            visual_profile_marker="BLACK_FREQUENCY_GROUND_MESH_VISUAL_PROFILE_VERIFIED glowScale=5.8 coreScale=1.65 edgeOffsetScale=2.4 edgeHalfWidth=0.026 pulseMin=0.045 pulseMax=0.068 coreInsideEdgeBand=true"
            if visual_profile_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: widened ground fissure visual profile self-check was not executed",flush=True)
                return False
            cliff_taper_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_CLIFF_TAPER_PROFILE_VERIFIED taper=([-+0-9.eE]+) previousTaper=0.25 wider=true invalidInputsZero=true",
                final_log)
            if (not cliff_taper_match
                    or not math.isclose(float(cliff_taper_match.group(1)),.22,rel_tol=0.0,abs_tol=1.0e-9)
                    or "CLIFF_BREAK_TAPER=.22D;" not in camera_source
                    or "cliffBreakTaper(Double.POSITIVE_INFINITY)==0.0D" not in camera_source
                    or "cliffBreakTaper(Double.NEGATIVE_INFINITY)==0.0D" not in camera_source):
                print("WORLD_CAPTURE_FAIL: visible cliff-end taper or non-finite input guards were not verified",flush=True)
                return False
            if ("GROUND_GLOW_WIDTH_SCALE=5.8D;" not in camera_source
                    or "GROUND_CORE_WIDTH_SCALE=1.65D;" not in camera_source
                    or "GROUND_EDGE_OFFSET_SCALE=2.4D;" not in camera_source
                    or "GROUND_EDGE_HALF_WIDTH=.026D;" not in camera_source
                    or "GROUND_MIN_CORE_EDGE_GAP=.0075D;" not in camera_source
                    or "minimumCoreEdgeGap<GROUND_MIN_CORE_EDGE_GAP" not in camera_source
                    or "BLACK_FREQUENCY_GROUND_MESH_CORE_EDGE_GAP_VERIFIED min={} required={} valid=true" not in camera_source):
                print("WORLD_CAPTURE_FAIL: wider ground fissure halo/core constants are not active",flush=True)
                return False
            relief_profile_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_SURFACE_RELIEF_VERIFIED coreRecess=([-+0-9.eE]+) filamentLift=([-+0-9.eE]+) edgeLift=([-+0-9.eE]+) coreClearance=([-+0-9.eE]+) glowClearance=([-+0-9.eE]+) filamentClearance=([-+0-9.eE]+) edgeClearance=([-+0-9.eE]+) ordered=true",
                final_log)
            expected_relief=(.020,.006,.020,.015,.035,.041,.055)
            if (not relief_profile_match
                    or any(not math.isclose(float(relief_profile_match.group(i)),expected_relief[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,8))):
                print("WORLD_CAPTURE_FAIL: layered ground fissure relief profile was not executed or is out of bounds",flush=True)
                return False
            if ("GROUND_SURFACE_CLEARANCE=.035D;" not in camera_source
                    or "GROUND_CORE_RECESS=.020D;" not in camera_source
                    or "GROUND_FILAMENT_LIFT=.006D;" not in camera_source
                    or "GROUND_EDGE_LIFT=.020D;" not in camera_source
                    or "return candidateY-1+surfaceOffset+GROUND_SURFACE_CLEARANCE;" not in camera_source
                    or "double offset,double halfWidth,double verticalOffset," not in camera_source
                    or camera_source.count("a.y()+verticalOffset")!=2
                    or camera_source.count("b.y()+verticalOffset")!=2
                    or "halfWidth*GROUND_GLOW_WIDTH_SCALE,0.0D," not in camera_source
                    or "halfWidth*GROUND_CORE_WIDTH_SCALE,-GROUND_CORE_RECESS," not in camera_source
                    or "halfWidth*.16D,GROUND_FILAMENT_LIFT," not in camera_source
                    or camera_source.count("GROUND_EDGE_HALF_WIDTH,GROUND_EDGE_LIFT,")!=2):
                print("WORLD_CAPTURE_FAIL: ground fissure layers are not vertically separated above the sampled surface",flush=True)
                return False
            edge_palette_marker="BLACK_FREQUENCY_GROUND_MESH_EDGE_PALETTE_VERIFIED red=0.46 green=0.18 blue=0.82"
            if edge_palette_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: violet ground-edge palette self-check was not executed",flush=True)
                return False
            if ribbon_cache_marker in final_log:
                edge_rail_pattern = re.compile(
                    r"ribbon\(builder,matrix,camera,segment,(-?)halfWidth\*GROUND_EDGE_OFFSET_SCALE,GROUND_EDGE_HALF_WIDTH,GROUND_EDGE_LIFT,\s*"
                    r"GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,edgeAlphaChannel\);"
                )
            else:
                edge_rail_pattern = re.compile(
                    r"ribbon\(builder,matrix,camera,segment,(-?)halfWidth\*GROUND_EDGE_OFFSET_SCALE,GROUND_EDGE_HALF_WIDTH,GROUND_EDGE_LIFT,\s*"
                    r"GROUND_EDGE_RED,GROUND_EDGE_GREEN,GROUND_EDGE_BLUE,edgeAlpha\);"
                )
            edge_rail_offsets = edge_rail_pattern.findall(camera_source)
            if ("private static final float GROUND_EDGE_RED=.46F;" not in camera_source
                    or "private static final float GROUND_EDGE_GREEN=.18F;" not in camera_source
                    or "private static final float GROUND_EDGE_BLUE=.82F;" not in camera_source
                    or "GROUND_EDGE_HALF_WIDTH=.026D;" not in camera_source
                    or sorted(edge_rail_offsets) != ["", "-"]):
                print("WORLD_CAPTURE_FAIL: current violet edge palette is not used by both symmetric ground rails",flush=True)
                return False
            if ("private static final double GROUND_MIN_HALF_WIDTH=.045D;" not in camera_source
                    or "private static final double GROUND_MAX_HALF_WIDTH=" not in camera_source
                    or "GROUND_BASE_HALF_WIDTH+GROUND_WAVE_HALF_WIDTH_RANGE+GROUND_WIDTH_FLICKER;" not in camera_source
                    or "double halfWidth=Math.max(GROUND_MIN_HALF_WIDTH," not in camera_source
                    or "GROUND_BASE_HALF_WIDTH+GROUND_WAVE_HALF_WIDTH_RANGE*wave+widthFlicker);" not in camera_source):
                print("WORLD_CAPTURE_FAIL: tested ground core width range is not the range used by the render pulse",flush=True)
                return False
            rebuild_source_path=ROOT/"addon/src/main/java/com/blackfrequency/rift/client/RiftClientState.java"
            rebuild_source=rebuild_source_path.read_text(encoding="utf-8",errors="replace")
            rebuild_match=re.search(r"REBUILD_INTERVAL\s*=\s*(\d+)L",rebuild_source)
            if not rebuild_match:
                print("WORLD_CAPTURE_FAIL: could not read ground mesh rebuild interval from source",flush=True)
                return False
            rebuild_interval=int(rebuild_match.group(1))
            if not 1<=rebuild_interval<=20:
                print(f"WORLD_CAPTURE_FAIL: ground mesh rebuild interval is outside the safe 1..20 tick range: {rebuild_interval}",flush=True)
                return False
            rebuild_marker=f"BLACK_FREQUENCY_GROUND_MESH_REBUILD_PROFILE_VERIFIED verticalDelta=2 interval={rebuild_interval}"
            if rebuild_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: player-height cache invalidation self-check was not executed",flush=True)
                return False
            overflow_marker=("BLACK_FREQUENCY_TEST GROUND_MESH_REBUILD_LONG_OVERFLOW_GUARD_VERIFIED "
                             "forwardExtreme=true nearMaxBefore=true nearMaxAt=true negativeClock=true backwardReset=true")
            if overflow_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: ground mesh rebuild interval overflow guard was not verified",flush=True)
                return False
            if ("previousTick<=Long.MAX_VALUE-REBUILD_INTERVAL" not in rebuild_source
                    or "tick>=previousTick+REBUILD_INTERVAL" not in rebuild_source
                    or "tick-previousTick>=REBUILD_INTERVAL" in rebuild_source):
                print("WORLD_CAPTURE_FAIL: ground mesh rebuild interval is not overflow-safe",flush=True)
                return False
            if os.environ.get("BF_TEST_RESTORATION")=="1":
                # A full fracture can reach 95,000 snapshots. Recovery restores at
                # most 12 blocks per 20 TPS tick (~396s at the cap); leave headroom
                # for client capture and the final stabilization hold. Do not raise
                # the gameplay restore budget or force-load chunks to satisfy CI.
                deadline_restore=time.time()+480
                restored=False
                restoration_capture_attempted_markers=set()
                while time.time()<deadline_restore and p.poll() is None:
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                    try_beacon_click(current)
                    initial_pulse_marker="BLACK_FREQUENCY_TEST RESTORE_INITIAL_BEACON_PULSE_EMITTED ring=24 sparks=4 viewer=true"
                    stabilization_pulse_marker="BLACK_FREQUENCY_TEST BEACON_STABILIZATION_PULSE viewerScan=shared"
                    aura_restoration_marker="BLACK_FREQUENCY_TEST BEACON_AURA_RESTORATION_ACTIVE_RENDERED progressZero=true loadedOnly=true"
                    for pulse_marker in (initial_pulse_marker,stabilization_pulse_marker):
                        if (not restoration_shot and aura_restoration_marker in current
                                and pulse_marker in current
                                and pulse_marker not in restoration_capture_attempted_markers
                                and "BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED" not in current):
                            restoration_capture_attempted_markers.add(pulse_marker)
                            from PIL import Image
                            capture_deadline=time.time()+1.1
                            while time.time()<capture_deadline and p.poll() is None:
                                snap("beacon-restoration-active.png")
                                with Image.open(shots/"beacon-restoration-active.png") as frame:
                                    active_metrics=measure_restoration_palette(frame.convert("RGB"))
                                if restoration_palette_passes(active_metrics):
                                    restoration_shot=True
                                    print("WORLD_CAPTURE: active restoration Dust captured after a real beacon pulse",
                                          active_metrics,flush=True)
                                    break
                                time.sleep(.08)
                            if not restoration_shot:
                                print("WORLD_CAPTURE: pulse reached the client but no bounded Dust frame was visible yet",
                                      pulse_marker,flush=True)
                    if "BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED" in current:
                        restored=True
                        break
                    capture_sky_before_beacon()
                    time.sleep(.1)
                if restored:
                    # The server sends the inactive state before the completion title.
                    # Require one real client render from the short aura fade, even if
                    # the server consumes the physical beacon immediately afterward.
                    fade_start_marker="BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_RENDERED"
                    fade_midpoint_marker="BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_MIDPOINT_RENDERED"
                    fade_tail_marker="BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_TAIL_RENDERED"
                    fade_deadline=time.time()+5.0
                    fade_midpoint_shot=False
                    fade_tail_shot=False
                    fade_logs=""
                    while time.time()<fade_deadline and p.poll() is None:
                        fade_logs=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                        if fade_midpoint_marker in fade_logs and not fade_midpoint_shot:
                            snap("beacon-restoration-fade-midpoint.png")
                            fade_midpoint_shot=True
                        if fade_tail_marker in fade_logs and not fade_tail_shot:
                            snap("beacon-restoration-fade-out.png")
                            fade_tail_shot=True
                        if fade_midpoint_shot and fade_tail_shot:
                            break
                        time.sleep(.02)
                    start_match=re.search(
                        r"BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_RENDERED ticksRemaining=(\d+)",
                        fade_logs)
                    midpoint_match=re.search(
                        r"BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_MIDPOINT_RENDERED ticksRemaining=(\d+) alphaFactor=([0-9.eE+-]+)",
                        fade_logs)
                    tail_match=re.search(
                        r"BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_TAIL_RENDERED ticksRemaining=(\d+) alphaFactor=([0-9.eE+-]+)",
                        fade_logs)
                    if not (fade_start_marker in fade_logs and fade_midpoint_shot and fade_tail_shot
                            and start_match and midpoint_match and tail_match):
                        print("WORLD_CAPTURE_FAIL: completed restoration did not render start, midpoint and tail of the beacon fade",flush=True)
                        return False
                    start_ticks=int(start_match.group(1))
                    midpoint_ticks=int(midpoint_match.group(1))
                    midpoint_alpha=float(midpoint_match.group(2))
                    tail_ticks=int(tail_match.group(1))
                    tail_alpha=float(tail_match.group(2))
                    if not (start_ticks>midpoint_ticks>tail_ticks
                            and .25<midpoint_alpha<=.60
                            and 1.0/255.0<tail_alpha<=.12):
                        print("WORLD_CAPTURE_FAIL: beacon fade render milestones were out of order or outside their alpha windows",
                              {"startTicks":start_ticks,"midpointTicks":midpoint_ticks,
                               "midpointAlpha":midpoint_alpha,"tailTicks":tail_ticks,
                               "tailAlpha":tail_alpha},flush=True)
                        return False
                    print("WORLD_CAPTURE: real beacon fade midpoint and visible tail captured",
                          {"startTicks":start_ticks,"midpointTicks":midpoint_ticks,
                           "midpointAlpha":midpoint_alpha,"tailTicks":tail_ticks,
                           "tailAlpha":tail_alpha},flush=True)
                    # Wait for the explicit title fade-in window so this frame proves
                    # the final UI, not 99% or the preceding containment effect.
                    time.sleep(0.75)
                    snap("beacon-restoration-finished.png")
                    print("WORLD_CAPTURE: restoration fade-out and final title captured",flush=True)
                else:
                    snap("beacon-restoration-timeout.png")
                    print("WORLD_CAPTURE_FAIL: restoration did not finish in client",flush=True)
                    return False
                if not restoration_shot:
                    print("WORLD_CAPTURE_FAIL: restoration finished without an active gameplay screenshot",flush=True)
                    return False
                restoration_path=shots/"beacon-restoration-active.png"
                fade_midpoint_path=shots/"beacon-restoration-fade-midpoint.png"
                fade_restoration_path=shots/"beacon-restoration-fade-out.png"
                finished_restoration_path=shots/"beacon-restoration-finished.png"
                if not fade_midpoint_path.is_file() or not fade_restoration_path.is_file():
                    print("WORLD_CAPTURE_FAIL: beacon restoration fade midpoint/tail screenshot is missing",flush=True)
                    return False
                from PIL import Image
                with Image.open(restoration_path) as source:
                    active_restoration_metrics=measure_restoration_palette(source.convert("RGB"))
                with Image.open(finished_restoration_path) as source:
                    finished_frame=source.convert("RGB")
                    finished_restoration_metrics=measure_restoration_palette(finished_frame)
                    finished_title_metrics=measure_completion_title_width(finished_frame)
                if not restoration_palette_evidence_passes(
                        active_restoration_metrics,finished_restoration_metrics):
                    print("WORLD_CAPTURE_FAIL: neither active nor completion frame shows bounded violet restoration Dust",
                          {"active":active_restoration_metrics,"finished":finished_restoration_metrics},flush=True)
                    return False
                palette_frame=("active" if restoration_palette_passes(active_restoration_metrics)
                               else "finished")
                print("WORLD_CAPTURE: restoration violet Dust palette verified",
                      {"capture":palette_frame,"active":active_restoration_metrics,
                       "finished":finished_restoration_metrics},flush=True)
                if not completion_title_width_passes(finished_title_metrics,finished_frame.size[0]):
                    print("WORLD_CAPTURE_FAIL: completion title exceeds compact width budget",
                          finished_title_metrics,flush=True)
                    return False
                print("WORLD_CAPTURE: compact completion title width verified",
                      finished_title_metrics,flush=True)
                if not terrain_shot:
                    print("WORLD_CAPTURE_FAIL: no player-visible ground fissure was captured",flush=True)
                    return False
                print("WORLD_CAPTURE: restoration finished in integrated client",flush=True)
            log_after=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            if os.environ.get("BF_TEST_NATURAL_BEACON")=="1":
                if not mine_beacon_after_restoration(log_after):
                    return False
                log_after=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else log_after
            if (os.environ.get("BF_TEST_RESTORATION")=="1"
                    and "BLACK_FREQUENCY_TEST RESTORATION_PARTICLE_PROFILE_VERIFIED ring=24 ringIntervalTicks=20 sparks=4 helix=16 helixIntervalTicks=8 blockCue=6 blockIntervalBlocks=48 audience=8 audienceIntervalTicks=20 cachedDust=true" not in log_after):
                print("WORLD_CAPTURE_FAIL: restoration particle cadence/profile was not verified",flush=True)
                return False
            size_marker="BLACK_FREQUENCY_TEST RESTORATION_PARTICLE_SIZE_VERIFIED baseScale=0.58 accentScale=0.76 audienceSpreadXZ=0.85 audienceSpreadY=0.55 cachedDust=true"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and size_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: bounded restoration particle size/spread was not verified",flush=True)
                return False
            if os.environ.get("BF_TEST_RESTORATION")=="1":
                ring_clearance=re.search(
                    r"BLACK_FREQUENCY_TEST RESTORATION_RING_CLEARANCE_VERIFIED offset=([0-9.]+) minOffset=([0-9.]+) maxOffset=([0-9.]+) clearsBeaconTop=true",
                    log_after)
                if not ring_clearance:
                    print("WORLD_CAPTURE_FAIL: recovery ring vertical envelope was not verified",flush=True)
                    return False
                ring_offset,ring_min,ring_max=map(float,ring_clearance.groups())
                if (not all(map(math.isfinite,(ring_offset,ring_min,ring_max)))
                        or ring_offset<=1.0 or ring_offset>1.5
                        or ring_min<=1.0 or ring_max>2.0 or ring_min>ring_max):
                    print("WORLD_CAPTURE_FAIL: recovery ring extends outside its safe beacon-relative envelope",
                          {"offset":ring_offset,"minOffset":ring_min,"maxOffset":ring_max},flush=True)
                    return False
                print("WORLD_CAPTURE: recovery ring clears beacon and remains vertically bounded",
                      {"offset":ring_offset,"minOffset":ring_min,"maxOffset":ring_max},flush=True)
            snapshot_payload_marker="BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_PAYLOAD_QUARANTINE_VERIFIED entries=2 retainedPayload=false"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and snapshot_payload_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: oversized recovery snapshot payload was not quarantined",flush=True)
                return False
            beacon_position_marker="BLACK_FREQUENCY_TEST RECOVERY_BEACON_POSITION_PRESENCE_VERIFIED missingOrWrongTypeRejected=true originCoordinatePreserved=true fallback=rift-anchor"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and beacon_position_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: missing/mistyped saved beacon position was not distinguished from BlockPos.ZERO",flush=True)
                return False
            snapshot_retention_marker="BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_RETENTION_BUDGET_VERIFIED "
            if os.environ.get("BF_TEST_RESTORATION")=="1" and snapshot_retention_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: aggregate recovery snapshot retention budget was not verified",flush=True)
                return False
            snapshot_aggregate_marker="BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_AGGREGATE_BUDGET_QUARANTINED "
            if os.environ.get("BF_TEST_RESTORATION")=="1" and snapshot_aggregate_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: aggregate recovery snapshot payloads were not quarantined",flush=True)
                return False
            scan_marker="BLACK_FREQUENCY_TEST RESTORE_PLAYER_SCAN_PROFILE_VERIFIED intervalTicks=4 menuClose=shared viewerScan=shared loadedOnly=true"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and scan_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: beacon-menu closure did not use the shared bounded player scan",flush=True)
                return False
            particle_audience_marker="BLACK_FREQUENCY_TEST RESTORE_PARTICLE_AUDIENCE_BOUNDS_VERIFIED maxDistance=37.0 maxDistanceSquared=1369.0 packetRange=32.0 effectEnvelope=5.0 strictBoundary=true invalidRejected=true"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and particle_audience_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: beacon particle audience did not match the bounded packet radius",flush=True)
                return False
            anomaly_marker="BLACK_FREQUENCY_TEST GRAVITY_ANOMALY_PARTICLE_PALETTE_VERIFIED base=deep-violet accent=bright-violet cached=true"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and anomaly_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: cached violet gravity anomaly particle palette was not verified",flush=True)
                return False
            anomaly_field_marker="BLACK_FREQUENCY_TEST ANOMALY_FIELD_TURBULENCE_VERIFIED baseWeight=1.0 crossAmplitude=0.12 bounded=true deterministic=true liftPocket=true sinkPocket=true timeDrift=true invalidRejected=true"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and anomaly_field_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: shared bounded gravity anomaly field was not verified",flush=True)
                return False
            multi_scale_marker="BLACK_FREQUENCY_TEST ANOMALY_FIELD_MULTI_SCALE_VERIFIED turbulenceAmplitude=0.06 samples=100 deterministic=true bounded=true"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and multi_scale_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: multi-scale gravity anomaly field was not verified",flush=True)
                return False
            ui_palette_marker="BLACK_FREQUENCY_TEST RECOVERY_UI_PALETTE_VERIFIED rgb=5714c7 legacyFormatting=false"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and ui_palette_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: recovery title/action bar did not use the explicit deep-violet RGB palette",flush=True)
                return False
            if os.environ.get("BF_TEST_PLAYER_BEACON")=="1":
                initial_pulse=re.search(
                    r"BLACK_FREQUENCY_TEST RESTORE_INITIAL_BEACON_PULSE_EMITTED ring=(\d+) sparks=(\d+) viewer=true",
                    log_after)
                if not initial_pulse or tuple(map(int,initial_pulse.groups()))!=(24,4):
                    print("WORLD_CAPTURE_FAIL: the real beacon click did not emit its initial ring and rising sparks",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST RESTORATION_COMPLETION_TITLE_TIMING_VERIFIED fadeIn=10 stay=80 fadeOut=20 total=110 packetBeforeTitle=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: explicit completion title timing was not verified",flush=True)
                    return False
                counts=re.search(r"BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED restored=([0-9]+) destroyed=([0-9]+) skippedOccupied=([0-9]+)",log_after)
                if not counts:
                    print("WORLD_CAPTURE_FAIL: missing final restoration accounting",flush=True)
                    return False
                restored_count,damaged_count,skipped_count=map(int,counts.groups())
                if restored_count+skipped_count!=damaged_count:
                    print("WORLD_CAPTURE_FAIL: unaccounted damaged blocks",restored_count,damaged_count,skipped_count,flush=True)
                    return False
                print("WORLD_CAPTURE: restoration accounting verified",restored_count,skipped_count,damaged_count,flush=True)
                if os.environ.get("BF_TEST_SPARSE_RECOVERY")=="1":
                    sparse=re.search(r"BLACK_FREQUENCY_TEST SPARSE_RECOVERY_ELIGIBLE edits=(\d+) pending=(\d+)",log_after)
                    if not sparse:
                        print("WORLD_CAPTURE_FAIL: sparse fracture never reached beacon eligibility",flush=True)
                        return False
                    sparse_edits,sparse_pending=map(int,sparse.groups())
                    if not (0<sparse_edits<160 and sparse_pending==sparse_edits and damaged_count==sparse_edits):
                        print("WORLD_CAPTURE_FAIL: sparse recovery counters disagree",
                              sparse_edits,sparse_pending,damaged_count,flush=True)
                        return False
                    print("WORLD_CAPTURE: SPARSE_NATURAL_BEACON_RECOVERY_VERIFIED",
                          restored_count,skipped_count,damaged_count,flush=True)
            if os.environ.get("BF_TEST_NATURAL_BEACON")=="1":
                markers={
                    "removed":re.search(r"BLACK_FREQUENCY_TEST NATURAL_BEACON_REMOVED_FOR_TEST x=(-?\d+) y=(-?\d+) z=(-?\d+)",log_after),
                    "missing":re.search(r"BLACK_FREQUENCY_TEST NATURAL_BEACON_MISSING x=(-?\d+) y=(-?\d+) z=(-?\d+)",log_after),
                    "replaced":re.search(r"BLACK_FREQUENCY_TEST NATURAL_BEACON_REPLACED x=(-?\d+) y=(-?\d+) z=(-?\d+)",log_after),
                    "placed":re.search(r"BLACK_FREQUENCY_TEST NATURAL_BEACON_PLACED x=(-?\d+) y=(-?\d+) z=(-?\d+)",log_after),
                    "clicked":re.search(r"BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION x=(-?\d+) y=(-?\d+) z=(-?\d+) natural=true",log_after),
                }
                if (not all(markers.values())
                        or not (markers["removed"].start()<markers["missing"].start()
                                <markers["replaced"].start()<markers["placed"].start()
                                <markers["clicked"].start())
                        or markers["removed"].groups()!=markers["missing"].groups()
                        or markers["replaced"].groups()!=markers["placed"].groups()
                        or markers["placed"].groups()!=markers["clicked"].groups()):
                    print("WORLD_CAPTURE_FAIL: lost natural beacon was not replaced and clicked",flush=True)
                    return False
                print("WORLD_CAPTURE: NATURAL_BEACON_RESPAWN_CLICK_VERIFIED",*markers["clicked"].groups(),flush=True)
                if os.environ.get("BF_TEST_SNOW_BEACON")=="1":
                    snow=re.search(r"BLACK_FREQUENCY_TEST SNOW_BEACON_SUPPORT_VERIFIED x=(-?\d+) y=(-?\d+) z=(-?\d+) snowY=(-?\d+)",log_after)
                    if (not snow or tuple(snow.groups()[:3])!=markers["clicked"].groups()
                            or int(snow.group(4))!=int(snow.group(2))-1):
                        print("WORLD_CAPTURE_FAIL: snow-supported replacement beacon was not physically clicked",flush=True)
                        return False
                    print("WORLD_CAPTURE: SNOW_BEACON_CLICK_VERIFIED",*snow.groups(),flush=True)
                preserved=log_after.find("BLACK_FREQUENCY_TEST CANCELED_BEACON_BREAK_PRESERVED")
                mined=log_after.find("BLACK_FREQUENCY_TEST NATURAL_BEACON_MINED_NO_DROP")
                consumed=log_after.find("BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_CONSUMED")
                no_drop=log_after.find("BLACK_FREQUENCY_TEST REAL_PLAYER_BEACON_BREAK_NO_DROP_VERIFIED")
                if not (0<=preserved<mined<consumed<no_drop):
                    print("WORLD_CAPTURE_FAIL: real player break must follow protection cancellation and verify no drops",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST NATURAL_BEACON_ID_CLEARED_AFTER_BREAK" not in log_after:
                    print("WORLD_CAPTURE_FAIL: natural beacon ownership UUID was not cleared after real mining",flush=True)
                    return False
                print("WORLD_CAPTURE: POST_RESTORE_BEACON_BREAK_GUARD_VERIFIED realPlayerInput=true noDrop=true",flush=True)
            fissure=parse_fissure_particle_emission(log_after)
            ground_mesh=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_RENDERED segments=(\d+) arms=(\d+) anchorX=(-?\d+) anchorZ=(-?\d+) sampledColumns=(\d+) cacheHits=(\d+)",
                log_after)
            if not ground_mesh:
                print("WORLD_CAPTURE_FAIL: client did not render a bounded ground fissure mesh",flush=True)
                return False
            continuity=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_STATS maxStep=([-+0-9.eE]+) steepStepsSkipped=(\d+)",
                log_after)
            if "BLACK_FREQUENCY_GROUND_MESH_STRUCTURAL_SUPPORT_FILTER_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: client ground mesh did not verify structural-surface filtering",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_BLOCK_ENTITY_FILTER_VERIFIED support=true surface=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: client ground mesh did not reject block-entity fixtures",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_SAMPLE_CURSOR_REUSE_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: ground sampler did not verify shared mutable cursor reuse",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_SAMPLE_CACHE_REUSE_VERIFIED retained=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: primitive surface cache was not verified for reuse",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_SAMPLE_CACHE_SENTINEL_VERIFIED miss=positiveInfinity unsupported=nan singleLookup=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: ground sample cache miss sentinel did not preserve unsupported-column entries",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_PARTIAL_TICK_GUARD_VERIFIED finiteClamped=true nonFiniteFallback=zero" not in log_after:
                print("WORLD_CAPTURE_FAIL: ground fissure animation did not verify finite partial-tick handling",flush=True)
                return False
            cloud_source_path=ROOT/"addon/src/main/java/com/blackfrequency/rift/client/RiftCloudEffects.java"
            cloud_source_text=cloud_source_path.read_text(encoding="utf-8",errors="replace")
            if ("ResourceLocation.tryBuild(\"minecraft\",\"overworld\")" not in cloud_source_text
                    or "event.register(OVERWORLD_ID,new RiftOverworldEffects());" not in cloud_source_text
                    or "new ResourceLocation(\"minecraft\",\"overworld\")" in cloud_source_text):
                print("WORLD_CAPTURE_FAIL: Overworld effects registration still uses the deprecated ResourceLocation constructor",flush=True)
                return False
            source_path=ROOT/"addon/src/main/java/com/blackfrequency/rift/client/RiftClientState.java"
            source_text=source_path.read_text(encoding="utf-8",errors="replace")
            build_source=re.search(
                r"private static GroundMesh buildSegments\([\s\S]*?private static int estimateAnchorSurfaceY",
                source_text)
            if (not re.search(r"private static final Long2DoubleOpenHashMap SURFACE_Y_BY_COLUMN",source_text)
                    or not re.search(r"private static final BlockPos\.MutableBlockPos SURFACE_CURSOR",source_text)
                    or "cache.defaultReturnValue(SURFACE_CACHE_MISS);" not in source_text
                    or not build_source or "SURFACE_Y_BY_COLUMN.clear();" not in build_source.group(0)
                    or "if(sampledY==SURFACE_CACHE_MISS)" not in build_source.group(0)
                    or "SURFACE_Y_BY_COLUMN.containsKey(key)" in build_source.group(0)):
                print("WORLD_CAPTURE_FAIL: ground sample cache does not use the single-lookup miss sentinel",flush=True)
                return False
            if ("BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_VERIFIED maxStep=1.25 maxGrade=2.5" not in log_after
                    or not continuity or abs(float(continuity.group(1))-1.25)>1.0E-9):
                print("WORLD_CAPTURE_FAIL: ground mesh did not verify its cliff-discontinuity guard",
                      flush=True)
                return False
            if ("BLACK_FREQUENCY_GROUND_MESH_SINGLE_STEP_BRIDGE_VERIFIED "
                    "maxStep=1.0 spanSamples=2 plateau=true bump=true repeatedStepRejected=true "
                    "cliffGuard=true invalidRejected=true" not in log_after):
                print("WORLD_CAPTURE_FAIL: isolated one-block ground-step bridge policy was not verified",
                      flush=True)
                return False
            grade_guard_marker=(
                "BLACK_FREQUENCY_GROUND_MESH_GRADE_GUARD_VERIFIED "
                "maxGrade=2.5 oneBlockRiseRejected=true snowAccepted=true cliffCapProfile=true"
            )
            if grade_guard_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: slope-aware ground fissure guard was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_GRADE_BUDGET_VERIFIED previousMaxRise=0.5625 currentMaxRise=0.5625 same=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: denser mesh changed the maximum allowed terrain rise",flush=True)
                return False
            grade_stats=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_GRADE_STATS maxGrade=([-+0-9.eE]+) "
                r"cliffCaps=(\d+) unsupportedGapCaps=(\d+) singleStepBridges=(\d+) steepStepsSkipped=(\d+)",
                log_after)
            if (not grade_stats
                    or abs(float(grade_stats.group(1))-2.5)>1.0E-9
                    or int(grade_stats.group(2))>int(grade_stats.group(5))
                    or int(grade_stats.group(4))+int(grade_stats.group(5))>GROUND_MESH_SAMPLES):
                print("WORLD_CAPTURE_FAIL: cliff-end taper or single-step bridge accounting is invalid",flush=True)
                return False
            if ("private static final double MAX_GROUND_SEGMENT_GRADE=2.5D;" not in camera_source
                    or "isContinuousGroundStep(previous.y(),current.y(),length)" not in camera_source
                    or "private static boolean canStartSingleGroundStepBridge" not in camera_source
                    or "private static boolean canBridgeSingleGroundStep" not in camera_source
                    or "canStartSingleGroundStepBridge(previous.y(),current.y(),length)" not in build_source.group(0)
                    or "canBridgeSingleGroundStep(previous.y(),pendingStep.y(),current.y()," not in build_source.group(0)
                    or "pendingStep=null;" not in build_source.group(0)
                    or "capLastGroundSegmentAtGap" not in camera_source):
                print("WORLD_CAPTURE_FAIL: renderer is not using slope-aware continuity and gap caps",flush=True)
                return False
            mesh_segments=int(ground_mesh.group(1))
            mesh_arms=int(ground_mesh.group(2))
            mesh_columns=int(ground_mesh.group(5))
            mesh_cache_hits=int(ground_mesh.group(6))
            if not (0<mesh_columns<GROUND_MESH_SAMPLES
                    and mesh_cache_hits>0
                    and mesh_columns+mesh_cache_hits==GROUND_MESH_SAMPLES):
                print("WORLD_CAPTURE_FAIL: per-build ground-column cache accounting is invalid",
                      "columns",mesh_columns,"cacheHits",mesh_cache_hits,flush=True)
                return False
            if (mesh_segments<MIN_GROUND_MESH_SEGMENTS
                    or mesh_segments>MAX_GROUND_MESH_SEGMENTS
                    or mesh_arms<MIN_GROUND_MESH_ARMS or mesh_arms>MAX_GROUND_MESH_ARMS):
                print("WORLD_CAPTURE_FAIL: final ground mesh is outside the bounded segment/arm budget",
                      "segments",mesh_segments,"arms",mesh_arms,flush=True)
                return False
            density = re.search(
                r"BLACK_FREQUENCY_TEST SURFACE_FISSURE_DENSITY_VERIFIED samples=(\d+) spacing=([0-9.]+) extent=([0-9.]+) bend=([0-9.]+)",
                log_after)
            if not density:
                print("WORLD_CAPTURE_FAIL: fissure density/radius self-check was not executed",flush=True)
                return False
            density_samples=int(density.group(1))
            density_spacing=float(density.group(2))
            density_extent=float(density.group(3))
            density_bend=float(density.group(4))
            if (density_samples!=FISSURE_PARTICLE_SAMPLES
                    or density_spacing<0.45 or density_spacing>0.5
                    or not math.isclose(density_extent,12*density_spacing,
                                        rel_tol=0.0,abs_tol=1.0e-9)
                    or density_extent>=48.0
                    or not math.isclose(density_bend,.24,rel_tol=0.0,abs_tol=1.0e-9)):
                print("WORLD_CAPTURE_FAIL: fissure density/radius is outside the verified bounds",
                      "samples",density_samples,"spacing",density_spacing,
                      "extent",density_extent,flush=True)
                return False
            ground_sampling = re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_SAMPLING_PROFILE_VERIFIED samples=(\d+) spacing=([0-9.]+) extent=([0-9.]+) radius=(\d+) bend=([0-9.]+)",
                log_after)
            if not ground_sampling:
                print("WORLD_CAPTURE_FAIL: client ground mesh sampling profile was not executed",flush=True)
                return False
            client_samples=int(ground_sampling.group(1))
            client_spacing=float(ground_sampling.group(2))
            client_extent=float(ground_sampling.group(3))
            client_radius=int(ground_sampling.group(4))
            client_bend=float(ground_sampling.group(5))
            if (client_samples!=GROUND_MESH_SAMPLES
                    or not math.isclose(client_spacing,density_spacing*.5,rel_tol=0.0,abs_tol=1.0e-9)
                    or not math.isclose(client_extent,density_extent,rel_tol=0.0,abs_tol=1.0e-9)
                    or client_radius!=48 or client_extent>=client_radius
                    or not math.isclose(client_bend,density_bend,rel_tol=0.0,abs_tol=1.0e-9)
                    or "BLACK_FREQUENCY_GROUND_MESH_DENSITY_VERIFIED meshSamples=96 serverSamples=48 ratio=2 evenSamplesAligned=true extent=5.4" not in log_after):
                print("WORLD_CAPTURE_FAIL: denser client mesh no longer aligns with the server fissure path",
                      "server",density_spacing,density_extent,
                      "client",client_spacing,client_extent,"radius",client_radius,flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_BEND_PROFILE_VERIFIED amplitude=0.24 maxOffset=0.24 shared=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: server fissure bend profile was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_BEND_PROFILE_VERIFIED amplitude=0.24 maxOffset=0.24 shared=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: client fissure bend profile was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_SAMPLE_CACHE_VERIFIED samples=48 geometryBitExact=true pulseBitExact=true geometryTrigPerEmission=0 dynamicPulseSin=48 radius=48" not in log_after:
                print("WORLD_CAPTURE_FAIL: server fissure sample cache was not bit-verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLE_BUDGET_VERIFIED total=52 accentDust=8 violetDust=40 edgeDust=4" not in log_after:
                print("WORLD_CAPTURE_FAIL: violet-dust fissure particle budget self-check was not executed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_ACCENT_DUST_VERIFIED red=0.3 green=0.14 blue=0.88 scale=0.85" not in log_after:
                print("WORLD_CAPTURE_FAIL: bright-violet accent DustParticleOptions was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_VIOLET_DUST_VERIFIED red=0.34 green=0.08 blue=0.78 scale=0.85" not in log_after:
                print("WORLD_CAPTURE_FAIL: explicit deep-violet DustParticleOptions color was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_DUST_PULSE_PROFILE_VERIFIED min=0.65 low=0.75 base=0.85 high=0.95 max=1.05 variants=5" not in log_after:
                print("WORLD_CAPTURE_FAIL: cached fissure dust pulse profile was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET_SELECTION_VERIFIED policy=balanced-view targetPitch=45 ties=stable invalidRejected=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: balanced fissure camera target policy was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_STRUCTURAL_SUPPORT_FILTER_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: server did not reject structural blocks as fissure terrain",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_VERIFIED red=0.42 green=0.18 blue=0.92 scale=0.5" not in log_after:
                print("WORLD_CAPTURE_FAIL: compact violet fissure edge Dust profile was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_LAYOUT_VERIFIED count=4 color=violet perArm=1 outerTip=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: four compact violet edge Dust motes were not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_CACHE_VERIFIED cached=true scale=0.5" not in log_after:
                print("WORLD_CAPTURE_FAIL: compact violet edge Dust option was not verified as cached",flush=True)
                return False
            if not valid_fissure_particle_emission(fissure):
                print("WORLD_CAPTURE_FAIL: deep-violet dust/accent composition or loaded sample count is invalid",
                      fissure,flush=True)
                return False
            edge_dust=re.search(
                r"BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_EMITTED count=([0-9]+) material=dust scale=0.5 fallbackArms=([0-9]+)",
                log_after)
            if (not edge_dust or int(edge_dust.group(1))!=4
                    or int(edge_dust.group(1))!=fissure[3]
                    or not 0<=int(edge_dust.group(2))<=4):
                print("WORLD_CAPTURE_FAIL: one compact edge Dust mote per valid fissure arm was not emitted",
                      flush=True)
                return False
            print("WORLD_CAPTURE: deep-violet base Dust and bright-violet accent Dust verified",fissure[0],flush=True)
            print("WORLD_CAPTURE: compact violet fissure edge Dust verified",edge_dust.group(1),flush=True)
            if not fracture_seen:
                print("WORLD_CAPTURE_FAIL: ground fracture never removed a block",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST FINALE_STARTED" not in log_after:
                print("WORLD_CAPTURE_FAIL: screenshots exist, but accelerated finale not observed",flush=True)
                return False
            if os.environ.get("BF_TEST_PLAYER_BEACON")=="1":
                if "BLACK_FREQUENCY_TEST PLAYER_RIFT_IMPULSES_SUPPRESSED_FOR_BEACON_TEST" not in log_after:
                    print("WORLD_CAPTURE_FAIL: test-only rift impulse suppression was not observed",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST PLAYER_VANILLA_FALL_SUPPRESSED_FOR_BEACON_TEST" not in log_after:
                    print("WORLD_CAPTURE_FAIL: vanilla fall was not held while the spawn support was fractured",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST PLAYER_VANILLA_FALL_RESTORED_AFTER_SAFE_PAD" not in log_after:
                    print("WORLD_CAPTURE_FAIL: vanilla fall was not restored after a verified safe pad",flush=True)
                    return False
                if re.search(r"\bEHTID_CI fell from a high place\b",log_after):
                    print("WORLD_CAPTURE_FAIL: test player died before beacon recovery",flush=True)
                    return False
                if not re.search(
                        r"BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT x=-?\d+ y=-?\d+ z=-?\d+ support=solid fallDistance=0\.0",
                        log_after):
                    print("WORLD_CAPTURE_FAIL: player was not reset onto a verified safe beacon pad",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_APPROACH_SIDE_SELECTED" not in log_after:
                    print("WORLD_CAPTURE_FAIL: server did not select a clear loaded-only beacon approach side",flush=True)
                    return False
                if "fissureVisible=true loadedOnly=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: selected beacon approach cannot see a loaded fissure sample",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_VERIFIED hit=beacon loadedChunksOnly=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: server did not verify a loaded-only line of sight to the physical beacon",flush=True)
                    return False
                if not beacon_aim_verified:
                    print("WORLD_CAPTURE_FAIL: client camera was not restored to the measured physical beacon aim",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION" not in log_after:
                    print("WORLD_CAPTURE_FAIL: beacon was not activated by actual player right-click",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_AURA_ROTATION_PROFILE_VERIFIED periodTicks=1200 outerTurns=1 innerTurns=-2 wrapContinuous=true partialBounded=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: beacon aura rotation profile was not verified",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_AURA_DISTANCE_FADE_PROFILE_VERIFIED start=40.0 end=48.0 curve=smoothstep monotonic=true invalidRejected=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: beacon aura distance fade profile was not verified",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_AURA_RENDERED cages=2 edgeRibbons=24 rotation=counterphase loadedOnly=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: rotating violet physical beacon containment aura was not drawn from the loaded beacon block",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_AURA_RESTORATION_ACTIVE_RENDERED progressZero=true loadedOnly=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: physical beacon aura disappeared during the zero-progress stabilization hold",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_START_ACTIONBAR_ONLY" not in log_after:
                    print("WORLD_CAPTURE_FAIL: beacon activation did not use the non-obstructive action bar",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST IDLE_BEACON_HINT_LIFECYCLE_VERIFIED idleBoundary=true restorationSuppressed=true completedSuppressed=true acceleratedTickSafe=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: idle beacon hint was not suppressed during recovery",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST IDLE_BEACON_PARTICLE_CADENCE_VERIFIED intervalTicks=20 particles=6 cachedDust=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: idle beacon Dust cadence was not verified",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST IDLE_BEACON_PARTICLE_AUDIENCE_BOUNDS_VERIFIED maxDistance=32.0 maxDistanceSquared=1024.0 strictBoundary=true invalidRejected=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: idle beacon particle audience exceeded the packet delivery radius",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST SURFACE_FRACTURE_PARTICLE_AUDIENCE_BOUNDS_VERIFIED maxDistance=32.0 maxDistanceSquared=1024.0 strictBoundary=true invalidRejected=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: surface fracture particle audience exceeded the packet delivery radius",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST IDLE_BEACON_VIOLET_PALETTE_VERIFIED red=0.48 green=0.14 blue=0.98 scale=0.9 count=6 intervalTicks=20 cached=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: bright violet beacon palette was not verified",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST IDLE_BEACON_PARTICLE_HALO_PROFILE_VERIFIED spreadXZ=0.34 spreadY=0.08 speed=0.006 flattened=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: idle beacon particle halo was not flattened and bounded",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST IDLE_BEACON_VIOLET_DUST_EMITTED count=6 red=0.48 green=0.14 blue=0.98 scale=0.9" not in log_after:
                    print("WORLD_CAPTURE_FAIL: bright violet idle beacon Dust was not emitted",flush=True)
                    return False
                after_click=shots/"beacon-after-right-click.png"
                if (not after_click.is_file()
                        or any(shots.glob("beacon-after-right-click_*.png"))):
                    print("WORLD_CAPTURE_FAIL: accepted beacon click did not produce one canonical screenshot",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST ACTUAL_PASSENGER_GRAVITY_IMMUNITY_VERIFIED" not in log_after:
                    print("WORLD_CAPTURE_FAIL: actual mounted-player gravity probe did not run",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST RESTORATION_GRAVITY_FADE_PLAYER_IMPULSE_VERIFIED full=true half=true zero=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: actual player gravity fade scaling probe did not run",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST PASSENGER_RIFT_IMPULSE_SUPPRESSED_VERIFIED controlImpulse=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: mounted player was not proven immune inside an active lift zone",flush=True)
                    return False
            if "BLACK_FREQUENCY_TEST CLIENT_CAMERA_ROTATION" not in log_after:
                print("WORLD_CAPTURE_FAIL: actual client camera rotation telemetry was not recorded",flush=True)
                return False
            if not sky_shot:
                print("WORLD_CAPTURE_FAIL: sky shader pixels were not visible in the upper-sky frame",flush=True)
                return False
            if "BLACK_FREQUENCY_RENDER_ACTIVE" not in log_after:
                print("WORLD_CAPTURE_FAIL: no actual sky renderer invocation observed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SKY_RENDER_THRESHOLD_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: client sky-render threshold regression was not executed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SKY_SHADER_READY_STATE_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: shader readiness lifecycle was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST FOG_INTERPOLATION_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: render-rate fog interpolation self-check was not executed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST FOG_INPUT_GUARDS_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: non-finite fog inputs were not guarded",flush=True)
                return False
            fog_color_profile=re.search(
                r"BLACK_FREQUENCY_TEST FOG_COLOR_BLEND_PROFILE_VERIFIED blendMin=([0-9.]+) blendMax=([0-9.]+)",
                log_after)
            if (not fog_color_profile
                    or not math.isclose(float(fog_color_profile.group(1)),.66,rel_tol=0.0,abs_tol=1.0e-6)
                    or not math.isclose(float(fog_color_profile.group(2)),.76,rel_tol=0.0,abs_tol=1.0e-6)):
                print("WORLD_CAPTURE_FAIL: violet fog blend profile is not the terrain-readable range",flush=True)
                return False
            fog_distance_profile=re.search(
                r"BLACK_FREQUENCY_TEST FOG_DISTANCE_PROFILE_VERIFIED contractionMin=([0-9.]+) contractionMax=([0-9.]+) farFloor=([0-9.]+) farAt16=([0-9.]+) farAt64=([0-9.]+) farAt256=([0-9.]+)",
                log_after)
            if (not fog_distance_profile
                    or not math.isclose(float(fog_distance_profile.group(1)),.62,rel_tol=0.0,abs_tol=1.0e-6)
                    or not math.isclose(float(fog_distance_profile.group(2)),.72,rel_tol=0.0,abs_tol=1.0e-6)
                    or not math.isclose(float(fog_distance_profile.group(3)),36.0,rel_tol=0.0,abs_tol=1.0e-6)
                    or not math.isclose(float(fog_distance_profile.group(4)),16.0,rel_tol=0.0,abs_tol=1.0e-6)
                    or not math.isclose(float(fog_distance_profile.group(5)),36.0,rel_tol=0.0,abs_tol=1.0e-6)
                    or not math.isclose(float(fog_distance_profile.group(6)),71.68,rel_tol=0.0,abs_tol=1.0e-3)):
                print("WORLD_CAPTURE_FAIL: terrain fog distance profile regressed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST VANILLA_CLOUD_SUPPRESSION_POLICY_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: vanilla cloud suppression policy was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST VANILLA_CLOUD_SHADER_FALLBACK_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: clouds are not preserved when the rift shader is unavailable",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST VANILLA_CLOUDS_SUPPRESSED" not in log_after:
                print("WORLD_CAPTURE_FAIL: vanilla clouds were not suppressed during the active rift",flush=True)
                return False
            beacon_click=log_after.rfind("BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION")
            cloud_restore=log_after.rfind("BLACK_FREQUENCY_TEST VANILLA_CLOUDS_RESTORED")
            if beacon_click<0 or cloud_restore<=beacon_click:
                print("WORLD_CAPTURE_FAIL: vanilla clouds were not restored after physical beacon activation",
                      flush=True)
                return False
            if "BLACK_FREQUENCY_TEST RESTORE_BEACON_PRESENCE_CACHED present=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: restoration did not confirm the loaded physical beacon",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST BEACON_STABILIZATION_PULSE viewerScan=shared" not in log_after:
                print("WORLD_CAPTURE_FAIL: shared nearby-player scan did not emit the beacon stabilization pulse",
                      flush=True)
                return False
            print("WORLD_CAPTURE: cloud fallback and shared physical-beacon viewer scan confirmed",flush=True)
            cleanup_marker="BLACK_FREQUENCY_TEST SKY_DEPTH_STATE_CLEANUP_VERIFIED"
            cleanup_count=log_after.count(cleanup_marker)
            if cleanup_count!=1:
                print(f"WORLD_CAPTURE_FAIL: expected one depth-state cleanup marker, observed {cleanup_count}",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SKY_DEPTH_AWARE_DRAW_SUBMITTED stage=after_weather" not in log_after:
                print("WORLD_CAPTURE_FAIL: post-weather depth-aware sky pass was not observed",flush=True)
                return False
            print("WORLD_CAPTURE: accelerated finale, post-weather depth-aware sky rendering, and depth-state cleanup confirmed",flush=True)
            return True
        time.sleep(2)
    print("WORLD_CAPTURE: no integrated-server marker; preserving diagnostic screenshot",flush=True)
    subprocess.run(["scrot","-a","0,0,1280,720",str(ROOT/"client-smoke-artifacts"/"world-attempt.png")],check=False)
    return False

latest=MC/"logs"/"latest.log"
deadline=time.time()+420
window_seen=False
ready_marker=False
rift_shader_ready=False
ready_since=None
fatal_patterns=("Mod Loading has failed","LoadingFailedException","ResolutionException",
                "Mixin apply failed","Exception in thread \"Render thread\"","FATAL")

while time.time()<deadline:
    code=p.poll()
    text=""
    if latest.exists():
        text=latest.read_text(encoding="utf-8",errors="replace")
    if "BLACK_FREQUENCY_SHADER_READY sky_rift" in text:
        rift_shader_ready=True
    if any(x in text for x in fatal_patterns):
        print("\nCLIENT_SMOKE_FAIL: fatal marker detected",file=sys.stderr)
        break

    if not window_seen:
        r=subprocess.run(["bash","-lc","xdotool search --onlyvisible --name 'Minecraft' 2>/dev/null | head -n1"],
                         capture_output=True,text=True)
        if r.stdout.strip():
            window_seen=True
            print("Visible Minecraft window detected:",r.stdout.strip(),flush=True)

    if ("Sound engine started" in text and "Reloading ResourceManager" in text) or "Created: " in text:
        if not ready_marker:
            ready_marker=True
            ready_since=time.time()
            print("Client resource/sound initialization marker reached.",flush=True)

    # Survive 30s after renderer/resources are up with a visible window.
    if window_seen and ready_marker and rift_shader_ready and ready_since and time.time()-ready_since>=30 and p.poll() is None:
        subprocess.run(["bash","-lc","mkdir -p client-smoke-artifacts; scrot -a 0,0,1280,720 client-smoke-artifacts/client.png || true"])
        world_ok=capture_test_world()
        if not world_ok:
            print("CLIENT_SMOKE_FAIL: integrated-world capture or validation failed; inspect preceding WORLD_CAPTURE_FAIL diagnostics",file=sys.stderr)
            p.terminate()
            sys.exit(1)
        print("CLIENT_SMOKE_PASS: shader registered and integrated singleplayer world created.",flush=True)
        p.terminate()
        try: p.wait(timeout=15)
        except subprocess.TimeoutExpired: p.kill()
        sys.exit(0)

    if code is not None:
        print(f"\nCLIENT_SMOKE_FAIL: process exited early with code {code}",file=sys.stderr)
        break
    time.sleep(2)

# Failure path screenshot/log tail.
subprocess.run(["bash","-lc","mkdir -p client-smoke-artifacts; scrot -a 0,0,1280,720 client-smoke-artifacts/client-failure.png || true"])
if p.poll() is None:
    p.terminate()
    try: p.wait(timeout=10)
    except subprocess.TimeoutExpired: p.kill()
if latest.exists():
    print("\n===== tail client/logs/latest.log =====",file=sys.stderr)
    print("\n".join(latest.read_text(encoding="utf-8",errors="replace").splitlines()[-220:]),file=sys.stderr)
sys.exit(1)
