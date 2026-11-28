import os, pathlib, subprocess, sys, time, threading, re, shutil, math
import minecraft_launcher_lib

ROOT=pathlib.Path(__file__).resolve().parents[1]
MC=ROOT/"client"
MOUSE_SENSITIVITY=0.6
CAMERA_ROTATION_LOG_THRESHOLD_DEGREES=0.25
CAMERA_INPUT_PROBE_PIXELS=4
MIN_GROUND_MESH_SEGMENTS=8
MIN_GROUND_MESH_ARMS=2
MAX_GROUND_MESH_ARMS=4
GROUND_MESH_SAMPLES=96
FISSURE_PARTICLE_SAMPLES=48
FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE=3.0
FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE=12.0

DEFERRED_RESTORE_RADIUS=48
DEFERRED_RENDER_DISTANCE=4
# Minecraft 1.20.1 clamps Simulation Distance to [5, 33]; 2 is rejected by Options.
DEFERRED_SIMULATION_DISTANCE=5
# Add a four-chunk margin beyond the fracture and the server's simulation-distance halo.
# Per-chunk return teleports then load only visited chunks within the recovery audience.
DEFERRED_PLAYER_TRAVEL_DISTANCE=224
DEFERRED_CLIENT_ANCHOR_X=512
DEFERRED_CLIENT_ANCHOR_Z=512
DEFERRED_CLIENT_SPAWN_X=1024
DEFERRED_CLIENT_SPAWN_Z=1024
DEFERRED_INITIAL_SPAWN_TICKET_RADIUS=11
DEFERRED_RESTORE_WARMUP_TICKS=600
if DEFERRED_RENDER_DISTANCE<4:
    raise RuntimeError("Deferred client profile must retain a cloud-capable render distance")
if DEFERRED_PLAYER_TRAVEL_DISTANCE<=DEFERRED_RESTORE_RADIUS+DEFERRED_SIMULATION_DISTANCE*16+64:
    raise RuntimeError("Deferred probe travel must clear the fracture and simulation-distance halo")
DEFERRED_SPAWN_ACK_RE=r"Set (?:the )?world spawn(?: point)? to "
def isolated_chat_command_body(command):
    # Open command chat with the slash key itself; do not race the chat-screen transition.
    if not isinstance(command,str) or not command.startswith("/") or len(command)<2:
        return None
    return command[1:]

# The lower half is the sampled surface; only the upper half contributes to skippedPlants.
STACKED_PLANT_FIXTURE_SKIPPED_PLANTS=1
STACKED_PLANT_FIXTURE_PARTICLE_OFFSET=2.40
STACKED_PLANT_FIXTURE_STATES = {
    "support": ("minecraft:grass_block", "BF_CANOPY_SUPPORT_VERIFIED"),
    "lower": ("minecraft:tall_grass[half=lower]", "BF_CANOPY_LOWER_VERIFIED"),
    "upper": ("minecraft:tall_grass[half=upper]", "BF_CANOPY_UPPER_VERIFIED"),
    "headroom": ("minecraft:air", "BF_CANOPY_HEADROOM_VERIFIED"),
}

def stacked_plant_fixture_positions(plant_y):
    # The renderer's groundY is measured from the lower plant's support at plant_y-1.
    # Keep the fixture aligned with queueAdjacentPlantSurfaceCanopyAccent().
    if type(plant_y) is not int or plant_y < -63 or plant_y > 317:
        return None
    return plant_y-1,plant_y+1,plant_y+2

def stacked_plant_fixture_commands(x,y,z,slot):
    values=(x,y,z)
    if any(type(value) is not int or value<-(1<<31) or value>(1<<31)-1 for value in values):
        raise ValueError("BLACK FREQUENCY stacked-plant fixture coordinates must be signed 32-bit integers")
    if y < -64 or y > 319 or slot not in STACKED_PLANT_FIXTURE_STATES:
        raise ValueError("BLACK FREQUENCY stacked-plant fixture slot or build height is invalid")
    block_state,marker=STACKED_PLANT_FIXTURE_STATES[slot]
    position=f"{x} {y} {z}"
    marker_text=f"{marker} x={x} y={y} z={z}"
    # The mutation is idempotent: an already-correct block is not sent through
    # setblock again (which reports "Could not set the block" for a no-op).
    mutation=(f"/execute unless block {position} {block_state} "
              f"run setblock {position} {block_state}")
    verification=(f"/execute if block {position} {block_state} run say {marker_text}")
    return mutation,verification,marker_text

def verify_stacked_plant_fixture_command_profile():
    if (stacked_plant_fixture_positions(75)!=(74,76,77)
            or stacked_plant_fixture_positions(-63)!=(-64,-62,-61)
            or stacked_plant_fixture_positions(317)!=(316,318,319)
            or stacked_plant_fixture_positions(-64) is not None
            or stacked_plant_fixture_positions(318) is not None):
        raise RuntimeError("BLACK FREQUENCY stacked-plant support/plant/headroom offset regression")
    fixture_y_by_slot={"support":74,"lower":75,"upper":76,"headroom":77}
    for slot,(state,marker) in STACKED_PLANT_FIXTURE_STATES.items():
        y=fixture_y_by_slot[slot]
        mutation,verification,marker_text=stacked_plant_fixture_commands(513,y,518,slot)
        if (f"execute unless block 513 {y} 518 {state}" not in mutation
                or f"run setblock 513 {y} 518 {state}" not in mutation
                or f"execute if block 513 {y} 518 {state} run say {marker}" not in verification
                or marker_text not in verification):
            raise RuntimeError("BLACK FREQUENCY idempotent stacked-plant fixture command regression")
    if (STACKED_PLANT_FIXTURE_SKIPPED_PLANTS!=1
            or not math.isclose(75+STACKED_PLANT_FIXTURE_PARTICLE_OFFSET,77.40,
                                rel_tol=0.0,abs_tol=1.0E-9)):
        raise RuntimeError("BLACK FREQUENCY two-block plant mote clearance regression")
    print("CI_PROFILE: stacked-plant fixture offsets and idempotent block commands verified",
          "supportY=74","lowerY=75","upperY=76","headroomY=77",
          "skippedPlants=1","particleOffset=2.40",
          "alreadyCorrectNoOp=true","buildBounds=true",flush=True)

verify_stacked_plant_fixture_command_profile()

if (isolated_chat_command_body("/setworldspawn 1024 64 1024")!="setworldspawn 1024 64 1024"
        or isolated_chat_command_body("/tp @s 512 120 512")!="tp @s 512 120 512"
        or isolated_chat_command_body("setworldspawn 1024 64 1024") is not None
        or isolated_chat_command_body("/") is not None):
    raise RuntimeError("BLACK FREQUENCY isolated chat command prefix regression")

DEFERRED_CLIENT_INPUT_READY_MARKER="BLACK_FREQUENCY_TEST CLIENT_INPUT_READY screen=null player=true"
INITIAL_PLAYER_SPAWN_RE=re.compile(
    r"logged in with entity id \d+ at \(([-+0-9.eE]+),\s*([-+0-9.eE]+),\s*([-+0-9.eE]+)\)")
DEFERRED_RESTORE_CHUNK_RE=re.compile(
    r"BLACK_FREQUENCY_TEST RESTORE_UNLOADED_CHUNK_DEFERRED "
    r"x=(-?\d+) y=(-?\d+) z=(-?\d+) "
    r"chunkX=(-?\d+) chunkZ=(-?\d+) loadedOnly=true")
DEFERRED_CHUNK_READINESS_RE=re.compile(
    r"BLACK_FREQUENCY_TEST RESTORE_DEFERRED_PROBE_CHUNK_READINESS "
    r"ticks=(\d+) uniqueChunks=(\d+) loadedChunks=(\d+) unloadedChunks=(\d+) "
    r"playerChunkX=(-?\d+) playerChunkZ=(-?\d+) "
    r"anchorChunkX=(-?\d+) anchorChunkZ=(-?\d+) "
    r"spawnChunkX=(-?\d+) spawnChunkZ=(-?\d+) loadedOnly=true")
FINALE_ANCHOR_RE=re.compile(
    r"BLACK_FREQUENCY_TEST FINALE_ANCHOR_VALID x=(-?\d+) z=(-?\d+)")
FIRST_BLOCK_REMOVED_RE=re.compile(
    r"BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED x=(-?\d+) y=(-?\d+) z=(-?\d+) surfaceY=(-?\d+) scanOrder=surface-first-center-out")
CANOPY_FIXTURE_SAMPLE_RE=re.compile(
    r"BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FIXTURE_SAMPLE_VERIFIED "
    r"sampleX=(-?\d+) sampleZ=(-?\d+) groundY=([-+0-9.eE]+) "
    r"plantX=(-?\d+) plantZ=(-?\d+) arm=(\d+) segment=(\d+) "
    r"anchorX=(-?\d+) anchorZ=(-?\d+) nextScan=true loadedOnly=true radius=48")
CANOPY_FIXTURE_ANCHOR_RESET_RE=re.compile(
    r"BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FIXTURE_ANCHOR_RESET_VERIFIED "
    r"previousX=(-?\d+) previousZ=(-?\d+) anchorX=(-?\d+) anchorZ=(-?\d+) "
    r"sampleReset=true liftedCounterReset=true")
if (not FIRST_BLOCK_REMOVED_RE.fullmatch(
        "BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED x=1 y=2 z=3 surfaceY=4 scanOrder=surface-first-center-out")
        or not CANOPY_FIXTURE_SAMPLE_RE.fullmatch(
        "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FIXTURE_SAMPLE_VERIFIED sampleX=1 sampleZ=2 groundY=64.01 plantX=1 plantZ=1 arm=0 segment=5 anchorX=0 anchorZ=0 nextScan=true loadedOnly=true radius=48")):
    raise RuntimeError("BLACK FREQUENCY canopy fixture log regex regression")

def find_canopy_fixture_sample_for_anchor(log_text,anchor_x,anchor_z):
    # A fixture sample must come from the exact active mesh anchor. A sample from
    # the initial spawn may still be geometrically inside the later 48-block
    # fracture, but it is not part of that mesh and cannot be retried there.
    for match in reversed(list(CANOPY_FIXTURE_SAMPLE_RE.finditer(log_text))):
        sample_x,sample_z=map(int,match.groups()[:2])
        plant_x,plant_z=map(int,match.groups()[3:5])
        sample_anchor_x,sample_anchor_z=map(int,match.groups()[7:9])
        if sample_anchor_x!=anchor_x or sample_anchor_z!=anchor_z:
            continue
        sample_dx=sample_x-anchor_x
        sample_dz=sample_z-anchor_z
        plant_dx=plant_x-anchor_x
        plant_dz=plant_z-anchor_z
        if (sample_dx*sample_dx+sample_dz*sample_dz<=48*48
                and plant_dx*plant_dx+plant_dz*plant_dz<=48*48):
            return match
    return None

_fixture_sample_probe_log=(
    "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FIXTURE_SAMPLE_VERIFIED "
    "sampleX=510 sampleZ=512 groundY=74.01 plantX=510 plantZ=511 arm=0 segment=5 "
    "anchorX=500 anchorZ=512 nextScan=true loadedOnly=true radius=48\n"
    "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FIXTURE_SAMPLE_VERIFIED "
    "sampleX=514 sampleZ=512 groundY=74.01 plantX=514 plantZ=511 arm=0 segment=5 "
    "anchorX=512 anchorZ=512 nextScan=true loadedOnly=true radius=48")
_selected_fixture_sample=find_canopy_fixture_sample_for_anchor(_fixture_sample_probe_log,512,512)
if _selected_fixture_sample is None or int(_selected_fixture_sample.group(4))!=514:
    raise RuntimeError("BLACK FREQUENCY canopy fixture anchor selection regression")
_stale_only_probe=(
    "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FIXTURE_SAMPLE_VERIFIED "
    "sampleX=510 sampleZ=512 groundY=74.01 plantX=510 plantZ=511 arm=0 segment=5 "
    "anchorX=500 anchorZ=512 nextScan=true loadedOnly=true radius=48")
if find_canopy_fixture_sample_for_anchor(_stale_only_probe,512,512) is not None:
    raise RuntimeError("BLACK FREQUENCY stale canopy fixture anchor regression")
_anchor_change_probe_log=(
    "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FIXTURE_SAMPLE_VERIFIED "
    "sampleX=2 sampleZ=0 groundY=66.01 plantX=2 plantZ=-1 arm=0 segment=5 "
    "anchorX=0 anchorZ=0 nextScan=true loadedOnly=true radius=48\n"
    "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FIXTURE_ANCHOR_RESET_VERIFIED "
    "previousX=0 previousZ=0 anchorX=4 anchorZ=-12 sampleReset=true liftedCounterReset=true\\n"
    "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FIXTURE_SAMPLE_VERIFIED "
    "sampleX=4 sampleZ=-12 groundY=64.01 plantX=5 plantZ=-12 arm=0 segment=5 "
    "anchorX=4 anchorZ=-12 nextScan=true loadedOnly=true radius=48")
_anchor_change_sample=find_canopy_fixture_sample_for_anchor(_anchor_change_probe_log,4,-12)
_anchor_change_reset=next((m for m in CANOPY_FIXTURE_ANCHOR_RESET_RE.finditer(_anchor_change_probe_log)
    if tuple(map(int,m.groups()))==(0,0,4,-12)),None)
if (_anchor_change_sample is None
        or tuple(map(int,_anchor_change_sample.groups()[7:9]))!=(4,-12)
        or _anchor_change_reset is None):
    raise RuntimeError("BLACK FREQUENCY same-session canopy fixture anchor reset regression")
def is_valid_deferred_restore_chunk_event(x,z,chunk_x,chunk_z,anchor_x,anchor_z,radius):
    values=(x,z,chunk_x,chunk_z,anchor_x,anchor_z,radius)
    if any(type(value) is not int for value in values):
        return False
    int_min=-(1<<31)
    int_max=(1<<31)-1
    if any(value<int_min or value>int_max for value in (x,z,anchor_x,anchor_z)):
        return False
    if radius<=0 or radius>128:
        return False
    # Java BlockPos chunk coordinates use arithmetic signed shifts, matching Python here.
    if (x>>4)!=chunk_x or (z>>4)!=chunk_z:
        return False
    dx=x-anchor_x
    dz=z-anchor_z
    return (abs(dx)<=radius and abs(dz)<=radius
            and dx*dx+dz*dz<=radius*radius)

def verify_deferred_restore_chunk_bounds_profile():
    controller_source_path=(ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"RiftController.java")
    controller_source=controller_source_path.read_text(encoding="utf-8")
    geometry_source_path=(ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"RiftGeometry.java")
    geometry_source=geometry_source_path.read_text(encoding="utf-8")
    client_state_source_path=(ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"client"/"RiftClientState.java")
    client_state_source=client_state_source_path.read_text(encoding="utf-8")
    radius_match=re.search(r"public static final int RADIUS\s*=\s*(\d+)\s*;",geometry_source)
    if (not radius_match or int(radius_match.group(1))!=DEFERRED_RESTORE_RADIUS
            or "private static final int RADIUS = RiftGeometry.RADIUS;" not in controller_source):
        raise RuntimeError("BLACK FREQUENCY deferred restore radius must match the shared server fracture radius")
    if ("private static final int DEFERRED_CLIENT_SCAN_WARMUP_TICKS=600;" not in controller_source
            or "private static int restoreSnapshotScanWarmupTicks(boolean deferredClientProbe)" not in controller_source
            or "return deferredClientProbe?DEFERRED_CLIENT_SCAN_WARMUP_TICKS:0;" not in controller_source
            or "s.restoreTicks>scanWarmupTicks" not in controller_source
            or "logDeferredProbeChunkReadiness(level,s);" not in controller_source
            or "private static final int DEFERRED_CLIENT_ANCHOR_X=512;" not in controller_source
            or "private static final int DEFERRED_CLIENT_ANCHOR_Z=512;" not in controller_source
            or "private static final int DEFERRED_CLIENT_SPAWN_X=1024;" not in controller_source
            or "private static final int DEFERRED_CLIENT_SPAWN_Z=1024;" not in controller_source
            or "private static boolean deferredClientProbeAnchorReady(ServerLevel level)" not in controller_source
            or "if(!deferredClientProbeAnchorReady(level))" not in controller_source
            or "DEFERRED_CLIENT_ANCHOR_PROFILE_VERIFIED" not in controller_source
            or "BLACK_FREQUENCY_TEST CLIENT_INPUT_READY screen=null player=true" not in client_state_source
            or 'Boolean.getBoolean("blackfrequency.ciTestMode")' not in client_state_source
            or 'Boolean.getBoolean("blackfrequency.ciDeferredChunkProbe")' not in client_state_source
            or "minecraft.screen==null" not in client_state_source):
        raise RuntimeError("BLACK FREQUENCY deferred probe must wait for natural chunk-ticket expiry and report loaded-only readiness")
    if ('if(Boolean.getBoolean("blackfrequency.ciDeferredChunkProbe")' not in controller_source
            or "&& !level.hasChunk(beacon.getX()>>4,beacon.getZ()>>4))" not in controller_source):
        raise RuntimeError("BLACK FREQUENCY may skip the synthetic beacon guard only in the deferred client probe")
    anchor_chunk=(DEFERRED_CLIENT_ANCHOR_X>>4,DEFERRED_CLIENT_ANCHOR_Z>>4)
    spawn_chunk=(DEFERRED_CLIENT_SPAWN_X>>4,DEFERRED_CLIENT_SPAWN_Z>>4)
    if (anchor_chunk!=(32,32) or spawn_chunk!=(64,64)
            or max(abs(anchor_chunk[0]),abs(anchor_chunk[1]))<=DEFERRED_INITIAL_SPAWN_TICKET_RADIUS
            or max(abs(anchor_chunk[0]-spawn_chunk[0]),abs(anchor_chunk[1]-spawn_chunk[1]))<=DEFERRED_INITIAL_SPAWN_TICKET_RADIUS
            or max(abs(spawn_chunk[0]),abs(spawn_chunk[1]))<=DEFERRED_INITIAL_SPAWN_TICKET_RADIUS):
        raise RuntimeError("BLACK FREQUENCY deferred anchor must clear the initial and relocated spawn-ticket halos")
    client_source=pathlib.Path(__file__).read_text(encoding="utf-8")
    input_ready_match=re.search(r"^    def wait_for_isolated_client_input_ready\(",client_source,re.M)
    isolated_command_match=re.search(r"^    def send_isolated_chat_command\(",client_source,re.M)
    input_ready_start=input_ready_match.start() if input_ready_match else -1
    isolated_command_start=isolated_command_match.start() if isolated_command_match else -1
    isolated_command_end=client_source.find("    def click(",isolated_command_start)
    input_ready_body=(client_source[input_ready_start:isolated_command_start]
        if input_ready_start>=0 and isolated_command_start>input_ready_start else "")
    isolated_command_body=(client_source[isolated_command_start:isolated_command_end]
        if isolated_command_start>=0 and isolated_command_end>isolated_command_start else "")
    if (not input_ready_body or "DEFERRED_CLIENT_INPUT_READY_MARKER" not in input_ready_body
            or DEFERRED_CLIENT_INPUT_READY_MARKER not in client_source
            or "wait_for_isolated_client_input_ready()" not in isolated_command_body
            or "nonlocal isolated_chat_open" not in isolated_command_body
            or "if isolated_chat_open:" not in isolated_command_body
            or "if confirmation is None:" not in isolated_command_body
            or 'print("WORLD_CAPTURE: submitted",description,flush=True)' not in isolated_command_body
            or "for attempt in range(2 if required else 1)" not in isolated_command_body
            or "command acknowledgement delayed; retrying" not in isolated_command_body
            or "isolated_chat_command_body(command)" not in isolated_command_body
            or 'key("Escape",focus=False)' not in isolated_command_body
            or 'key("slash",focus=False)' not in isolated_command_body
            or "time.sleep(1.75)" not in isolated_command_body
             or '["xclip","-selection","clipboard","-loops","1","-i"]' not in isolated_command_body
             or 'key("ctrl+v",focus=False)' not in isolated_command_body
             or "time.sleep(0.35)" not in isolated_command_body):
        raise RuntimeError("BLACK FREQUENCY isolated spawn relocation must wait for client readiness and retry an unacknowledged idempotent command")
    spawn_verified_at=client_source.find('print("WORLD_CAPTURE: player spawn verified in log"')
    client_world_ready_wait=client_source.find('client_world_ready_deadline=time.monotonic()+60.0',spawn_verified_at)
    input_ready_wait=client_source.find('"BLACK_FREQUENCY_TEST CLIENT_INPUT_READY screen=null player=true" in current',spawn_verified_at)
    anchor_creative_command=client_source.find('"/gamemode creative @s"',spawn_verified_at)
    anchor_teleport_command=client_source.find('f"/tp @s {DEFERRED_CLIENT_ANCHOR_X} 120 {DEFERRED_CLIENT_ANCHOR_Z}"',spawn_verified_at)
    shared_spawn_command=client_source.find('f"/setworldspawn {DEFERRED_CLIENT_SPAWN_X} 64 {DEFERRED_CLIENT_SPAWN_Z}"')
    if (spawn_verified_at<0 or client_world_ready_wait<=spawn_verified_at
            or input_ready_wait<=client_world_ready_wait
            or anchor_creative_command<=input_ready_wait
            or anchor_teleport_command<=anchor_creative_command or shared_spawn_command<0):
        raise RuntimeError("BLACK FREQUENCY deferred player must move to the remote anchor before the finale and keep shared spawn separate")
    creative_command=client_source.rfind('"/gamemode creative @s"')
    travel_command=client_source.rfind('f"/tp @s ~{DEFERRED_PLAYER_TRAVEL_DISTANCE} 120 ~"')
    return_command=client_source.rfind('f"/tp @s {pad_x} {pad_y} {pad_z}"')
    survival_command=client_source.rfind('"/gamemode survival @s"')
    if (creative_command<0 or travel_command<=creative_command
            or return_command<=travel_command or survival_command<=return_command):
        raise RuntimeError("BLACK FREQUENCY deferred travel must protect the player and restore Survival before the physical beacon test")
    fixtures=(
        (48,0,3,0,0,0,48,True),
        (-48,0,-3,0,0,0,48,True),
        (33,33,2,2,0,0,48,True),
        (34,34,2,2,0,0,48,False),
        (49,0,3,0,0,0,48,False),
        (15,0,1,0,0,0,48,False),
        (-17,0,-2,0,-1,0,48,True),
        (0,0,0,0,0,0,-1,False),
        (1<<31,0,1<<27,0,0,0,48,False),
    )
    for fixture in fixtures:
        *values,expected=fixture
        actual=is_valid_deferred_restore_chunk_event(*values)
        if actual is not expected:
            raise RuntimeError("BLACK FREQUENCY deferred snapshot coordinate/radius fixture regression")
    print("WORLD_CAPTURE: deferred snapshot chunk coordinates and circular fracture bounds verified",
          "radius",DEFERRED_RESTORE_RADIUS,"negativeCoordinates=true","boundary=true","loadedOnly=true",flush=True)
    readiness_fixture=("BLACK_FREQUENCY_TEST RESTORE_DEFERRED_PROBE_CHUNK_READINESS "
        "ticks=600 uniqueChunks=4 loadedChunks=1 unloadedChunks=3 "
        "playerChunkX=46 playerChunkZ=32 anchorChunkX=32 anchorChunkZ=32 "
        "spawnChunkX=64 spawnChunkZ=64 loadedOnly=true")
    readiness_match=DEFERRED_CHUNK_READINESS_RE.search(readiness_fixture)
    if not readiness_match or tuple(map(int,readiness_match.groups()))!=(600,4,1,3,46,32,32,32,64,64):
        raise RuntimeError("BLACK FREQUENCY deferred chunk-readiness fixture regression")
    print("WORLD_CAPTURE: deferred scan waits",DEFERRED_RESTORE_WARMUP_TICKS,
          "server ticks and records loaded-only chunk readiness",flush=True)

def is_fissure_target_horizontal_distance(horizontal_squared):
    try:
        value=float(horizontal_squared)
    except (TypeError,ValueError,OverflowError):
        return False
    minimum_squared=FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE**2
    maximum_squared=FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE**2
    return math.isfinite(value) and minimum_squared<=value<=maximum_squared

MAX_GROUND_MESH_SEGMENTS=MAX_GROUND_MESH_ARMS*(GROUND_MESH_SAMPLES//MAX_GROUND_MESH_ARMS-1)

def camera_degrees_per_pixel():
    factor=MOUSE_SENSITIVITY*0.6+0.2
    return factor*factor*factor*8.0*0.15

def verify_camera_input_profile():
    expected_delta=CAMERA_INPUT_PROBE_PIXELS*camera_degrees_per_pixel()
    if (not math.isfinite(expected_delta)
            or expected_delta<CAMERA_ROTATION_LOG_THRESHOLD_DEGREES*2.0):
        raise RuntimeError("BLACK FREQUENCY camera input calibration is below the renderer telemetry threshold")
    print("WORLD_CAPTURE: camera input calibration verified",
          "pixels",CAMERA_INPUT_PROBE_PIXELS,
          "expectedCameraDelta",round(expected_delta,3),
          "telemetryThreshold",CAMERA_ROTATION_LOG_THRESHOLD_DEGREES,flush=True)

GRAVITY_TRAJECTORY_HORIZONTAL_CAP=.35
GRAVITY_TRAJECTORY_LIFT_VERTICAL_CAP=.43
GRAVITY_TRAJECTORY_SINK_VERTICAL_CAP=.55
GRAVITY_TRAJECTORY_MODE_RE=re.compile(
    r"BLACK_FREQUENCY_TEST GRAVITY_ZONE_SERVER_PLAYER_TRAJECTORY_MODE_VERIFIED mode=(lift|sink) steps=(\d+) .*?maxHorizontalVelocity=([0-9.eE+-]+) maxVerticalVelocity=([0-9.eE+-]+) horizontalCap=([0-9.eE+-]+) verticalCap=([0-9.eE+-]+) loadedChunksOnly=true"
)
GRAVITY_COLLISION_PATH_RE=re.compile(
    r"BLACK_FREQUENCY_TEST GRAVITY_ZONE_SERVER_PLAYER_COLLISION_PATH_VERIFIED mode=(lift|sink) steps=(\d+) collisionSamples=(\d+) collisionFree=true loadedChunksOnly=true"
)

GRAVITY_WORLD_BORDER_RE=re.compile(
    r"BLACK_FREQUENCY_TEST GRAVITY_PROBE_WORLD_BORDER_GUARD_VERIFIED insideAccepted=true outsideRejected=true straddlingRejected=true loadedChunksOnly=true"
)


FISSURE_PARTICLE_EMISSION_RE=re.compile(
    r"BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLES_EMITTED samples=(\d+) accentDust=(\d+) violetDust=(\d+) edgeDust=(\d+) total=(\d+) heightmap=motion-blocking(?=\s|$)"
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
        # Exact candidate observed in the failing Sparse client run on 2026-11-20.
        (67.1,73,58.11826889234223,0.39171582940392113),
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
    controller_source_path=ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"RiftController.java"
    controller_source=controller_source_path.read_text(encoding="utf-8")
    geometry_source_path=ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"RiftGeometry.java"
    geometry_source=geometry_source_path.read_text(encoding="utf-8")
    spacing_match=re.search(
        r"public static final double FISSURE_SAMPLE_SPACING=([0-9.]+)D;",
        geometry_source)
    maximum_range=re.search(
        r"private static final double FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE = ([0-9.]+)D;",
        controller_source)
    minimum_range_expression=(
        "private static final double FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE = FISSURE_SAMPLE_SPACING*5.0D;")
    if (minimum_range_expression not in controller_source or not spacing_match or not maximum_range
            or not math.isclose(float(spacing_match.group(1))*5.0,FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE,
                                rel_tol=0.0,abs_tol=1.0E-9)
            or not math.isclose(float(maximum_range.group(1)),FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE,
                                rel_tol=0.0,abs_tol=1.0E-9)
            or "isFissureTargetHorizontalDistance(beaconPadFar*beaconPadFar)" not in controller_source):
        raise RuntimeError("BLACK FREQUENCY beacon-side fissure visibility range source contract regression")
    sparse_fixture_squared=58.11826889234223
    if (not is_fissure_target_horizontal_distance(sparse_fixture_squared)
            or sparse_fixture_squared<=6.0**2
            or is_fissure_target_horizontal_distance((FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE+1.0E-6)**2)):
        raise RuntimeError("BLACK FREQUENCY observed Sparse fissure standoff range regression")
    target_method_start=controller_source.index(
        "private static FissureTarget findVisibleFissureTarget(ServerLevel level,State s,\n"
        "            net.minecraft.server.level.ServerPlayer viewer,net.minecraft.world.phys.Vec3 eye)")
    target_method_end=controller_source.index("\n    // Run after ordinary explosion modifiers",target_method_start)
    target_method=controller_source[target_method_start:target_method_end]
    distance_guard=target_method.find("if(!isFissureTargetHorizontalDistance(horizontalSquared)) continue;")
    chunk_probe=target_method.find("!level.hasChunk(blockX>>4,blockZ>>4)")
    surface_probe=target_method.find("findFissureGroundSample(level,blockX,blockZ,cursor,groundSample)")
    loaded_ray_guard=target_method.find("fissureRayChunksLoaded(eye,target")
    visual_ray=target_method.find("ClipContext.Block.VISUAL")
    collider_ray=target_method.find("ClipContext.Block.COLLIDER")
    if (distance_guard<0 or chunk_probe<0 or surface_probe<0
            or distance_guard>chunk_probe or distance_guard>surface_probe
            or loaded_ray_guard<0 or visual_ray<0 or collider_ray>=0
            or loaded_ray_guard>visual_ray):
        raise RuntimeError("BLACK FREQUENCY fissure target must cull range, verify loaded chunks, and use visual-shape occlusion")
    print("WORLD_CAPTURE: balanced fissure target, 12-block range, loaded-only visual foliage occlusion verified",flush=True)

def parse_fissure_particle_emission(log_text):
    match=FISSURE_PARTICLE_EMISSION_RE.search(log_text)
    return tuple(int(value) for value in match.groups()) if match else None


def verify_green_corruption_shader_source():
    shader_path=(ROOT/"addon"/"src"/"main"/"resources"/"assets"/"blackfrequency"/"shaders"/"core"/"sky_rift.fsh")
    shader=shader_path.read_text(encoding="utf-8")
    client_state_path=(ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"client"/"RiftClientState.java")
    client_state=client_state_path.read_text(encoding="utf-8")
    preview_start=client_state.find("public static float greenPhase()")
    preview_end=client_state.find("\n    private static float smootherstep01",preview_start)
    preview_body=client_state[preview_start:preview_end] if preview_start>=0 and preview_end>preview_start else ""
    if ("VERIFY_RIFT_CLIENT_PROFILES && Boolean.getBoolean(\"blackfrequency.ciGreenPhasePreview\")" not in client_state
            or "if(CI_GREEN_PHASE_PREVIEW && progress>0.0F)" not in preview_body
            or "BLACK_FREQUENCY_CI_GREEN_PHASE_PREVIEW_ACTIVE" not in preview_body
            or "return 1.0F;" not in preview_body
            or "return greenPhase(collapse,finaleTicks);" not in preview_body):
        raise RuntimeError("BLACK FREQUENCY green-phase preview must be client-only, profile-gated, and inactive without an active rift")
    if ("float fadeIn=smootherstep01((ticks-GREEN_PHASE_START_TICK)/(float)GREEN_PHASE_FADE_TICKS);" not in client_state
            or "float fadeOut=smootherstep01((GREEN_PHASE_END_TICK-ticks)/(float)GREEN_PHASE_FADE_TICKS);" not in client_state):
        raise RuntimeError("BLACK FREQUENCY green corruption phase must use the verified quintic easing")
    print("WORLD_CAPTURE: green-phase preview is gated behind the disposable client profile")
    required=(
        "float greenOutsideCore=1.0-clamp(fractureCoverage,0.0,1.0);",
        "float greenCorruptionGlow=clamp(rimLight*.88+majorGlow*.14+filaments*.05,0.0,1.0)*greenOutsideCore;",
        "float greenBlueMix=clamp(greenCorruptionGlow*green,0.0,1.0);",
        "float greenBlueTarget=min(color.b,color.g*.42);",
        "color.b=mix(color.b,greenBlueTarget,greenBlueMix);",
        "color=mix(color,vec3(.002,.011,.009),green*.18*greenOutsideCore);",
        "color+=vec3(.035,1.25,.035)*greenCorruptionGlow*green*(.72+.28*rimPulse);",
        "color+=vec3(.025,1.35,.035)*lightningFlash*majorGlow*green*greenOutsideCore*.42;",
    )
    if (shader.count("float fractureCoverage=")!=1
            or any(token not in shader for token in required)
            or "color=mix(color,vec3(.002,.011,.009),green*.18);" in shader
            or "color=mix(color,vec3(.002,.011,.009),green);" in shader):
        raise RuntimeError("BLACK FREQUENCY green corruption must illuminate fracture edges without washing out dark cores")
    if shader.index("float fractureCoverage=")>shader.index("float greenOutsideCore="):
        raise RuntimeError("BLACK FREQUENCY green edge mask must be derived after fracture-core coverage")
    if not (shader.index("float greenCorruptionGlow=")<shader.index("float greenBlueMix=")
            <shader.index("float greenBlueTarget=")
            <shader.index("color.b=mix(color.b,greenBlueTarget,greenBlueMix);")
            <shader.index("color+=vec3(.035,1.25,.035)")):
        raise RuntimeError("BLACK FREQUENCY green chroma correction must bound blue on masked rims before green emission")
    def corrected_blue(blue,green,glow,phase):
        amount=max(0.0,min(1.0,glow*phase))
        target=min(blue,green*.42)
        return blue+(target-blue)*amount
    untouched=corrected_blue(.82,.18,0.0,1.0)
    phase_off=corrected_blue(.82,.18,1.0,0.0)
    full_rim=corrected_blue(.82,.18,1.0,1.0)
    half_rim=corrected_blue(.82,.18,.5,1.0)
    if (abs(untouched-.82)>1.0e-9 or abs(phase_off-.82)>1.0e-9
            or abs(full_rim-.0756)>1.0e-9 or not full_rim<half_rim<untouched):
        raise RuntimeError("BLACK FREQUENCY green chroma clamp must preserve violet when inactive and bound blue at full green coverage")
    print("WORLD_CAPTURE: green rim blue channel capped to 42% of pre-emission green at full coverage",flush=True)
    print("WORLD_CAPTURE: green base tint and edge emission are both masked off fracture cores",flush=True)
    print("WORLD_CAPTURE: blue suppression separates toxic-green rims from violet edge emission",flush=True)
    print("WORLD_CAPTURE: green corruption keeps near-black cores and adds bounded toxic-green edge emission",flush=True)


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
            or "isClientPacketStateStale(ticksSincePacket)" not in client
            or "BEACON_AURA_STALE_PACKET_EXPIRY_VERIFIED restorationOnly=true idle=false timeoutTicks=100" not in client
            or "nextBeaconAuraCompletionFadeTicks(" not in client
            or "beaconAuraCompletionFadeTicks,wasRestoring,worldRestorationActive,collapsing,safeProgress)" not in client
            or "BEACON_AURA_COMPLETION_FADE_PROFILE_VERIFIED ticks=16 curve=quintic partialTick=true quarter={} midpoint={} threeQuarter={} c2Endpoints=true" not in client
            or "return smootherstep01(remaining);" not in client
            or "return smootherstep01(t);" not in client
            or "smootherstepSecondDerivative01(0.0D)==0.0D" not in client
            or "beaconAuraCompletionScale(" not in client
            or "BEACON_AURA_COMPLETION_COLLAPSE_PROFILE_VERIFIED start=1.0 midpoint=0.775 end=0.55 monotonic=true geometryScaled=true" not in client
            or "BEACON_AURA_COMPLETION_FADE_RENDERED" not in client
            or "BEACON_AURA_COMPLETION_FADE_MIDPOINT_RENDERED" not in client
            or "BEACON_AURA_COMPLETION_FADE_TAIL_RENDERED" not in client
            or "BEACON_AURA_COMPLETION_FADE_MILESTONES_VERIFIED midpointInRange=true tailVisible=true" not in client
            or "BEACON_AURA_RESTORATION_ACTIVE_RENDERED progress={} loadedOnly=true" not in client
            or "BEACON_AURA_DISTANCE_FADE_PROFILE_VERIFIED start=40.0 end=48.0 curve=quintic quarter={} midpoint={} threeQuarter={} c2Endpoints=true monotonic=true invalidRejected=true" not in client
            or "beaconAuraDistanceFade(distanceSquared)" not in client
            or "distanceSquared<=BEACON_AURA_FADE_START_DISTANCE_SQR" not in client
            or "BEACON_AURA_RIBBON_PROFILE_VERIFIED outerHalfWidth=0.022 innerHalfWidth=0.012 cameraFacing=true allocationFree=true" not in client
            or "BEACON_AURA_OUTER_CAGE_DEEMPHASIS_VERIFIED min=0.3 max=0.48 innerUnchanged=true" not in client
            or "private static final double BEACON_AURA_OUTER_ALPHA_BASE=.30D;" not in client
            or "private static final double BEACON_AURA_OUTER_ALPHA_PULSE=.18D;" not in client
            or "BEACON_AURA_OUTER_ALPHA_BASE+BEACON_AURA_OUTER_ALPHA_PULSE*pulse" not in client
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
    cache_start=sky.find("private static boolean cacheShaderUniforms(")
    cache_end=sky.find("\n    }",cache_start)
    render_start=sky.find("private static void renderRift(")
    render_end=sky.find("\n        }\n    }",render_start)
    registration_start=sky.find("public static void shaders(RegisterShadersEvent event)")
    registration_end=sky.find("\n        }\n    }",registration_start)
    cache_body=sky[cache_start:cache_end] if cache_start>=0 and cache_end>cache_start else ""
    render_body=sky[render_start:render_end] if render_start>=0 and render_end>render_start else ""
    registration_body=sky[registration_start:registration_end] if registration_start>=0 and registration_end>registration_start else ""
    if (cache_body.count('.getUniform("')!=3
            or "clearShaderUniforms();" not in registration_body
            or "riftStrengthUniform.set(strength);" not in render_body
            or "greenPhaseUniform.set(RiftClientState.greenPhase());" not in render_body
            or "gameTimeUniform.set(interpolatedGameTime(" not in render_body
            or ".getUniform(" in render_body
            or "return shaderReady && shader!=null" not in sky
            or "gameTimeUniform!=null" not in sky
            or "shaderReloadAwaitingDraw=reloadingReadyShader;" not in registration_body
            or "SKY_SHADER_RELOAD_INVALIDATION_VERIFIED" not in registration_body
            or "SKY_SHADER_RELOAD_REBOUND_VERIFIED" not in registration_body
            or "SKY_SHADER_RELOAD_POST_DRAW_VERIFIED" not in render_body):
        raise RuntimeError("BLACK FREQUENCY sky shader uniforms must be cached per shader instance and invalidated on reload")
    print("WORLD_CAPTURE: shader uniform cache and resource-reload lifecycle source contract verified",flush=True)
    if ("private static BlockState readCanonicalSnapshotBlockState(ServerLevel level,CompoundTag stateTag)" not in controller
            or controller.count('readCanonicalSnapshotBlockState(level,entry.getCompound("state"))')!=2
            or "NbtUtils.writeBlockState(decoded).equals(stateTag)" not in controller
            or "RECOVERY_SNAPSHOT_BLOCK_STATE_CANONICAL_ROUNDTRIP_VERIFIED" not in controller
            or "RECOVERY_SNAPSHOT_LIST_CORRUPTION_PARTIAL_STATUS_VERIFIED" not in controller
            or "RECOVERY_SNAPSHOT_CORRUPTION_COUNTER_PERSISTENCE_VERIFIED" not in controller):
        raise RuntimeError("BLACK FREQUENCY recovery must reject non-canonical saved block states before writes")
    print("WORLD_CAPTURE: loaded-only physical beacon aura packet and render contract verified",flush=True)

def verify_restoration_particle_source():
    source_path=ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"RiftController.java"
    source=source_path.read_text(encoding="utf-8")
    geometry_contract=(ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"RiftGeometry.java").read_text(encoding="utf-8")
    gravity_probe_start=source.find("private static void verifyActualPassengerGravityImmunity(")
    gravity_probe_end=source.find("\n    private static",gravity_probe_start+1)
    gravity_probe_body=source[gravity_probe_start:gravity_probe_end] if gravity_probe_start>=0 and gravity_probe_end>gravity_probe_start else ""
    if ("fieldSamples[tick]=gravityAnomalyField(candidateX,candidateZ,tick,worldSeed);" not in source
            or "int loadedChunkX=probe.x>>4,loadedChunkZ=probe.z>>4;" not in gravity_probe_body
            or "if(!level.hasChunk(loadedChunkX,loadedChunkZ))" not in gravity_probe_body
            or "for(int offsetX=4;offsetX<=12 && !trajectoryPositionFound;offsetX+=4)" not in gravity_probe_body
            or "for(int offsetZ=4;offsetZ<=12;offsetZ+=4)" not in gravity_probe_body
            or "minChunkX!=loadedChunkX || maxChunkX!=loadedChunkX" not in gravity_probe_body
            or "minChunkZ!=loadedChunkZ || maxChunkZ!=loadedChunkZ" not in gravity_probe_body
            or "if((player.blockPosition().getX()>>4)!=loadedChunkX" not in gravity_probe_body
            or "BLACK_FREQUENCY_TEST GRAVITY_TRAJECTORY_PROBE_POSITION_VERIFIED" not in gravity_probe_body
            or "sameLoadedChunk=true playerBoxInsideChunk=true noChunkLoad=true" not in gravity_probe_body
            or "level.getChunk(" in gravity_probe_body
            or "double zone=gravityAnomalyField(px,pz,s.finaleTicks,level.getSeed());" not in source
            or "double zone=gravityAnomalyField(player.getX(),player.getZ(),s.finaleTicks,level.getSeed());" not in source
            or "double base=Math.sin(x*.115D+gravityFieldBasePhase)" not in source
            or "double cross=Math.sin((x+z)*.047D+gravityFieldCrossPhase-gravityFieldCrossTimeOffset);" not in source
            or "double turbulence=Math.sin((x-z)*.083D+gravityFieldTurbulencePhase+gravityFieldTurbulenceTimeOffset);" not in source
            or "double microTurbulence=Math.sin((x*.17D+z*.13D)" not in source
            or "private static double gravityAnomalyAperiodicNoise(double x,double z,int ticks,long worldSeed)" not in source
            or "double aperiodicTurbulence=gravityAnomalyAperiodicNoise(x,z,ticks,worldSeed);" not in source
            or "private static double gravityAnomalyField(double x,double z,int ticks,long worldSeed)" not in source
            or "private static void verifyGravityAnomalyWorldSeedProfile()" not in source
            or "gravityAnomalyLatticeHash(int cellX,int cellZ,long worldSeed)" not in source
            or "worldSeed^0xD1B54A32D192ED03L" not in source
            or "ANOMALY_FIELD_WORLD_SEED_PROFILE_VERIFIED worldSeedScoped=true deterministic=true distinctWorlds=true bounded=true noRandomAllocation=true" not in source
            or "GRAVITY_APERIODIC_TURBULENCE_AMPLITUDE=.045D;" not in source
            or "double field=base+.12D*cross+.06D*turbulence+.035D*microTurbulence\n            +GRAVITY_APERIODIC_TURBULENCE_AMPLITUDE*aperiodicTurbulence;" not in source
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
    if ("boolean snowLayer=surface.is(net.minecraft.world.level.block.Blocks.SNOW);" not in client_source
            or "(!surface.blocksMotion() || snowLayer)" not in client_source
            or "snowSurfaceAccepted &= isGroundSurfaceAvailable(snow);" not in client_source
            or "fullSnowBlockRejected" not in client_source):
        raise RuntimeError("BLACK FREQUENCY snow layers must pass the surface filter without admitting full snow blocks")
    leaf_accent_start=client_source.find("private static boolean queueExposedLeafCanopyAccent(")
    leaf_accent_end=client_source.find("\n        private static double sampleGroundY",leaf_accent_start+1)
    leaf_accent_body=client_source[leaf_accent_start:leaf_accent_end] if leaf_accent_start>=0 and leaf_accent_end>leaf_accent_start else ""
    leaf_surface_clearance_start=client_source.find("private static int canopyAccentLeafSurfaceSkippedPlants(")
    leaf_surface_clearance_end=client_source.find("\n        private static double canopyAccentLiftedParticleY",leaf_surface_clearance_start+1)
    leaf_surface_clearance_body=(client_source[leaf_surface_clearance_start:leaf_surface_clearance_end]
        if leaf_surface_clearance_start>=0 and leaf_surface_clearance_end>leaf_surface_clearance_start else "")
    heightmap_prefilter_start=client_source.find("private static boolean canopyAccentHeightmapMayContainValidLeafAccent(")
    heightmap_prefilter_end=client_source.find("\n        // Sample one deterministic column",heightmap_prefilter_start+1)
    heightmap_prefilter_body=(client_source[heightmap_prefilter_start:heightmap_prefilter_end]
        if heightmap_prefilter_start>=0 and heightmap_prefilter_end>heightmap_prefilter_start else "")
    heightmap_prefilter_at=leaf_accent_body.find("canopyAccentHeightmapMayContainValidLeafAccent(groundY,topY)")
    leaf_state_at=leaf_accent_body.find("BlockState leaf=level.getBlockState(cursor);")
    if ("GROUND_CANOPY_LEAF_ACCENT_MAX_HEIGHT_ABOVE_GROUND=24.0D;" not in client_source
            or "GROUND_CANOPY_ACCENT_VERTICAL_OFFSET=.40D;" not in client_source
            or "GROUND_CANOPY_ACCENT_VERTICAL_OFFSET>=.36D" not in client_source
            or "GROUND_CANOPY_ACCENT_VERTICAL_OFFSET<=.44D" not in client_source
            or heightmap_prefilter_at<0 or leaf_state_at<0 or heightmap_prefilter_at>leaf_state_at
            or "Double.isFinite(groundY)" not in heightmap_prefilter_body
            or "maximumPossibleAccentY>groundY+1.0D" not in heightmap_prefilter_body
            or "minimumPossibleAccentY-groundY<=GROUND_CANOPY_LEAF_ACCENT_MAX_HEIGHT_ABOVE_GROUND" not in heightmap_prefilter_body
            or "GROUND_CANOPY_HEIGHTMAP_PREFILTER_PROFILE_VERIFIED" not in client_source
            or "level.hasChunk(blockX>>4,blockZ>>4)" not in leaf_accent_body
            or "getChunk(blockX>>4,blockZ>>4," not in leaf_accent_body
            or "net.minecraft.world.level.chunk.ChunkStatus.FULL,false" not in leaf_accent_body
            or "chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE" not in leaf_accent_body
            or "canopyAccentLeafSurfaceSkippedPlants(level,blockX,blockZ,topY,cursor)" not in leaf_accent_body
            or "canopyAccentExclusiveHeightmapTopY(reportedTopY,reportedTopOccupied)" not in leaf_accent_body
            or "!level.getWorldBorder().isWithinBounds(cursor)" not in leaf_accent_body
            or "canopyAccentLeafSurfaceY(topY,skipped)" not in client_source
            or "if(!leaf.is(BlockTags.LEAVES)) {" not in leaf_accent_body
            or "groundCanopyAccentQueueNonLeafRejected++" not in leaf_accent_body
            or "isCanopyAccentHeadroomAvailable(head)" not in leaf_accent_body
            or "canopyAccentPlantSurfaceParticleY((int)leafY,shapeHeight,skippedPlants)" not in leaf_accent_body
            or "private static int canopyAccentLeafSurfaceSkippedPlants(" not in client_source
            or "isCanopyAccentSkippablePlant(candidate,level,cursor)" not in leaf_surface_clearance_body
            or "GROUND_CANOPY_ACCENT_MAX_SKIPPED_PLANTS" not in client_source
            or "GROUND_CANOPY_LEAF_HEIGHTMAP_SOURCE_VERIFIED heightmap=world_surface includesLeaves=true includesPlants=true downwardPlantScan=2 roofRejected=true headroomAir=true loadedOnly=true" not in client_source
            or "GROUND_CANOPY_ACCENT_HEIGHTMAP_TOP_NORMALIZATION_PROFILE_VERIFIED" not in client_source
            or "if(!Double.isFinite(accentY)) {" not in leaf_accent_body
            or "groundCanopyAccentQueuePlantLifted++" not in leaf_accent_body
            or "isCanopyAccentHeightWithinBounds(groundY,accentY)" not in leaf_accent_body
            or "hasCanopyAccentCandidate(blockX,blockZ)" not in leaf_accent_body
            or "queueExposedLeafCanopyAccent(level,blockX,blockZ,groundY,arm,cursor)" not in client_source
            or "GROUND_CANOPY_LEAF_ACCENT_PROFILE_VERIFIED" not in client_source
            or "GROUND_CANOPY_ACCENT_LEAF_EDGE_OFFSET=.52D;" not in client_source
            or "private static double canopyAccentLeafEdgeOffset(" not in client_source
            or "private static boolean isCanopyAccentLeafEdgeTargetSafe(" not in client_source
            or "level.hasChunk(targetX>>4,targetZ>>4)" not in client_source
            or "level.getBlockState(SURFACE_CURSOR).isAir()" not in client_source):
        raise RuntimeError("BLACK FREQUENCY leaf canopy accents must use exposed top leaves from already-loaded chunks only")
    surface_plant_clearance_start=client_source.find("private static int canopyAccentPlantSurfaceSkippedPlants(")
    surface_plant_clearance_end=client_source.find("\n        private static double canopyAccentLiftedParticleY(",surface_plant_clearance_start+1)
    surface_plant_clearance_body=(client_source[surface_plant_clearance_start:surface_plant_clearance_end]
        if surface_plant_clearance_start>=0 and surface_plant_clearance_end>surface_plant_clearance_start else "")
    surface_plant_queue_start=client_source.find("private static boolean queuePlantSurfaceCanopyAccent(")
    surface_plant_queue_end=client_source.find("\n        /**",surface_plant_queue_start+1)
    surface_plant_queue_body=(client_source[surface_plant_queue_start:surface_plant_queue_end]
        if surface_plant_queue_start>=0 and surface_plant_queue_end>surface_plant_queue_start else "")
    plant_surface_particle_start=client_source.find("private static double canopyAccentPlantSurfaceParticleY(")
    plant_surface_particle_end=client_source.find("\n        private static boolean canopyAccentCandidateInsideExpandedSegmentBounds",plant_surface_particle_start+1)
    plant_surface_particle_body=(client_source[plant_surface_particle_start:plant_surface_particle_end]
        if plant_surface_particle_start>=0 and plant_surface_particle_end>plant_surface_particle_start else "")
    sample_ground_start=client_source.find("private static double sampleGroundY(")
    sample_ground_end=client_source.find("\n        private static long columnKey",sample_ground_start+1)
    sample_ground_body=(client_source[sample_ground_start:sample_ground_end]
        if sample_ground_start>=0 and sample_ground_end>sample_ground_start else "")
    if ("for(int skipped=0;skipped<=GROUND_CANOPY_ACCENT_MAX_SKIPPED_PLANTS;skipped++)" not in surface_plant_clearance_body
            or "isCanopyAccentHeadroomAvailable(head)" not in surface_plant_clearance_body
            or "isCanopyAccentSkippablePlant(head,level,cursor)" not in surface_plant_clearance_body
            or "canopyAccentPlantSurfaceParticleY" not in sample_ground_body
            or "canopyAccentPlantSurfaceShapeHeight(level,surface,blockX,candidateY,blockZ," not in sample_ground_body
            or "canopyAccentPlantSurfaceSkippedPlants(" not in sample_ground_body
            or "if(!Double.isFinite(shapeHeight) || shapeHeight<=0.0D" not in client_source
            or "skippedPlantSurfaceBlocks>=0" not in sample_ground_body
            or "skippedPlantSurfaceBlocks<0" not in sample_ground_body
            or "queuePlantSurfaceCanopyAccent" not in sample_ground_body
            or "groundCanopyAccentQueueAttempts++" not in surface_plant_queue_body
            or "findCanopyAccentCandidateIndex(blockX,blockZ," not in surface_plant_queue_body
            or "canUpgradeLeafTopCanopyAccentCandidate(existingCandidate," not in surface_plant_queue_body
            or "GROUND_CANOPY_ACCENT_LEAF_TOP[existingCandidate]=false;" not in surface_plant_queue_body
            or "groundCanopyAccentQueueAccepted++" not in surface_plant_queue_body
            or "groundCanopyAccentQueuePlantLifted++" not in surface_plant_queue_body
            or "if(skippedPlants==0) {" not in plant_surface_particle_body
            or "if(!Double.isFinite(shapeHeight) || shapeHeight<=0.0D) return Double.NaN;" not in plant_surface_particle_body
            or "long clearY=(long)surfaceY+1L+(long)skippedPlants;" not in plant_surface_particle_body
            or "GROUND_CANOPY_ACCENT_STACKED_PLANT_HEIGHT_INDEPENDENCE_VERIFIED" not in client_source
            or "GROUND_CANOPY_ACCENT_SKIPPED_PLANTS[candidate]=skippedPlants;" not in client_source
            or "GROUND_CANOPY_ACCENT_SURFACE_PLANT_LIFT_PROFILE_VERIFIED" not in client_source
            or "private static boolean isCanopyAccentPlantCandidate(BlockState state)" not in client_source
            or "return isCanopyAccentPlantCandidate(state) && level!=null && pos!=null\n                && state.getCollisionShape(level,pos).isEmpty();" not in client_source):
        raise RuntimeError("BLACK FREQUENCY stacked ground-plant accents must use bounded non-colliding clearance and complete queue accounting")
    plant_shape_helper_start=client_source.find("private static double canopyAccentPlantSurfaceShapeHeight(")
    plant_shape_helper_end=client_source.find("\n        private static boolean queueAdjacentPlantSurfaceCanopyAccent",plant_shape_helper_start+1)
    plant_shape_helper_body=(client_source[plant_shape_helper_start:plant_shape_helper_end]
        if plant_shape_helper_start>=0 and plant_shape_helper_end>plant_shape_helper_start else "")
    if ("if(skippedPlants>0) return 0.0D;" not in plant_shape_helper_body
            or "surface.getShape(level,cursor).max(Direction.Axis.Y)" not in plant_shape_helper_body
            or "if(skippedPlants<0 || skippedPlants>GROUND_CANOPY_ACCENT_MAX_SKIPPED_PLANTS)" not in plant_shape_helper_body
            or "GROUND_CANOPY_ACCENT_STACKED_PLANT_SHAPE_QUERY_SKIPPED_VERIFIED" not in client_source):
        raise RuntimeError("BLACK FREQUENCY lifted plant accents must skip unused outline-shape queries with bounded inputs")
    double_plant_marker=(
        "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_VANILLA_DOUBLE_PLANT_PROFILE_VERIFIED "
        "blocks=6 halves=lower,upper stateAllowlist=true maxSkipped=2"
    )
    double_plant_blocks=(
        "Blocks.TALL_GRASS","Blocks.LARGE_FERN","Blocks.LILAC",
        "Blocks.PEONY","Blocks.ROSE_BUSH","Blocks.SUNFLOWER"
    )
    if (double_plant_marker not in client_source
            or "DoublePlantBlock.HALF" not in client_source
            or "DoubleBlockHalf.LOWER" not in client_source
            or "DoubleBlockHalf.UPPER" not in client_source
            or any(block not in client_source for block in double_plant_blocks)):
        raise RuntimeError("BLACK FREQUENCY vanilla double-plant lower/upper states must pass the bounded canopy allowlist")
    adjacent_accent_start=client_source.find("private static boolean queueAdjacentExposedLeafCanopyAccent(")
    adjacent_accent_end=client_source.find("\n        private static double sampleGroundY",adjacent_accent_start+1)
    adjacent_accent_body=client_source[adjacent_accent_start:adjacent_accent_end] if adjacent_accent_start>=0 and adjacent_accent_end>adjacent_accent_start else ""
    adjacent_plant_start=client_source.find("private static boolean queueAdjacentPlantSurfaceCanopyAccent(")
    adjacent_plant_end=client_source.find("\n        private static boolean queueAdjacentExposedLeafCanopyAccent",adjacent_plant_start+1)
    adjacent_plant_body=(client_source[adjacent_plant_start:adjacent_plant_end]
        if adjacent_plant_start>=0 and adjacent_plant_end>adjacent_plant_start else "")
    neighbor_scan_start=client_source.find("private static void scanCanopyAccentNeighborSamples(")
    neighbor_scan_end=client_source.find("\n        private static double sampleGroundY",neighbor_scan_start+1)
    neighbor_scan_body=client_source[neighbor_scan_start:neighbor_scan_end] if neighbor_scan_start>=0 and neighbor_scan_end>neighbor_scan_start else ""
    fixture_anchor_reset_start=client_source.find("private static void resetCanopyAccentFixtureProbeForAnchorChange(")
    fixture_anchor_reset_end=client_source.find("\n        private static",fixture_anchor_reset_start+1)
    fixture_anchor_reset_body=(client_source[fixture_anchor_reset_start:fixture_anchor_reset_end]
        if fixture_anchor_reset_start>=0 and fixture_anchor_reset_end>fixture_anchor_reset_start else "")
    # The last fixture anchor survives geometry-cache invalidation; cachedAnchorX/Z may not.
    fixture_anchor_reset_call=re.search(
        r"if\s*\(\s*anchorChanged\s*\)\s*\{\s*"
        r"resetCanopyAccentFixtureProbeForAnchorChange\(\s*"
        r"worldAnchorX\s*,\s*worldAnchorZ\s*\)\s*;",
        client_source
    )
    if (not fixture_anchor_reset_body
            or "canopyAccentFixtureSampleLogged=false;" not in fixture_anchor_reset_body
            or "groundCanopyAccentLiftedLoggedCount=0;" not in fixture_anchor_reset_body
            or "previousSampleAnchorX=canopyAccentFixtureSampleAnchorX;" not in fixture_anchor_reset_body
            or "previousSampleAnchorZ=canopyAccentFixtureSampleAnchorZ;" not in fixture_anchor_reset_body
            or "if(VERIFY_GROUND_MESH_PROFILES && sampleAnchorChanged)" not in fixture_anchor_reset_body
            or "canopyAccentFixtureSampleAnchorX=nextAnchorX;" not in fixture_anchor_reset_body
            or "canopyAccentFixtureSampleAnchorZ=nextAnchorZ;" not in fixture_anchor_reset_body
            or "GROUND_CANOPY_ACCENT_FIXTURE_ANCHOR_RESET_VERIFIED" not in fixture_anchor_reset_body
            or "canopyAccentFixtureSampleAnchorX=Integer.MIN_VALUE;" not in client_source
            or "canopyAccentFixtureSampleAnchorZ=Integer.MIN_VALUE;" not in client_source
            or "canopyAccentFixtureSampleAnchorX=worldAnchorX;" not in neighbor_scan_body
            or "canopyAccentFixtureSampleAnchorZ=worldAnchorZ;" not in neighbor_scan_body
            or fixture_anchor_reset_call is None):
        raise RuntimeError("BLACK FREQUENCY canopy fixture probe must preserve its sampled anchor across cache invalidation")
    geometry_cache_clear_start=client_source.find("private static void clearGeometryCache()")
    geometry_cache_clear_end=client_source.find(
        "\n        private static void clearGeometryCacheIfPresent",geometry_cache_clear_start+1)
    geometry_cache_clear_body=(client_source[geometry_cache_clear_start:geometry_cache_clear_end]
        if geometry_cache_clear_start>=0 and geometry_cache_clear_end>geometry_cache_clear_start else "")
    if ("groundCanopyAccentLiftedLoggedCount=0;" not in geometry_cache_clear_body
            or "canopyAccentFixtureSampleLogged=false;" not in geometry_cache_clear_body
            or "groundCanopyAccentNeighborSampleCount=0;" not in geometry_cache_clear_body
            or "groundCanopyAccentCandidateCount=0;" not in geometry_cache_clear_body
            or "canopyAccentFixtureSampleAnchorX=" in geometry_cache_clear_body
            or "canopyAccentFixtureSampleAnchorZ=" in geometry_cache_clear_body):
        raise RuntimeError("BLACK FREQUENCY geometry cache invalidation must reset per-mesh canopy probe state")
    fixture_queue_call=neighbor_scan_body.find("boolean queued=queueAdjacentExposedLeafCanopyAccent")
    fixture_retry_branch=neighbor_scan_body.find("if(!queued) {",fixture_queue_call)
    fixture_marker=neighbor_scan_body.find("GROUND_CANOPY_ACCENT_FIXTURE_SAMPLE_VERIFIED",fixture_queue_call)
    fixture_retry_continue=neighbor_scan_body.find("continue;",fixture_retry_branch)
    fixture_found=neighbor_scan_body.find("GROUND_CANOPY_ACCENT_NEIGHBOR_SAMPLE_FOUND[sample]=true",fixture_queue_call)
    if not (fixture_queue_call>=0 and fixture_retry_branch>fixture_queue_call
            and fixture_marker>fixture_retry_branch
            and fixture_retry_continue>fixture_marker
            and fixture_found>fixture_retry_continue):
        raise RuntimeError(
            "BLACK FREQUENCY fixture samples must be published only after an unqueued "
            "neighbor attempt so the sample remains eligible for the next scan"
        )
    if ("canopyAccentAdjacentColumnX(blockX,arm,segment,oppositeSide,distance)" not in adjacent_accent_body
            or "canopyAccentAdjacentColumnZ(blockZ,arm,segment,oppositeSide,distance)" not in adjacent_accent_body
            or "Math.abs(dx)>RADIUS" not in adjacent_accent_body
            or "Math.abs(dz)>RADIUS" not in adjacent_accent_body
            or "queueAdjacentPlantSurfaceCanopyAccent(level,(int)adjacentX,(int)adjacentZ,groundY,arm,cursor)" not in adjacent_accent_body
            or "queueExposedLeafCanopyAccent(level,(int)adjacentX,(int)adjacentZ,groundY,arm,cursor)" not in adjacent_accent_body
            or "Math.floor(groundY)" not in adjacent_plant_body
            or "level.hasChunk(blockX>>4,blockZ>>4)" not in adjacent_plant_body
            or "isGroundSupport(support)" not in adjacent_plant_body
            or "groundSupportHeight(support,level,cursor)" not in adjacent_plant_body
            or "canopyAccentPlantSurfaceSkippedPlants" not in adjacent_plant_body
            or "canopyAccentPlantSurfaceShapeHeight(" not in adjacent_plant_body
            or "GROUND_CANOPY_ACCENT_MAX_GROUND_DELTA" not in adjacent_plant_body
            or "getChunk(" in adjacent_plant_body
            or "GROUND_CANOPY_ACCENT_ADJACENT_PLANT_SCAN_PROFILE_VERIFIED supportChecked=true groundTolerance=0.35 loadedOnly=true radius=48 maxSkipped=2" not in client_source
            or "queuePlantSurfaceCanopyAccent(\n                level,surface,blockX,blockZ,plantGroundY,accentY,skippedPlants,arm,true)" not in adjacent_plant_body
            or "GROUND_CANOPY_ACCENT_LIFTED_PARTICLE_EMITTED" not in client_source
            or "GROUND_CANOPY_ACCENT_FIXTURE_SAMPLE_VERIFIED" not in neighbor_scan_body
            or "offset>0" not in neighbor_scan_body
            or "level.hasChunk(neighborX>>4,neighborZ>>4)" not in neighbor_scan_body
            or "canopyAccentCandidateTouchesMesh(neighborX,neighborZ,sampleGroundY,segments)" not in neighbor_scan_body
            or "&& !directLiftedAccent" not in client_source
            or "for(int candidate=candidatesBeforeSample;candidate<groundCanopyAccentCandidateCount;candidate++)" not in client_source
            or "for(int pass=0;pass<4;pass++)" not in neighbor_scan_body
            or "canopyAccentNeighborScanDistance(pass)" not in neighbor_scan_body
            or "canopyAccentNeighborScanOppositeSide(pass)" not in neighbor_scan_body
            or "hasScannedCanopyAccentNeighborColumn" not in neighbor_scan_body
            or "groundCanopyAccentNeighborDuplicateScans++" not in neighbor_scan_body
            or "groundCanopyAccentNeighborScans++" not in neighbor_scan_body
            or "GROUND_CANOPY_ACCENT_NEIGHBOR_SCAN_BUDGET=80;" not in client_source
            or "GROUND_CANOPY_ACCENT_NEIGHBOR_SAMPLE_BUDGET=ARM_COUNT*SEGMENTS_PER_ARM;" not in client_source
            or "GROUND_CANOPY_ACCENT_MAX_HORIZONTAL_DISTANCE=1.60D;" not in client_source
            or "long tangentSign=segment<=SEGMENTS_PER_ARM/2?1L:-1L;" not in client_source
            or "distance==2" not in client_source
            or "arm==0?tangentSign:arm==2?-tangentSign:0L" not in client_source
            or "arm==1?tangentSign:arm==3?-tangentSign:0L" not in client_source
            or "queueCanopyAccentNeighborScanSample(blockX,blockZ,sampledY,arm,segment)" not in client_source
            or "scanCanopyAccentNeighborSamples(level,result,worldAnchorX,worldAnchorZ,SURFACE_CURSOR)" not in client_source
            or "perSample=1..4 maxNeighborScans=80 scanOrder=roundRobinPasses alternatingSides=true perpendicular=true diagonalFallback=true tangentTowardArmCenter=true fallbackOnMiss=true loadedOnly=true radius=48 uniqueColumns=true duplicateColumnsSkipped=true" not in client_source
            or "GROUND_CANOPY_DIAGONAL_SCAN_PROFILE_VERIFIED normalOffset=1 tangentOffset=1 towardArmCenter=true maxHorizontalDistance=1.6 diagonalNear=true straightDistanceTwoRejected=true" not in client_source
            or "GROUND_CANOPY_LEAF_ADJACENT_SCAN_PROFILE_VERIFIED" not in client_source):
        raise RuntimeError("BLACK FREQUENCY adjacent canopy scan must be round-robin, unique-column, radius-bounded, and loaded-only")
    fissure_start=source.find("private static FissureEmission emitSurfaceFissure(")
    fissure_end=source.find("\n    private static",fissure_start+1)
    fissure_emitter=source[fissure_start:fissure_end] if fissure_start>=0 and fissure_end>fissure_start else ""
    ground_sampler_start=source.find("private static boolean findFissureGroundSample(")
    ground_sampler_end=source.find("\n    /**",ground_sampler_start+1)
    ground_sampler=source[ground_sampler_start:ground_sampler_end] if ground_sampler_start>=0 and ground_sampler_end>ground_sampler_start else ""
    particle_height_start=source.find("private static double fissureSurfaceParticleY(")
    particle_height_end=source.find("\n    private static void verifyFissureSurfaceParticleHeight",particle_height_start)
    particle_height=source[particle_height_start:particle_height_end] if particle_height_start>=0 and particle_height_end>particle_height_start else ""
    target_start=source.find("private static FissureTarget findVisibleFissureTarget(")
    target_end=source.find("\n    // Run after ordinary explosion modifiers",target_start+1)
    target_body=source[target_start:target_end] if target_start>=0 and target_end>target_start else ""
    border_guard=ground_sampler.find("isFissureColumnInsideWorldBorder(level.getWorldBorder(),x,z,level.getMinBuildHeight(),cursor)")
    chunk_guard=ground_sampler.find("!level.hasChunk(x>>4,z>>4)")
    if ("if(result==null) return false;\n        result.clear();\n        if(level==null || cursor==null) return false;" not in ground_sampler
            or "BlockState support=level.getBlockState(cursor);" not in ground_sampler
            or "if(isFissureGroundSupport(support,level,cursor)) {" not in ground_sampler
            or "private static boolean hasFullFissureSupportTop(" not in source
            or "private static boolean isFissureGroundSupport(\n            BlockState state,net.minecraft.world.level.BlockGetter level,BlockPos pos)" not in source
            or border_guard<0 or chunk_guard<0 or border_guard>chunk_guard
            or "BlockState surface=level.getBlockState(cursor);" not in ground_sampler
            or "double shapeHeight=surface.isAir() ? 0.0D" not in ground_sampler
            or "result.particleY=fissureSurfaceParticleY(y,shapeHeight);" not in ground_sampler
            or "findFissureGroundSample(level,blockX,blockZ,cursor,groundSample)" not in fissure_emitter
            or "double particleY=groundSample.particleY;" not in fissure_emitter
            or "findFissureGroundSample(level,blockX,blockZ,cursor,groundSample)" not in target_body
            or "groundSample.particleY" not in target_body
            or "level.getBlockState(cursor)" in fissure_emitter
            or "level.getBlockState(cursor)" in target_body):
        raise RuntimeError("BLACK FREQUENCY fissure samples must reuse one validated surface BlockState for height and particle placement")
    if ("SURFACE_FISSURE_SAMPLE_STATE_REUSE_VERIFIED" not in source
            or "scratchReusable=true" not in source
            or "surfaceReads=1 particleRereads=0" not in source
            or "private static final int FISSURE_SURFACE_SEARCH_DEPTH=32;" not in source
            or "private static boolean isFissureCanopyOccluder(BlockState state)" not in source
            or "state.is(net.minecraft.tags.BlockTags.LEAVES)" not in source
            or "state.is(net.minecraft.tags.BlockTags.LOGS)" not in source
            or "state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)" not in source
            or "==net.minecraft.core.Direction.Axis.Y" not in source
            or "depth<FISSURE_SURFACE_SEARCH_DEPTH" not in ground_sampler
            or "private static final net.minecraft.world.level.levelgen.Heightmap.Types FISSURE_SURFACE_HEIGHTMAP=" not in source
            or "net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING;" not in source
            or "level.getHeight(FISSURE_SURFACE_HEIGHTMAP,x,z);" not in ground_sampler
            or "MOTION_BLOCKING_NO_LEAVES" in ground_sampler
            or "heightmapIncludesLeaves=true" not in source
            or "heightmap=motion-blocking ground=terrain-support-filtered" not in source
            or "heightmap=motion-blocking-no-leaves" in source
            or "if(isFissureCanopyOccluder(surface)) continue;" not in ground_sampler
            or "SURFACE_FISSURE_WORLD_BORDER_GUARD_VERIFIED inside=true outsideRejected=true nullRejected=true resultCleared=true" not in source):
        raise RuntimeError("BLACK FREQUENCY canopy traversal must skip only vertical trunks/leaves within the bounded surface scan and reject out-of-border columns before chunk reads")
    print("WORLD_CAPTURE: leaf-inclusive heightmap feeds bounded canopy traversal while preserving roof occlusion",flush=True)
    print("WORLD_CAPTURE: fissure surface sampling rejects out-of-border columns before loaded-chunk reads",flush=True)
    pad_start=source.find('if(playerInteractionTest && Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest"))')
    pad_end=source.find("} else if(playerInteractionTest)",pad_start+1)
    pad_body=source[pad_start:pad_end] if pad_start>=0 and pad_end>pad_start else ""
    completion_start=source.find("if(s.restoreIndex<0 && s.restoreTicks>=100)")
    completion_end=source.find("\n        s.setDirty();",completion_start)
    completion_body=source[completion_start:completion_end] if completion_start>=0 and completion_end>completion_start else ""
    completion_title_packets=("ClientboundSetTitlesAnimationPacket(","ClientboundSetTitleTextPacket(","ClientboundSetSubtitleTextPacket(")
    if ("private static Component restorationCompletionMessage(" not in source
            or "player.displayClientMessage(restorationCompletionMessage(" not in completion_body
            or "RESTORATION_COMPLETION_ACTIONBAR_SENT" not in completion_body
            or any(packet in completion_body for packet in completion_title_packets)
            or "RESTORATION_COMPLETION_ACTIONBAR_ONLY_VERIFIED success=true partial=true titlePackets=false" not in source
            or "RESTORE_SUCCESS_TITLE" in source
            or "RESTORE_PARTIAL_TITLE" in source
            or "RESTORE_TITLE_FADE_" in source):
        raise RuntimeError("BLACK FREQUENCY recovery completion must use a verified action-bar message without title packets")
    if ("public static final double FISSURE_BEND_AMPLITUDE=.24D;" not in geometry_contract
            or source.count("RiftGeometry.FISSURE_BEND_AMPLITUDE")<5
            or client_source.count("RiftGeometry.FISSURE_BEND_AMPLITUDE")<4
            or "CI_BEACON_PAD_FLOOR_Y_OFFSET=0;" not in source
            or "CI_BEACON_PAD_PLAYER_Y_OFFSET=1;" not in source
            or "CI_BEACON_PAD_LATERAL_RADIUS=0;" not in source
            or "CI_BEACON_PAD_STANDOFF=3.0D;" not in source
            or "CI_BEACON_PAD_GAP_ROW=2;" not in source
            or "CI_BEACON_PAD_FIRST_SOLID_ROW=3;" not in source
            or "CI_BEACON_PAD_LAST_SOLID_ROW=3;" not in source
            or "CI_BEACON_PLAYER_EYE_HEIGHT=1.62D;" not in source
            or "ciBeaconSightlineHeightAtBeaconFace()" not in source
            or "ciBeaconSightlineHeightAtPadInnerFace()" not in source
            or "Math.abs(distanceSquared-9.25D)>1.0E-9D" not in source
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
            or "FissureTarget targetSample=findVisibleFissureTarget(level,s,player,eye);" not in source
            or "targetSample!=null && hasContiguousVisibleFissureSpan(targetSample.visibleNeighbours())" not in source
            or "fissureVisibilityRequired=true" not in source
            or "verifyCiBeaconPadSignSelection();" not in source
            or "for(int offset=CI_BEACON_PAD_FIRST_SOLID_ROW;offset<=CI_BEACON_PAD_LAST_SOLID_ROW;offset++)" not in pad_body
            or "for(int lateral=-CI_BEACON_PAD_LATERAL_RADIUS;lateral<=CI_BEACON_PAD_LATERAL_RADIUS;lateral++)" not in pad_body
            or "hasLoadedBeaconSightline(level,demoPlayer,demoPos)" not in pad_body
            or "BEACON_SIGHTLINE_VERIFIED" not in pad_body
            or "demoPos.offset(dx,CI_BEACON_PAD_FLOOR_Y_OFFSET,dz)" not in pad_body
            or "Math.abs(padDx)>RADIUS" not in pad_body
            or "Math.abs(padDz)>RADIUS" not in pad_body
            or "level.getWorldBorder().isWithinBounds(floor)" not in pad_body
            or "level.hasChunk(floor.getX()>>4,floor.getZ()>>4)" not in pad_body
            or "demoPos.getY()+CI_BEACON_PAD_PLAYER_Y_OFFSET" not in pad_body
            or "demoPos.getX()+.5D+padDirectionX*CI_BEACON_PAD_STANDOFF" not in pad_body
            or "demoPos.getZ()+.5D+padDirectionZ*CI_BEACON_PAD_STANDOFF" not in pad_body
            or "lateralRadius={}" not in pad_body
            or "PLAYER_BEACON_SAFE_PAD_PROFILE_VERIFIED" not in pad_body):
        raise RuntimeError("BLACK FREQUENCY elevated perpendicular beacon pad must stay loaded, bounded, visible and within interaction reach")
    if ("BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();" not in target_body
            or "FissureGroundSample groundSample=new FissureGroundSample();" not in target_body
            or "findFissureGroundSample(level,blockX,blockZ,cursor,groundSample)" not in target_body
            or "groundSample.particleY" not in target_body
            or "fissureRayChunksLoaded(eye,target,(chunkX,chunkZ)->level.hasChunk(chunkX,chunkZ))" not in target_body
            or "LoadedChunkLookup" not in source
            or "Math.floor(startX)" not in source
            or "BEACON_RAY_CHUNK_TRAVERSAL_VERIFIED" not in source
            or "new BlockPos(" in target_body
            or "private static int findFissureGroundY(" in source):
        raise RuntimeError("BLACK FREQUENCY visible fissure target must reuse its loaded-only sample cursor")
    if ("BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();" not in fissure_emitter
            or "FissureGroundSample groundSample=new FissureGroundSample();" not in fissure_emitter
            or "findFissureGroundSample(level,blockX,blockZ,cursor,groundSample)" not in fissure_emitter
            or "double particleY=groundSample.particleY;" not in fissure_emitter
            or "new BlockPos(" in fissure_emitter
            or "new BlockPos(" in ground_sampler
            or "cursor.set(x,y,z)" not in ground_sampler
            or "cursor.set(x,y-1,z)" not in ground_sampler
            or "new BlockPos(" in particle_height
            or "cursor.set(x,surfaceY,z)" in particle_height):
        raise RuntimeError("BLACK FREQUENCY server fissure sampling must reuse one mutable block-position cursor")
    if ("private static final DustParticleOptions FISSURE_VIOLET_EDGE_DUST_SMALL" not in source
            or "private static final DustParticleOptions FISSURE_VIOLET_EDGE_DUST_LARGE" not in source
            or "private static DustParticleOptions fissureEdgeDustForWave(double wave)" not in source
            or "edgeWave=wave;" not in fissure_emitter
            or "DustParticleOptions edgeDust=fissureEdgeDustForWave(edgeWave);" not in fissure_emitter
            or "level.sendParticles(edgeDust," not in fissure_emitter
            or "level.sendParticles(FISSURE_VIOLET_EDGE_DUST," in fissure_emitter
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
            or "MAX_SNAPSHOT_ENTRY_ESTIMATE_BYTES=700L" not in source
            or "RECOVERY_SNAPSHOT_ENTRY_SIZE_BOUND_VERIFIED" not in source
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
    beacon_chunk_lookup=body.find("if(beaconPositionValid) {",beacon_anchor_guard)
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
            or "beaconChunkLoaded=level.hasChunk(beaconChunkX,beaconChunkZ);" not in body
            or "beaconChunkLoaded=s.restoreChunksLoadedThisTick.contains(beaconChunkKey);" not in body
            or "boolean beaconPresent=beaconChunkLoaded" not in body
            or "if(restoreChunkKey==beaconChunkKey) beaconChunkSnapshotCacheHit=true;" not in body
            or "RESTORE_BEACON_CHUNK_CACHE_PROFILE" not in body
            or "level.hasChunkAt(activatedBeacon)" in body
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
    activation_start=controller.find("public void onBeacon(")
    activation_end=controller.find("\n    /**",activation_start)
    activation=controller[activation_start:activation_end] if activation_start>=0 and activation_end>activation_start else ""
    gate_start=controller.find("private static boolean mayActivateBeacon(")
    gate_end=controller.find("\n    private static boolean mayRecoverCiBeaconAimAfterMiss",gate_start)
    gate=controller[gate_start:gate_end] if gate_start>=0 and gate_end>gate_start else ""
    sightline_start=controller.find("private static boolean hasLoadedBeaconSightline(")
    sightline_end=controller.find("\n    /**\n     * Pick a real emitted fissure sample",sightline_start)
    sightline=controller[sightline_start:sightline_end] if sightline_start>=0 and sightline_end> sightline_start else ""
    helper_start=controller.find("private static double eyeDistanceSquaredToBlock(")
    helper_end=controller.find("\n    private static boolean withinBeaconInteractionReach",helper_start)
    helper=controller[helper_start:helper_end] if helper_start>=0 and helper_end>helper_start else ""
    verify_start=controller.find("private static void verifyCiNaturalBeaconBreakNoDrop(")
    verify_end=controller.find("\n    @SubscribeEvent",verify_start)
    verify=controller[verify_start:verify_end] if verify_start>=0 and verify_end>verify_start else ""
    pad_profile_start=controller.find("private static void verifyCiBeaconPadProfile(")
    pad_profile_end=controller.find("\n    @FunctionalInterface",pad_profile_start)
    pad_profile=controller[pad_profile_start:pad_profile_end] if pad_profile_start>=0 and pad_profile_end>pad_profile_start else ""
    completion_start=controller.find("if(s.restoreIndex<0 && s.restoreTicks>=100)")
    probe_start=controller.find('if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPostRestoreBeaconBreakTest"))',completion_start)
    probe_end=controller.find("\n            for(var player:level.players())",probe_start)
    probe=controller[probe_start:probe_end] if probe_start>=0 and probe_end>probe_start else ""
    script=pathlib.Path(__file__).read_text(encoding="utf-8")
    if (not handler or "event.setCanceled(true);" not in handler
            or "level.setBlock(pos,Blocks.AIR.defaultBlockState(),3)" not in handler
            or "ciPendingNaturalBeaconBreakVerificationPos=pos.immutable();" not in handler
            or "ciPendingNaturalBeaconBreakVerificationSurvival=!miner.isCreative()" not in handler
            or "ciPendingNaturalBeaconBreakVerificationDistanceSquared=distanceSquared;" not in handler
            or "eyeDistanceSquaredToBlock(miner.getEyePosition(),pos)" not in handler
            or "withinBeaconInteractionReach(distanceSquared)" not in handler
            or "eyeDistanceSquaredToBlock(event.getEntity().getEyePosition(),pos)" not in activation
            or "hit.getBlockPos().equals(pos)" not in activation
            or "hit.getDirection()" not in activation
            or "face.getStepX()*1.0E-4D" not in activation
            or "face.getStepY()*1.0E-4D" not in activation
            or "face.getStepZ()*1.0E-4D" not in activation
            or "eyeDistanceSquaredToHit(player.getEyePosition(),requestedHitPoint)" not in activation
            or "withinBeaconInteractionReach(hitDistanceSquared)" not in activation
            or "hasLoadedBeaconSightline(level,player,pos,hitPoint,requestedHitPoint)" not in activation
            or "BEACON_HIT_POINT_ACCEPTED_VERIFIED" not in activation
            or "shouldSuppressActiveRecoveryBeaconMenu(s.started,s.restoring,s.restored," not in activation
            or "s.beaconPosPresent,s.beaconPos,pos.asLong(),!s.snapshots.isEmpty()" not in activation
            or "BEACON_ACTIVE_MENU_INTERACTION_SUPPRESSED" not in activation
            or "private static boolean shouldSuppressActiveRecoveryBeaconMenu(" not in controller
            or "ACTIVE_RECOVERY_BEACON_MENU_GUARD_VERIFIED" not in controller
            or "for(var nearbyPlayer:level.players())" not in activation
            or "for(var player:level.players())" in activation
            or "BEACON_ACTIVATION_SIGHTLINE_VERIFIED hit=beacon loadedChunksOnly=true" not in activation
            or "private static boolean verifyBeaconSightlineOcclusion(" not in sightline
            or "Blocks.BARRIER.defaultBlockState()" not in sightline
            or "BEACON_SIGHTLINE_OCCLUSION_REJECTED" not in sightline
            or "private static boolean hasLoadedBeaconSightline(" not in sightline
            or "sameBeaconHitPoint(hit.getLocation(),expectedHitPoint)" not in sightline
            or "private static double eyeDistanceSquaredToHit(" not in controller
            or "private static boolean sameBeaconHitPoint(" not in controller
            or "fissureRayChunksLoaded(eye,target,(chunkX,chunkZ)->level.hasChunk(chunkX,chunkZ))" not in sightline
            or "ClipContext.Block.OUTLINE" not in sightline
            or "ClipContext.Fluid.NONE" not in sightline
            or "level.clip(new net.minecraft.world.level.ClipContext(eye,target," not in sightline
            or "withinBeaconInteractionReach(distanceSquared)" not in gate
            or "NATURAL_BEACON_MINED_NO_DROP" not in handler
            or "private static double eyeDistanceSquaredToBlock(" not in controller
            or "private static boolean withinBeaconInteractionReach(" not in controller
            or "withinBeaconInteractionReach(boundaryX)" not in controller
            or "private static boolean mayMineRecoveryBeacon(" not in controller
            or "mayMineRecoveryBeacon(miner.isAlive(),miner.isRemoved(),miner.isSpectator(),miner.getAbilities().mayBuild)" not in handler
            or "BEACON_BREAK_BUILD_PERMISSION_GUARD_VERIFIED" not in controller
            or "REAL_PLAYER_ADVENTURE_BEACON_BREAK_REJECTED" not in controller
            or "NATURAL_BEACON_BREAK_REACH_GUARD_VERIFIED" not in controller
            or "Math.max(minX,Math.min(eye.x,minX+1.0D))" not in helper
            or "Math.max(minY,Math.min(eye.y,minY+1.0D))" not in helper
            or "Math.max(minZ,Math.min(eye.z,minZ+1.0D))" not in helper
            or "eye.distanceToSqr(closestX,closestY,closestZ)" not in helper
            or "private static void verifyCiNaturalBeaconBreakNoDrop(" not in controller
            or "verifyCiNaturalBeaconBreakNoDrop(level,s);" not in controller
            or "verifyCiBeaconBreakReachGeometry();" not in pad_profile
            or "BEACON_BREAK_REACH_AABB_VERIFIED" not in controller
            or "getEntitiesOfClass(" not in verify
            or "countItem(net.minecraft.world.item.Items.BEACON)" not in verify
            or "loadedOnly=true" not in verify
            or "boolean survivalMining=ciPendingNaturalBeaconBreakVerificationSurvival;" not in verify
            or "double distanceSquared=ciPendingNaturalBeaconBreakVerificationDistanceSquared;" not in verify
            or "distanceToSqr(" not in verify
            or "withinBeaconInteractionReach(distanceSquared)" not in verify
            or "capturedAtBreak=true" not in verify
            or probe.count("new net.minecraftforge.event.level.BlockEvent.BreakEvent(")!=2
            or "adventureEvent" not in probe or "mayBuild=false" not in probe
            or "testEvent" in probe
            or "POST_RESTORE_BEACON_BREAK_CONSUMED" in probe
            or 'subprocess.run(["xdotool","mousedown","1"]' not in script
            or "REAL_PLAYER_BEACON_BREAK_NO_DROP_VERIFIED" not in script
            or "beacon-after-left-click.png" not in script):
        raise RuntimeError("BLACK FREQUENCY post-restoration beacon mining must capture survival and eye-to-block reach at the real break event")
    print("WORLD_CAPTURE: beacon activation enforces eye-to-AABB reach and loaded-only line of sight; mining enforces reach",flush=True)

def point_to_block_aabb_distance_squared(point,origin):
    if not all(math.isfinite(value) for value in (*point,*origin)):
        return float("nan")
    closest=tuple(max(base,min(value,base+1.0)) for value,base in zip(point,origin))
    return sum((value-near)**2 for value,near in zip(point,closest))

def verify_beacon_interaction_reach_geometry():
    interaction_limit=4.5*4.5
    pad_eye=(3.0+.5,1.0+1.62,0.5)
    pad_distance=point_to_block_aabb_distance_squared(pad_eye,(0.0,0.0,0.0))
    edge_distance=point_to_block_aabb_distance_squared((5.5,0.5,0.5),(0.0,0.0,0.0))
    outside_distance=point_to_block_aabb_distance_squared((5.5001,0.5,0.5),(0.0,0.0,0.0))
    invalid_distance=point_to_block_aabb_distance_squared((float("nan"),0.5,0.5),(0.0,0.0,0.0))
    if (not math.isclose(pad_distance,8.8744,rel_tol=0.0,abs_tol=1.0E-9)
            or pad_distance>interaction_limit
            or not math.isclose(edge_distance,interaction_limit,rel_tol=0.0,abs_tol=1.0E-9)
            or outside_distance<=interaction_limit
            or math.isfinite(invalid_distance)):
        raise RuntimeError("BLACK FREQUENCY eye-to-block AABB reach geometry regression")
    print("WORLD_CAPTURE: eye-to-block AABB reach geometry verified pad=8.8744 boundary=20.25 outsideRejected=true invalidRejected=true",flush=True)

verify_fissure_target_view_score_profile()
verify_deferred_restore_chunk_bounds_profile()
verify_restoration_particle_source()
verify_beacon_aura_source()
verify_green_corruption_shader_source()
verify_real_beacon_break_source()
verify_beacon_interaction_reach_geometry()
verify_camera_input_profile()

def valid_fissure_particle_emission(counts):
    if counts is None:
        return False
    samples,accent_dust,violet_dust,edge_dust,total=counts
    return (32<=samples<=FISSURE_PARTICLE_SAMPLES
            and 0<=accent_dust<=8
            and 0<=violet_dust==samples-accent_dust
            and 3<=edge_dust<=4
            and total==samples+edge_dust and total<=52
            and (samples!=FISSURE_PARTICLE_SAMPLES
                 or (accent_dust==8 and violet_dust==40 and edge_dust==4)))

_fissure_fixture=(
    "BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLES_EMITTED "
    "samples=48 accentDust=8 violetDust=40 edgeDust=4 total=52 "
    "heightmap=motion-blocking"
)
if (parse_fissure_particle_emission(_fissure_fixture)!=(48,8,40,4,52)
        or not valid_fissure_particle_emission((48,8,40,4,52))
        or not valid_fissure_particle_emission((36,6,30,3,39))
        or valid_fissure_particle_emission((32,4,28,2,34))
        or valid_fissure_particle_emission((48,8,40,5,53))
        or parse_fissure_particle_emission(_fissure_fixture.replace("violetDust","reversePortal")) is not None
        or parse_fissure_particle_emission(_fissure_fixture.replace("accentDust","dragonBreath")) is not None
        or parse_fissure_particle_emission(_fissure_fixture.replace("edgeDust","sh"+"ards")) is not None
        or parse_fissure_particle_emission(_fissure_fixture.replace(
            "heightmap=motion-blocking","heightmap=motion-blocking-no-leaves")) is not None):
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

deferred_chunk_probe=(
    os.environ.get("BF_ISOLATED_TEST")=="1"
    and os.environ.get("BF_TEST_RESTORATION")=="1"
    and os.environ.get("BF_TEST_SPARSE_RECOVERY")!="1"
    and os.environ.get("BF_TEST_GREEN_PHASE_PREVIEW")!="1"
)

opts=minecraft_launcher_lib.utils.generate_test_options()
opts.update({
    "username":"EHTID_CI",
    "executablePath":"java",
    "defaultExecutablePath":"java",
    "gameDirectory":str(MC),
    "jvmArguments":["-Xms1G","-Xmx6G","-Djava.awt.headless=false","-Dblackfrequency.ciTestMode=true","-Dblackfrequency.verifyGroundMeshProfiles=true","-Dblackfrequency.ciTickStep=2400"] + (["-Dblackfrequency.ciGreenPhasePreview=true"] if os.environ.get("BF_TEST_GREEN_PHASE_PREVIEW")=="1" else []) + (["-Dblackfrequency.ciClientSmokeTest=true"] if os.environ.get("BF_TEST_RESTORATION")=="1" else []) + (["-Dblackfrequency.ciDeferredChunkProbe=true"] if deferred_chunk_probe else []) + (["-Dblackfrequency.ciExerciseRestoration=true"] if os.environ.get("BF_TEST_RESTORATION")=="1" else []) + (["-Dblackfrequency.ciPlayerBeaconTest=true"] if os.environ.get("BF_TEST_PLAYER_BEACON")=="1" else []) + (["-Dblackfrequency.ciNaturalBeaconTest=true"] if os.environ.get("BF_TEST_NATURAL_BEACON")=="1" else []) + (["-Dblackfrequency.ciPostRestoreBeaconBreakTest=true"] if os.environ.get("BF_TEST_NATURAL_BEACON")=="1" else []) + (["-Dblackfrequency.ciSparseRecoveryTest=true"] if os.environ.get("BF_TEST_SPARSE_RECOVERY")=="1" else []) + (["-Dblackfrequency.ciSnowBeaconTest=true"] if os.environ.get("BF_TEST_SNOW_BEACON")=="1" else []),
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
# Keep four rendered chunks so the vanilla cloud layer remains available during the
# visual probe. The player still travels well beyond the 48-block fracture and the
# five-chunk simulation halo, allowing natural unload without force-loading snapshots.
option_overrides=[
    ("rawMouseInput","false"),
    ("mouseSensitivity",str(MOUSE_SENSITIVITY)),
    ("pauseOnLostFocus","false"),
    # Use Fancy graphics in this disposable client: leaves are transparent and the
    # cloud layer is enabled, so both visual occlusion and cloud suppression are tested.
    ("graphicsMode","1"),
    # Minecraft 1.20.1 CloudStatus uses the unquoted options.txt tokens false/fast/true.
    ("renderClouds","true"),
]
if deferred_chunk_probe:
    option_overrides.extend((("renderDistance",str(DEFERRED_RENDER_DISTANCE)),
                             ("simulationDistance",str(DEFERRED_SIMULATION_DISTANCE))))
for option,value in option_overrides:
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
if option_values.get("graphicsMode")!="1":
    raise SystemExit("Could not enable Fancy graphics for the visual-occlusion smoke test")
if option_values.get("renderClouds")!="true":
    raise SystemExit("Could not serialize renderClouds as the Minecraft 1.20.1 CloudStatus token")
print("WORLD_CAPTURE: Fancy graphics and vanilla clouds enabled for visual smoke tests (renderClouds:true)",flush=True)
if deferred_chunk_probe and (
        option_values.get("renderDistance")!=str(DEFERRED_RENDER_DISTANCE)
        or option_values.get("simulationDistance")!=str(DEFERRED_SIMULATION_DISTANCE)):
    raise SystemExit("Could not apply the isolated deferred-chunk render/simulation distance profile")
print("WORLD_CAPTURE: raw mouse input disabled for deterministic X11 camera control",flush=True)
print("WORLD_CAPTURE: pauseOnLostFocus=false to keep restoration ticking during capture",flush=True)
if deferred_chunk_probe:
    spawn_ack_fixture="[System] [CHAT] Set the world spawn point to 512, 64, 512 [0.0]"
    if not re.search(DEFERRED_SPAWN_ACK_RE,spawn_ack_fixture):
        raise SystemExit("Deferred probe spawn acknowledgement regex does not match Minecraft 1.20.1 output")
    print("WORLD_CAPTURE: real deferred-chunk probe enabled",
          f"renderDistance={DEFERRED_RENDER_DISTANCE}",
          f"simulationDistance={DEFERRED_SIMULATION_DISTANCE}",flush=True)
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
def read_client_log(fallback=""):
    # The tee'd process stream is line-flushed; latest.log can lag the console appender
    # during the final capture and otherwise hide freshly emitted render-thread markers.
    try:
        text=console.read_text(encoding="utf-8",errors="replace")
        if text:
            return text
    except OSError:
        pass
    try:
        text=latest.read_text(encoding="utf-8",errors="replace")
        if text:
            return text
    except OSError:
        pass
    return fallback

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
    deferred_shared_spawn_relocated=False
    deferred_initial_spawn_chunk=None
    deferred_anchor_creative=False
    deferred_anchor_survival_restored=False
    game_window=None
    def find_game_window():
        named=subprocess.run(["xdotool","search","--onlyvisible","--name","Minecraft"],
                             capture_output=True,text=True,check=False)
        candidates=named.stdout.splitlines() if named.returncode==0 else []
        if not candidates:
            # GLFW may expose an empty or non-Minecraft WM_NAME on a fresh Xvfb session.
            # Fall back to the largest visible application window, not the root window.
            visible=subprocess.run(["xdotool","search","--onlyvisible","--name",".*"],
                                   capture_output=True,text=True,check=False)
            candidates=visible.stdout.splitlines() if visible.returncode==0 else []
        best=None
        best_area=0
        for candidate in candidates:
            geometry=subprocess.run(["xdotool","getwindowgeometry","--shell",candidate],
                                    capture_output=True,text=True,check=False)
            values=dict(line.split("=",1) for line in geometry.stdout.splitlines() if "=" in line)
            try:
                width=int(values.get("WIDTH","0"))
                height=int(values.get("HEIGHT","0"))
            except ValueError:
                continue
            area=width*height
            if width>=800 and height>=600 and area>best_area:
                best=candidate
                best_area=area
        return best
    def focus_game():
        nonlocal game_window
        if not game_window:
            game_window=find_game_window()
        if not game_window:
            return False
        # Xvfb runs without a window manager, so windowactivate may have no active-window target.
        # Set X input focus directly and verify the server-reported focused window.
        for _ in range(3):
            focused=subprocess.run(["xdotool","windowfocus","--sync",game_window],
                                   stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
            current=subprocess.run(["xdotool","getwindowfocus"],
                                   capture_output=True,text=True,check=False)
            if (focused.returncode==0 and current.returncode==0
                    and current.stdout.strip()==str(game_window)):
                return True
            time.sleep(0.15)
        # Reacquire once in case Minecraft recreated its GLFW window during loading.
        game_window=find_game_window()
        if not game_window:
            return False
        focused=subprocess.run(["xdotool","windowfocus","--sync",game_window],
                               stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
        current=subprocess.run(["xdotool","getwindowfocus"],
                               capture_output=True,text=True,check=False)
        return focused.returncode==0 and current.returncode==0 and current.stdout.strip()==str(game_window)
    def key(k,focus=True):
        if focus:
            focus_game()
        return subprocess.run(["xdotool","key","--clearmodifiers",k],check=False)
    def wait_for_isolated_client_input_ready(timeout=20.0):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline and p.poll() is None:
            try:
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            except OSError:
                current=""
            if DEFERRED_CLIENT_INPUT_READY_MARKER in current:
                return True
            time.sleep(0.1)
        return False
    isolated_chat_open=False
    def send_isolated_chat_command(command,confirmation,description,timeout=8.0,required=True):
        nonlocal isolated_chat_open
        # This helper is used only in the disposable isolated client world.
        # A player teleport loads chunks through normal player movement; it never
        # calls setChunkForced or any server-side chunk-loading API.
        command_body=isolated_chat_command_body(command)
        if command_body is None:
            print("WORLD_CAPTURE_FAIL: isolated command must start with '/' for",description,flush=True)
            return False
        if not wait_for_isolated_client_input_ready():
            print("WORLD_CAPTURE_FAIL: client input was not ready for",description,flush=True)
            return False
        for attempt in range(2 if required else 1):
            if not focus_game():
                print("WORLD_CAPTURE: Minecraft window focus was not confirmed; retrying",
                      description,"attempt",attempt+1,flush=True)
                continue
            log_offset=latest.stat().st_size if latest.exists() else 0
            print("WORLD_CAPTURE_INPUT: Minecraft window focus confirmed for",description,
                  "attempt",attempt+1,flush=True)
            # Escape only when a previous input attempt left ChatScreen open. Sending
            # Escape unconditionally toggles the pause menu in a clean world and pauses
            # the integrated server between fixture commands.
            if isolated_chat_open:
                closed=key("Escape",focus=False)
                if closed.returncode!=0:
                    print("WORLD_CAPTURE: could not close the previous chat screen; retrying",
                          description,"attempt",attempt+1,flush=True)
                    continue
                isolated_chat_open=False
                time.sleep(0.12)
            # The slash key opens ChatScreen with '/' already entered. Waiting before
            # typing the body prevents early xdotool characters from being lost during
            # the screen transition (which previously turned /setworldspawn into chat text).
            opened=key("slash",focus=False)
            if opened.returncode==0:
                isolated_chat_open=True
            time.sleep(1.75)
            print("WORLD_CAPTURE_INPUT: command chat opened with slash key; typing body",
                  description,flush=True)
            clipboard=subprocess.run(
                ["xclip","-selection","clipboard","-loops","1","-i"],
                input=command_body.encode("utf-8"),check=False)
            pasted=key("ctrl+v",focus=False)
            time.sleep(0.35)
            submitted=key("Return",focus=False)
            if submitted.returncode==0:
                isolated_chat_open=False
            if (opened.returncode!=0 or clipboard.returncode!=0
                    or pasted.returncode!=0 or submitted.returncode!=0):
                print("WORLD_CAPTURE: isolated command input failed; retrying",
                      description,"attempt",attempt+1,flush=True)
                continue
            # Mutating fixture commands are followed by unique execute-if-block
            # assertions. Do not accept a delayed generic setblock response as proof.
            if confirmation is None:
                print("WORLD_CAPTURE: submitted",description,flush=True)
                return True
            deadline=time.monotonic()+timeout
            while time.monotonic()<deadline and p.poll() is None:
                try:
                    with latest.open("rb") as stream:
                        size=stream.seek(0,os.SEEK_END)
                        if size<log_offset:
                            fresh=""
                        else:
                            stream.seek(log_offset)
                            fresh=stream.read().decode("utf-8",errors="replace")
                except OSError:
                    fresh=""
                if re.search(confirmation,fresh):
                    print("WORLD_CAPTURE: server acknowledged",description,flush=True)
                    return True
                time.sleep(0.1)
            if required and attempt==0 and p.poll() is None:
                print("WORLD_CAPTURE: command acknowledgement delayed; retrying",
                      description,flush=True)
                time.sleep(0.25)
        if not required:
            return False
        print("WORLD_CAPTURE_FAIL: no server acknowledgement for",description,flush=True)
        return False
    def click(x,y,focus=True):
        # Hold/release explicitly: some GLFW clients ignore xdotool's fast click.
        if focus:
            focus_game()
        subprocess.run(["xdotool","mousemove","--sync",str(x),str(y)],check=False)
        time.sleep(0.25)
        subprocess.run(["xdotool","mousedown","1"],check=False)
        time.sleep(0.18)
        subprocess.run(["xdotool","mouseup","1"],check=False)
        print("WORLD_CAPTURE_INPUT: mouse click",x,y,flush=True)
    shots=ROOT/"client-smoke-artifacts"
    os.makedirs(shots,exist_ok=True)
    game_window=find_game_window()
    print("WORLD_CAPTURE_INPUT: selected visible game window",game_window,flush=True)
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
    def move_camera_horizontal(delta_x,focus=True):
        # Do not reactivate the X11 window after an empty-sky click has grabbed GLFW's cursor.
        if focus:
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
        return round((target_pitch-current_pitch)/camera_degrees_per_pixel())
    def camera_delta_for_yaw(current_yaw,target_yaw):
        # Positive X mouse motion increases Minecraft yaw; use the shortest
        # signed correction across the -180/180 boundary.
        delta=(target_yaw-current_yaw+180.0)%360.0-180.0
        return round(delta/camera_degrees_per_pixel())

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

    def read_client_camera_pose(log_text=None):
        if log_text is None:
            log_text=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
        matches=list(re.finditer(
            r"BLACK_FREQUENCY_TEST CLIENT_CAMERA_POSE x=([-+0-9.eE]+) y=([-+0-9.eE]+) z=([-+0-9.eE]+) yaw=([-+0-9.eE]+) pitch=([-+0-9.eE]+)",
            log_text))
        if not matches:
            return None
        pose=tuple(map(float,matches[-1].groups()))
        return pose if all(math.isfinite(value) for value in pose) else None

    def beacon_aim_from_camera_pose(pose,beacon_pos):
        if pose is None or beacon_pos is None or len(pose)!=5 or len(beacon_pos)!=3:
            raise ValueError("beacon aim requires a finite rendered camera pose and block position")
        values=tuple(float(value) for value in pose)+tuple(float(value) for value in beacon_pos)
        if not all(math.isfinite(value) for value in values):
            raise ValueError("beacon aim coordinates must be finite")
        eye_x,eye_y,eye_z,_,_=values[:5]
        block_x,block_y,block_z=values[5:]
        dx=block_x+0.5-eye_x
        dy=block_y+0.5-eye_y
        dz=block_z+0.5-eye_z
        horizontal=math.hypot(dx,dz)
        if not math.isfinite(horizontal) or horizontal<=1.0E-6:
            raise ValueError("beacon aim requires a non-zero horizontal distance")
        yaw=(math.degrees(math.atan2(dz,dx))-90.0+180.0)%360.0-180.0
        pitch=-math.degrees(math.atan2(dy,horizontal))
        if not math.isfinite(yaw) or not math.isfinite(pitch):
            raise ValueError("beacon aim produced a non-finite rotation")
        return yaw,pitch

    def read_post_restore_beacon_aim(log_text=None):
        if log_text is None:
            log_text=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
        matches=list(re.finditer(
            r"BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_READY(?:_FOR_REAL_PLAYER)? x=(-?[0-9]+) y=(-?[0-9]+) z=(-?[0-9]+)",
            log_text))
        if not matches:
            return None
        pose=read_client_camera_pose(log_text)
        if pose is None:
            return None
        beacon_pos=tuple(map(int,matches[-1].groups()))
        try:
            return beacon_aim_from_camera_pose(pose,beacon_pos)
        except (TypeError,ValueError,OverflowError):
            return None

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

    def wait_for_camera_rotation_change(previous,timeout=1.5):
        if previous is None:
            return None
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            current=read_client_camera_rotation()
            if current is not None:
                yaw_delta=abs(wrapped_angle_delta(current[0],previous[0]))
                pitch_delta=abs(current[1]-previous[1])
                if max(yaw_delta,pitch_delta)>=CAMERA_ROTATION_LOG_THRESHOLD_DEGREES:
                    return current
            time.sleep(0.05)
        return None

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

    def camera_rotation_command(target_yaw,target_pitch):
        if (not math.isfinite(target_yaw) or not math.isfinite(target_pitch)
                or target_pitch < -90.0 or target_pitch > 90.0):
            raise ValueError("Invalid physical beacon camera rotation")
        # Cheats are enabled only in the disposable isolated CI world. Relative
        # coordinates preserve the player's safe-pad position; only view rotation changes.
        return f"/tp @s ~ ~ ~ {target_yaw:.6f} {target_pitch:.6f}"

    def set_camera_orientation_in_disposable_world(target_yaw,target_pitch):
        if os.environ.get("BF_ISOLATED_TEST")!="1":
            return None
        current=read_client_camera_rotation()
        if (current is not None
                and abs(wrapped_angle_delta(current[0],target_yaw))<=1.0
                and abs(current[1]-target_pitch)<=1.0):
            return current
        log_offset=latest.stat().st_size if latest.exists() else 0
        focus_game()
        key("t",focus=False)
        time.sleep(0.12)
        command=camera_rotation_command(target_yaw,target_pitch)
        typed=subprocess.run(
            ["xdotool","type","--clearmodifiers","--delay","1",command],check=False)
        if typed.returncode!=0:
            print("WORLD_CAPTURE_FAIL: could not type exact disposable-world camera rotation",
                  flush=True)
            return None
        key("Return",focus=False)
        deadline=time.monotonic()+3.0
        while time.monotonic()<deadline and p.poll() is None:
            try:
                with latest.open("rb") as stream:
                    size=stream.seek(0,os.SEEK_END)
                    stream.seek(0 if size<log_offset else log_offset)
                    fresh=stream.read().decode("utf-8",errors="replace")
            except OSError:
                fresh=""
            measured=read_client_camera_rotation(fresh)
            if (measured is not None
                    and abs(wrapped_angle_delta(measured[0],target_yaw))<=1.0
                    and abs(measured[1]-target_pitch)<=1.0):
                print("WORLD_CAPTURE: disposable-world beacon camera rotation verified",
                      round(measured[0],3),round(measured[1],3),flush=True)
                return measured
            time.sleep(0.05)
        measured=read_client_camera_rotation()
        print("WORLD_CAPTURE_FAIL: exact disposable-world camera rotation was not rendered",
              measured,"expected",(target_yaw,target_pitch),flush=True)
        return None

    def aim_camera_at_beacon(log_text=None,allow_disposable_teleport=False):
        if log_text is None:
            log_text=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
        post_restore_marker=re.search(
            r"BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_READY(?:_FOR_REAL_PLAYER)? x=(-?[0-9]+) y=(-?[0-9]+) z=(-?[0-9]+)",
            log_text)
        target=read_post_restore_beacon_aim(log_text)
        if post_restore_marker is not None:
            deadline=time.monotonic()+2.0
            while target is None and time.monotonic()<deadline and p.poll() is None:
                time.sleep(0.05)
                log_text=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else log_text
                target=read_post_restore_beacon_aim(log_text)
            if target is None:
                print("WORLD_CAPTURE: current rendered camera pose is not ready for post-restoration beacon aim",flush=True)
                return False
            print("WORLD_CAPTURE: post-restoration beacon aim recomputed from live camera pose",
                  round(target[0],3),round(target[1],3),flush=True)
        else:
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
            if allow_disposable_teleport:
                # The isolated CI world may set view rotation with a player command;
                # the following right-click is still sent by the real client input path.
                current=set_camera_orientation_in_disposable_world(target[0],target[1])
            else:
                current=restore_camera_orientation(target[0],target[1])
        if (current is None
                or abs(wrapped_angle_delta(current[0],target[0]))>3.0
                or abs(current[1]-target[1])>3.0):
            if allow_disposable_teleport:
                current=set_camera_orientation_in_disposable_world(target[0],target[1])
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
    camera_pose_sample="BLACK_FREQUENCY_TEST CLIENT_CAMERA_POSE x=9.5 y=67.62 z=-3.5 yaw=90.0 pitch=35.247574"
    parsed_camera_pose=read_client_camera_pose(camera_pose_sample)
    if (parsed_camera_pose is None or parsed_camera_pose[:3]!=(9.5,67.62,-3.5)
            or parsed_camera_pose[3:]!=(90.0,35.247574)):
        raise RuntimeError("BLACK FREQUENCY rendered camera pose parser regression")
    renderer_source_path=ROOT/"addon"/"src"/"main"/"java"/"com"/"blackfrequency"/"rift"/"client"/"RiftSkyRenderer.java"
    renderer_source=renderer_source_path.read_text(encoding="utf-8")
    camera_log_offset=renderer_source.index("logCiCameraRotation(event.getCamera());")
    invisible_guard_offset=renderer_source.index("if(!shouldRender(strength)) return;")
    if camera_log_offset>invisible_guard_offset:
        raise RuntimeError("BLACK FREQUENCY camera pose telemetry stopped before the invisible-rift guard")
    post_restore_aim_sample="\n".join((
        "BLACK_FREQUENCY_TEST BEACON_AIM_SET yaw=-7.6293945E-6 pitch=54.20249",
        "BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_READY_FOR_REAL_PLAYER x=6 y=65 z=-4",
        camera_pose_sample))
    recomputed_post_restore_aim=read_post_restore_beacon_aim(post_restore_aim_sample)
    if (recomputed_post_restore_aim is None
            or abs(wrapped_angle_delta(recomputed_post_restore_aim[0],90.0))>1.0E-6
            or abs(recomputed_post_restore_aim[1]-35.24757505834628)>1.0E-6):
        raise RuntimeError("BLACK FREQUENCY post-restoration live beacon aim regression")
    legacy_post_restore_sample=post_restore_aim_sample.replace("_FOR_REAL_PLAYER","")
    legacy_post_restore_aim=read_post_restore_beacon_aim(legacy_post_restore_sample)
    if (legacy_post_restore_aim is None
            or abs(wrapped_angle_delta(legacy_post_restore_aim[0],recomputed_post_restore_aim[0]))>1.0E-9
            or abs(legacy_post_restore_aim[1]-recomputed_post_restore_aim[1])>1.0E-9):
        raise RuntimeError("BLACK FREQUENCY legacy post-restoration beacon marker regression")
    if read_post_restore_beacon_aim("BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_READY_FOR_REAL_PLAYER x=6 y=65 z=-4") is not None:
        raise RuntimeError("BLACK FREQUENCY post-restoration aim accepted a missing camera pose")
    try:
        beacon_aim_from_camera_pose((float("nan"),67.0,-3.5,0.0,0.0),(6,65,-4))
    except ValueError:
        pass
    else:
        raise RuntimeError("BLACK FREQUENCY post-restoration aim accepted a non-finite camera pose")
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
        camera_delta_for_pitch(90.0,44.65)<0,
        camera_delta_for_pitch(44.65,90.0)>0,
    )
    if not all(camera_direction_cases):
        raise RuntimeError("BLACK FREQUENCY camera correction direction regression")
    factor=MOUSE_SENSITIVITY*0.6+0.2
    degrees_per_pixel=factor*factor*factor*8.0*0.15
    overshoot_correction=camera_delta_for_pitch(90.0,44.65)
    corrected_pitch=90.0+overshoot_correction*degrees_per_pixel
    if overshoot_correction>=0 or abs(corrected_pitch-44.65)>degrees_per_pixel:
        raise RuntimeError("BLACK FREQUENCY camera pitch overshoot correction regression")
    print("WORLD_CAPTURE: closed-loop camera pitch overshoot correction verified",flush=True)
    exact_rotation_sample=camera_rotation_command(89.999985,35.247574)
    if exact_rotation_sample!="/tp @s ~ ~ ~ 89.999985 35.247574":
        raise RuntimeError("BLACK FREQUENCY disposable camera rotation command regression")
    try:
        camera_rotation_command(0.0,float("nan"))
    except ValueError:
        pass
    else:
        raise RuntimeError("BLACK FREQUENCY non-finite disposable camera rotation was accepted")
    print("WORLD_CAPTURE: exact disposable-world camera rotation command and finite guards verified",flush=True)
    print("WORLD_CAPTURE: client camera telemetry, live post-restoration beacon aim and correction directions verified",flush=True)
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
        # The more saturated blue-violet mesh edge remains distinct from cached Dust colors.
        return (105 <= r <= 155 and 30 <= g <= 100 and b >= 165
                and b-r >= 45 and b > r*1.25)
    # Samples include deep-violet Dust and both the previous and current mesh edge; reject legacy magenta.
    if (is_rift_violet_pixel(142,127,155)
            or not is_rift_violet_pixel(134,44,150)
            or not is_rift_violet_pixel(117,46,209)
            or not is_ground_mesh_pixel(128,36,230)
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
            or not is_ground_mesh_pixel(128,36,230)
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
    zenith_width_chain=(
        "float zenithSpokePulse=.5+.5*sin(zenithAngle*5.+t*.31+.6*sin(zenithAngle*2.-t*.11));",
        "float zenithWidth=mix(.005,.014,clamp(.35*crownSurge+.65*zenithSpokePulse,0.,1.));",
    )
    if any(token not in shader_source for token in zenith_width_chain):
        raise RuntimeError("BLACK FREQUENCY zenith spoke width shader profile regression")
    print("WORLD_CAPTURE: asymmetric zenith spoke width profile source verified",flush=True)
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

    def can_start_sky_capture_after_ground(terrain_shot,sky_capture_attempted,logs):
        # The sky sweep blocks the capture loop for ~40s and can reload resources.
        # Never let it pre-empt the player-visible ground fissure frame.
        return (terrain_shot and not sky_capture_attempted
                and "BLACK_FREQUENCY_RENDER_ACTIVE" in logs
                and "BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED" in logs
                and "BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER" in logs
                and "BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION" not in logs)

    _sky_capture_gate_sample = "\n".join((
        "BLACK_FREQUENCY_RENDER_ACTIVE",
        "BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED",
        "BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER",
    ))
    if (can_start_sky_capture_after_ground(False,False,_sky_capture_gate_sample)
            or can_start_sky_capture_after_ground(True,True,_sky_capture_gate_sample)
            or not can_start_sky_capture_after_ground(True,False,_sky_capture_gate_sample)
            or can_start_sky_capture_after_ground(True,False,
                _sky_capture_gate_sample+"\\nBLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION")):
        raise RuntimeError("BLACK FREQUENCY sky capture must wait for the ground fissure frame")
    print("WORLD_CAPTURE: blocking sky sweep is gated behind the player-visible ground fissure frame",flush=True)

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

    def is_legacy_magenta_pixel(r,g,b):
        # The previous hot-magenta palette has near-equal red/blue channels.
        # Blue-heavy blends from the current deep-violet cage are not legacy magenta.
        return r>=180 and b>=180 and g<=90 and abs(r-b)<=70

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
                if is_legacy_magenta_pixel(r,g,b):
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

    def measure_completion_overlay(frame):
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
            return {"left":0,"right":0,"width":0,"pixels":0,
                    "fraction":0.0,"centerOffset":float("inf")}
        left,right=active[0],active[-1]
        overlay_width=right-left+1
        return {"left":left,"right":right,"width":overlay_width,
                "pixels":sum(column_counts),
                "fraction":overlay_width/width,
                "centerOffset":abs((left+right)/2-width/2)}

    def completion_overlay_visible(metrics,frame_width):
        # A dense centered violet overlay can obscure the physical beacon.
        # The pixel-density floor keeps thin ground-fissure ribbons out of this classification.
        return (metrics["width"]>=200
                and metrics["centerOffset"]<=frame_width*.08
                and metrics["pixels"]>=5000)

    from PIL import Image,ImageDraw

    def measure_beacon_menu(frame):
        width,height=frame.size
        left,right=int(width*.22),int(width*.78)
        top,bottom=int(height*.03),int(height*.96)
        pixels=frame.convert("RGB").crop((left,top,right,bottom)).getdata()
        hits=0
        for red,green,blue in pixels:
            if (175<=red<=220 and 175<=green<=220 and 175<=blue<=220
                    and max(red,green,blue)-min(red,green,blue)<=18):
                hits+=1
        return hits/((right-left)*(bottom-top))

    def beacon_menu_visible(fraction):
        return math.isfinite(fraction) and fraction>=.10

    _beacon_menu_fixture=Image.new("RGB",(1280,720),(35,52,30))
    ImageDraw.Draw(_beacon_menu_fixture).rectangle((300,32,980,684),fill=(198,198,198))
    _beacon_world_fixture=Image.new("RGB",(1280,720),(35,52,30))
    if (not beacon_menu_visible(measure_beacon_menu(_beacon_menu_fixture))
            or beacon_menu_visible(measure_beacon_menu(_beacon_world_fixture))):
        raise RuntimeError("BLACK FREQUENCY vanilla beacon menu classifier regression")
    print("WORLD_CAPTURE: vanilla beacon menu classifier verified",flush=True)

    _compact_overlay_fixture=Image.new("RGB",(1280,720),(0,0,0))
    ImageDraw.Draw(_compact_overlay_fixture).rectangle((300,240,980,320),fill=(86,20,197))
    _wide_overlay_fixture=Image.new("RGB",(1280,720),(0,0,0))
    ImageDraw.Draw(_wide_overlay_fixture).rectangle((50,240,1230,320),fill=(86,20,197))
    _blank_overlay_fixture=Image.new("RGB",(1280,720),(0,0,0))
    _sparse_fissure_fixture=Image.new("RGB",(1280,720),(0,0,0))
    ImageDraw.Draw(_sparse_fissure_fixture).line(
        (280,280,1000,280),fill=(86,20,197),width=6)
    _compact_overlay_metrics=measure_completion_overlay(_compact_overlay_fixture)
    _wide_overlay_metrics=measure_completion_overlay(_wide_overlay_fixture)
    _blank_overlay_metrics=measure_completion_overlay(_blank_overlay_fixture)
    _sparse_fissure_metrics=measure_completion_overlay(_sparse_fissure_fixture)
    if (not completion_overlay_visible(_compact_overlay_metrics,1280)
            or not completion_overlay_visible(_wide_overlay_metrics,1280)
            or completion_overlay_visible(_blank_overlay_metrics,1280)
            or completion_overlay_visible(_sparse_fissure_metrics,1280)
            or _compact_overlay_metrics["pixels"]<5000
            or _sparse_fissure_metrics["pixels"]>=5000):
        raise RuntimeError("BLACK FREQUENCY completion-overlay classifier regression")
    print("WORLD_CAPTURE: completion overlay classifier verified",flush=True)

    if (not is_legacy_magenta_pixel(201,66,214)
            or not is_legacy_magenta_pixel(255,0,255)
            or is_legacy_magenta_pixel(151,63,219)
            or is_legacy_magenta_pixel(153,65,220)):
        raise RuntimeError("BLACK FREQUENCY legacy-magenta classifier rejects current violet cage blends")
    print("WORLD_CAPTURE: legacy magenta classifier separates violet cage blends from the retired palette",flush=True)

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

    def verify_sky_pixels(name, region, minimum, max_green=None, min_green=None):
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
                green_rows=[]
                green_longest_runs=[]
                for y in range(crop.height):
                    row_purple=0
                    row_longest=0
                    current_run=0
                    row_green=0
                    green_row_longest=0
                    green_current_run=0
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
                            row_green+=1
                            green_current_run+=1
                            green_row_longest=max(green_row_longest,green_current_run)
                        else:
                            green_current_run=0
                        # Ground cracks can contain the same violet pixels as the sky shader.
                        # Require a real dark sky field behind the tear, not just colored geometry.
                        if max(r,g,b)<50 and b>=g:
                            dark_sky+=1
                    purple_rows.append(row_purple)
                    purple_longest_runs.append(row_longest)
                    green_rows.append(row_green)
                    green_longest_runs.append(green_row_longest)
                coverage=purple/(crop.width*crop.height)
                green_coverage=green/(crop.width*crop.height)
                dark_sky_coverage=dark_sky/(crop.width*crop.height)
                if min_green is None:
                    score=coverage-.35*green_coverage
                    row_threshold=max(6,int(crop.width*.01))
                    peak_threshold=max(12,int(crop.width*.025))
                    min_contiguous_run=max(64,int(crop.width*.22))
                    line_rows=sum(1 for count in purple_rows if count>=row_threshold)
                    peak_row=max(purple_rows,default=0)
                    continuous_rows=sum(1 for run in purple_longest_runs if run>=min_contiguous_run)
                    max_contiguous_run=max(purple_longest_runs,default=0)
                    line_visible=(line_rows>=8 and peak_row>=peak_threshold
                                  and has_continuous_sky_band(purple_longest_runs,min_contiguous_run))
                else:
                    # Green preview frames must contain an actual rendered green fracture,
                    # not merely the test-only uniform value or a green HUD element.
                    score=green_coverage-.35*coverage
                    row_threshold=max(2,int(crop.width*.004))
                    peak_threshold=max(3,int(crop.width*.012))
                    min_contiguous_run=max(16,int(crop.width*.06))
                    line_rows=sum(1 for count in green_rows if count>=row_threshold)
                    peak_row=max(green_rows,default=0)
                    continuous_rows=sum(1 for run in green_longest_runs if run>=min_contiguous_run)
                    max_contiguous_run=max(green_longest_runs,default=0)
                    line_visible=(line_rows>=1 and peak_row>=peak_threshold
                                  and continuous_rows>=1 and max_contiguous_run>=min_contiguous_run)
                candidate=(score,coverage,green_coverage,dark_sky_coverage,line_rows,peak_row,
                           continuous_rows,max_contiguous_run,left,top,peak_threshold,min_contiguous_run)
                valid=(coverage>=minimum and dark_sky_coverage>=.08 and line_visible
                       and (max_green is None or green_coverage<=max_green)
                       and (min_green is None or green_coverage>=min_green))
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
        if min_green is None:
            line_visible=(line_rows>=8 and peak_row>=peak_threshold
                          and continuous_rows>=6 and max_contiguous_run>=min_contiguous_run)
        else:
            line_visible=(line_rows>=1 and peak_row>=peak_threshold
                          and continuous_rows>=1 and max_contiguous_run>=min_contiguous_run)
        visible=best_valid is not None
        selection="valid-window" if visible else "best-diagnostic-window"
        print(f"WORLD_CAPTURE: SKY_PIXEL_COVERAGE {name}={coverage:.4f} green={green_coverage:.4f} darkSky={dark_sky_coverage:.4f} lineRows={line_rows} peakRow={peak_row} continuousRows={continuous_rows} maxRun={max_contiguous_run} minRun={min_contiguous_run} window={left},{top} required={minimum:.4f} minDarkSky=0.0800 maxGreen={max_green} minGreen={min_green} visible={visible} selection={selection}",flush=True)
        return visible
    beacon_attempts=0
    beacon_ready_at=None
    last_beacon_attempt=0
    beacon_aim_verified=False
    vertical_cull_probe_attempted=False
    vertical_cull_probe_verified=False
    def try_beacon_click(logs):
        nonlocal beacon_attempts,beacon_ready_at,last_beacon_attempt,beacon_aim_verified
        nonlocal deferred_anchor_creative,deferred_anchor_survival_restored
        if os.environ.get("BF_TEST_PLAYER_BEACON")!="1":
            return
        # Do not begin mouse automation until the server confirms that the
        # disposable player is protected from random gravity deaths.
        if ("BLACK_FREQUENCY_TEST PLAYER_RIFT_IMPULSES_SUPPRESSED_FOR_BEACON_TEST" not in logs
                or "BLACK_FREQUENCY_TEST PLAYER_VANILLA_FALL_SUPPRESSED_FOR_BEACON_TEST" not in logs
                or "BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT" not in logs
                or "BLACK_FREQUENCY_TEST PLAYER_VANILLA_FALL_RESTORED_AFTER_SAFE_PAD" not in logs):
            return
        if (deferred_chunk_probe and deferred_anchor_creative
                and not deferred_anchor_survival_restored):
            if not send_isolated_chat_command(
                    "/gamemode survival @s",
                    r"Set own game mode to Survival Mode",
                    "restore Survival on the verified safe beacon pad"):
                return
            deferred_anchor_survival_restored=True
            print("WORLD_CAPTURE: restored Survival after the remote-anchor safe-pad teleport",
                  "physicalInteraction=true",flush=True)
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
        # Run the live cull probe before F3+T. Resource reload can pause the
        # integrated server; stale pre-probe log markers must never satisfy this test.
        if os.environ.get("BF_ISOLATED_TEST")=="1" and not vertical_cull_probe_verified:
            if not run_vertical_ground_cull_probe():
                return
        if not sky_capture_attempted:
            capture_sky_before_beacon()
        if not sky_capture_attempted:
            return
        fresh_logs=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else logs
        isolated_camera_command=os.environ.get("BF_ISOLATED_TEST")=="1"
        if not aim_camera_at_beacon(fresh_logs,allow_disposable_teleport=isolated_camera_command):
            return
        if isolated_camera_command:
            print("WORLD_CAPTURE: isolated beacon aim set from server telemetry; following right-click remains physical",flush=True)
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
                    and "BLACK_FREQUENCY_TEST BEACON_ACTIVATION_SIGHTLINE_VERIFIED hit=beacon loadedChunksOnly=true" in interaction_logs
                    and "BLACK_FREQUENCY_TEST BEACON_HIT_POINT_ACCEPTED_VERIFIED" in interaction_logs
                    and "BLACK_FREQUENCY_TEST BEACON_START_ACTIONBAR_ONLY" in interaction_logs
                    and "BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED" in interaction_logs):
                interaction_confirmed=True
                time.sleep(0.35)
                break
            time.sleep(0.1)
        if interaction_confirmed:
            if vertical_cull_probe_verified:
                print("WORLD_CAPTURE_INPUT: post-cull physical beacon right-click confirmed by server",flush=True)
            print("WORLD_CAPTURE: physical beacon activation confirmed before screenshot",flush=True)
            # Exercise the same physical beacon again while recovery is active.
            # The server must suppress the vanilla BeaconScreen for this exact
            # recovery beacon, while leaving unrelated beacons untouched.
            repeat_marker="BLACK_FREQUENCY_TEST BEACON_ACTIVE_MENU_INTERACTION_SUPPRESSED"
            repeat_logs=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            repeat_count_before=repeat_logs.count(repeat_marker)
            focus_game()
            repeat_down=subprocess.run(["xdotool","mousedown","3"],check=False)
            time.sleep(0.12)
            repeat_up=subprocess.run(["xdotool","mouseup","3"],check=False)
            if repeat_down.returncode!=0 or repeat_up.returncode!=0:
                raise RuntimeError("BLACK FREQUENCY could not send the repeated physical beacon right-click")
            print("WORLD_CAPTURE_INPUT: repeated physical right-click during active restoration",flush=True)
            repeat_deadline=time.monotonic()+2.0
            repeat_confirmed=False
            while time.monotonic()<repeat_deadline and p.poll() is None:
                repeat_logs=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                if repeat_logs.count(repeat_marker)>repeat_count_before:
                    repeat_confirmed=True
                    break
                if "BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED" in repeat_logs:
                    break
                time.sleep(0.05)
            if not repeat_confirmed:
                raise RuntimeError("BLACK FREQUENCY repeated active-beacon click was not rejected by the server")
            time.sleep(0.12)
            snap("beacon-after-right-click.png")
            with Image.open(shots/"beacon-after-right-click.png") as frame:
                menu_fraction=measure_beacon_menu(frame)
            if beacon_menu_visible(menu_fraction):
                raise RuntimeError(
                    "BLACK FREQUENCY vanilla BeaconScreen opened during active recovery "
                    f"(neutral-gray fraction={menu_fraction:.4f})")
            print("WORLD_CAPTURE: repeated active-beacon click suppressed the vanilla menu",
                  {"neutralGrayFraction":round(menu_fraction,4)},flush=True)
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
        if not aim_camera_at_beacon(log_text,allow_disposable_teleport=True):
            return False
        overlay_deadline=time.monotonic()+3.0
        overlay_absent=False
        overlay_attempts=0
        last_overlay_metrics=None
        while time.monotonic()<overlay_deadline and p.poll() is None:
            snap("beacon-before-left-click.png")
            try:
                with Image.open(shots/"beacon-before-left-click.png") as frame:
                    overlay_metrics=measure_completion_overlay(frame.convert("RGB"))
                    frame_width=frame.width
            except (OSError,ValueError) as error:
                print("WORLD_CAPTURE: pre-mining frame not readable yet",str(error),flush=True)
                time.sleep(.1)
                continue
            overlay_attempts+=1
            last_overlay_metrics=overlay_metrics
            if not completion_overlay_visible(overlay_metrics,frame_width):
                overlay_absent=True
                break
            if overlay_attempts==1 or overlay_attempts%10==0:
                print("WORLD_CAPTURE: completion overlay still visible before beacon break",
                      overlay_metrics,flush=True)
            time.sleep(.1)
        if not overlay_absent:
            print("WORLD_CAPTURE_FAIL: completion overlay remained visible before physical beacon break",
                  {"attempts":overlay_attempts,"lastMetrics":last_overlay_metrics},flush=True)
            return False
        print("WORLD_CAPTURE: completion overlay absent before physical beacon break",
              {"attempts":overlay_attempts,"metrics":last_overlay_metrics},flush=True)
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
                    proof=re.search(
                        r"BLACK_FREQUENCY_TEST REAL_PLAYER_BEACON_BREAK_NO_DROP_VERIFIED "
                        r"survival=true withinReach=true distanceSquared=([0-9.]+) capturedAtBreak=true",delta)
                    if not proof or float(proof.group(1))>4.5*4.5:
                        print("WORLD_CAPTURE_FAIL: beacon mining was not confirmed in survival within 4.5 blocks",flush=True)
                        return False
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
        click(640,430)  # Allow Cheats: Off -> On, only in this disposable CI world
        time.sleep(0.5)
        snap("02d-world-cheats-enabled.png")
        print("WORLD_CAPTURE: disposable world cheats enabled for the vertical cull probe",flush=True)
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
                time.sleep(.1)
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
            if deferred_chunk_probe:
                # Server login precedes the first playable client frame. Do not send
                # chat commands while the integrated client is still on "Loading terrain".
                client_world_ready_deadline=time.monotonic()+60.0
                client_world_ready=False
                while time.monotonic()<client_world_ready_deadline and p.poll() is None:
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                    if "BLACK_FREQUENCY_TEST CLIENT_INPUT_READY screen=null player=true" in current:
                        client_world_ready=True
                        break
                    time.sleep(.1)
                if not client_world_ready:
                    print("WORLD_CAPTURE_FAIL: integrated server accepted the player but the client input screen never closed",flush=True)
                    snap("deferred-anchor-world-not-rendered.png")
                    return False
                print("WORLD_CAPTURE: client input surface ready after the world-loading screen closed",
                      "screen=null","player=true",flush=True)
                snap("deferred-anchor-world-ready.png")
                initial_spawn_match=INITIAL_PLAYER_SPAWN_RE.search(current)
                if not initial_spawn_match:
                    print("WORLD_CAPTURE_FAIL: initial player spawn coordinates are missing for deferred ticket separation",flush=True)
                    return False
                initial_x,_,initial_z=map(float,initial_spawn_match.groups())
                deferred_initial_spawn_chunk=(math.floor(initial_x)>>4,math.floor(initial_z)>>4)
                anchor_chunk=(DEFERRED_CLIENT_ANCHOR_X>>4,DEFERRED_CLIENT_ANCHOR_Z>>4)
                if max(abs(anchor_chunk[0]-deferred_initial_spawn_chunk[0]),
                       abs(anchor_chunk[1]-deferred_initial_spawn_chunk[1]))<=DEFERRED_INITIAL_SPAWN_TICKET_RADIUS:
                    print("WORLD_CAPTURE_FAIL: remote fracture anchor overlaps the original spawn-ticket halo",
                          {"initialSpawnChunk":deferred_initial_spawn_chunk,"anchorChunk":anchor_chunk},flush=True)
                    return False
                if not send_isolated_chat_command(
                        "/gamemode creative @s",
                        r"Set own game mode to Creative Mode",
                        "enter Creative before moving the deferred probe anchor"):
                    return False
                deferred_anchor_creative=True
                if not send_isolated_chat_command(
                        f"/tp @s {DEFERRED_CLIENT_ANCHOR_X} 120 {DEFERRED_CLIENT_ANCHOR_Z}",
                        r"Teleported .* to ",
                        "move the player to the remote deferred-probe anchor before the finale"):
                    return False
                print("WORLD_CAPTURE: moved deferred probe player away from the original spawn-ticket halo",
                      {"initialSpawnChunk":deferred_initial_spawn_chunk,"anchorChunk":anchor_chunk},
                      "loadedOnly=true",flush=True)
            # Capture gameplay immediately: restoration can begin before the
            # initial frame series ends, so waiting until afterward misses it.
            restoration_shot=False
            terrain_shot=False
            terrain_capture_attempted=False
            terrain_capture_failed=False
            canopy_fixture_seeded=False
            canopy_fixture_verified=False
            canopy_fixture_position=None
            canopy_fixture_seeded_at=None
            sky_shot=False
            sky_capture_attempted=False
            shader_reload_attempted=False
            shader_reload_verified=False
            def capture_terrain_fracture(logs):
                nonlocal terrain_shot,terrain_capture_attempted,terrain_capture_failed
                nonlocal canopy_fixture_seeded,canopy_fixture_verified,canopy_fixture_position,canopy_fixture_seeded_at
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
                    r"BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET x=([-+0-9.eE]+) y=([-+0-9.eE]+) z=([-+0-9.eE]+) distanceSquared=([-+0-9.eE]+) horizontalDistanceSquared=([-+0-9.eE]+) viewScore=([-+0-9.eE]+) visibleNeighbours=(\d+) selection=balanced-view-span",
                    logs)
                safe_pad=re.search(
                    r"BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT x=(-?\d+) y=(-?\d+) z=(-?\d+) support=solid fallDistance=0\.0",
                    logs)
                if os.environ.get("BF_ISOLATED_TEST")=="1":
                    # The cull-complete marker is printed by this harness, not latest.log.
                    # Gate on the verified probe state so the fixture is seeded after camera return.
                    if (not canopy_fixture_seeded and marker and safe_pad
                            and vertical_cull_probe_verified):
                        anchor_match=FINALE_ANCHOR_RE.search(logs)
                        first_edit=FIRST_BLOCK_REMOVED_RE.search(logs)
                        if anchor_match and first_edit:
                            anchor_x,anchor_z=map(int,anchor_match.groups())
                            fixture_sample_match=find_canopy_fixture_sample_for_anchor(
                                logs,anchor_x,anchor_z)
                            stale_fixture_samples=[
                                match for match in CANOPY_FIXTURE_SAMPLE_RE.finditer(logs)
                                if tuple(map(int,match.groups()[7:9]))!=(anchor_x,anchor_z)
                            ]
                            if stale_fixture_samples:
                                previous_anchor=tuple(map(int,stale_fixture_samples[-1].groups()[7:9]))
                                expected_reset=(*previous_anchor,anchor_x,anchor_z)
                                reset_confirmed=any(
                                    tuple(map(int,match.groups()))==expected_reset
                                    for match in CANOPY_FIXTURE_ANCHOR_RESET_RE.finditer(logs)
                                )
                                if not reset_confirmed or fixture_sample_match is None:
                                    terrain_capture_failed=True
                                    print(
                                        "WORLD_CAPTURE_FAIL: active mesh anchor changed without a fresh canopy fixture sample",
                                        {"previousAnchor":previous_anchor,"activeAnchor":(anchor_x,anchor_z),
                                         "freshSample":fixture_sample_match is not None,
                                         "resetConfirmed":reset_confirmed},flush=True)
                                    return
                        else:
                            fixture_sample_match=None
                        if anchor_match and first_edit and fixture_sample_match:
                            anchor_x,anchor_z=map(int,anchor_match.groups())
                            sample_x,sample_z=map(int,fixture_sample_match.groups()[:2])
                            sample_ground_y=float(fixture_sample_match.group(3))
                            fixture_x,fixture_z=map(int,fixture_sample_match.groups()[3:5])
                            if not math.isfinite(sample_ground_y):
                                terrain_capture_failed=True
                                print("WORLD_CAPTURE_FAIL: renderer published a non-finite canopy fixture height",
                                      {"sample":(sample_x,sample_z),"groundY":sample_ground_y},flush=True)
                                return
                            fixture_y=math.floor(sample_ground_y)
                            plant_positions=stacked_plant_fixture_positions(fixture_y)
                            dx=fixture_x-anchor_x
                            dz=fixture_z-anchor_z
                            sample_dx=sample_x-anchor_x
                            sample_dz=sample_z-anchor_z
                            if (plant_positions is None
                                    or sample_dx*sample_dx+sample_dz*sample_dz>48*48
                                    or dx*dx+dz*dz>48*48):
                                terrain_capture_failed=True
                                print("WORLD_CAPTURE_FAIL: sampled stacked-plant fixture escaped build or fracture bounds",
                                      {"anchor":(anchor_x,anchor_z),"sample":(sample_x,sample_z),
                                       "fixture":(fixture_x,fixture_y,fixture_z),"groundY":sample_ground_y},flush=True)
                                return
                            support_y,plant_upper_y,headroom_y=plant_positions
                            fixture_mutations=(
                                ("support",support_y,"prepare sampled adjacent-plant support"),
                                ("lower",fixture_y,"place sampled lower tall-grass half"),
                                ("upper",plant_upper_y,"place sampled upper tall-grass half"),
                                ("headroom",headroom_y,"clear sampled stacked-plant headroom"),
                            )
                            for slot,block_y,description in fixture_mutations:
                                mutation_command,verification_command,marker_text=stacked_plant_fixture_commands(
                                    fixture_x,block_y,fixture_z,slot)
                                if not send_isolated_chat_command(
                                        mutation_command,None,description):
                                    terrain_capture_failed=True
                                    return
                                if not send_isolated_chat_command(
                                        verification_command,re.escape(marker_text),
                                        "verify "+description,timeout=6.0):
                                    terrain_capture_failed=True
                                    return
                            fixture_check=(
                                f"/execute if block {fixture_x} {support_y} {fixture_z} minecraft:grass_block "
                                f"if block {fixture_x} {fixture_y} {fixture_z} minecraft:tall_grass[half=lower] "
                                f"if block {fixture_x} {plant_upper_y} {fixture_z} minecraft:tall_grass[half=upper] "
                                f"if block {fixture_x} {headroom_y} {fixture_z} minecraft:air "
                                f"run say BF_CANOPY_STACKED_FIXTURE_VERIFIED x={fixture_x} y={fixture_y} z={fixture_z}"
                            )
                            if not send_isolated_chat_command(
                                    fixture_check,r"BF_CANOPY_STACKED_FIXTURE_VERIFIED",
                                    "verify adjacent stacked-plant fixture",timeout=6.0):
                                terrain_capture_failed=True
                                return
                            canopy_fixture_seeded=True
                            canopy_fixture_position=(fixture_x,fixture_y,fixture_z)
                            canopy_fixture_seeded_at=time.monotonic()
                            print("WORLD_CAPTURE: placed a verified two-block tall-grass fixture after vertical-cull probe",
                                  {"anchor":(anchor_x,anchor_z),"sample":(sample_x,sample_ground_y,sample_z),
                                   "support":(fixture_x,support_y,fixture_z),
                                   "fixture":canopy_fixture_position,"arm":int(fixture_sample_match.group(6)),
                                   "segment":int(fixture_sample_match.group(7)),
                                   "radiusSquared":dx*dx+dz*dz,"loadedOnly":True,
                                   "afterVerticalCull":True},flush=True)
                            time.sleep(.25)
                            return
                    if canopy_fixture_seeded and not canopy_fixture_verified:
                        fixture_match=re.search(
                            r"BF_CANOPY_STACKED_FIXTURE_VERIFIED x=(-?\d+) y=(-?\d+) z=(-?\d+)",
                            logs)
                        lifted_pattern=(
                            r"BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_LIFTED_PARTICLE_EMITTED "
                            r"x=(-?\d+) y=([-+0-9.eE]+) z=(-?\d+) skippedPlants=(\d+) "
                            r"source=adjacent loadedOnly=true radius=48 clientOnly=true"
                        )
                        lifted_match=next((match for match in re.finditer(lifted_pattern,logs)
                            if canopy_fixture_position
                            and int(match.group(1))==canopy_fixture_position[0]
                            and int(match.group(3))==canopy_fixture_position[2]),None)
                        if fixture_match and lifted_match and canopy_fixture_position:
                            fixture_from_log=tuple(map(int,fixture_match.groups()))
                            lifted_y=float(lifted_match.group(2))
                            skipped=int(lifted_match.group(4))
                            # The lower half is the sampled surface; only the upper half is skipped.
                            # The mote occupies the first clear cell above it, with the 0.40 particle offset.
                            expected_particle_y=canopy_fixture_position[1]+STACKED_PLANT_FIXTURE_PARTICLE_OFFSET
                            if (fixture_from_log!=canopy_fixture_position
                                    or skipped!=STACKED_PLANT_FIXTURE_SKIPPED_PLANTS
                                    or not math.isclose(lifted_y,expected_particle_y,
                                                        rel_tol=0.0,abs_tol=1.0E-6)):
                                terrain_capture_failed=True
                                print("WORLD_CAPTURE_FAIL: adjacent stacked-plant mote did not match the verified fixture",
                                      {"fixture":canopy_fixture_position,"lifted":lifted_match.groups(),
                                       "expectedParticleY":expected_particle_y},flush=True)
                                return
                            canopy_fixture_verified=True
                            print("WORLD_CAPTURE: adjacent stacked-plant particle emitted above the upper half",
                                  {"fixture":canopy_fixture_position,"particleY":lifted_y,
                                   "skippedPlants":skipped,"source":"adjacent"},flush=True)
                        elif canopy_fixture_seeded_at is not None and time.monotonic()-canopy_fixture_seeded_at>20.0:
                            terrain_capture_failed=True
                            print("WORLD_CAPTURE_FAIL: no adjacent stacked-plant particle was emitted within 20 seconds after vertical-cull probe",
                                  {"fixture":canopy_fixture_position},flush=True)
                            return
                        else:
                            return
                if os.environ.get("BF_ISOLATED_TEST")=="1" and not canopy_fixture_verified:
                    return
                pad_profile=re.search(
                    r"BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_PAD_PROFILE_VERIFIED floorOffset=0 playerOffset=1 lateralRadius=0 axis=([XZ]) firstSolidRow=3 gapRow=2 lastSolidRow=3 standoff=3\.0 distanceSquared=([-+0-9.eE]+) padBlocks=(\d+) withinReach=true beamColumnUntouched=true withinRiftRadius=true perpendicular=true",
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
                        or pad_block_count!=1):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: perpendicular beacon pad is outside interaction reach or incomplete",
                          pad_axis,pad_distance_squared,pad_block_count,flush=True)
                    return
                player_x,player_y,player_z=map(int,safe_pad.groups())
                target_horizontal_squared=float(target.group(5))
                target_view_score=float(target.group(6))
                target_visible_neighbours=int(target.group(7))
                if not 1<=target_visible_neighbours<=2:
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: fissure target has no contiguous visible adjacent sample",
                          target_visible_neighbours,flush=True)
                    return
                if not is_fissure_target_horizontal_distance(target_horizontal_squared):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: fissure camera target is outside the bounded standoff",
                          target_horizontal_squared,
                          "allowed",FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE,
                          "to",FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE,flush=True)
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
                key("F1")
                # The one-shot mouse delta can overshoot Minecraft's +/-90 degree
                # pitch clamp. Re-read rendered telemetry and close the remaining
                # error instead of assuming X11 input sums equal camera rotation.
                subprocess.run(["xdotool","mousemove","--sync","640","360"],check=False)
                time.sleep(.2)
                restored=True
                try:
                    restore_camera_orientation(target_yaw,target_pitch)
                    actual_orientation=wait_for_camera_orientation(
                        target_yaw,target_pitch,tolerance=5.0,timeout=1.0)
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
                    restored_orientation=restore_camera_orientation(current_yaw,current_pitch)
                    if (restored_orientation is None
                            or abs(wrapped_angle_delta(restored_orientation[0],current_yaw))>3.0
                            or abs(restored_orientation[1]-current_pitch)>3.0):
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
            def verify_shader_resource_reload():
                nonlocal shader_reload_attempted,shader_reload_verified
                if shader_reload_attempted:
                    return shader_reload_verified
                if os.environ.get("BF_ISOLATED_TEST")=="1" and not vertical_cull_probe_verified:
                    print("WORLD_CAPTURE_FAIL: resource reload cannot precede the live vertical cull probe",
                          flush=True)
                    return False
                shader_reload_attempted=True
                before=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                ready_marker="BLACK_FREQUENCY_SHADER_READY sky_rift"
                initial_ready_count=before.count(ready_marker)
                if initial_ready_count<1:
                    print("WORLD_CAPTURE_FAIL: initial sky shader readiness was not logged before resource reload",flush=True)
                    return False
                print("WORLD_CAPTURE_INPUT: triggering real Minecraft F3+T resource reload",flush=True)
                key("F3+t")
                deadline=time.monotonic()+60.0
                while time.monotonic()<deadline and p.poll() is None:
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                    invalidated="BLACK_FREQUENCY_TEST SKY_SHADER_RELOAD_INVALIDATION_VERIFIED oldUniformsCleared=true readyDisabled=true" in current
                    rebound="BLACK_FREQUENCY_TEST SKY_SHADER_RELOAD_REBOUND_VERIFIED uniforms=true ready=true" in current
                    redrawn="BLACK_FREQUENCY_TEST SKY_SHADER_RELOAD_POST_DRAW_VERIFIED stage=after_weather depthRestored=true" in current
                    ready_count=current.count(ready_marker)
                    if invalidated and rebound and redrawn and ready_count>=initial_ready_count+1:
                        shader_reload_verified=True
                        print("WORLD_CAPTURE: real F3+T reload invalidated old uniforms, rebound the new shader, and completed a depth-safe draw",
                              "readyRegistrations",ready_count,flush=True)
                        return True
                    time.sleep(0.2)
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                print("WORLD_CAPTURE_FAIL: real F3+T resource reload did not complete invalidation/rebind/post-draw",
                      {"initialReady":initial_ready_count,
                       "readyRegistrations":current.count(ready_marker),
                       "invalidated":"BLACK_FREQUENCY_TEST SKY_SHADER_RELOAD_INVALIDATION_VERIFIED" in current,
                       "rebound":"BLACK_FREQUENCY_TEST SKY_SHADER_RELOAD_REBOUND_VERIFIED" in current,
                       "redrawn":"BLACK_FREQUENCY_TEST SKY_SHADER_RELOAD_POST_DRAW_VERIFIED" in current},
                      flush=True)
                return False
            def capture_sky_before_beacon():
                nonlocal sky_shot,sky_capture_attempted
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                if not can_start_sky_capture_after_ground(terrain_shot,sky_capture_attempted,current):
                    return
                # F3+T can pause the integrated server during resource reload. The
                # live vertical-cull probe must finish before any sky capture/reload
                # path can pause the world and strand its teleport commands.
                if os.environ.get("BF_ISOLATED_TEST")=="1" and not vertical_cull_probe_verified:
                    if not run_vertical_ground_cull_probe():
                        return
                    print("WORLD_CAPTURE: live vertical cull probe completed before sky capture and resource reload",
                          flush=True)
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
                    green_phase_preview=os.environ.get("BF_TEST_GREEN_PHASE_PREVIEW")=="1"
                    if green_phase_preview:
                        preview_deadline=time.monotonic()+8.0
                        while time.monotonic()<preview_deadline:
                            preview_log=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                            if "BLACK_FREQUENCY_CI_GREEN_PHASE_PREVIEW_ACTIVE" in preview_log:
                                break
                            time.sleep(.1)
                        else:
                            raise RuntimeError("BLACK FREQUENCY green-phase preview did not reach an active rendered rift")
                    for index in range(10 if upper_camera_confirmed else 0):
                        if index:
                            move_camera_horizontal(170)
                            wait_for_camera_rotation(timeout=0.8)
                        time.sleep(0.45)
                        candidate_name=f"sky-rift-upper-candidate-{index:02d}.png"
                        snap(candidate_name)
                        last_upper_candidate=shots/candidate_name
                        candidate_visible=(
                            verify_sky_pixels(candidate_name,"upper",.02,None,.0015)
                            if green_phase_preview else verify_sky_pixels(candidate_name,"upper",.08,.25)
                        )
                        if candidate_visible:
                            if green_phase_preview:
                                shutil.copyfile(last_upper_candidate,shots/"sky-rift-green-phase.png")
                            shutil.copyfile(last_upper_candidate,shots/"sky-rift-upper.png")
                            upper_visible=True
                            print("WORLD_CAPTURE: adaptive green-phase upper-sky frame selected" if green_phase_preview
                                  else "WORLD_CAPTURE: adaptive upper-sky frame selected",
                                  candidate_name,flush=True)
                            if green_phase_preview:
                                print("WORLD_CAPTURE: GREEN_PHASE_SCREENSHOT_VERIFIED",flush=True)
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
                # The live cull probe has already run before this capture; keep the
                # shader reload here while the rift is still active so a post-reload draw
                # can be observed before the physical beacon starts recovery.
                if not verify_shader_resource_reload():
                    sky_shot=False
            def run_vertical_ground_cull_probe():
                nonlocal vertical_cull_probe_attempted,vertical_cull_probe_verified
                if vertical_cull_probe_verified:
                    return True
                if vertical_cull_probe_attempted:
                    raise RuntimeError("BLACK FREQUENCY vertical ground cull probe was already attempted but did not complete")
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                safe_pad=re.search(
                    r"BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT x=(-?\d+) y=(-?\d+) z=(-?\d+) support=solid fallDistance=0\.0",
                    current)
                first_edit=re.search(
                    r"BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED x=(-?\d+) y=(-?\d+) z=(-?\d+)",
                    current)
                if not safe_pad or not first_edit:
                    return False
                # Ignore the pre-event spawn mesh. Probe only after a mesh for the
                # actual first fractured column has been rendered.
                first_anchor_x=int(first_edit.group(1))
                first_anchor_z=int(first_edit.group(3))
                rendered_meshes=list(re.finditer(
                    r"BLACK_FREQUENCY_GROUND_MESH_RENDERED segments=(\d+) arms=(\d+) anchorX=(-?\d+) anchorZ=(-?\d+)",
                    current))
                matching_mesh=next((match for match in reversed(rendered_meshes)
                    if match.start()>first_edit.start()
                    and int(match.group(1))>=MIN_GROUND_MESH_SEGMENTS
                    and int(match.group(2))>=MIN_GROUND_MESH_ARMS
                    and int(match.group(3))==first_anchor_x
                    and int(match.group(4))==first_anchor_z),None)
                if matching_mesh is None:
                    return False
                x,y,z=(int(value) for value in safe_pad.groups())
                vertical_cull_probe_attempted=True
                # Only markers appended after this cursor belong to this probe.
                # Earlier cull/resume markers may come from camera setup and are not evidence.
                probe_log_offset=latest.stat().st_size if latest.exists() else 0

                def read_probe_log():
                    try:
                        with latest.open("rb") as stream:
                            current_size=stream.seek(0,os.SEEK_END)
                            if current_size<probe_log_offset:
                                return ""
                            stream.seek(probe_log_offset)
                            return stream.read().decode("utf-8",errors="replace")
                    except OSError:
                        return ""

                def send_chat_command(command,confirmation,description,timeout=8.0):
                    # Chat input can be delayed while the integrated server is busy.
                    # Do not send the next command until the server's own response is in latest.log.
                    for attempt in range(2):
                        log_offset=latest.stat().st_size if latest.exists() else 0
                        if not focus_game():
                            print("WORLD_CAPTURE: Minecraft window focus was not confirmed; retrying",
                                  description,"attempt",attempt+1,flush=True)
                            continue
                        print("WORLD_CAPTURE_INPUT: Minecraft window focus confirmed for",description,flush=True)
                        opened=key("t",focus=False)
                        # Let the chat screen consume the opening key before typing.
                        time.sleep(0.35)
                        typed=subprocess.run(
                            ["xdotool","type","--clearmodifiers","--delay","5",command],
                            check=False)
                        # Flush the final characters before Enter; the game may be busy rendering.
                        time.sleep(0.12)
                        submitted=key("Return",focus=False)
                        if opened.returncode!=0 or typed.returncode!=0 or submitted.returncode!=0:
                            raise RuntimeError("BLACK FREQUENCY vertical cull probe could not submit a Minecraft command")
                        deadline=time.monotonic()+timeout
                        while time.monotonic()<deadline and p.poll() is None:
                            try:
                                with latest.open("rb") as stream:
                                    size=stream.seek(0,os.SEEK_END)
                                    if size<log_offset:
                                        fresh=""
                                    else:
                                        stream.seek(log_offset)
                                        fresh=stream.read().decode("utf-8",errors="replace")
                            except OSError:
                                fresh=""
                            if re.search(confirmation,fresh):
                                print("WORLD_CAPTURE: server acknowledged command",
                                      description,"attempt",attempt+1,flush=True)
                                return True
                            time.sleep(0.1)
                        if attempt==0 and p.poll() is None:
                            print("WORLD_CAPTURE: command acknowledgement delayed; retrying",
                                  description,flush=True)
                    raise RuntimeError("BLACK FREQUENCY vertical cull probe did not receive server acknowledgement for "+description)

                print("WORLD_CAPTURE: starting real vertical camera cull probe from the verified safe pad",
                      (x,y,z),flush=True)
                restored=False
                try:
                    # PLAYER_BEACON_SAFE_TELEPORT is a destination, not proof that the
                    # player is already standing there. Establish the baseline first; otherwise
                    # y+80 can return the player to the original spawn height and never cross
                    # the live renderer's vertical-cull boundary.
                    send_chat_command(f"/tp @s {x} {y} {z}",
                        r"Teleported .* to ","move to verified safe pad before vertical cull")
                    send_chat_command("/gamemode spectator @s",
                        r"Set own game mode to Spectator Mode","spectator mode")
                    send_chat_command(f"/tp @s {x} {y+80} {z}",
                        r"Teleported .* to ","vertical camera teleport")
                    camera_sample=None
                    entered=None
                    probe_logs=""
                    enter_deadline=time.monotonic()+8.0
                    while time.monotonic()<enter_deadline and p.poll() is None:
                        probe_logs=read_probe_log()
                        camera_sample=re.search(
                            r"BLACK_FREQUENCY_TEST GROUND_MESH_CAMERA_VERTICAL_RANGE_SAMPLE cameraY=([-+0-9.eE]+) surfaceY=([-+0-9.eE]+) verticalDelta=([-+0-9.eE]+) horizontalDistanceSquared=(\d+) anchorChanged=(true|false) cachedSegments=(\d+) levelMatches=(true|false)",
                            probe_logs)
                        entered=re.search(
                            r"BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_ENTERED cameraY=([-+0-9.eE]+) surfaceY=([-+0-9.eE]+) verticalDelta=([-+0-9.eE]+) threshold=64 anchorX=(-?\d+) anchorZ=(-?\d+)",
                            probe_logs)
                        if camera_sample and entered:
                            break
                        time.sleep(0.1)
                    if not camera_sample:
                        raise RuntimeError("BLACK FREQUENCY vertical cull probe teleport did not produce live camera-height telemetry")
                    vertical_delta=float(camera_sample.group(3))
                    horizontal_distance_squared=int(camera_sample.group(4))
                    anchor_changed=camera_sample.group(5)=="true"
                    cached_segments=int(camera_sample.group(6))
                    level_matches=camera_sample.group(7)=="true"
                    if (not math.isfinite(vertical_delta) or vertical_delta<=64.0
                            or horizontal_distance_squared>64*64 or anchor_changed
                            or cached_segments<1 or not level_matches):
                        raise RuntimeError(
                            "BLACK FREQUENCY vertical cull probe reached an invalid camera state: "
                            f"verticalDelta={vertical_delta} horizontalDistanceSquared={horizontal_distance_squared} "
                            f"anchorChanged={anchor_changed} cachedSegments={cached_segments} levelMatches={level_matches}")
                    if not entered:
                        raise RuntimeError("BLACK FREQUENCY camera crossed the vertical range but the renderer did not enter the cull path")
                    if not math.isfinite(float(entered.group(3))) or float(entered.group(3))<=64.0:
                        raise RuntimeError("BLACK FREQUENCY vertical cull entry reported an invalid camera delta")
                    skip_milestone=None
                    milestone_deadline=time.monotonic()+3.0
                    while time.monotonic()<milestone_deadline and p.poll() is None:
                        probe_logs=read_probe_log()
                        skip_milestone=re.search(
                            r"BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_SKIP_MILESTONE frames=3 anchorX=(-?\d+) anchorZ=(-?\d+) cameraY=([-+0-9.eE]+)",
                            probe_logs)
                        if skip_milestone:
                            break
                        time.sleep(0.05)
                    if not skip_milestone:
                        raise RuntimeError("BLACK FREQUENCY vertical cull probe did not reach three live skipped render passes")
                    if (int(skip_milestone.group(1))!=int(entered.group(4))
                            or int(skip_milestone.group(2))!=int(entered.group(5))
                            or not math.isfinite(float(skip_milestone.group(3)))
                            or float(skip_milestone.group(3))<=64.0):
                        raise RuntimeError("BLACK FREQUENCY vertical cull skip milestone did not match the entered camera/anchor")
                    send_chat_command(f"/tp @s {x} {y} {z}",
                        r"Teleported .* to ","return to safe pad")
                    send_chat_command("/gamemode survival @s",
                        r"Set own game mode to Survival Mode","return to survival")
                    camera_after_return=read_client_camera_rotation()
                    if (camera_after_return is None
                            or not all(math.isfinite(value) for value in camera_after_return)):
                        raise RuntimeError("BLACK FREQUENCY post-cull rendered camera telemetry is missing or non-finite")
                    print("WORLD_CAPTURE: post-cull rendered camera telemetry remains finite",
                          "yaw",round(camera_after_return[0],3),
                          "pitch",round(camera_after_return[1],3),
                          "physicalBeaconClickWillVerifyMouse=true",flush=True)

                    summary=None
                    resumed=None
                    reset_reason=None
                    reset_rebuild=None
                    resume_deadline=time.monotonic()+8.0
                    while time.monotonic()<resume_deadline and p.poll() is None:
                        probe_logs=read_probe_log()
                        summary=re.search(
                            r"BLACK_FREQUENCY_GROUND_MESH_VERTICAL_CULL_SKIP_SUMMARY frames=(\d+) worldBorderProbesSkipped=(\d+) loadedChunkProbesSkipped=(\d+) rebuildChecksSkipped=(\d+) endedBy=([a-z-]+)",
                            probe_logs)
                        resumed=re.search(
                            r"BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_RESUMED rebuilt=true segments=(\d+) arms=(\d+) cameraY=([-+0-9.eE]+) surfaceY=([-+0-9.eE]+) anchorChanged=(true|false)",
                            probe_logs)
                        reset_reason=re.search(
                            r"BLACK_FREQUENCY_TEST CLIENT_STATE_RESET reason=(client-level-unavailable|client-level-replaced|stale-packet-timeout) previousLevelPresent=(true|false)",
                            probe_logs)
                        if summary and summary.group(5) in ("client-level-unavailable","client-level-replaced","stale-packet-timeout"):
                            after_summary=probe_logs[summary.end():]
                            height_change=re.search(
                                r"BLACK_FREQUENCY_GROUND_MESH_HEIGHT_INVALIDATED previousReferenceY=(-?\d+) referenceY=(-?\d+) delta=(\d+)",
                                after_summary)
                            if height_change:
                                after_height=after_summary[height_change.end():]
                                rebuilt=re.search(
                                    r"BLACK_FREQUENCY_GROUND_MESH_RENDERED segments=(\d+) arms=(\d+) anchorX=(-?\d+) anchorZ=(-?\d+)",
                                    after_height)
                                if (rebuilt and int(height_change.group(3))>=64
                                        and abs(int(height_change.group(2))-y)<=2):
                                    reset_rebuild=(height_change,rebuilt)
                        if summary and (resumed or (reset_reason and reset_rebuild)):
                            break
                        time.sleep(0.1)
                    if not summary:
                        raise RuntimeError("BLACK FREQUENCY vertical cull probe did not emit a skip summary after its log cursor")
                    reset_path=summary.group(5) in ("client-level-unavailable","client-level-replaced","stale-packet-timeout")
                    if reset_path:
                        if (not reset_reason or reset_reason.group(1)!=summary.group(5)
                                or (summary.group(5)=="stale-packet-timeout" and reset_reason.group(2)!="true")
                                or not reset_rebuild):
                            raise RuntimeError("BLACK FREQUENCY client state reset did not rebuild the ground mesh after camera return")
                    elif not resumed:
                        raise RuntimeError("BLACK FREQUENCY vertical cull probe did not resume the retained mesh after camera return")
                    sample_position=probe_logs.find("BLACK_FREQUENCY_TEST GROUND_MESH_CAMERA_VERTICAL_RANGE_SAMPLE")
                    entered_position=probe_logs.find("BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_ENTERED")
                    summary_position=probe_logs.find("BLACK_FREQUENCY_GROUND_MESH_VERTICAL_CULL_SKIP_SUMMARY")
                    resumed_position=probe_logs.find("BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_RESUMED")
                    milestone_position=probe_logs.find("BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_SKIP_MILESTONE")
                    reset_position=probe_logs.find("BLACK_FREQUENCY_TEST CLIENT_STATE_RESET")
                    if not (0<=sample_position<entered_position<milestone_position<summary_position):
                        raise RuntimeError("BLACK FREQUENCY vertical cull probe markers were not emitted in sample/enter/milestone/skip order")
                    unavailable_anchor_position=probe_logs.find(
                        "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_ANCHOR_CHUNK_UNAVAILABLE_PRESERVED")
                    if (unavailable_anchor_position>=0
                            and not (entered_position<unavailable_anchor_position<summary_position)):
                        raise RuntimeError("BLACK FREQUENCY unavailable-anchor preservation marker was out of cull/resume order")
                    frames,border_skips,chunk_skips,rebuild_skips=(int(summary.group(i)) for i in range(1,5))
                    if (frames<3 or border_skips!=frames or chunk_skips!=frames
                            or rebuild_skips!=frames):
                        raise RuntimeError("BLACK FREQUENCY vertical cull skip accounting did not match the real skipped frames")
                    if reset_path:
                        height_change,rebuilt=reset_rebuild
                        if not (0<=reset_position<summary_position
                                and int(height_change.group(3))>=64
                                and abs(int(height_change.group(2))-y)<=2
                                and int(rebuilt.group(1))>=MIN_GROUND_MESH_SEGMENTS
                                and int(rebuilt.group(2))>=MIN_GROUND_MESH_ARMS
                                and int(rebuilt.group(3))==int(entered.group(4))
                                and int(rebuilt.group(4))==int(entered.group(5))):
                            raise RuntimeError("BLACK FREQUENCY client state reset rebuild did not return to the original anchor")
                        segments,arms=int(rebuilt.group(1)),int(rebuilt.group(2))
                    else:
                        if not (0<=summary_position<resumed_position
                                and summary.group(5)=="camera-returned-or-anchor-changed"
                                and resumed.group(5)=="false"):
                            raise RuntimeError("BLACK FREQUENCY vertical cull resume markers were not emitted in order")
                        segments,arms=int(resumed.group(1)),int(resumed.group(2))
                    if segments<MIN_GROUND_MESH_SEGMENTS or arms<MIN_GROUND_MESH_ARMS:
                        raise RuntimeError("BLACK FREQUENCY ground mesh did not rebuild after the camera returned")
                    restored=True
                    vertical_cull_probe_verified=True
                    print("WORLD_CAPTURE: actual vertical cull probe verified",
                          f"verticalDelta={vertical_delta:.2f}",f"frames={frames}",
                          f"borderSkipped={border_skips}",f"chunkSkipped={chunk_skips}",
                          f"rebuildChecksSkipped={rebuild_skips}",
                          f"resumedSegments={segments}",f"resumedArms={arms}",flush=True)
                    return True
                finally:
                    if not restored and p.poll() is None:
                        # Keep the disposable player recoverable even if a diagnostic assertion fails.
                        try:
                            send_chat_command(f"/tp @s {x} {y} {z}",
                                r"Teleported .* to ","failure recovery teleport")
                            send_chat_command("/gamemode survival @s",
                                r"Set own game mode to Survival Mode","failure recovery survival")
                        except Exception as recovery_error:
                            print("WORLD_CAPTURE_WARN: could not complete disposable-player recovery",
                                  repr(recovery_error),flush=True)

            for stage in range(32):
                if p.poll() is not None:
                    break
                snap(f"world-stage-{stage:02d}.png")
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                if (deferred_chunk_probe and not deferred_shared_spawn_relocated
                        and "BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER" in current):
                    if not send_isolated_chat_command(
                            f"/setworldspawn {DEFERRED_CLIENT_SPAWN_X} 64 {DEFERRED_CLIENT_SPAWN_Z}",
                            DEFERRED_SPAWN_ACK_RE,
                            "move shared spawn beyond the remote fracture anchor"):
                        return False
                    deferred_shared_spawn_relocated=True
                    print("WORLD_CAPTURE: moved shared spawn beyond the remote fracture anchor",
                          "loadedOnly=true",flush=True)
                    time.sleep(.25)
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else current
                if (os.environ.get("BF_ISOLATED_TEST")=="1"
                        and not vertical_cull_probe_verified):
                    if ("BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT" in current
                            and "BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED" in current):
                        if run_vertical_ground_cull_probe():
                            print("WORLD_CAPTURE: live vertical cull probe completed immediately after the active fracture mesh became available",
                                  flush=True)
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
                if (deferred_chunk_probe and not deferred_shared_spawn_relocated
                        and "BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER" in current):
                    if not send_isolated_chat_command(
                            f"/setworldspawn {DEFERRED_CLIENT_SPAWN_X} 64 {DEFERRED_CLIENT_SPAWN_Z}",
                            DEFERRED_SPAWN_ACK_RE,
                            "move shared spawn beyond the remote fracture anchor"):
                        return False
                    deferred_shared_spawn_relocated=True
                    print("WORLD_CAPTURE: moved shared spawn beyond the remote fracture anchor",
                          "loadedOnly=true",flush=True)
                    time.sleep(.25)
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else current
                # Run the live camera-cull probe as soon as its safe-pad and first-edit
                # fixtures exist. The client intentionally expires an inactive state packet
                # after 100 ticks; the later terrain camera sweep can consume that lease and
                # reset the mesh cache before three real skipped render passes are observed.
                if (os.environ.get("BF_ISOLATED_TEST")=="1"
                        and not vertical_cull_probe_verified
                        and "BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT" in current
                        and "BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED" in current):
                    probe_ready_deadline=time.monotonic()+4.0
                    while (not vertical_cull_probe_verified and not vertical_cull_probe_attempted
                            and time.monotonic()<probe_ready_deadline and p.poll() is None):
                        if run_vertical_ground_cull_probe():
                            print("WORLD_CAPTURE: live vertical cull probe completed before terrain camera sweep and packet expiry",
                                  flush=True)
                            break
                        time.sleep(0.1)
                    if vertical_cull_probe_attempted and not vertical_cull_probe_verified:
                        print("WORLD_CAPTURE_FAIL: early live vertical cull probe was attempted but did not complete",
                              flush=True)
                        return False
                capture_terrain_fracture(current)
                if terrain_capture_failed:
                    break
                try_beacon_click(current)
                capture_sky_before_beacon()
                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else current
                if "BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED" in current and not fracture_seen:
                    fracture_seen=True
                    print("WORLD_CAPTURE: first physical terrain fracture confirmed",flush=True)
                beacon_test_required=os.environ.get("BF_TEST_PLAYER_BEACON")=="1"
                beacon_interaction_confirmed=(
                    "BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION" in current
                    and "BLACK_FREQUENCY_TEST BEACON_ACTIVATION_SIGHTLINE_VERIFIED hit=beacon loadedChunksOnly=true" in current
                    and "BLACK_FREQUENCY_TEST BEACON_HIT_POINT_ACCEPTED_VERIFIED" in current
                    and "BLACK_FREQUENCY_TEST BEACON_START_ACTIONBAR_ONLY" in current
                    and "BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED" in current
                )
                # A terrain frame is not the end of the smoke test: keep polling so
                # the next real client-input attempt can follow sky capture/reload.
                if fracture_seen and terrain_shot and (
                        not beacon_test_required or beacon_interaction_confirmed):
                    break
                time.sleep(0.1)
            if not fracture_seen:
                print("WORLD_CAPTURE_FAIL: no terrain edit marker during capture window",flush=True)
                snap("terrain-fracture-not-seen.png")
                return False
            if not terrain_shot:
                final_log=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                empty_mesh_matches=list(re.finditer(
                    r"BLACK_FREQUENCY_GROUND_MESH_EMPTY(?:\s|$)",final_log))
                last_empty_mesh=empty_mesh_matches[-1].start() if empty_mesh_matches else -1
                last_rendered_mesh=final_log.rfind("BLACK_FREQUENCY_GROUND_MESH_RENDERED")
                if "BLACK_FREQUENCY_GROUND_MESH_EVENT_ACTIVE" not in final_log:
                    print("WORLD_CAPTURE_FAIL: AFTER_PARTICLES ground renderer was never dispatched",flush=True)
                elif last_empty_mesh>last_rendered_mesh:
                    print("WORLD_CAPTURE_FAIL: latest ground mesh build had no valid loaded surface segments",flush=True)
                elif last_rendered_mesh<0:
                    print("WORLD_CAPTURE_FAIL: ground renderer ran but did not reach its mesh readiness threshold",flush=True)
                else:
                    print("WORLD_CAPTURE_FAIL: mesh marker exists but no player-visible fissure frame was captured",flush=True)
                print("WORLD_CAPTURE_FAIL: no player-visible ground fissure was captured",flush=True)
                # Preserve the earlier frame that was actually measured; keep the
                # post-failure diagnostic view under a separate filename.
                snap("recovery-beacon-ground-context-final-failure.png")
                return False
            final_log=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            camera_source_path=ROOT/"addon/src/main/java/com/blackfrequency/rift/client/RiftClientState.java"
            try:
                camera_source=camera_source_path.read_text(encoding="utf-8",errors="replace")
            except OSError as exc:
                print(f"WORLD_CAPTURE_FAIL: could not read GroundRenderer source for structural verification: {exc}",
                      flush=True)
                return False
            opening_ramp_marker = (
                "BLACK_FREQUENCY_RIFT_OPENING_RAMP_PROFILE_VERIFIED "
                "ticks=60 curve=quintic quarter=0.103515625 midpoint=0.5 threeQuarter=0.8964844 endpoints=true monotonic=true"
            )
            if opening_ramp_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: sky-rift opening ramp did not pass its monotonic quintic profile",flush=True)
                return False
            smootherstep_marker = (
                "BLACK_FREQUENCY_SMOOTHERSTEP_PROFILE_VERIFIED "
                "curve=quintic quarter=0.103515625 midpoint=0.5 threeQuarter=0.8964844 "
                "endpoints=true firstDerivativeZero=true secondDerivativeZero=true monotonic=true bounded=true invalidFallback=true"
            )
            if smootherstep_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: quintic easing profile did not pass its C2 boundary checks",flush=True)
                return False
            if "BLACK_FREQUENCY_GREEN_PHASE_EASING_VERIFIED curve=quintic" not in final_log:
                print("WORLD_CAPTURE_FAIL: green corruption phase did not use the verified quintic easing",flush=True)
                return False
            packet_interpolation_marker = (
                "BLACK_FREQUENCY_RIFT_PACKET_INTERPOLATION_VERIFIED "
                "intervalTicks=10 cap=10 monotonic=true overflowSafe=true negativeSafe=true"
            )
            if packet_interpolation_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: client did not interpolate finale ticks between authoritative state packets",flush=True)
                return False
            if ("private static float riftOpeningRamp(int ticks)" not in camera_source
                    or "return smootherstep01(ticks/(float)RIFT_OPENING_RAMP_TICKS);" not in camera_source
                    or "return collapse ? strength()*riftOpeningRamp(finaleTicks) : strength();" not in camera_source
                    or "private static int serverFinaleTicks;" not in camera_source
                    or "private static final int RIFT_STATE_PACKET_INTERVAL_TICKS=10;" not in camera_source
                    or "private static int predictedFinaleTicks(int packetFinaleTicks,int packetAgeTicks)" not in camera_source
                    or "finaleTicks=predictedFinaleTicks(serverFinaleTicks,ticksSincePacket);" not in camera_source
                    or "ticksSincePacket=Math.min(CLIENT_STATE_PACKET_TIMEOUT_TICKS+1,ticksSincePacket+1);" not in camera_source):
                print("WORLD_CAPTURE_FAIL: sky-rift opening ramp is not wired into visible client strength",flush=True)
                return False
            support_top_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_TOP_PROFILE_VERIFIED "
                "fullBlock=1.0 bottomSlab=0.5 invalidFallback=1.0 nonCollidingCoverIgnored=true"
            )
            if support_top_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: support-top sampling did not prove that non-colliding foliage cannot lift the fissure mesh",flush=True)
                return False
            snow_surface_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_SNOW_LAYER_SURFACE_HEIGHT_VERIFIED "
                "layers=1..8 minOffset=1.125 maxOffset=2.0 invalidFallback=0.5 "
                "overheightClamp=1.5 monotonic=true shapeDerived=true "
                "surfaceAccepted=true fullSnowBlockRejected=true"
            )
            if snow_surface_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: snow-layer ground surface height was not derived from the actual snow shape",flush=True)
                return False
            partial_support_snow_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_PARTIAL_SUPPORT_SNOW_OFFSET_VERIFIED "
                "supportHeight=0.5 snowHeight=0.5 offset=1.0 separateFromLayerSweep=true"
            )
            if (partial_support_snow_marker not in final_log
                    or "double partialSupportSnowOffset=groundSurfaceOffset(.5D,.5D);" not in camera_source
                    or "Math.abs(partialSupportSnowOffset-1.0D)" not in camera_source):
                print("WORLD_CAPTURE_FAIL: partial-block support height was not independently verified under snow",flush=True)
                return False
            support_shape_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_SHAPE_GUARD_VERIFIED "
                "fullBlock=1.0 partial=0.5 outlineFallback=true emptyRejected=true"
            )
            if support_shape_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: ground mesh accepted an empty or zero-height support shape",flush=True)
                return False
            slab_support_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_SLAB_SUPPORT_VERIFIED "
                "bottom=0.5 top=1.0 double=1.0 bottomSnow=1.0 topSnow=1.5 stairsRejected=true shapeDerived=true"
            )
            if (slab_support_marker not in final_log
                    or "!state.is(BlockTags.SLABS)" in camera_source
                    or "SlabBlock.TYPE" not in camera_source
                    or "SlabType.BOTTOM" not in camera_source
                    or "SlabType.TOP" not in camera_source
                    or "SlabType.DOUBLE" not in camera_source
                    or "boolean supportsSlabs=" not in camera_source
                    or "!isGroundSupport(net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState())" not in camera_source):
                print("WORLD_CAPTURE_FAIL: slab support must use real bottom/top/double collision heights while stairs remain excluded",flush=True)
                return False
            uniform_top_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_UNIFORM_TOP_SUPPORT_VERIFIED "
                "fullBlock=true bottomSlab=true topSlab=true doubleSlab=true cauldronRejected=true"
            )
            support_scan_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_SCAN_ALLOCATION_VERIFIED "
                "strategy=forAllBoxes reusableProbe=true materializedAabbs=false staleResultCleared=true"
            )
            support_probe_bounds_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_PROBE_BOUNDS_VERIFIED "
                "nonFiniteRejected=true invertedBoundsRejected=true invertedYRejected=true degenerateYRejected=true validAccepted=true"
            )
            support_height_bound_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_HEIGHT_BOUND_VERIFIED "
                "maxHeight=1.0 overheightRejected=true epsilonTolerance=true"
            )
            support_horizontal_bounds_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_HORIZONTAL_BOUNDS_VERIFIED "
                "overhangRejected=true epsilonTolerance=true voxelShapeMultiBoxRejected=true orderIndependent=true"
            )
            support_vertical_bounds_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_VERTICAL_BOUNDS_VERIFIED "
                "underhangRejected=true epsilonTolerance=true voxelShapeMultiBoxRejected=true"
            )
            if (uniform_top_marker not in final_log
                    or support_scan_marker not in final_log
                    or support_probe_bounds_marker not in final_log
                    or support_height_bound_marker not in final_log
                    or support_horizontal_bounds_marker not in final_log
                    or support_vertical_bounds_marker not in final_log
                    or "minY < -GROUND_SUPPORT_SHAPE_EPSILON" not in camera_source
                    or "verifyVerticalSupportProbeBounds()" not in camera_source
                    or "fullBlock.move(0,-1.25D,0)" not in camera_source
                    or "private static boolean hasFullFootprintTop(" not in camera_source
                    or "private static final class FullFootprintTopProbe" not in camera_source
                    or "shape.forAllBoxes(GROUND_SUPPORT_TOP_PROBE);" not in camera_source
                    or "!Double.isFinite(minX)" not in camera_source
                    or "!Double.isFinite(maxZ)" not in camera_source
                    or "minX>maxX" not in camera_source
                    or "minY>=maxY" not in camera_source
                    or "minZ>maxZ" not in camera_source
                    or "minX < -GROUND_SUPPORT_SHAPE_EPSILON" not in camera_source
                    or "maxX > 1.0D+GROUND_SUPPORT_SHAPE_EPSILON" not in camera_source
                    or "minZ < -GROUND_SUPPORT_SHAPE_EPSILON" not in camera_source
                    or "maxZ > 1.0D+GROUND_SUPPORT_SHAPE_EPSILON" not in camera_source
                    or "verifyHorizontalSupportProbeBounds()" not in camera_source
                    or "private boolean invalidBounds;" not in camera_source
                    or "return found && !invalidBounds;" not in camera_source
                    or "Shapes.or(fullBlock,fullBlock.move(-1.25D,0,0))" not in camera_source
                    or "height<=1.0D+GROUND_SUPPORT_SHAPE_EPSILON" not in camera_source
                    or "overheightSupportRejected" not in camera_source
                    or "verifyMalformedSupportProbeBounds()" not in camera_source
                    or "shape.toAabbs()" in camera_source
                    or "Blocks.CAULDRON.defaultBlockState()" not in camera_source):
                print("WORLD_CAPTURE_FAIL: full-footprint support must reject horizontal overhangs and stream VoxelShape boxes through the reusable allocation-bounded probe",flush=True)
                return False
            sample_start=camera_source.find("private static double sampleGroundY(")
            sample_end=camera_source.find("private static long columnKey(",sample_start+1)
            sample_source=(camera_source[sample_start:sample_end]
                           if sample_start>=0 and sample_end>sample_start else "")
            snow_shape_probe = re.compile(
                r"double snowLayerHeight=surface\.is\(net\.minecraft\.world\.level\.block\.Blocks\.SNOW\)"
                r"\s*\?\s*surface\.getShape\(level,cursor\)\.max\(Direction\.Axis\.Y\)"
                r"\s*:\s*Double\.NaN;"
            )
            snow_shape_match=snow_shape_probe.search(sample_source)
            snow_shape_index=snow_shape_match.start() if snow_shape_match else -1
            support_offset_profile=re.compile(
                r"double supportOffset=boundedShapeHeight\(supportHeight,1\.0D\);"
                r"\s*if\(Double\.isFinite\(snowLayerHeight\)\s*&&\s*snowLayerHeight>0\.0D\)"
                r"\s*return supportOffset\+Math\.min\(1\.0D,snowLayerHeight\);"
                r"\s*return supportOffset;"
            )
            headroom_index=sample_source.find("cursor.set(blockX,candidateY+1,blockZ);")
            snow_offset_index=sample_source.find("groundSurfaceOffset(supportHeight,snowLayerHeight)")
            if ("private static double groundSurfaceOffset(double supportHeight,double snowLayerHeight)" not in camera_source
                    or snow_shape_index<0
                    or headroom_index<0
                    or snow_offset_index<0
                    or snow_shape_index>headroom_index
                    or headroom_index>snow_offset_index
                    or "coverShape" in sample_source
                    or "Double.isFinite(snowLayerHeight)" not in camera_source
                    or "Math.min(1.0D,snowLayerHeight)" not in camera_source
                    or not support_offset_profile.search(camera_source)):
                print("WORLD_CAPTURE_FAIL: ground mesh surface-height contract does not preserve foliage occlusion while lifting above snow layers",flush=True)
                return False
            if ("private static boolean isGroundHeadClear(BlockState head)" not in camera_source
                    or "isGroundHeadClear(head)" not in sample_source):
                print("WORLD_CAPTURE_FAIL: ground mesh does not guard powder-snow headroom",flush=True)
                return False
            color_profile_marker="BLACK_FREQUENCY_GROUND_MESH_COLOR_CHANNEL_PROFILE_VERIFIED zero=0 half=128 one=255 lowClamp=0 highClamp=255 nonFinite=true"
            if color_profile_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: per-ribbon color conversion profile was not executed",flush=True)
                return False
            glow_alpha_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_GLOW_ALPHA_VERIFIED multiplier=([0-9.]+) fullStrengthPulseMin=([0-9.]+) fullStrengthPulseMax=([0-9.]+) minAlpha=([0-9.]+) maxAlpha=([0-9.]+)",
                final_log)
            expected_glow_alpha=(.74,.56,1.0,.4144,.74)
            if (not glow_alpha_match
                    or any(not math.isclose(float(glow_alpha_match.group(i)),expected_glow_alpha[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-6)
                           for i in range(1,6))
                    or "GROUND_GLOW_ALPHA=.74F;" not in camera_source
                    or "int glowAlphaChannelA=colorChannel(alphaA*GROUND_GLOW_ALPHA);" not in camera_source
                    or "int glowAlphaChannelB=colorChannel(alphaB*GROUND_GLOW_ALPHA);" not in camera_source):
                print("WORLD_CAPTURE_FAIL: per-endpoint violet ground halo alpha profile is missing or outside its tested bounds",flush=True)
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
            render_loop_match=re.search(
                r"for\s*\(\s*int\s+segmentIndex\s*=\s*firstVisibleSegmentIndex\s*;\s*segmentIndex\s*<\s*cachedSegments\.size\(\)\s*;\s*segmentIndex\+\+\s*\)",
                camera_source)
            if not render_loop_match:
                print("WORLD_CAPTURE_FAIL: ground render loop no longer starts at the cached first-visible segment",flush=True)
                return False
            render_loop_start=render_loop_match.start()
            render_loop_end=camera_source.find("BufferUploader.drawWithShader(builder.end());",render_loop_start)
            if render_loop_end<=render_loop_start:
                print("WORLD_CAPTURE_FAIL: ground render loop end marker is missing",flush=True)
                return False
            render_loop_source=camera_source[render_loop_start:render_loop_end]
            render_preamble_start=camera_source.find("double time=(double)tick+boundedRenderPartialTick")
            render_preamble=(camera_source[render_preamble_start:render_loop_start]
                             if render_preamble_start>=0 and render_loop_start>render_preamble_start else "")
            if ("double phaseSin,double phaseCos,double flickerPhaseSin,double flickerPhaseCos" not in camera_source
                    or "GroundSample sample,double taperA,double taperB,double coreWidthScaleA,double coreWidthScaleB" not in camera_source
                    or "double phaseSin=Math.sin(phase);" not in camera_source
                    or "double phaseCos=Math.cos(phase);" not in camera_source
                    or "Math.sin(phase*1.9D),Math.cos(phase*1.9D)" not in camera_source
                    or render_preamble.count("Math.sin(")!=3
                    or render_preamble.count("Math.cos(")!=3
                    or "GroundSegment segment=cachedSegments.get(segmentIndex);" not in render_loop_source
                    or "GroundSample sample=segment.sample();" not in render_loop_source
                    or "if(segmentIndex==firstVisibleSegmentIndex)" not in render_loop_source
                    or "segmentFadeA=firstVisibleFadeA;" not in render_loop_source
                    or "segmentFadeB=firstVisibleFadeB;" not in render_loop_source
                    or "sample.phaseSin()" not in render_loop_source
                    or "sample.phaseCos()" not in render_loop_source
                    or "sample.flickerPhaseSin()" not in render_loop_source
                    or "sample.flickerPhaseCos()" not in render_loop_source
                    or "Math.sin(" in render_loop_source or "Math.cos(" in render_loop_source):
                print("WORLD_CAPTURE_FAIL: ground animation cache is not reused by the indexed render loop",flush=True)
                return False
            print("WORLD_CAPTURE: ground animation uses six shared time-base trig calls per frame",flush=True)
            core_gap_marker=("BLACK_FREQUENCY_GROUND_MESH_CORE_SEGMENT_GAP_PROFILE_VERIFIED "
                             "samples=96 gaps=2 adjacentGapPairs=0 gapArms=2 minGapSeparation=26 deterministic=true gapRate=1/64")
            if core_gap_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: deterministic ground core gaps were not verified",flush=True)
                return False
            core_gap_layer_policy_marker=("BLACK_FREQUENCY_TEST GROUND_MESH_CORE_GAP_LAYER_POLICY_VERIFIED "
                                          "gapCount=2 outerHalo=true bevels=true edgePolicy=preserved coreSuppressed=true "
                                          "gapEnvelopeVisible=true fadeReuseIntoGap=true fadeReuseOutOfGap=true "
                                          "disconnectedIdentityFallback=true invalidFadeRejected=true")
            if core_gap_layer_policy_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: core gaps did not preserve the connected violet envelope",flush=True)
                return False
            edge_notch_marker=("BLACK_FREQUENCY_GROUND_MESH_EDGE_NOTCH_PROFILE_VERIFIED "
                               "samples=96 left=4 right=4 asymmetricSamples=6 adjacentLeftPairs=0 adjacentRightPairs=0 "
                               "deterministic=true notchRate=1/32")
            core_width_marker=("BLACK_FREQUENCY_GROUND_MESH_CORE_WIDTH_PROFILE_VERIFIED "
                               "samples=96 minBound=0.78 maxBound=1.22 maxAdjacentDeltaLimit=0.075 interpolated=true bounded=true "
                               "harmonics=2 broadAmplitude=0.16 detailAmplitude=0.05 detailFrequency=0.43 armPhase=1.13 invalidFallback=1.0")
            if edge_notch_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: independently notched ground lips were not verified",flush=True)
                return False
            edge_mask_marker=("BLACK_FREQUENCY_GROUND_MESH_EDGE_MASK_VERIFIED "
                              "leftBit=1 rightBit=2 capMask=3 ordinaryMask=0 invalidMask=0")
            edge_mask_marker_count=final_log.count(edge_mask_marker)
            if edge_mask_marker_count!=1:
                print(f"WORLD_CAPTURE_FAIL: expected one shared ground-edge mask probe, observed {edge_mask_marker_count}",
                      flush=True)
                return False
            if core_width_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: interpolated ground-core width profile was not verified",flush=True)
                return False
            core_skew_marker=("BLACK_FREQUENCY_GROUND_MESH_CORE_ASYMMETRY_PROFILE_VERIFIED "
                              "samples=96 minBound=-0.15 maxBound=0.15 maxAdjacentDeltaLimit=0.04 interpolated=true deterministic=true invalidFallback=0.0")
            if core_skew_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: cached asymmetric core-wall profile was not verified",flush=True)
                return False
            if ("private static final double GROUND_MIN_CORE_WIDTH_SCALE=.78D;" not in camera_source
                    or "private static final double GROUND_MAX_CORE_WIDTH_SCALE=1.22D;" not in camera_source
                    or "private static double groundCoreWidthScale(double phase,int arm)" not in camera_source
                    or "GROUND_CORE_WIDTH_DETAIL_FREQUENCY=.43D;" not in camera_source
                    or "Math.sin(phase*GROUND_CORE_WIDTH_DETAIL_FREQUENCY" not in camera_source
                    or "groundCoreWidthScale(phase,arm)" not in camera_source
                    or "groundCoreWidthScale(Double.NaN,0)!=1.0D" not in camera_source
                    or "previousSample.coreWidthScale()" not in camera_source
                    or "sample.coreWidthScale()" not in camera_source
                    or "segment.coreWidthScaleA()" not in camera_source
                    or "segment.coreWidthScaleB()" not in camera_source
                    or "private static double groundCoreSkew(double phase,int arm)" not in camera_source
                    or "groundCoreSkew(phase,arm)" not in camera_source
                    or "previousSample.coreSkew()" not in camera_source
                    or "sample.coreSkew()" not in camera_source
                    or "segment.coreSkewA()" not in camera_source
                    or "segment.coreSkewB()" not in camera_source
                    or "private static void coreRibbon(" not in camera_source):
                print("WORLD_CAPTURE_FAIL: dark core widths are not interpolated between cached surface samples",flush=True)
                return False
            if ("boolean coreSegmentGap,\n                boolean leftEdgeNotched,boolean rightEdgeNotched" not in camera_source
                    or "private static boolean isGroundCoreSegmentGap(double phase)" not in camera_source
                    or "sample.coreSegmentGap()!=isGroundCoreSegmentGap(phase)" not in camera_source
                    or "private static boolean shouldRenderGroundCore(boolean coreSegmentGap)" not in camera_source
                    or "if(shouldRenderGroundCore(coreGap))" not in camera_source
                    or "if(segment==null) continue;" not in camera_source
                    or "previousFadePoint=segment.b();" not in camera_source
                    or "previousFadeB=segmentFadeB;" not in camera_source
                    or re.search(r"if\(coreGap\)\s*\{[^}]*previousFadePoint=null;",camera_source) is not None
                    or "if(segment!=null && groundSegmentDistanceFade(segment,camera)>0.0D) return true;" not in camera_source
                    or min(camera_source.find("ribbon(builder,matrix,camera,segment,0.0D,halfWidth*GROUND_GLOW_WIDTH_SCALE"),
                            camera_source.find("if(shouldRenderGroundCore(coreGap))"),
                            camera_source.find("int edgeMask=groundEdgeMask(sample,segment);"))<0
                    or not (camera_source.find("ribbon(builder,matrix,camera,segment,0.0D,halfWidth*GROUND_GLOW_WIDTH_SCALE")
                            < camera_source.find("if(shouldRenderGroundCore(coreGap))")
                            < camera_source.find("int edgeMask=groundEdgeMask(sample,segment);"))
                    or "private static boolean isGroundEdgeNotched(double phase,long salt)" not in camera_source
                    or "return (groundSampleHash(phase,salt) & 31L)==0L;" not in camera_source
                    or "private static final long GROUND_CORE_SEGMENT_GAP_SALT=0x2E2AC13EF8E8D8D2L;" not in camera_source
                    or "return (groundSampleHash(phase,GROUND_CORE_SEGMENT_GAP_SALT) & 63L)==0L;" not in camera_source
                    or "int adjacentCoreGapPairs=0;" not in camera_source
                    or "if(previousCoreSegmentGap) adjacentCoreGapPairs++;" not in camera_source
                    or "Integer.bitCount(coreGapArmMask)!=2" not in camera_source
                    or "minimumCoreGapSeparation<12" not in camera_source
                    or "adjacentCoreGapPairs!=0" not in camera_source
                    or "isGroundEdgeNotched(phase,GROUND_LEFT_EDGE_NOTCH_SALT)" not in camera_source
                    or "isGroundEdgeNotched(phase,GROUND_RIGHT_EDGE_NOTCH_SALT)" not in camera_source
                    or "private static boolean isGroundSegmentCap(GroundSegment segment)" not in camera_source
                    or "private static int groundEdgeMask(GroundSample sample,GroundSegment segment)" not in camera_source
                    or "if(!sample.leftEdgeNotched()) mask|=GROUND_EDGE_LEFT_MASK;" not in camera_source
                    or "if(!sample.rightEdgeNotched()) mask|=GROUND_EDGE_RIGHT_MASK;" not in camera_source):
                print("WORLD_CAPTURE_FAIL: crack-lip notches are not cached and independently applied",flush=True)
                return False
            ribbon_cache_marker=("BLACK_FREQUENCY_GROUND_MESH_RIBBON_COLOR_CACHE_VERIFIED "
                                 "staticRgbChannels=15 dynamicAlphaChannels=10 edgeAlphaBaseShared=true edgeNotchesCached=true edgePaletteBound=true")
            if ribbon_cache_marker in final_log:
                expected_static_channels=(
                    "private static final int GROUND_GLOW_RED_CHANNEL=colorChannel(.20F);",
                    "private static final int GROUND_GLOW_GREEN_CHANNEL=colorChannel(.025F);",
                    "private static final int GROUND_GLOW_BLUE_CHANNEL=colorChannel(.43F);",
                    "private static final int GROUND_CORE_RED_CHANNEL=colorChannel(.008F);",
                    "private static final int GROUND_CORE_GREEN_CHANNEL=colorChannel(.001F);",
                    "private static final int GROUND_CORE_BLUE_CHANNEL=colorChannel(.022F);",
                    "private static final int GROUND_BEVEL_RED_CHANNEL=colorChannel(.055F);",
                    "private static final int GROUND_BEVEL_GREEN_CHANNEL=colorChannel(.008F);",
                    "private static final int GROUND_BEVEL_BLUE_CHANNEL=colorChannel(.12F);",
                    "private static final int GROUND_FILAMENT_RED_CHANNEL=colorChannel(.28F);",
                    "private static final int GROUND_FILAMENT_GREEN_CHANNEL=colorChannel(.015F);",
                    "private static final int GROUND_FILAMENT_BLUE_CHANNEL=colorChannel(.62F);",
                    "private static final int GROUND_EDGE_RED_CHANNEL=colorChannel(GROUND_EDGE_RED);",
                    "private static final int GROUND_EDGE_GREEN_CHANNEL=colorChannel(GROUND_EDGE_GREEN);",
                    "private static final int GROUND_EDGE_BLUE_CHANNEL=colorChannel(GROUND_EDGE_BLUE);")
                dynamic_alpha_calls=(
                    "int glowAlphaChannelA=colorChannel(alphaA*GROUND_GLOW_ALPHA);",
                    "int glowAlphaChannelB=colorChannel(alphaB*GROUND_GLOW_ALPHA);",
                    "int bevelAlphaChannelA=colorChannel(alphaA*.82F);",
                    "int bevelAlphaChannelB=colorChannel(alphaB*.82F);",
                    "int coreAlphaChannelA=colorChannel(alphaA*.94F);",
                    "int coreAlphaChannelB=colorChannel(alphaB*.94F);",
                    "int filamentAlphaChannelA=colorChannel(alphaA*corePulse);",
                    "int filamentAlphaChannelB=colorChannel(alphaB*corePulse);",
                    "int edgeAlphaChannelA=colorChannel(edgeAlphaA);",
                    "int edgeAlphaChannelB=colorChannel(edgeAlphaB);")
                ribbon_call_patterns=(
                    r"GROUND_GLOW_RED_CHANNEL,GROUND_GLOW_GREEN_CHANNEL,GROUND_GLOW_BLUE_CHANNEL,\s*glowAlphaChannelA,glowAlphaChannelB",
                    r"bevelRibbon\(builder,matrix,camera,segment,halfWidth,true,leftBevelLiftScale,\s*GROUND_BEVEL_RED_CHANNEL,GROUND_BEVEL_GREEN_CHANNEL,GROUND_BEVEL_BLUE_CHANNEL,\s*bevelAlphaChannelA,bevelAlphaChannelB\)",
                    r"bevelRibbon\(builder,matrix,camera,segment,halfWidth,false,rightBevelLiftScale,\s*GROUND_BEVEL_RED_CHANNEL,GROUND_BEVEL_GREEN_CHANNEL,GROUND_BEVEL_BLUE_CHANNEL,\s*bevelAlphaChannelA,bevelAlphaChannelB\)",
                    r"GROUND_FILAMENT_RED_CHANNEL,GROUND_FILAMENT_GREEN_CHANNEL,GROUND_FILAMENT_BLUE_CHANNEL,\s*filamentAlphaChannelA,filamentAlphaChannelB",
                    r"GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,\s*edgeAlphaChannelA,edgeAlphaChannelB")
                prefix_start=camera_source.find("int firstVisibleSegmentIndex=-1;")
                prefix_end=camera_source.find("Matrix4f matrix=event.getPoseStack().last().pose();",prefix_start)
                prefix_source=(camera_source[prefix_start:prefix_end]
                               if prefix_start>=0 and prefix_end>prefix_start else "")
                render_loop_match=re.search(
                    r"for\s*\(\s*int\s+segmentIndex\s*=\s*firstVisibleSegmentIndex\s*;\s*segmentIndex\s*<\s*cachedSegments\.size\(\)\s*;\s*segmentIndex\+\+\s*\)\s*\{",
                    camera_source)
                render_start=render_loop_match.start() if render_loop_match else -1
                render_end=camera_source.find("BufferUploader.drawWithShader(builder.end());",render_start)
                render_source=(camera_source[render_start:render_end]
                               if render_start>=0 and render_end>render_start else "")
                if (not vertex_source or ".color(red,green,blue,alpha)" not in vertex_source
                        or "colorChannel(" in vertex_source or not ribbon_source
                        or "int redChannel,int greenChannel,int blueChannel,int alphaChannelA,int alphaChannelB" not in ribbon_source
                        or "colorChannel(" in ribbon_source
                        or ribbon_source.count("redChannel,greenChannel,blueChannel,alphaChannelA")!=2
                        or ribbon_source.count("redChannel,greenChannel,blueChannel,alphaChannelB")!=2
                        or any(field not in camera_source for field in expected_static_channels)
                        or not prefix_source
                        or "firstVisibleSegmentIndex=i;" not in prefix_source
                        or "firstVisibleFadeA=fadeA;" not in prefix_source
                        or "firstVisibleFadeB=fadeB;" not in prefix_source
                        or any(render_source.count(call)!=1 for call in dynamic_alpha_calls)
                        or "if(segmentIndex==firstVisibleSegmentIndex)" not in render_source
                        or "segmentFadeA=firstVisibleFadeA;" not in render_source
                        or "segmentFadeB=firstVisibleFadeB;" not in render_source
                        or render_source.count("colorChannel(")!=10
                        or "coreRibbon(builder,matrix,camera,segment,halfWidth*GROUND_CORE_WIDTH_SCALE,-GROUND_CORE_RECESS," not in render_source
                        or any(re.search(pattern,render_source) is None for pattern in ribbon_call_patterns)
                        or len(re.findall(r"GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,\s*edgeAlphaChannelA,edgeAlphaChannelB",render_source))!=2
                        or render_source.count("int edgeMask=groundEdgeMask(sample,segment);")!=1
                         or "if((edgeMask&GROUND_EDGE_LEFT_MASK)!=0)" not in render_source
                        or "if((edgeMask&GROUND_EDGE_RIGHT_MASK)!=0)" not in render_source
                        or "isGroundEdgeNotched(" in render_source
                        or "verifyPackedColorProfile();" not in build_source
                        or "verifyRibbonColorCacheProfile();" not in build_source):
                    print("WORLD_CAPTURE_FAIL: ground ribbon must cache 15 static RGB channels and compute ten endpoint alphas across five material layers",flush=True)
                    return False
                reuse_profile=re.search(
                    r"BLACK_FREQUENCY_TEST GROUND_MESH_VISIBILITY_PREFIX_REUSE_VERIFIED firstVisibleIndex=(\d+) prefixFadeChecks=(\d+) cachedFirstSegmentFade=true redundantPrefixRechecksAvoided=(\d+)",
                    final_log)
                if (not reuse_profile
                        or int(reuse_profile.group(2))<2
                        or int(reuse_profile.group(2))!=int(reuse_profile.group(3))):
                    print("WORLD_CAPTURE_FAIL: runtime ground mesh did not prove first-visible endpoint fade reuse",flush=True)
                    return False
                endpoint_fade_profile=(
                    "BLACK_FREQUENCY_TEST GROUND_MESH_ENDPOINT_FADE_REUSE_PROFILE_VERIFIED "
                    "sharedPointIdentity=true distinctPointFallback=true invalidFadeRejected=true")
                endpoint_fade_runtime=re.search(
                    r"BLACK_FREQUENCY_TEST GROUND_MESH_ENDPOINT_FADE_REUSE_VERIFIED reusedStarts=(\d+) recomputedStarts=(\d+) gapResetPolicy=true",
                    final_log)
                cache_advance=render_source.find("previousFadePoint=segment.b();")
                hidden_segment_skip=render_source.find("if(segmentFadeA<=0.0D && segmentFadeB<=0.0D) continue;")
                if (endpoint_fade_profile not in final_log
                        or not endpoint_fade_runtime
                        or int(endpoint_fade_runtime.group(1))<=0
                        or int(endpoint_fade_runtime.group(2))<=0
                        or "canReuseGroundPointFade(segment.a(),previousFadePoint,previousFadeB)" not in render_source
                        or "previousFadeB=segmentFadeB;" not in render_source
                        or cache_advance<0 or hidden_segment_skip<0 or cache_advance>hidden_segment_skip
                        or "verifyGroundEndpointFadeReuseProfile();" not in build_source):
                    print("WORLD_CAPTURE_FAIL: adjacent ground endpoint fades were not safely reused across connected segments",flush=True)
                    return False
                print("WORLD_CAPTURE: adjacent ground segments reuse shared endpoint fades; disconnected starts recompute by point identity",flush=True)
                print("WORLD_CAPTURE: ground ribbon reuses 15 static RGB channels, ten endpoint alpha channels, and cached first-visible fades",flush=True)
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
            if "BLACK_FREQUENCY_TEST GROUND_MESH_CULL_LIFECYCLE_RESET_VERIFIED inactive=true verticalRange=true staleResume=false cameraSample=false" not in final_log:
                print("WORLD_CAPTURE_FAIL: discarded ground mesh retained stale vertical-cull resume telemetry",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_3D_RANGE_PROFILE_VERIFIED radius=64 horizontalBoundary=true verticalBoundary=true invalidRejected=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: 3D ground mesh render-range boundaries were not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_PREBUILD_VERTICAL_CULL_VERIFIED sameAnchorOutside=true insideRetained=true anchorChangeRetained=true emptyMeshRetained=true invalidRangeCulled=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: cached ground mesh pre-build vertical culling was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_LATCH_VERIFIED stable=true hysteresisBandSkip=true returnRebuildAt62=true anchorChangeRebuild=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: vertically culled ground mesh rebuilt or failed to resume at the expected boundary",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_RESUME_ANCHOR_PROFILE_VERIFIED sameAnchor=false changedAnchor=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: vertical cull resume misclassified a same-anchor camera return as an anchor change",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_SKIP_COUNTER_VERIFIED increments=true saturates=true negativeRecovered=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: vertical cull skipped-frame accounting was not verified",flush=True)
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
            prebuild_cull=ground_render.find("shouldCullCachedGroundMeshBeforeRebuild(anchorChanged,!cachedSegments.isEmpty()")
            rebuild_check=ground_render.find("if(shouldRebuild(")
            if prebuild_cull<0 or rebuild_check<0 or prebuild_cull>rebuild_check:
                print("WORLD_CAPTURE_FAIL: vertical mesh culling must precede periodic terrain rebuilds",flush=True)
                return False
            latch_skip=ground_render.find("if(shouldSkipGroundMeshBuildWhileVerticallyCulled(")
            skip_counter=ground_render.find("verticallyCulledSkippedFrames=nextVerticalCullSkippedFrames(verticallyCulledSkippedFrames)")
            resume_flush=ground_render.find('flushVerticalCullSkipDiagnostics("camera-returned-or-anchor-changed")')
            cursor_set=ground_render.find("SURFACE_CURSOR.set(worldAnchorX,level.getMinBuildHeight(),worldAnchorZ);")
            border_check=ground_render.find("if(!level.getWorldBorder().isWithinBounds(SURFACE_CURSOR))")
            loaded_chunk_probe=ground_render.find("if(!level.hasChunk(worldAnchorX>>4,worldAnchorZ>>4))")
            unloaded_anchor_clear=ground_render.find("clearCacheForUnavailableAnchor(worldAnchorX,worldAnchorZ);")
            if ("private static final BlockPos.MutableBlockPos SURFACE_CURSOR=" not in camera_source
                    or not ground_render or latch_skip<0 or skip_counter<0 or resume_flush<0 or cursor_set<0
                    or not (latch_skip<skip_counter<resume_flush<cursor_set<border_check<loaded_chunk_probe<unloaded_anchor_clear)
                    or "new BlockPos(" in ground_render
                    or "private static void flushVerticalCullSkipDiagnostics(String reason)" not in camera_source):
                print("WORLD_CAPTURE_FAIL: vertical cull must count skipped frames and flush the summary before world-border/chunk probes",flush=True)
                return False
            if ("private static boolean verticalCullResumePending;" not in camera_source
                    or "GROUND_MESH_VERTICAL_CULL_ENTERED" not in camera_source
                    or "verticalCullResumePending=true;" not in camera_source
                    or "GROUND_MESH_VERTICAL_CULL_RESUMED" not in camera_source):
                print("WORLD_CAPTURE_FAIL: actual vertical cull entry/resume diagnostics are missing from the renderer",flush=True)
                return False
            unavailable_anchor_start=camera_source.find("private static void clearCacheForUnavailableAnchor(int worldAnchorX,int worldAnchorZ)")
            unavailable_anchor_end=camera_source.find("\n        private static void verifyInactiveCacheCleanup",unavailable_anchor_start+1)
            unavailable_anchor_helper=(camera_source[unavailable_anchor_start:unavailable_anchor_end]
                                       if unavailable_anchor_start>=0 and unavailable_anchor_end>unavailable_anchor_start else "")
            first_unavailable_guard=unavailable_anchor_helper.find("if(!unavailableAnchorChunkLogged) {")
            non_cull_branch=unavailable_anchor_helper.find("if(!preserveVerticalCull) {")
            full_cache_reset=unavailable_anchor_helper.find("clearCache();")
            preserve_branch=unavailable_anchor_helper.find("} else {")
            geometry_release=unavailable_anchor_helper.find("clearGeometryCache();")
            profile_reset=unavailable_anchor_helper.find("resetCacheProfileFlags();")
            camera_sample_latch=unavailable_anchor_helper.find("verticalCameraRangeSampleLogged=true;")
            handled_log=unavailable_anchor_helper.find("GROUND_MESH_ANCHOR_CHUNK_UNAVAILABLE_HANDLED")
            preserved_log=unavailable_anchor_helper.find("GROUND_MESH_VERTICAL_CULL_ANCHOR_CHUNK_UNAVAILABLE_PRESERVED")
            unavailable_latch=unavailable_anchor_helper.find("unavailableAnchorChunkLogged=true;")
            if ("boolean preserveVerticalCull=verticallyCulled || verticalCullResumePending;" not in unavailable_anchor_helper
                    or "preservedCull={} loadedOnly=true noChunkLoad=true resetOnce=true" not in unavailable_anchor_helper
                    or "getChunk(" in unavailable_anchor_helper
                    or first_unavailable_guard<0
                    or not (first_unavailable_guard<non_cull_branch<full_cache_reset<preserve_branch
                            <geometry_release<profile_reset<camera_sample_latch<handled_log<unavailable_latch)
                    or preserved_log<handled_log
                    or unavailable_anchor_helper.count("clearCache();")!=1
                    or unavailable_anchor_helper.count("resetCacheProfileFlags();")!=1
                    or unavailable_anchor_helper.count("unavailableAnchorChunkLogged=true;")!=1):
                print("WORLD_CAPTURE_FAIL: every unavailable-anchor episode must clear once, preserve an active cull latch and never force chunk loads",flush=True)
                return False
            print("WORLD_CAPTURE: vertically culled ground mesh skips world-border and loaded-chunk probes until resume",flush=True)
            print("WORLD_CAPTURE: unavailable anchor chunks clear stale cache once in both cull states; active cull state is preserved without forcing loads",flush=True)
            print("WORLD_CAPTURE: actual vertical cull entry and rebuild diagnostics are wired into the renderer",flush=True)
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
                r"BLACK_FREQUENCY_GROUND_MESH_TAPER_VERIFIED start=([0-9.eE+-]+) first=([0-9.eE+-]+) second=([0-9.eE+-]+) middle=([0-9.eE+-]+) secondLast=([0-9.eE+-]+) last=([0-9.eE+-]+) end=([0-9.eE+-]+) asymmetric=(true|false) invalidClamped=(true|false)",
                final_log)
            expected_taper=(.5,.5+1.0/27.0,17.0/27.0,1.0,
                            .06+.94*(7.0/27.0),.06+.94*(2.0/27.0),.06)
            if (not taper_match
                    or any(not math.isclose(float(taper_match.group(i)),expected_taper[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,8))
                    or taper_match.group(8)!="true" or taper_match.group(9)!="true"
                    or "double startProgress=Math.min(1.0,boundedSample/(double)GROUND_ENDPOINT_TAPER_SAMPLES);" not in camera_source
                    or "double endProgress=Math.min(1.0,(SEGMENTS_PER_ARM-boundedSample)/(double)GROUND_ENDPOINT_TAPER_SAMPLES);" not in camera_source
                    or "double startWidth=.5D+.5D*startEased;" not in camera_source
                    or "double endWidth=CLIFF_BREAK_TAPER+(1.0D-CLIFF_BREAK_TAPER)*endEased;" not in camera_source
                    or "BLACK_FREQUENCY_GROUND_MESH_TAPER_EASING_VERIFIED startCurve=smoothstep outerCurve=smoothstep zeroSlopeAtEnds=true sameSpan=true beaconStartFloor=0.5 outerTipFloor=0.06" not in final_log):
                print("WORLD_CAPTURE_FAIL: ground fissure must retain its beacon-side width while tapering outer tips smoothly",flush=True)
                return False
            visual_profile_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_VISUAL_PROFILE_VERIFIED glowScale=([-+0-9.eE]+) coreScale=([-+0-9.eE]+) edgeOffsetScale=([-+0-9.eE]+) edgeHalfWidth=([-+0-9.eE]+) pulseMin=([-+0-9.eE]+) pulseMax=([-+0-9.eE]+) coreInsideEdgeBand=(true|false)",
                final_log)
            expected_visual_profile=(6.25,1.5,2.6,.025,.045,.074)
            glow_width_bounds=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_GLOW_WIDTH_BOUNDS_VERIFIED min=([-+0-9.eE]+) max=([-+0-9.eE]+) halfBlockLimit=0.5 bounded=true",
                final_log)
            if (not glow_width_bounds
                    or not math.isclose(float(glow_width_bounds.group(1)),.28125,rel_tol=0.0,abs_tol=1.0e-9)
                    or not math.isclose(float(glow_width_bounds.group(2)),.4625,rel_tol=0.0,abs_tol=1.0e-9)
                    or "maximumGlowHalfWidth<.5D" not in camera_source):
                print("WORLD_CAPTURE_FAIL: widened ground halo exceeded its half-block footprint or was not verified",
                      flush=True)
                return False
            if (not visual_profile_match
                    or any(not math.isclose(float(visual_profile_match.group(i)),expected_visual_profile[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,7))
                    or visual_profile_match.group(7)!="true"):
                print("WORLD_CAPTURE_FAIL: broadened ground fissure visual profile self-check was not executed or is out of bounds",
                      flush=True)
                return False
            slope_width_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_SLOPE_WIDTH_PROFILE_VERIFIED reduction=([-+0-9.eE]+) flat=([-+0-9.eE]+) halfGrade=([-+0-9.eE]+) maxGrade=([-+0-9.eE]+) overGrade=([-+0-9.eE]+) minPulseHalfWidth=([-+0-9.eE]+) minEdgeHalfWidth=([-+0-9.eE]+) maxEdgeHalfWidth=([-+0-9.eE]+) minCoreEdgeGap=([-+0-9.eE]+) bounded=true monotonic=true cachedPerSegment=true invalidNeutral=true coreSeparated=true",
                final_log)
            expected_slope_width=(.15,1.0,.925,.85,.85,.03825,.0129222972972973,.025,.00782067770270272)
            if (not slope_width_match
                    or any(not math.isclose(float(slope_width_match.group(i)),expected_slope_width[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,10))
                    or "GROUND_SLOPE_WIDTH_REDUCTION=.15D;" not in camera_source
                    or "GROUND_EDGE_HALF_WIDTH=.025D;" not in camera_source
                    or "private static double groundSlopeWidthScale(GroundPoint a,GroundPoint b)" not in camera_source
                    or "private static double groundEdgeHalfWidth(double halfWidth)" not in camera_source
                    or "double coreSkewA,double coreSkewB,double slopeWidthScale)" not in camera_source
                    or "groundSlopeWidthScale(a,b)" not in camera_source
                    or "verifyGroundSlopeWidthProfile();" not in camera_source
                    or "halfWidth*=segment.slopeWidthScale();" not in camera_source
                    or "double edgeHalfWidth=groundEdgeHalfWidth(halfWidth);" not in camera_source
                    or "centerA+edgeHalfWidth" not in camera_source
                    or "centerB-edgeHalfWidth" not in camera_source):
                print("WORLD_CAPTURE_FAIL: slope-aware ground ribbon width was not bounded or cached per segment",
                      flush=True)
                return False
            cliff_taper_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_CLIFF_TAPER_PROFILE_VERIFIED taper=([-+0-9.eE]+) previousTaper=0.25 sharper=true invalidInputsZero=true",
                final_log)
            if (not cliff_taper_match
                    or not math.isclose(float(cliff_taper_match.group(1)),.06,rel_tol=0.0,abs_tol=1.0e-9)
                    or "CLIFF_BREAK_TAPER=.06D;" not in camera_source
                    or "cliffBreakTaper(Double.POSITIVE_INFINITY)==0.0D" not in camera_source
                    or "cliffBreakTaper(Double.NEGATIVE_INFINITY)==0.0D" not in camera_source):
                print("WORLD_CAPTURE_FAIL: visible cliff-end taper or non-finite input guards were not verified",flush=True)
                return False
            edge_width_profiles = {
                "GROUND_EDGE_HALF_WIDTH=.025D;": {
                    "embedded": (.28125,.4625,.0447525,.0996912972972973,.155733,.1708632,.0151302,.005,.010,.011,.015),
                },
            }
            edge_width_profile = next(
                (profile for declaration, profile in edge_width_profiles.items()
                 if declaration in camera_source),
                None,
            )
            if ("GROUND_GLOW_WIDTH_SCALE=6.25D;" not in camera_source
                    or "GROUND_CORE_WIDTH_SCALE=1.5D;" not in camera_source
                    or "GROUND_EDGE_OFFSET_SCALE=2.6D;" not in camera_source
                    or "GROUND_EDGE_OFFSET_SKEW_SCALE=.12D;" not in camera_source
                    or "GROUND_CORE_SKEW_AMPLITUDE=.15D;" not in camera_source
                    or edge_width_profile is None
                    or "GROUND_MIN_CORE_EDGE_GAP=.0075D;" not in camera_source
                    or "minimumCoreEdgeGap<GROUND_MIN_CORE_EDGE_GAP" not in camera_source
                    or "BLACK_FREQUENCY_GROUND_MESH_CORE_EDGE_GAP_VERIFIED min={} required={} valid=true" not in camera_source):
                print("WORLD_CAPTURE_FAIL: broadened ground fissure halo/core constants are not active",flush=True)
                return False
            relief_profile_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_SURFACE_RELIEF_VERIFIED coreRecess=([-+0-9.eE]+) filamentLift=([-+0-9.eE]+) edgeLift=([-+0-9.eE]+) coreClearance=([-+0-9.eE]+) glowClearance=([-+0-9.eE]+) filamentClearance=([-+0-9.eE]+) edgeClearance=([-+0-9.eE]+) ordered=true",
                final_log)
            expected_relief=(.005,.001,.005,.005,.010,.011,.015)
            if (not relief_profile_match
                    or any(not math.isclose(float(relief_profile_match.group(i)),expected_relief[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,8))):
                print("WORLD_CAPTURE_FAIL: layered ground fissure relief profile was not executed or is out of bounds",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_RECESSED_CORE_PROFILE_VERIFIED minClearance=0.005 supportTop=true depthTest=true" not in final_log:
                print("WORLD_CAPTURE_FAIL: near-surface fissure core clearance was not verified",flush=True)
                return False
            bevel_profile=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_BEVEL_PROFILE_VERIFIED width=([-+0-9.eE]+) lift=([-+0-9.eE]+) innerRecess=([-+0-9.eE]+) minOuterClearance=([-+0-9.eE]+) maxOuterClearance=([-+0-9.eE]+) maxOuterHalfWidth=([-+0-9.eE]+) maxGlowHalfWidth=([-+0-9.eE]+) withinGlow=true offsets=true invalidRejected=true",
                final_log)
            glow_scale_match=re.search(r"GROUND_GLOW_WIDTH_SCALE=([-+0-9.eE]+)D;",camera_source)
            expected_glow_half_width=(.074*float(glow_scale_match.group(1))
                                      if glow_scale_match else float("nan"))
            expected_bevel=(.045,.010,.005,.019,.021,.074*1.5*1.22*1.15+.045,expected_glow_half_width)
            if (not bevel_profile
                    or any(not math.isclose(float(bevel_profile.group(i)),expected_bevel[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,8))
                    or "private static final double GROUND_BEVEL_WIDTH=.045D;" not in camera_source
                    or "private static final double GROUND_BEVEL_LIFT=.010D;" not in camera_source
                    or "groundBevelOuterOffset(" not in camera_source):
                print("WORLD_CAPTURE_FAIL: beveled ground fissure cross-section was not verified",flush=True)
                return False
            bevel_pulse=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_BEVEL_PULSE_PROFILE_VERIFIED minScale=([-+0-9.eE]+) maxScale=([-+0-9.eE]+) midpointLeft=([-+0-9.eE]+) midpointRight=([-+0-9.eE]+) complementary=true bounded=true invalidRejected=true noExtraTrig=true",
                final_log)
            expected_bevel_pulse=(.9,1.1,1.0,1.0)
            bevel_lift_start=camera_source.find("private static double groundBevelLiftScale(")
            bevel_lift_end=camera_source.find("private static void bevelRibbon(",bevel_lift_start)
            bevel_lift_source=(camera_source[bevel_lift_start:bevel_lift_end]
                               if bevel_lift_start>=0 and bevel_lift_end>bevel_lift_start else "")
            if (not bevel_pulse
                    or any(not math.isclose(float(bevel_pulse.group(i)),expected_bevel_pulse[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,5))
                    or "GROUND_BEVEL_LIFT_PULSE_RANGE=.10D;" not in camera_source
                    or "double leftBevelLiftScale=groundBevelLiftScale(wave,true);" not in camera_source
                    or "double rightBevelLiftScale=groundBevelLiftScale(wave,false);" not in camera_source
                    or "double bevelLift=GROUND_BEVEL_LIFT*liftScale;" not in camera_source
                    or not bevel_lift_source
                    or "Math.sin(" in bevel_lift_source):
                print("WORLD_CAPTURE_FAIL: travelling bevel-lift pulse was not bounded, complementary, or allocation-free",flush=True)
                return False
            snow_offset_marker=(
                "BLACK_FREQUENCY_GROUND_MESH_SNOW_SURFACE_OFFSET_VERIFIED "
                "fullBlock=1.125 partialSupport=1.0625 supportIncluded=true invalidSnowFallback=true bounded=true"
            )
            if snow_offset_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: snow-layer fissure height did not follow the support collision top",flush=True)
                return False
            if ("double supportOffset=boundedShapeHeight(supportHeight,1.0D);" not in camera_source
                    or "return supportOffset+Math.min(1.0D,snowLayerHeight);" not in camera_source):
                print("WORLD_CAPTURE_FAIL: snow-layer offset ignores the support collision height",flush=True)
                return False
            if ("GROUND_SURFACE_CLEARANCE=.010D;" not in camera_source
                    or "GROUND_MIN_CORE_CLEARANCE=.005D;" not in camera_source
                    or "coreClearance<GROUND_MIN_CORE_CLEARANCE" not in camera_source
                    or "GROUND_CORE_RECESS=.005D;" not in camera_source
                    or "GROUND_FILAMENT_LIFT=.001D;" not in camera_source
                    or "GROUND_EDGE_LIFT=.005D;" not in camera_source
                    or "double groundY=candidateY-1+surfaceOffset+GROUND_SURFACE_CLEARANCE;" not in camera_source
                    or "return groundY;" not in camera_source
                    or "double offset,double halfWidth,double verticalOffset," not in camera_source
                    or camera_source.count("a.y()+verticalOffset")!=2
                    or camera_source.count("b.y()+verticalOffset")!=2
                    or "halfWidth*GROUND_GLOW_WIDTH_SCALE,0.0D," not in camera_source
                    or "coreRibbon(builder,matrix,camera,segment,halfWidth*GROUND_CORE_WIDTH_SCALE,-GROUND_CORE_RECESS," not in camera_source
                    or "halfWidth*.16D,GROUND_FILAMENT_LIFT," not in camera_source
                    or "GROUND_EDGE_HALF_WIDTH" not in camera_source
                    or "a.y()+GROUND_EDGE_LIFT" not in camera_source
                    or "b.y()+GROUND_EDGE_LIFT" not in camera_source
                    or camera_source.count("edgeRibbon(builder,matrix,camera,segment,halfWidth,true,")!=1
                    or camera_source.count("edgeRibbon(builder,matrix,camera,segment,halfWidth,false,")!=1):
                print("WORLD_CAPTURE_FAIL: ground fissure layers are not vertically separated above the sampled surface",flush=True)
                return False
            embedded_crack_profile=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_EMBEDDED_CRACK_PROFILE_VERIFIED minGlowHalfWidth=([-+0-9.eE]+) maxGlowHalfWidth=([-+0-9.eE]+) minCoreHalfWidth=([-+0-9.eE]+) minEdgeInnerHalfWidth=([-+0-9.eE]+) maxCoreHalfWidth=([-+0-9.eE]+) maxEdgeInnerHalfWidth=([-+0-9.eE]+) minCoreEdgeGap=([-+0-9.eE]+) coreClearance=([-+0-9.eE]+) glowClearance=([-+0-9.eE]+) filamentClearance=([-+0-9.eE]+) edgeClearance=([-+0-9.eE]+) surfaceAligned=true",
                final_log)
            expected_embedded=edge_width_profile["embedded"]
            if (not embedded_crack_profile
                    or any(not math.isclose(float(embedded_crack_profile.group(i)),expected_embedded[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,12))):
                print("WORLD_CAPTURE_FAIL: embedded ground-core wall and edge-clearance profile was not verified",flush=True)
                return False
            edge_palette_marker="BLACK_FREQUENCY_GROUND_MESH_EDGE_PALETTE_VERIFIED red=0.5 green=0.14 blue=0.9"
            if edge_palette_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: violet ground-edge palette self-check was not executed",flush=True)
                return False
            def read_ground_double(name):
                match=re.search(
                    rf"private static final double {re.escape(name)}=([-+0-9.eE]+)D;",
                    camera_source)
                if not match:
                    return None
                value=float(match.group(1))
                return value if math.isfinite(value) else None

            ground_edge_constants=tuple(read_ground_double(name) for name in (
                "GROUND_BASE_HALF_WIDTH",
                "GROUND_WAVE_HALF_WIDTH_RANGE",
                "GROUND_WIDTH_FLICKER",
                "GROUND_EDGE_OFFSET_SCALE",
                "GROUND_CORE_SKEW_AMPLITUDE",
                "GROUND_EDGE_OFFSET_SKEW_SCALE",
            ))
            if (any(value is None for value in ground_edge_constants)
                    or re.search(
                        r"GROUND_MAX_HALF_WIDTH=\s*GROUND_BASE_HALF_WIDTH\+GROUND_WAVE_HALF_WIDTH_RANGE\+GROUND_WIDTH_FLICKER;",
                        camera_source) is None):
                print("WORLD_CAPTURE_FAIL: ground edge-skew constants could not be derived from the active Java profile",
                      flush=True)
                return False
            (base_half_width,wave_half_width_range,width_flicker,edge_offset_scale,
             core_skew_amplitude,edge_offset_skew_scale)=ground_edge_constants
            maximum_half_width=base_half_width+wave_half_width_range+width_flicker
            expected_max_offset_delta=(maximum_half_width*edge_offset_scale
                                        *core_skew_amplitude*edge_offset_skew_scale)
            expected_edge_skew=(edge_offset_skew_scale,
                                core_skew_amplitude*edge_offset_skew_scale,
                                expected_max_offset_delta)
            edge_skew_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_EDGE_OFFSET_ASYMMETRY_VERIFIED scale=([-+0-9.eE]+) maxSkew=([-+0-9.eE]+) maxOffsetDelta=([-+0-9.eE]+) correlatedWithCore=true bounded=true",
                final_log)
            if (not edge_skew_match
                    or any(not math.isclose(float(edge_skew_match.group(i)),expected_edge_skew[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,4))
                    or "private static double groundEdgeCenterOffset(double halfWidth,double coreSkew,boolean left)" not in camera_source
                    or "private static double groundEdgeMaximumOffsetDelta(double halfWidth)" not in camera_source
                    or "groundEdgeMaximumOffsetDelta(halfWidth)" not in camera_source
                    or "groundEdgeMaximumOffsetDelta(maximumHalfWidth)" not in camera_source
                    or "groundEdgeMaximumOffsetDelta(Double.NaN)==0.0D" not in camera_source
                    or "groundEdgeCenterOffset(halfWidth,segment.coreSkewA(),left)" not in camera_source
                    or "groundEdgeCenterOffset(halfWidth,segment.coreSkewB(),left)" not in camera_source
                    or "private static void edgeRibbon(" not in camera_source
                    or camera_source.count("edgeRibbon(builder,matrix,camera,segment,halfWidth,true,")!=1
                    or camera_source.count("edgeRibbon(builder,matrix,camera,segment,halfWidth,false,")!=1):
                print("WORLD_CAPTURE_FAIL: asymmetric violet edge rails are not tied to the bounded core skew",flush=True)
                return False
            edge_interpolation_match=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_EDGE_ENDPOINT_INTERPOLATION_VERIFIED maxOffsetDelta=([-+0-9.eE]+) sharedBoundary=true invalidRejected=true",
                final_log)
            if (not edge_interpolation_match
                    or not math.isclose(float(edge_interpolation_match.group(1)),expected_max_offset_delta,
                                        rel_tol=0.0,abs_tol=1.0e-9)):
                print("WORLD_CAPTURE_FAIL: violet edge rails do not interpolate their skew continuously at segment endpoints",flush=True)
                return False
            distance_fade_profile=re.search(
                r"BLACK_FREQUENCY_TEST GROUND_MESH_DISTANCE_FADE_VERIFIED start=48 end=64 curve=quintic quarter=([-+0-9.eE]+) midpoint=([-+0-9.eE]+) threeQuarter=([-+0-9.eE]+) c2Endpoints=true monotonic=true invalidRejected=true",
                final_log)
            expected_distance_fade=(.896484375,.5,.103515625)
            distance_fade_start=camera_source.find("private static double groundDistanceFade(double distanceSquared)")
            distance_fade_end=camera_source.find("private static double groundPointDistanceFade",distance_fade_start)
            distance_fade_source=(camera_source[distance_fade_start:distance_fade_end]
                                  if distance_fade_start>=0 and distance_fade_end>distance_fade_start else "")
            if (not distance_fade_profile
                    or any(not math.isclose(float(distance_fade_profile.group(i)),expected_distance_fade[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,4))
                    or "return 1.0D-smootherstep01(t);" not in distance_fade_source):
                print("WORLD_CAPTURE_FAIL: ground render-envelope fade is not C2 quintic or its profile changed",flush=True)
                return False
            endpoint_distance_fade=re.search(
                r"BLACK_FREQUENCY_TEST GROUND_MESH_ENDPOINT_DISTANCE_FADE_VERIFIED metric=per-endpoint start=48 end=64 near=([-+0-9.eE]+) mid=([-+0-9.eE]+) crossing=([-+0-9.eE]+) crossingEnd=([-+0-9.eE]+) far=([-+0-9.eE]+) invalidRejected=true monotonic=true",
                final_log)
            expected_endpoint_fade=(1.0,.6785021465821046,.0031918912249969678,0.0,0.0)
            if (not endpoint_distance_fade
                    or any(not math.isclose(float(endpoint_distance_fade.group(i)),expected_endpoint_fade[i-1],
                                             rel_tol=0.0,abs_tol=1.0e-9)
                           for i in range(1,6))
                    or "private static double groundPointDistanceFade(GroundPoint point,Vec3 camera)" not in camera_source
                    or "private static double groundSegmentDistanceFade(GroundSegment segment,Vec3 camera)" not in camera_source
                    or "double segmentFadeA,segmentFadeB;" not in camera_source
                     or "segmentFadeA=firstVisibleFadeA;" not in camera_source
                     or "segmentFadeB=firstVisibleFadeB;" not in camera_source
                     or "segmentFadeA=groundPointDistanceFade(segment.a(),camera);" not in camera_source
                    or "segmentFadeB=groundPointDistanceFade(segment.b(),camera);" not in camera_source
                    or "float segmentStrengthA=boundedStrength*(float)segmentFadeA;" not in camera_source
                    or "float segmentStrengthB=boundedStrength*(float)segmentFadeB;" not in camera_source
                    or "int glowAlphaChannelA=colorChannel(alphaA*GROUND_GLOW_ALPHA);" not in camera_source
                    or "int glowAlphaChannelB=colorChannel(alphaB*GROUND_GLOW_ALPHA);" not in camera_source
                    or "int coreAlphaChannelA=colorChannel(alphaA*.94F);" not in camera_source
                    or "int coreAlphaChannelB=colorChannel(alphaB*.94F);" not in camera_source
                    or "int filamentAlphaChannelA=colorChannel(alphaA*corePulse);" not in camera_source
                    or "int filamentAlphaChannelB=colorChannel(alphaB*corePulse);" not in camera_source
                    or "int edgeAlphaChannelA=colorChannel(edgeAlphaA);" not in camera_source
                    or "int edgeAlphaChannelB=colorChannel(edgeAlphaB);" not in camera_source
                    or "float boundedStrength=(float)Math.min(1.0F,strength);" not in camera_source):
                print("WORLD_CAPTURE_FAIL: per-endpoint ground fissure fade is not verified across every ribbon layer",flush=True)
                return False
            empty_guard_index=camera_source.find("if(firstVisibleSegmentIndex<0) return;",prefix_start)
            ground_buffer_index=camera_source.find(
                "BufferBuilder builder=Tesselator.getInstance().getBuilder();",prefix_start)
            if ("BLACK_FREQUENCY_TEST GROUND_MESH_EMPTY_BUFFER_GUARD_VERIFIED nearVisible=true crossingVisible=true farHidden=true invalidCameraHidden=true" not in final_log
                    or "private static boolean hasVisibleGroundSegment(List<GroundSegment> segments,Vec3 camera)" not in camera_source
                    or empty_guard_index<0
                    or ground_buffer_index<0
                    or empty_guard_index>ground_buffer_index):
                print("WORLD_CAPTURE_FAIL: empty ground-mesh guard must return before BufferBuilder allocation",flush=True)
                return False
            edge_rail_pattern = re.compile(
                r"edgeRibbon\(builder,matrix,camera,segment,halfWidth,(true|false),\s*"
                r"GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,\s*"
                r"edgeAlphaChannelA,edgeAlphaChannelB\);"
            )
            edge_rail_sides = edge_rail_pattern.findall(camera_source)
            if ("private static final float GROUND_EDGE_RED=.50F;" not in camera_source
                    or "private static final float GROUND_EDGE_GREEN=.14F;" not in camera_source
                    or "private static final float GROUND_EDGE_BLUE=.90F;" not in camera_source
                    or edge_width_profile is None
                    or "private static void edgeRibbon(" not in camera_source
                    or "double edgeHalfWidth=groundEdgeHalfWidth(halfWidth);" not in camera_source
                     or "(centerA+edgeHalfWidth)*segment.taperA()" not in camera_source
                     or "(centerB-edgeHalfWidth)*segment.taperB()" not in camera_source
                    or "GROUND_EDGE_LIFT" not in camera_source
                    or sorted(edge_rail_sides) != ["false", "true"]):
                print("WORLD_CAPTURE_FAIL: saturated violet palette is not bound to both endpoint-interpolated edge rails",flush=True)
                return False
            if ("private static final double GROUND_MIN_HALF_WIDTH=.045D;" not in camera_source
                    or "GROUND_BASE_HALF_WIDTH=.052D;" not in camera_source
                    or "GROUND_WAVE_HALF_WIDTH_RANGE=.017D;" not in camera_source
                    or "GROUND_WIDTH_FLICKER=.005D;" not in camera_source
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
            empty_rebuild_match=re.search(r"EMPTY_MESH_REBUILD_INTERVAL\s*=\s*(\d+)L",rebuild_source)
            if not empty_rebuild_match:
                print("WORLD_CAPTURE_FAIL: empty-mesh rebuild backoff is missing",flush=True)
                return False
            empty_rebuild_interval=int(empty_rebuild_match.group(1))
            if not rebuild_interval<empty_rebuild_interval<=80 or empty_rebuild_interval%rebuild_interval:
                print(f"WORLD_CAPTURE_FAIL: empty-mesh rebuild backoff is unsafe: {empty_rebuild_interval}",flush=True)
                return False
            rebuild_marker=f"BLACK_FREQUENCY_GROUND_MESH_REBUILD_PROFILE_VERIFIED verticalDelta=2 interval={rebuild_interval}"
            if rebuild_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: player-height cache invalidation self-check was not executed",flush=True)
                return False
            empty_rebuild_marker=(f"BLACK_FREQUENCY_GROUND_MESH_EMPTY_REBUILD_BACKOFF_VERIFIED "
                                 f"populatedInterval={rebuild_interval} emptyInterval={empty_rebuild_interval} "
                                 f"ratio={empty_rebuild_interval//rebuild_interval} immediateInvalidation=true")
            if empty_rebuild_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: empty-mesh retry backoff self-check was not executed",flush=True)
                return False
            if ("long interval=hasSegments ? REBUILD_INTERVAL : EMPTY_MESH_REBUILD_INTERVAL;" not in rebuild_source
                    or "!cachedSegments.isEmpty()" not in rebuild_source):
                print("WORLD_CAPTURE_FAIL: empty-mesh backoff is not selected from the cached mesh state",flush=True)
                return False
            overflow_marker=("BLACK_FREQUENCY_TEST GROUND_MESH_REBUILD_LONG_OVERFLOW_GUARD_VERIFIED "
                             "forwardExtreme=true nearMaxBefore=true nearMaxAt=true negativeClock=true backwardReset=true")
            if overflow_marker not in final_log:
                print("WORLD_CAPTURE_FAIL: ground mesh rebuild interval overflow guard was not verified",flush=True)
                return False
            if ("previousTick<=Long.MAX_VALUE-interval" not in rebuild_source
                    or "tick>=previousTick+interval" not in rebuild_source
                    or "tick-previousTick>=interval" in rebuild_source):
                print("WORLD_CAPTURE_FAIL: ground mesh rebuild interval is not overflow-safe",flush=True)
                return False
            print(f"WORLD_CAPTURE: empty ground meshes retry every {empty_rebuild_interval} ticks; populated meshes every {rebuild_interval}",flush=True)
            if os.environ.get("BF_TEST_RESTORATION")=="1":
                # A full fracture can reach 95,000 snapshots. Recovery restores at
                # most 12 blocks per 20 TPS tick (~396s at the cap); leave headroom
                # for client capture and the final stabilization hold. Do not raise
                # the gameplay restore budget or force-load chunks to satisfy CI.
                deadline_restore=time.time()+480
                restored=False
                restoration_capture_attempted_markers=set()
                deferred_restore_chunks_seen=set()
                deferred_restore_chunks_reentered=set()
                deferred_restore_deferred_counts={}
                deferred_restore_reentry_counts={}
                deferred_probe_player_moved=False
                deferred_restore_chunk_re=DEFERRED_RESTORE_CHUNK_RE
                while time.time()<deadline_restore and p.poll() is None:
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                    try_beacon_click(current)
                    # Capture the real beacon pulse first. Then move beyond the fracture
                    # plus the integrated server's simulation-distance halo. Per-chunk
                    # return teleports re-enter only the chunks the player actually visits.
                    if (deferred_chunk_probe and not deferred_probe_player_moved
                            and restoration_shot
                            and "BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED" in current):
                        # Keep the remote player alive while waiting for chunk tickets to expire.
                        # Creative is temporary; Survival is restored before the real beacon break.
                        if not send_isolated_chat_command(
                                "/gamemode creative @s",
                                r"Set own game mode to Creative Mode",
                                "enter creative mode for safe deferred travel"):
                            return False
                        if not send_isolated_chat_command(
                                f"/tp @s ~{DEFERRED_PLAYER_TRAVEL_DISTANCE} 120 ~",
                                r"Teleported .* to ",
                                "leave the fracture and unload its recovery chunks"):
                            return False
                        deferred_probe_player_moved=True
                        print("WORLD_CAPTURE: player left the fracture to trigger natural chunk unload",
                              "loadedOnly=true",flush=True)
                        time.sleep(.25)
                        current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else current
                    if os.environ.get("BF_ISOLATED_TEST")=="1":
                        deferred_events={}
                        anchor_match=FINALE_ANCHOR_RE.search(current)
                        for deferred in deferred_restore_chunk_re.finditer(current):
                            x,y,z,chunk_x,chunk_z=map(int,deferred.groups())
                            if anchor_match is None:
                                print("WORLD_CAPTURE_FAIL: deferred snapshot has no verified fracture anchor",
                                      flush=True)
                                return False
                            anchor_x,anchor_z=map(int,anchor_match.groups())
                            if not is_valid_deferred_restore_chunk_event(
                                    x,z,chunk_x,chunk_z,anchor_x,anchor_z,DEFERRED_RESTORE_RADIUS):
                                print("WORLD_CAPTURE_FAIL: deferred snapshot escaped the original circular fracture",
                                      {"x":x,"y":y,"z":z,"chunkX":chunk_x,"chunkZ":chunk_z,
                                       "anchorX":anchor_x,"anchorZ":anchor_z},
                                      flush=True)
                                return False
                            chunk_key=(chunk_x,chunk_z)
                            deferred_events[chunk_key]=deferred_events.get(chunk_key,0)+1
                        for chunk_key,total_events in deferred_events.items():
                            processed_events=deferred_restore_deferred_counts.get(chunk_key,0)
                            if processed_events>=total_events:
                                continue
                            event_number=processed_events+1
                            deferred_restore_deferred_counts[chunk_key]=event_number
                            chunk_x,chunk_z=chunk_key
                            reentered_marker=(
                                "BLACK_FREQUENCY_TEST RESTORE_UNLOADED_CHUNK_REENTERED "
                                f"chunkX={chunk_x} chunkZ={chunk_z} loadedOnly=true")
                            observed_reentries=current.count(reentered_marker)
                            if observed_reentries>=event_number:
                                deferred_restore_chunks_seen.add(chunk_key)
                                deferred_restore_chunks_reentered.add(chunk_key)
                                deferred_restore_reentry_counts[chunk_key]=observed_reentries
                                continue
                            target_x=chunk_x*16+8
                            target_z=chunk_z*16+8
                            if not send_isolated_chat_command(
                                    f"/tp @s {target_x} 120 {target_z}",
                                    r"Teleported .* to ",
                                    f"load deferred recovery chunk {chunk_x},{chunk_z}"):
                                return False
                            deferred_restore_chunks_seen.add(chunk_key)
                            reentry_deadline=time.monotonic()+12.0
                            reentered=False
                            while time.monotonic()<reentry_deadline and p.poll() is None:
                                current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else current
                                observed_reentries=current.count(reentered_marker)
                                if observed_reentries>=event_number:
                                    deferred_restore_chunks_reentered.add(chunk_key)
                                    deferred_restore_reentry_counts[chunk_key]=observed_reentries
                                    reentered=True
                                    break
                                if "BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED" in current:
                                    break
                                time.sleep(0.1)
                            if not reentered:
                                print("WORLD_CAPTURE_FAIL: deferred recovery chunk did not emit a loaded-only re-entry",
                                      chunk_x,chunk_z,flush=True)
                                return False
                            print("WORLD_CAPTURE: player movement loaded deferred recovery chunk",
                                  chunk_x,chunk_z,"loadedOnly=true",flush=True)
                    initial_pulse_marker="BLACK_FREQUENCY_TEST RESTORE_INITIAL_BEACON_PULSE_EMITTED ring=24 sparks=4 viewer=true"
                    stabilization_pulse_marker="BLACK_FREQUENCY_TEST BEACON_STABILIZATION_PULSE viewerScan=shared"
                    aura_restoration_marker="BLACK_FREQUENCY_TEST BEACON_AURA_RESTORATION_ACTIVE_RENDERED progress="
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
                    if deferred_chunk_probe:
                        final_log=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                        safe_pad=re.search(
                            r"BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT "
                            r"x=(-?\d+) y=(-?\d+) z=(-?\d+) support=solid fallDistance=0\.0",
                            final_log)
                        if not safe_pad:
                            print("WORLD_CAPTURE_FAIL: safe recovery pad coordinates are missing after deferred probe",
                                  flush=True)
                            return False
                        pad_x,pad_y,pad_z=map(int,safe_pad.groups())
                        if not send_isolated_chat_command(
                                f"/tp @s {pad_x} {pad_y} {pad_z}",
                                r"Teleported .* to ",
                                "return to physical recovery beacon pad before final validation"):
                            return False
                        if not send_isolated_chat_command(
                                "/gamemode survival @s",
                                r"Set own game mode to Survival Mode",
                                "restore survival mode before physical beacon break"):
                            return False
                        print("WORLD_CAPTURE: returned to physical beacon pad before deferred-probe validation",
                              pad_x,pad_y,pad_z,flush=True)
                        time.sleep(0.25)
                        final_log=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else final_log
                        creative_mode_at=final_log.rfind("Set own game mode to Creative Mode")
                        survival_mode_at=final_log.rfind("Set own game mode to Survival Mode")
                        if creative_mode_at<0 or survival_mode_at<=creative_mode_at:
                            print("WORLD_CAPTURE_FAIL: deferred travel did not restore Survival after Creative mode",flush=True)
                            return False
                        if "fell from a high place" in final_log[creative_mode_at:survival_mode_at]:
                            print("WORLD_CAPTURE_FAIL: deferred travel player died before returning to Survival",flush=True)
                            return False
                        if "BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_READY_FOR_REAL_PLAYER" not in final_log:
                            print("WORLD_CAPTURE_FAIL: post-restoration beacon was not exposed for the real player test",
                                  flush=True)
                            return False
                        if "BLACK_FREQUENCY_TEST RESTORE_SCAN_BUDGET_SELECTED value=1 deferredClient=true" not in final_log:
                            print("WORLD_CAPTURE_FAIL: deferred recovery did not use the slow client-only scan budget",flush=True)
                            return False
                        if f"BLACK_FREQUENCY_TEST RESTORE_SCAN_WARMUP_SELECTED ticks={DEFERRED_RESTORE_WARMUP_TICKS} deferredClient=true" not in final_log:
                            print("WORLD_CAPTURE_FAIL: deferred recovery did not wait for natural chunk-ticket expiry",flush=True)
                            return False
                        readiness=DEFERRED_CHUNK_READINESS_RE.search(final_log)
                        if not readiness:
                            print("WORLD_CAPTURE_FAIL: deferred probe did not report loaded-only chunk readiness",flush=True)
                            return False
                        (readiness_ticks,unique_chunks,loaded_chunks,unloaded_chunks,
                         player_chunk_x,player_chunk_z,readiness_anchor_chunk_x,readiness_anchor_chunk_z,
                         spawn_chunk_x,spawn_chunk_z)=map(int,readiness.groups())
                        anchor_match=FINALE_ANCHOR_RE.search(final_log)
                        if not anchor_match:
                            print("WORLD_CAPTURE_FAIL: deferred readiness has no verified fracture anchor",flush=True)
                            return False
                        anchor_x,anchor_z=map(int,anchor_match.groups())
                        anchor_chunk_x,anchor_chunk_z=anchor_x>>4,anchor_z>>4
                        if deferred_initial_spawn_chunk is None:
                            print("WORLD_CAPTURE_FAIL: original spawn-ticket center was not captured before the remote anchor move",flush=True)
                            return False
                        initial_spawn_chunk_x,initial_spawn_chunk_z=deferred_initial_spawn_chunk
                        if (readiness_ticks!=DEFERRED_RESTORE_WARMUP_TICKS
                                or unique_chunks<1 or loaded_chunks+unloaded_chunks!=unique_chunks
                                or unloaded_chunks<1):
                            print("WORLD_CAPTURE_FAIL: no valid recovery chunk unloaded naturally before scanning",
                                  {"ticks":readiness_ticks,"unique":unique_chunks,
                                   "loaded":loaded_chunks,"unloaded":unloaded_chunks},flush=True)
                            return False
                        if ((anchor_chunk_x,anchor_chunk_z)!=(DEFERRED_CLIENT_ANCHOR_X>>4,DEFERRED_CLIENT_ANCHOR_Z>>4)
                                or (readiness_anchor_chunk_x,readiness_anchor_chunk_z)!=(anchor_chunk_x,anchor_chunk_z)):
                            print("WORLD_CAPTURE_FAIL: fracture anchor changed from the verified remote chunk",
                                  {"anchor":(anchor_chunk_x,anchor_chunk_z),
                                   "readinessAnchor":(readiness_anchor_chunk_x,readiness_anchor_chunk_z)},flush=True)
                            return False
                        if max(abs(anchor_chunk_x-initial_spawn_chunk_x),abs(anchor_chunk_z-initial_spawn_chunk_z))<=DEFERRED_INITIAL_SPAWN_TICKET_RADIUS:
                            print("WORLD_CAPTURE_FAIL: original spawn-ticket halo still overlaps the fracture anchor",
                                  {"initialSpawn":(initial_spawn_chunk_x,initial_spawn_chunk_z),
                                   "anchor":(anchor_chunk_x,anchor_chunk_z)},flush=True)
                            return False
                        if max(abs(player_chunk_x-anchor_chunk_x),abs(player_chunk_z-anchor_chunk_z))<=DEFERRED_SIMULATION_DISTANCE:
                            print("WORLD_CAPTURE_FAIL: player chunk remained inside the simulation-distance halo",
                                  {"player":(player_chunk_x,player_chunk_z),"anchor":(anchor_chunk_x,anchor_chunk_z)},flush=True)
                            return False
                        if max(abs(spawn_chunk_x-anchor_chunk_x),abs(spawn_chunk_z-anchor_chunk_z))<=DEFERRED_INITIAL_SPAWN_TICKET_RADIUS:
                            print("WORLD_CAPTURE_FAIL: relocated shared spawn remained inside the fracture's chunk halo",
                                  {"spawn":(spawn_chunk_x,spawn_chunk_z),"anchor":(anchor_chunk_x,anchor_chunk_z)},flush=True)
                            return False
                        if max(abs(spawn_chunk_x-initial_spawn_chunk_x),abs(spawn_chunk_z-initial_spawn_chunk_z))<=DEFERRED_INITIAL_SPAWN_TICKET_RADIUS:
                            print("WORLD_CAPTURE_FAIL: original and relocated spawn regions overlap unexpectedly",
                                  {"initialSpawn":(initial_spawn_chunk_x,initial_spawn_chunk_z),
                                   "spawn":(spawn_chunk_x,spawn_chunk_z)},flush=True)
                            return False
                        print("WORLD_CAPTURE: natural chunk-unload readiness verified",
                              {"uniqueChunks":unique_chunks,"loaded":loaded_chunks,
                               "unloaded":unloaded_chunks,"playerChunk":(player_chunk_x,player_chunk_z),
                               "spawnChunk":(spawn_chunk_x,spawn_chunk_z)},flush=True)
                        unresolved_deferred_chunks={
                            key:(count,deferred_restore_reentry_counts.get(key,0))
                            for key,count in deferred_restore_deferred_counts.items()
                            if deferred_restore_reentry_counts.get(key,0)<count
                        }
                        if (not deferred_shared_spawn_relocated
                                or not deferred_probe_player_moved
                                or not deferred_restore_chunks_seen
                                or not deferred_restore_chunks_reentered
                                or unresolved_deferred_chunks):
                            print("WORLD_CAPTURE_FAIL: low-distance probe did not re-enter every deferred valid snapshot chunk",
                                  unresolved_deferred_chunks,flush=True)
                            return False
                        if "BLACK_FREQUENCY_TEST RESTORE_UNLOADED_CHUNK_HINT_SENT" not in final_log:
                            print("WORLD_CAPTURE_FAIL: deferred recovery did not send the player-facing wait hint",
                                  flush=True)
                            return False
                        print("WORLD_CAPTURE: valid unloaded recovery chunks re-entered by player travel",
                              len(deferred_restore_chunks_reentered),"loadedOnly=true",flush=True)
                    # The server sends the inactive state before the completion overlay.
                    # Require one real client render from the short aura fade, even if
                    # the server consumes the physical beacon immediately afterward.
                    fade_start_marker="BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_RENDERED"
                    fade_midpoint_marker="BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_MIDPOINT_RENDERED"
                    fade_tail_marker="BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_TAIL_RENDERED"
                    # Green Phase uses llvmpipe in CI; a slow render after the midpoint
                    # screenshot can otherwise consume the entire five-second wall-clock
                    # window before the client reaches the visible tail. Keep the game focused
                    # (singleplayer pauses on focus loss) and allow bounded renderer headroom.
                    if not focus_game():
                        print("WORLD_CAPTURE_FAIL: Minecraft lost focus before beacon fade tail capture",
                              flush=True)
                        return False
                    fade_deadline=time.monotonic()+12.0
                    fade_midpoint_shot=False
                    fade_tail_shot=False
                    fade_logs=""
                    while time.monotonic()<fade_deadline and p.poll() is None:
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
                        r"BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_RENDERED ticksRemaining=(\d+) cageScale=([0-9.eE+-]+)",
                        fade_logs)
                    midpoint_match=re.search(
                        r"BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_MIDPOINT_RENDERED ticksRemaining=(\d+) alphaFactor=([0-9.eE+-]+) cageScale=([0-9.eE+-]+)",
                        fade_logs)
                    tail_match=re.search(
                        r"BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_TAIL_RENDERED ticksRemaining=(\d+) alphaFactor=([0-9.eE+-]+) cageScale=([0-9.eE+-]+)",
                        fade_logs)
                    if not (fade_start_marker in fade_logs and fade_midpoint_shot and fade_tail_shot
                            and start_match and midpoint_match and tail_match):
                        print("WORLD_CAPTURE_FAIL: completed restoration did not render start, midpoint and tail of the beacon fade",flush=True)
                        return False
                    start_ticks=int(start_match.group(1))
                    start_scale=float(start_match.group(2))
                    midpoint_ticks=int(midpoint_match.group(1))
                    midpoint_alpha=float(midpoint_match.group(2))
                    midpoint_scale=float(midpoint_match.group(3))
                    tail_ticks=int(tail_match.group(1))
                    tail_alpha=float(tail_match.group(2))
                    tail_scale=float(tail_match.group(3))
                    # The first render callback can arrive a few client ticks after the
                    # server-state transition. Validate the observed scale against the
                    # exact partial-tick easing interval instead of assuming partialTick=0.
                    bounded_start_ticks=max(0,min(16,start_ticks))
                    def start_scale_at(partial_tick):
                        remaining=max(0.0,min(1.0,(bounded_start_ticks-partial_tick)/16.0))
                        # Mirror RiftClientState.smootherstep01: the production fade is quintic,
                        # not the legacy cubic smoothstep used by the original capture check.
                        fade=remaining*remaining*remaining*(remaining*(remaining*6.0-15.0)+10.0)
                        return .55+.45*fade
                    expected_start_scale_min=start_scale_at(1.0)
                    expected_start_scale_max=start_scale_at(0.0)
                    if not (start_ticks>=12 and start_ticks>midpoint_ticks>tail_ticks
                            and expected_start_scale_min-1.0e-6<=start_scale<=expected_start_scale_max+1.0e-6
                            and .25<midpoint_alpha<=.60
                            and .65<=midpoint_scale<=.83
                            and 1.0/255.0<tail_alpha<=.12
                            and .55<=tail_scale<=.605):
                        print("WORLD_CAPTURE_FAIL: beacon fade milestones or cage collapse scale were outside their expected windows",
                              {"startTicks":start_ticks,"startScale":start_scale,
                               "midpointTicks":midpoint_ticks,"midpointAlpha":midpoint_alpha,
                               "midpointScale":midpoint_scale,"tailTicks":tail_ticks,
                               "tailAlpha":tail_alpha,"tailScale":tail_scale},flush=True)
                        return False
                    print("WORLD_CAPTURE: real beacon fade midpoint and visible tail captured",
                          {"startTicks":start_ticks,"midpointTicks":midpoint_ticks,
                           "midpointAlpha":midpoint_alpha,"tailTicks":tail_ticks,
                           "tailAlpha":tail_alpha},flush=True)
                    # Wait for the explicit title fade-in window so this frame proves
                    # the final UI, not 99% or the preceding containment effect.
                    time.sleep(0.75)
                    snap("beacon-restoration-finished.png")
                    print("WORLD_CAPTURE: restoration fade-out and action-bar-only final frame captured",flush=True)
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
                    finished_overlay_metrics=measure_completion_overlay(finished_frame)
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
                if completion_overlay_visible(finished_overlay_metrics,finished_frame.size[0]):
                    print("WORLD_CAPTURE_FAIL: centered completion overlay still covers the physical beacon",
                          finished_overlay_metrics,flush=True)
                    return False
                print("WORLD_CAPTURE: completion overlay absent; physical beacon unobstructed",
                      finished_overlay_metrics,flush=True)
                if not terrain_shot:
                    print("WORLD_CAPTURE_FAIL: no player-visible ground fissure was captured",flush=True)
                    return False
                print("WORLD_CAPTURE: restoration finished in integrated client",flush=True)
            log_after=read_client_log()
            canonical_snapshot_marker=("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_BLOCK_STATE_CANONICAL_ROUNDTRIP_VERIFIED "
                                       "valid=true invalidPropertyValue=true unknownProperty=true unknownBlock=true")
            if canonical_snapshot_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: recovery accepted a non-canonical saved block state",flush=True)
                return False
            list_corruption_marker=("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_LIST_CORRUPTION_PARTIAL_STATUS_VERIFIED "
                                    "activeRecovery=true invalidContainers=2 restored=0 partialMessage=true persisted=true")
            if list_corruption_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: corrupt recovery snapshot container was not reported as incomplete",flush=True)
                return False
            counter_persistence_marker=("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_CORRUPTION_COUNTER_PERSISTENCE_VERIFIED "
                                        "previousWarning=true incremented=true dirty=true roundTrip=true")
            if counter_persistence_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: a later corrupt snapshot container was not persisted after the one-shot warning",flush=True)
                return False
            counter_normalization_marker=("BLACK_FREQUENCY_TEST CORRUPT_STATE_COUNTERS_PERSISTED "
                                          "negative=true excessive=true wrongType=true legacyMissingAllowed=true")
            if counter_normalization_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: malformed saved counters were clamped in memory but not persisted",flush=True)
                return False
            if os.environ.get("BF_TEST_NATURAL_BEACON")=="1":
                if not mine_beacon_after_restoration(log_after):
                    return False
                log_after=read_client_log(log_after)
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
            snapshot_entry_size_marker="BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_ENTRY_SIZE_BOUND_VERIFIED "
            if os.environ.get("BF_TEST_RESTORATION")=="1" and snapshot_entry_size_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: per-entry recovery snapshot size bound was not verified",flush=True)
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
            aperiodic_anomaly_marker="BLACK_FREQUENCY_TEST ANOMALY_FIELD_APERIODIC_TURBULENCE_VERIFIED"
            if os.environ.get("BF_TEST_RESTORATION")=="1" and aperiodic_anomaly_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: deterministic aperiodic gravity turbulence was not verified",flush=True)
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
                reach_marker="BLACK_FREQUENCY_TEST BEACON_BREAK_REACH_AABB_VERIFIED boundary=20.25 axes=3 negativeCoordinates=true outsideRejected=true invalidRejected=true"
                if reach_marker not in log_after:
                    print("WORLD_CAPTURE_FAIL: Java AABB reach boundary probe did not execute in the Forge client",flush=True)
                    return False
                interaction_guard_marker=("BLACK_FREQUENCY_TEST BEACON_INTERACTION_GUARD_VERIFIED "
                    "maxDistance=4.5 maxDistanceSquared=20.25 eyeToAabb=true strictBoundary=true remote64Rejected=true invalidRejected=true")
                if interaction_guard_marker not in log_after:
                    print("WORLD_CAPTURE_FAIL: beacon activation did not enforce the real eye-to-AABB reach boundary",flush=True)
                    return False
                break_guard_marker=("BLACK_FREQUENCY_TEST NATURAL_BEACON_BREAK_REACH_GUARD_VERIFIED "
                    "exactBoundary=true outsideRejected=true invalidRejected=true eventGuard=true")
                if break_guard_marker not in log_after:
                    print("WORLD_CAPTURE_FAIL: server break event did not enforce the shared beacon reach guard",flush=True)
                    return False
                build_permission_marker=("BLACK_FREQUENCY_TEST REAL_PLAYER_ADVENTURE_BEACON_BREAK_REJECTED "
                    "mayBuild=false blockPreserved=true ownershipPreserved=true")
                synthetic_skip_marker="BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_SYNTHETIC_SKIPPED_UNLOADED loadedOnly=true"
                if (build_permission_marker not in log_after
                        and not (deferred_chunk_probe and synthetic_skip_marker in log_after)):
                    print("WORLD_CAPTURE_FAIL: adventure-mode synthetic break was neither verified nor safely deferred",flush=True)
                    return False
                initial_pulse=re.search(
                    r"BLACK_FREQUENCY_TEST RESTORE_INITIAL_BEACON_PULSE_EMITTED ring=(\d+) sparks=(\d+) viewer=true",
                    log_after)
                if not initial_pulse or tuple(map(int,initial_pulse.groups()))!=(24,4):
                    print("WORLD_CAPTURE_FAIL: the real beacon click did not emit its initial ring and rising sparks",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST RESTORATION_COMPLETION_ACTIONBAR_ONLY_VERIFIED success=true partial=true titlePackets=false" not in log_after:
                    print("WORLD_CAPTURE_FAIL: recovery completion action bar or no-title contract was not verified",flush=True)
                    return False
                counts=re.search(r"BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED restored=([0-9]+) destroyed=([0-9]+) skippedOccupied=([0-9]+) skippedInvalid=([0-9]+)",log_after)
                if not counts:
                    print("WORLD_CAPTURE_FAIL: missing final restoration accounting",flush=True)
                    return False
                restored_count,damaged_count,skipped_occupied_count,skipped_invalid_count=map(int,counts.groups())
                skipped_count=skipped_occupied_count+skipped_invalid_count
                if restored_count+skipped_count!=damaged_count:
                    print("WORLD_CAPTURE_FAIL: unaccounted damaged blocks",restored_count,skipped_count,damaged_count,flush=True)
                    return False
                actionbar_match=re.search(
                    r"BLACK_FREQUENCY_TEST RESTORATION_COMPLETION_ACTIONBAR_SENT restored=([0-9]+) skippedOccupied=([0-9]+) skippedInvalid=([0-9]+) player=true",
                    log_after)
                if not actionbar_match:
                    print("WORLD_CAPTURE_FAIL: the real player did not receive the completion action-bar message",flush=True)
                    return False
                actionbar_counts=tuple(map(int,actionbar_match.groups()))
                if actionbar_counts!=(restored_count,skipped_occupied_count,skipped_invalid_count):
                    print("WORLD_CAPTURE_FAIL: completion action-bar counters disagree with restoration accounting",
                          actionbar_counts,(restored_count,skipped_occupied_count,skipped_invalid_count),flush=True)
                    return False
                print("WORLD_CAPTURE: real player received matching completion action-bar accounting",
                      restored_count,skipped_occupied_count,skipped_invalid_count,flush=True)
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
                if deferred_chunk_probe and synthetic_skip_marker in log_after:
                    if not (0<=mined<consumed<no_drop):
                        print("WORLD_CAPTURE_FAIL: deferred-mode real player break did not verify no drops",flush=True)
                        return False
                elif not (0<=preserved<mined<consumed<no_drop):
                    print("WORLD_CAPTURE_FAIL: real player break must follow protection cancellation and verify no drops",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST NATURAL_BEACON_ID_CLEARED_AFTER_BREAK" not in log_after:
                    print("WORLD_CAPTURE_FAIL: natural beacon ownership UUID was not cleared after real mining",flush=True)
                    return False
                print("WORLD_CAPTURE: POST_RESTORE_BEACON_BREAK_GUARD_VERIFIED realPlayerInput=true noDrop=true",flush=True)
            fissure=parse_fissure_particle_emission(log_after)
            sample_reuse_marker=(
                "BLACK_FREQUENCY_TEST SURFACE_FISSURE_SAMPLE_STATE_REUSE_VERIFIED "
                "surfaceReads=1 particleRereads=0 scratchReusable=true"
            )
            if sample_reuse_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: fissure particle height did not reuse the validated surface state",flush=True)
                return False
            canopy_marker=(
                "BLACK_FREQUENCY_TEST SURFACE_FISSURE_CANOPY_TRAVERSAL_VERIFIED "
                "verticalLogs=true leaves=true horizontalLogsRejected=true "
                "roofBlocksRejected=true nonSolidPlantsRetained=true heightmapIncludesLeaves=true depthBound=32"
            )
            if canopy_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: server fissure scan did not verify safe traversal beneath tree canopy",flush=True)
                return False
            world_canopy_marker=(
                "BLACK_FREQUENCY_TEST SURFACE_FISSURE_WORLD_CANOPY_SAMPLE_VERIFIED "
                "heightmapTopOffset=5 canopyBlocks=4 surfaceFound=true horizontalLogRejected=true "
                "roofRejected=true loadedOnly=true searchRadius=8 restored=true "
                "rejectedResultCleared=true unloadedChunkRejected=true"
            )
            if world_canopy_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: rejected fissure samples retained stale output or touched an unloaded chunk",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST FRACTURE_ANCHOR_COORDINATE_QUARANTINE_VERIFIED missing=true wrongType=true activeFractureStopped=true emptyFractureReinitialized=true restoringPreserved=true validUnchanged=true fallbackBeacon=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: malformed saved fracture anchors were not quarantined safely",
                      flush=True)
                return False
            ground_mesh=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_RENDERED segments=(\d+) arms=(\d+) anchorX=(-?\d+) anchorZ=(-?\d+) sampledColumns=(\d+) cacheHits=(\d+)",
                log_after)
            if not ground_mesh:
                print("WORLD_CAPTURE_FAIL: client did not render a bounded ground fissure mesh",flush=True)
                return False
            canopy_selector_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_SELECTOR_PROFILE_VERIFIED "
                "budget=8 candidateBudget=32 selection=three-of-four-sparse plantOrLeafTop=true "
                "invalidRejected=true heightBounded=true segmentBounded=true"
            )
            if (canopy_selector_marker not in log_after
                    or "&& ((blockX&1)==0 || (blockZ&1)==0);" not in camera_source):
                print("WORLD_CAPTURE_FAIL: bounded three-of-four plant/leaf-top canopy accent selector was not verified",flush=True)
                return False
            canopy_leaf_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_LEAF_ACCENT_PROFILE_VERIFIED "
                "maxHeightAboveGround=24.0 topLeafOnly=true exposedAirOrLiftedBush=true maxPlantLift=2 loadedChunkOnly=true forceLoad=false"
            )
            if canopy_leaf_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: exposed leaf-top accents did not pass their loaded-only canopy profile",flush=True)
                return False
            canopy_rotation_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_ROTATION_PROFILE_VERIFIED "
                "candidateCount=12 budget=8 firstWindow=0-7 secondWindow=8-11 wrap=true "
                "negativeStart=true invalidCountRejected=true"
            )
            if (canopy_rotation_marker not in log_after
                    or "groundCanopyAccentCandidateCursor" not in camera_source
                    or "canopyAccentCandidateIndex" not in camera_source):
                print("WORLD_CAPTURE_FAIL: canopy accent emission window does not rotate across queued fissure candidates",flush=True)
                return False
            canopy_branch_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_BRANCH_BALANCE_PROFILE_VERIFIED "
                "candidateBudget=32 particleBudget=8 arms=4 selection=round-robin-arm-then-farthest "
                "uniqueOrder=true perArmBudget=2 deterministic=true scratchArrays=true allocationFree=true cursorStep=1"
            )
            if (canopy_branch_marker not in log_after
                    or "buildCanopyAccentSpatialOrder(GROUND_CANOPY_ACCENT_X,GROUND_CANOPY_ACCENT_Z,\n                GROUND_CANOPY_ACCENT_ARM," not in camera_source
                    or "GROUND_CANOPY_ACCENT_ARM[candidate]=arm;" not in camera_source
                    or "armCounts[arm]<=armSelected[arm]" not in camera_source
                    or "int i=GROUND_CANOPY_ACCENT_SPATIAL_ORDER[offset];" not in camera_source
                    or "candidateDistanceSquared<nearestDistanceSquared[candidate]" not in camera_source
                    or "groundCanopyAccentCandidateCursor=canopyAccentCandidateIndex(start,1,candidateCount);" not in camera_source
                    or "unevenBranchBalanceProfile" not in camera_source
                    or "GROUND_CANOPY_ACCENT_UNEVEN_BRANCH_BALANCE_VERIFIED" not in log_after):
                print("WORLD_CAPTURE_FAIL: canopy accents are not balanced across the four bounded fissure arms",flush=True)
                return False
            canopy_emission_throttle_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_EMISSION_THROTTLE_PROFILE_VERIFIED "
                "intervalTicks=10 first=true sameTickBlocked=true beforeBoundaryBlocked=true "
                "boundaryAllowed=true clockRollbackReset=true overflowSafe=true"
            )
            if (canopy_emission_throttle_marker not in log_after
                    or "shouldAttemptCanopyAccentScan(gameTime,groundCanopyAccentLastAttemptTick)" not in camera_source):
                print("WORLD_CAPTURE_FAIL: canopy accent emission cadence was not verified in the live client",flush=True)
                return False
            canopy_attempt_throttle_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_ATTEMPT_THROTTLE_PROFILE_VERIFIED "
                "intervalTicks=10 zeroEmissionScanRecorded=true emptyScanPreservesAttempt=true"
            )
            if (canopy_attempt_throttle_marker not in log_after
                    or "groundCanopyAccentLastAttemptTick=nextCanopyAccentAttemptTick(" not in camera_source
                    or "candidatesVisited>0 ? gameTime : lastAttemptTick" not in camera_source):
                print("WORLD_CAPTURE_FAIL: rejected canopy candidate scans were not rate-limited in the live client",flush=True)
                return False
            canopy_particle_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_PARTICLE_PROFILE_VERIFIED "
                "scale=1.75 verticalOffset=0.4 verticalSpeed=0.003 "
                "scaleBounded=true heightBounded=true speedBounded=true"
            )
            if (canopy_particle_marker not in log_after
                    or "GROUND_CANOPY_ACCENT_PARTICLE_SCALE=1.75F;" not in camera_source
                    or "GROUND_CANOPY_ACCENT_PARTICLE_SCALE>=1.65F" not in camera_source
                    or "GROUND_CANOPY_ACCENT_PARTICLE_SCALE<=1.80F" not in camera_source
                    or "GROUND_CANOPY_ACCENT_VERTICAL_OFFSET=.40D;" not in camera_source
                    or "GROUND_CANOPY_ACCENT_VERTICAL_SPEED=.003D;" not in camera_source
                    or "GROUND_CANOPY_ACCENT_HORIZONTAL_SPEED=.0045D;" not in camera_source
                    or "canopyAccentHorizontalVelocity(x,z,0,emissionEpoch,dimensionSalt)" not in camera_source
                    or "canopyAccentHorizontalVelocity(x,z,1,emissionEpoch,dimensionSalt)" not in camera_source
                    or "long dimensionSalt=canopyAccentDimensionSalt(level.dimension().location().toString());" not in camera_source
                    or "Math.floorDiv(gameTime,(long)GROUND_CANOPY_ACCENT_EMISSION_INTERVAL_TICKS)" not in camera_source
                    or "private static double canopyAccentVerticalSpeed(boolean liftedAccent)" not in camera_source
                    or "return liftedAccent ? GROUND_CANOPY_ACCENT_LIFTED_VERTICAL_SPEED : GROUND_CANOPY_ACCENT_VERTICAL_SPEED;" not in camera_source
                    or "double verticalSpeed=canopyAccentVerticalSpeed(liftedAccent);" not in camera_source
                    or "velocityX,verticalSpeed,velocityZ" not in camera_source
                    or "&& liftedParticleVelocityBounded" not in camera_source):
                print("WORLD_CAPTURE_FAIL: bounded canopy accent particle profile was not verified",flush=True)
                return False
            canopy_lifted_particle_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_LIFTED_PARTICLE_PROFILE_VERIFIED "
                "regularScale=1.75 liftedScale=1.95 leafTopOrAdjacentStacked=true samePalette=true cachedOptions=true budgetUnchanged=true"
            )
            if (canopy_lifted_particle_marker not in log_after
                    or "GROUND_CANOPY_ACCENT_LEAF_TOP_MOTION_PROFILE_VERIFIED" not in camera_source
                    or "private static int findCanopyAccentCandidateIndex(" not in camera_source
                    or "private static boolean canUpgradeLeafTopCanopyAccentCandidate(" not in camera_source
                    or "GROUND_CANOPY_ACCENT_LIFTED_PARTICLE_SCALE=1.95F;" not in camera_source
                    or "GROUND_CANOPY_ACCENT_LIFTED_PARTICLE_SCALE>=1.90F" not in camera_source
                    or "GROUND_CANOPY_ACCENT_LIFTED_PARTICLE_SCALE<=2.00F" not in camera_source
                    or "GROUND_CANOPY_ACCENT_LIFTED_DUST=" not in camera_source
                    or "boolean leafTopAccent=GROUND_CANOPY_ACCENT_LEAF_TOP[i];" not in camera_source
                    or "isLiftedCanopyAccent(leafTopAccent," not in camera_source
                    or "GROUND_CANOPY_ACCENT_ADJACENT_PLANT[i],GROUND_CANOPY_ACCENT_SKIPPED_PLANTS[i]);" not in camera_source
                    or "liftedAccent ? GROUND_CANOPY_ACCENT_LIFTED_DUST" not in camera_source
                    or "GROUND_CANOPY_ACCENT_LEAF_TOP[candidate]=true;" not in camera_source
                    or "GROUND_CANOPY_ACCENT_LEAF_TOP[candidate]=false;" not in camera_source
                    or "GROUND_CANOPY_ACCENT_LEAF_TOP=new boolean[GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET];" not in camera_source
                    or "&& particleScaleBounded && liftedParticleScaleBounded && particleOffsetBounded" not in camera_source):
                print("WORLD_CAPTURE_FAIL: lifted bush accents did not use the bounded cached high-visibility option",flush=True)
                return False
            canopy_lifted_motion_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_LIFTED_MOTION_PROFILE_VERIFIED "
                "regularVerticalSpeed=0.003 liftedVerticalSpeed=0.006 leafTopOrAdjacentStacked=true bounded=true particleBudgetUnchanged=true"
            )
            leaf_top_motion_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_LEAF_TOP_MOTION_PROFILE_VERIFIED "
                "leafTopUsesLiftedDust=true leafTopUsesLiftedSpeed=true adjacentStackedUnchanged=true "
                "regularGroundUnchanged=true invalidSkippedRejected=true bounded=true"
            )
            if ("GROUND_CANOPY_ACCENT_LEAF_TOP_UPGRADE_PROFILE_VERIFIED sameColumn=true plantWins=true budgetIndependent=true existingPlantPreserved=true" not in log_after
                    or canopy_lifted_motion_marker not in log_after
                    or leaf_top_motion_marker not in log_after
                    or "GROUND_CANOPY_ACCENT_LIFTED_VERTICAL_SPEED=.006D;" not in camera_source
                    or "GROUND_CANOPY_ACCENT_LIFTED_VERTICAL_SPEED>=.005D" not in camera_source
                    or "GROUND_CANOPY_ACCENT_LIFTED_VERTICAL_SPEED<=.007D" not in camera_source):
                print("WORLD_CAPTURE_FAIL: lifted canopy mote motion was not bounded or verified",flush=True)
                return False
            leaf_edge_offset_profile_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_LEAF_EDGE_OFFSET_PROFILE_VERIFIED "
                "maxOffset=0.52 dominantAxisCrossesBoundary=true cameraFacing=true loadedAirOnly=true radiusBounded=true"
            )
            if (leaf_edge_offset_profile_marker not in log_after
                    or "private static boolean isCanopyAccentLeafEdgeTargetWithinRadius(" not in camera_source
                    or "canopyAccentLeafEdgeOffset(cameraDeltaX,cameraDeltaZ,true)" not in camera_source
                    or "canopyAccentLeafEdgeOffset(cameraDeltaX,cameraDeltaZ,false)" not in camera_source
                    or "GroundMesh mesh=buildSegments(level,worldAnchorX,worldAnchorZ,surfaceReferenceY,camera);" not in camera_source):
                print("WORLD_CAPTURE_FAIL: camera-facing leaf-edge offset policy was not verified",flush=True)
                return False
            leaf_edge_fallback_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_LEAF_EDGE_FALLBACK_PROFILE_VERIFIED "
                "minMinorRatio=0.25 dominantRejectedFallback=true cameraFacing=true loadedAirOnly=true radiusBounded=true"
            )
            if (leaf_edge_fallback_marker not in log_after
                    or "private static int canopyAccentLeafEdgeFallbackAxis(double cameraDeltaX,double cameraDeltaZ)" not in camera_source
                    or "int fallbackAxis=canopyAccentLeafEdgeFallbackAxis(cameraDeltaX,cameraDeltaZ);" not in camera_source
                    or "isCanopyAccentLeafEdgeOffsetSafe(" not in camera_source):
                print("WORLD_CAPTURE_FAIL: secondary camera-facing leaf-edge fallback was not verified",flush=True)
                return False
            leaf_top_particle_emitted=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_LEAF_TOP_PARTICLE_EMITTED" in log_after
            )
            leaf_edge_offset_applied=re.search(
                r"BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_LEAF_EDGE_CAMERA_OFFSET_APPLIED "
                r"applied=(\d+) maxOffset=0\.52 cameraFacing=true loadedAirOnly=true radiusBounded=true",
                log_after)
            if leaf_top_particle_emitted:
                if not leaf_edge_offset_applied or int(leaf_edge_offset_applied.group(1))<=0:
                    print("WORLD_CAPTURE_FAIL: leaf-top motes were emitted but none reached a verified camera-facing loaded air cell",flush=True)
                    return False
                print("WORLD_CAPTURE: camera-facing leaf-edge placement was exercised",flush=True)
            elif leaf_edge_offset_applied and int(leaf_edge_offset_applied.group(1))>0:
                print("WORLD_CAPTURE_FAIL: leaf-edge offset was reported without an emitted leaf-top mote",flush=True)
                return False
            else:
                print("WORLD_CAPTURE: no leaf-top candidates in this recovery profile; edge placement remained inactive",flush=True)
            canopy_drift_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_DRIFT_PROFILE_VERIFIED "
                "deterministic=true temporalVariation=true dimensionScoped=true epochTicks=10 horizontalMax=0.0045 horizontalOnly=true liftedVerticalSeparate=true bounded=true"
            )
            dimension_salt_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_DIMENSION_SALT_PROFILE_VERIFIED "
                "algorithm=fnv1a64 stable=true distinct=true emptyFallback=true"
            )
            if (canopy_drift_marker not in log_after
                    or dimension_salt_marker not in log_after
                    or "private static long canopyAccentDimensionSalt(String dimensionKey)" not in camera_source
                    or "hash*=0x100000001b3L;" not in camera_source
                    or "SplitMix64 combines the dimension key, block column, axis and ten-tick emission epoch" not in camera_source
                    or "hash^=dimensionSalt;" not in camera_source
                    or "hash^=emissionEpoch*0xD1B54A32D192ED03L;" not in camera_source
                    or "canopyAccentHorizontalDriftWithinBounds(velocityX,velocityZ)" not in camera_source):
                print("WORLD_CAPTURE_FAIL: deterministic bounded canopy mote drift was not verified",flush=True)
                return False
            canopy_color_transition_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_COLOR_TRANSITION_VERIFIED "
                "brightVioletToDeepViolet=true cachedOptions=true perParticleUpdate=true budgetUnchanged=true"
            )
            if (canopy_color_transition_marker not in log_after
                    or "import net.minecraft.core.particles.DustColorTransitionOptions;" not in camera_source
                    or "GROUND_CANOPY_ACCENT_START_COLOR=new Vector3f(.62F,.26F,1.0F);" not in camera_source
                    or "GROUND_CANOPY_ACCENT_END_COLOR=new Vector3f(.20F,.04F,.46F);" not in camera_source
                    or "new DustColorTransitionOptions(GROUND_CANOPY_ACCENT_START_COLOR,GROUND_CANOPY_ACCENT_END_COLOR," not in camera_source):
                print("WORLD_CAPTURE_FAIL: animated violet canopy mote color transition was not verified",flush=True)
                return False
            canopy_clearance_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FOLIAGE_CLEARANCE_VERIFIED "
                "offset=0.4 particleScale=1.75 liftedParticleScale=1.95 baseRibbonUnchanged=true bounded=true"
            )
            if canopy_clearance_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: canopy accent clearance above short foliage was not verified",flush=True)
                return False
            canopy_headroom_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_HEADROOM_POLICY_VERIFIED "
                "airOnlyOrLiftedBush=true leavesRejected=true plantsBounded=true fluidsRejected=true solidRejected=true ribbonStillAllowed=true"
            )
            canopy_plant_clearance_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_PLANT_CLEARANCE_PROFILE_VERIFIED "
                "maxLift=2 bushOnly=true collisionEmpty=true leavesRejected=true fluidsRejected=true solidRejected=true"
            )
            canopy_collision_prefilter_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_COLLISION_SHAPE_PREFILTER_VERIFIED "
                "bushOnly=true leavesRejectedBeforeShape=true fluidsRejectedBeforeShape=true solidsRejectedBeforeShape=true"
            )
            if (canopy_headroom_marker not in log_after
                    or canopy_plant_clearance_marker not in log_after
                    or canopy_collision_prefilter_marker not in log_after
                    or "isCanopyAccentHeadroomAvailable(head)" not in camera_source
                    or "return head!=null && head.isAir() && head.getFluidState().isEmpty();" not in camera_source
                    or "GROUND_CANOPY_ACCENT_MAX_SKIPPED_PLANTS=2;" not in camera_source
                    or "isCanopyAccentSkippablePlant" not in camera_source):
                print("WORLD_CAPTURE_FAIL: canopy accent clearance must lift only above bounded non-colliding plants",flush=True)
                return False
            canopy_segment_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_SEGMENT_FILTER_VERIFIED "
                "near=true far=false broadPhaseCorner=true exactCornerRejected=true invalidBoundsRejected=true heightBounded=true invalidRejected=true emptyMeshRejected=true"
            )
            if canopy_segment_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: canopy accent candidates were not filtered against retained fissure segments",
                      flush=True)
                return False
            canopy_neighbor_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_LEAF_ADJACENT_SCAN_PROFILE_VERIFIED "
                "perSample=1..4 maxNeighborScans=80 scanOrder=roundRobinPasses alternatingSides=true perpendicular=true "
                "diagonalFallback=true tangentTowardArmCenter=true fallbackOnMiss=true loadedOnly=true radius=48 uniqueColumns=true duplicateColumnsSkipped=true"
            )
            if canopy_neighbor_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: bounded diagonal canopy scan profile was not exercised",flush=True)
                return False
            canopy_diagonal_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_DIAGONAL_SCAN_PROFILE_VERIFIED "
                "normalOffset=1 tangentOffset=1 towardArmCenter=true maxHorizontalDistance=1.6 "
                "diagonalNear=true straightDistanceTwoRejected=true"
            )
            if canopy_diagonal_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: diagonal canopy offsets did not satisfy the exact segment-distance guard",
                      flush=True)
                return False
            canopy_budget=re.search(
                r"BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_BUDGET_VERIFIED "
                r"candidates=(\d+) emitted=(\d+) candidateBudget=32 budget=8 "
                r"loadedOnly=true radius=48 clientOnly=true plantOrLeafTop=true segmentBounded=true "
                r"neighborScans=(\d+) neighborScanBudget=80 fallbackScans=(\d+) "
                r"fallbackHits=(\d+) diagonalHits=(\d+) neighborDuplicates=(\d+) neighborSamples=(\d+)",
                log_after)
            if (not canopy_budget or int(canopy_budget.group(1))>32
                or int(canopy_budget.group(2))>8
                or int(canopy_budget.group(2))>int(canopy_budget.group(1))
                or int(canopy_budget.group(3))>80
                or int(canopy_budget.group(4))>int(canopy_budget.group(3))
                or int(canopy_budget.group(5))>int(canopy_budget.group(4))
                or int(canopy_budget.group(6))>int(canopy_budget.group(5))
                or int(canopy_budget.group(7))>int(canopy_budget.group(8))*4
                or int(canopy_budget.group(3))+int(canopy_budget.group(7))>int(canopy_budget.group(8))*4
                or int(canopy_budget.group(8))>116):
                print("WORLD_CAPTURE_FAIL: client canopy accent candidates or unique neighbor scans exceeded their bounded budgets",
                      canopy_budget.groups() if canopy_budget else None,flush=True)
                return False
            canopy_rejections=re.search(
                r"BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_REJECTION_PROFILE_VERIFIED "
                r"attempts=(\d+) accepted=(\d+) invalid=(\d+) budget=(\d+) chunk=(\d+) "
                r"heightmap=(\d+) heightmapTopAdjusted=(\d+) nonLeaf=(\d+) headroom=(\d+) height=(\d+) "
                r"pattern=(\d+) duplicate=(\d+) segment=(\d+) plantLifted=(\d+)",
                log_after)
            if not canopy_rejections:
                print("WORLD_CAPTURE_FAIL: canopy accent rejection reasons were not reported",flush=True)
                return False
            rejection_counts=[int(value) for value in canopy_rejections.groups()]
            (attempts,accepted,invalid,budget,chunk,heightmap,heightmap_top_adjusted,non_leaf,headroom,
             height,pattern,duplicate,segment,plant_lifted)=rejection_counts
            if ("BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_VANILLA_DOUBLE_PLANT_PROFILE_VERIFIED "
                    "blocks=6 halves=lower,upper stateAllowlist=true maxSkipped=2") not in log_after:
                print("WORLD_CAPTURE_FAIL: vanilla double-plant runtime profile marker was not emitted",flush=True)
                return False
            if plant_lifted==0:
                print("WORLD_CAPTURE: initial sampled columns had no stacked plant; the deterministic adjacent fixture is checked by the emitted-particle marker",flush=True)
            else:
                print(f"WORLD_CAPTURE: initial sampled columns queued {plant_lifted} stacked-plant accents",flush=True)
            if os.environ.get("BF_ISOLATED_TEST")=="1":
                fixture_match=re.search(
                    r"BF_CANOPY_STACKED_FIXTURE_VERIFIED x=(-?\d+) y=(-?\d+) z=(-?\d+)",log_after)
                lifted_pattern=(
                    r"BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_LIFTED_PARTICLE_EMITTED "
                    r"x=(-?\d+) y=([-+0-9.eE]+) z=(-?\d+) skippedPlants=(\d+) "
                    r"source=adjacent loadedOnly=true radius=48 clientOnly=true"
                )
                anchor_match=FINALE_ANCHOR_RE.search(log_after)
                if not fixture_match or not anchor_match:
                    print("WORLD_CAPTURE_FAIL: the seeded adjacent double-plant fixture was not verified",flush=True)
                    return False
                fixture_x,fixture_y,fixture_z=map(int,fixture_match.groups())
                lifted_match=next((match for match in re.finditer(lifted_pattern,log_after)
                    if int(match.group(1))==fixture_x and int(match.group(3))==fixture_z),None)
                if not lifted_match:
                    print("WORLD_CAPTURE_FAIL: no emitted adjacent lifted-particle marker matched the fixture",flush=True)
                    return False
                lifted_y=float(lifted_match.group(2))
                skipped=int(lifted_match.group(4))
                anchor_x,anchor_z=map(int,anchor_match.groups())
                dx,dz=fixture_x-anchor_x,fixture_z-anchor_z
                if (skipped!=STACKED_PLANT_FIXTURE_SKIPPED_PLANTS or dx*dx+dz*dz>48*48
                        or not math.isclose(lifted_y,fixture_y+STACKED_PLANT_FIXTURE_PARTICLE_OFFSET,
                                            rel_tol=0.0,abs_tol=1.0E-6)):
                    print("WORLD_CAPTURE_FAIL: adjacent lifted-particle coordinates, clearance, or radius were invalid",
                          {"fixture":(fixture_x,fixture_y,fixture_z),"lifted":lifted_match.groups(),
                           "anchor":(anchor_x,anchor_z)},flush=True)
                    return False
                print("WORLD_CAPTURE: adjacent stacked-plant particle verified",
                      {"fixture":(fixture_x,fixture_y,fixture_z),"particleY":lifted_y,
                       "skippedPlants":skipped,"radiusSquared":dx*dx+dz*dz,
                       "loadedOnly":True},flush=True)
            stacked_height_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_STACKED_PLANT_HEIGHT_INDEPENDENCE_VERIFIED "
                "directSurfaceRequiresShape=true liftedSurfaceIgnoresShape=true zeroShape=true nonFiniteShape=true bounded=true"
            )
            if stacked_height_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: stacked-plant surface-height independence profile was not exercised",flush=True)
                return False
            shape_query_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_STACKED_PLANT_SHAPE_QUERY_SKIPPED_VERIFIED "
                "liftedSkipsOutlineShape=true directSurfaceUsesShape=true invalidRejected=true"
            )
            if shape_query_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: lifted-plant outline-shape query policy was not exercised",flush=True)
                return False
            # Segment rejection happens after queue acceptance, so it is a subset of accepted, not a pre-queue rejection.
            reasons=(invalid,budget,chunk,heightmap,non_leaf,headroom,height,pattern,duplicate)
            if (attempts>GROUND_MESH_SAMPLES+80
                    or accepted>attempts
                    or sum(reasons)+accepted!=attempts
                    or heightmap_top_adjusted>attempts
                    or segment>accepted
                    or plant_lifted>accepted):
                print("WORLD_CAPTURE_FAIL: canopy accent rejection accounting is inconsistent",
                      rejection_counts,flush=True)
                return False
            canopy_heightmap_prefilter_marker=(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_HEIGHTMAP_PREFILTER_PROFILE_VERIFIED "
                "openSurfaceRejected=true canopyAccepted=true heightBounded=true finiteGuard=true conservative=true"
            )
            if canopy_heightmap_prefilter_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: canopy heightmap prefilter profile was not exercised",flush=True)
                return False
            if ("BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_HEIGHTMAP_TOP_NORMALIZATION_PROFILE_VERIFIED "
                    "exclusive=true inclusive=true longArithmetic=true boundedCorrection=true") not in log_after:
                print("WORLD_CAPTURE_FAIL: canopy heightmap top normalization profile was not exercised",flush=True)
                return False
            continuity=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_STATS maxStep=([-+0-9.eE]+) steepStepsSkipped=(\d+)",
                log_after)
            if "BLACK_FREQUENCY_GROUND_MESH_STRUCTURAL_SUPPORT_FILTER_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: client ground mesh did not verify structural-surface filtering",flush=True)
                return False
            headroom_hazard_marker = (
                "BLACK_FREQUENCY_GROUND_MESH_HEADROOM_HAZARD_FILTER_VERIFIED "
                "powderSnow=true water=true lava=true air=true"
            )
            if headroom_hazard_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: ground mesh did not reject powder snow occupying fissure headroom",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST GROUND_MESH_SURFACE_SEARCH_RANGE_VERIFIED renderRadius=64 searchBelow=64 searchAbove=8 bounded=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: ground surface sampling does not cover the full vertical render radius",flush=True)
                return False
            if not re.search(
                    r"BLACK_FREQUENCY_GROUND_MESH_SURFACE_WINDOW referenceY=-?\d+ topOffset=8 bottomOffset=64 segments=\d+ arms=\d+ sampledColumns=\d+ cacheHits=\d+",
                    log_after):
                print("WORLD_CAPTURE_FAIL: ground surface window is not bounded to the 64-block render radius",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_PROFILE_TEST_MODE enabled=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: expensive ground mesh profile probes were not enabled in the smoke client",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_TIMING_BUDGET_VERIFIED maxSamples=4 first=true last=true afterBudget=false invalidCounter=false" not in log_after:
                print("WORLD_CAPTURE_FAIL: ground mesh timing continued beyond its bounded diagnostic budget",flush=True)
                return False
            build_timings = re.findall(
                r"BLACK_FREQUENCY_GROUND_MESH_BUILD_TIMING elapsedMicros=(\d+) firstBuild=(true|false) profileChecks=(true|false) sampledColumns=(\d+) cacheHits=(\d+) segments=(\d+) arms=(\d+) steepStepBreaks=(\d+)",
                log_after)
            if (len(build_timings)<2 or build_timings[0][1]!="true"
                    or any(sample[1]=="true" for sample in build_timings[1:])
                    or any(sample[2]!="true" for sample in build_timings)):
                print("WORLD_CAPTURE_FAIL: ground mesh rebuild timing did not include profile-enabled warmup and steady-state samples",flush=True)
                return False
            for elapsed,first,profile_checks,sampled,hits,segments,arms,steep in build_timings:
                values=tuple(map(int,(elapsed,sampled,hits,segments,arms,steep)))
                if (values[0]<0 or values[1]>96 or values[2]>96 or values[3]>92
                        or values[4]>4 or values[5]>96):
                    print("WORLD_CAPTURE_FAIL: ground mesh timing counters exceeded bounded geometry limits",flush=True)
                    return False
            print(f"WORLD_CAPTURE: GROUND_MESH_BUILD_TIMING_VERIFIED samples={len(build_timings)} firstBuildSeparated=true profileChecks=true",flush=True)
            visibility_reuse = re.search(
                r"BLACK_FREQUENCY_TEST GROUND_MESH_VISIBILITY_PREFIX_REUSE_VERIFIED firstVisibleIndex=(\d+) prefixFadeChecks=(\d+) cachedFirstSegmentFade=true redundantPrefixRechecksAvoided=(\d+)",
                log_after)
            if (not visibility_reuse or int(visibility_reuse.group(2)) < 2
                    or int(visibility_reuse.group(2)) != int(visibility_reuse.group(3))):
                print("WORLD_CAPTURE_FAIL: ground mesh did not reuse the first visible segment's endpoint fades",flush=True)
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
                    or "event.register(OVERWORLD_ID,effects);" not in cloud_source_text
                    or "VANILLA_CLOUD_EFFECTS_REGISTERED" not in cloud_source_text
                    or "VANILLA_CLOUD_RENDER_CALLBACK" not in cloud_source_text
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
            continuity_profile=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_VERIFIED maxStep=([-+0-9.eE]+) "
                r"maxGrade=([-+0-9.eE]+) cliffTaper=([-+0-9.eE]+)",
                log_after)
            if (not continuity_profile or not continuity
                    or abs(float(continuity.group(1))-1.25)>1.0E-9
                    or abs(float(continuity_profile.group(1))-1.25)>1.0E-9):
                print("WORLD_CAPTURE_FAIL: ground mesh did not verify its cliff-discontinuity guard",
                      flush=True)
                return False
            if ("BLACK_FREQUENCY_GROUND_MESH_SINGLE_STEP_BRIDGE_VERIFIED "
                    "maxStep=1.0 spanSamples=2 plateau=true bump=true repeatedStepRejected=true "
                    "cliffGuard=true invalidRejected=true" not in log_after):
                print("WORLD_CAPTURE_FAIL: isolated one-block ground-step bridge policy was not verified",
                      flush=True)
                return False
            if ("private static final double MAX_GROUND_SEGMENT_GRADE=.5625D/SAMPLE_SPACING;" not in camera_source
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
            supported_density_spacing=any(
                math.isclose(density_spacing,allowed,rel_tol=0.0,abs_tol=1.0e-9)
                for allowed in (0.45,0.60))
            if (density_samples!=FISSURE_PARTICLE_SAMPLES
                    or not supported_density_spacing
                    or not math.isclose(density_extent,12*density_spacing,
                                        rel_tol=0.0,abs_tol=1.0e-9)
                    or density_extent+density_bend>=48.0
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
                    or f"BLACK_FREQUENCY_GROUND_MESH_DENSITY_VERIFIED meshSamples=96 serverSamples=48 ratio=2 evenSamplesAligned=true extent={density_extent!r}" not in log_after):
                print("WORLD_CAPTURE_FAIL: denser client mesh no longer aligns with the server fissure path",
                      "server",density_spacing,density_extent,
                      "client",client_spacing,client_extent,"radius",client_radius,flush=True)
                return False
            # Validate the grade only after the client sampling profile defines client_spacing.
            expected_grade=0.5625/client_spacing
            if (not continuity_profile
                    or abs(float(continuity_profile.group(2))-expected_grade)>1.0E-9
                    or abs(float(continuity_profile.group(3))-.06)>1.0E-9):
                print("WORLD_CAPTURE_FAIL: logged ground grade/taper no longer matches the fixed rise budget",
                      "expectedGrade",expected_grade,
                      "profile",continuity_profile.groups() if continuity_profile else None,
                      flush=True)
                return False
            grade_guard_marker=(
                "BLACK_FREQUENCY_GROUND_MESH_GRADE_GUARD_VERIFIED "
                f"maxGrade={expected_grade:g} oneBlockRiseRejected=true snowAccepted=true cliffCapProfile=true"
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
                    or abs(float(grade_stats.group(1))-expected_grade)>1.0E-9
                    or int(grade_stats.group(2))>int(grade_stats.group(5))
                    or int(grade_stats.group(4))+int(grade_stats.group(5))>GROUND_MESH_SAMPLES):
                print("WORLD_CAPTURE_FAIL: cliff-end taper or single-step bridge accounting is invalid",flush=True)
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
            if "BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_BEACON_STANDOFF_PROFILE_VERIFIED maxHorizontal=12.0 beaconPadNear=6.5 beaconPadFar=11.75 boundaryRejected=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: beacon-side fissure view range profile was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_SPARSE_RANGE_FIXTURE_VERIFIED horizontalSquared=58.11826889234223 acceptedBeyondLegacySixBlockCap=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: observed Sparse fissure standoff regression fixture was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET_SELECTION_VERIFIED policy=balanced-view-span targetPitch=45 visibilityTieWindowDegrees=8.0 neighbors=2 ties=stable invalidRejected=true minHorizontal=3.0 maxHorizontal=12.0" not in log_after:
                print("WORLD_CAPTURE_FAIL: contiguous visible fissure camera target policy was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_STRUCTURAL_SUPPORT_FILTER_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: server did not reject structural blocks as fissure terrain",flush=True)
                return False
            support_shape_marker = (
                "BLACK_FREQUENCY_TEST SURFACE_FISSURE_SUPPORT_SHAPE_GUARD_VERIFIED "
                "fullBlock=true cauldronRejected=true overhangRejected=true multiBoxRejected=true slabPolicyPreserved=true"
            )
            if support_shape_marker not in log_after:
                print("WORLD_CAPTURE_FAIL: server fissure sampler did not validate full-footprint collision support",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_VERIFIED red=0.42 green=0.18 blue=0.92 scale=0.7" not in log_after:
                print("WORLD_CAPTURE_FAIL: compact violet fissure edge Dust profile was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_PULSE_PROFILE_VERIFIED min=0.64 base=0.7 max=0.74 variants=3 bounded=true phaseDriven=true cached=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: phase-driven cached edge Dust pulse profile was not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_LAYOUT_VERIFIED count=4 color=violet perArm=1 outerTip=true" not in log_after:
                print("WORLD_CAPTURE_FAIL: four compact violet edge Dust motes were not verified",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_CACHE_VERIFIED cached=true scale=0.7" not in log_after:
                print("WORLD_CAPTURE_FAIL: compact violet edge Dust option was not verified as cached",flush=True)
                return False
            if not valid_fissure_particle_emission(fissure):
                print("WORLD_CAPTURE_FAIL: deep-violet dust/accent composition or loaded sample count is invalid",
                      fissure,flush=True)
                return False
            edge_dust=re.search(
                r"BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_EMITTED count=([0-9]+) validArms=([0-9]+) material=dust minScale=0.64 baseScale=0.7 maxScale=0.74 minCount=([0-9]+) baseCount=([0-9]+) maxCount=([0-9]+) fallbackArms=([0-9]+)",
                log_after)
            if (not edge_dust
                    or int(edge_dust.group(1))!=int(edge_dust.group(2))
                    or int(edge_dust.group(1))!=fissure[3]
                    or not 3<=int(edge_dust.group(2))<=4
                    or sum(int(edge_dust.group(index)) for index in (3,4,5))!=int(edge_dust.group(1))
                    or not 0<=int(edge_dust.group(6))<=int(edge_dust.group(2))):
                print("WORLD_CAPTURE_FAIL: one compact edge Dust mote per valid fissure arm was not emitted",
                      flush=True)
                return False
            print("WORLD_CAPTURE: deep-violet base Dust and bright-violet accent Dust verified",fissure[0],flush=True)
            print("WORLD_CAPTURE: phase-driven violet fissure edge Dust verified",
                  {"count":edge_dust.group(1),"validArms":edge_dust.group(2),
                   "small":edge_dust.group(3),"base":edge_dust.group(4),
                   "large":edge_dust.group(5)},flush=True)
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
                if "BLACK_FREQUENCY_TEST BEACON_ACTIVATION_SIGHTLINE_VERIFIED hit=beacon loadedChunksOnly=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: actual beacon activation did not pass the server-side loaded-only sightline guard",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_HIT_POINT_ACCEPTED_VERIFIED" not in log_after:
                    print("WORLD_CAPTURE_FAIL: actual beacon click did not pass exact hit-point reach and ray-match validation",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_OCCLUSION_REJECTED temporaryBarrier=true restored=true loadedChunksOnly=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: server sightline guard did not reject and restore a temporary obstruction",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_AURA_ROTATION_PROFILE_VERIFIED periodTicks=1200 outerTurns=1 innerTurns=-2 wrapContinuous=true partialBounded=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: beacon aura rotation profile was not verified",flush=True)
                    return False
                completion_fade_profile=re.search(
                    r"BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_PROFILE_VERIFIED ticks=16 curve=quintic partialTick=true quarter=([-+0-9.eE]+) midpoint=([-+0-9.eE]+) threeQuarter=([-+0-9.eE]+) c2Endpoints=true",
                    log_after)
                distance_fade_profile=re.search(
                    r"BLACK_FREQUENCY_TEST BEACON_AURA_DISTANCE_FADE_PROFILE_VERIFIED start=40.0 end=48.0 curve=quintic quarter=([-+0-9.eE]+) midpoint=([-+0-9.eE]+) threeQuarter=([-+0-9.eE]+) c2Endpoints=true monotonic=true invalidRejected=true",
                    log_after)
                expected_fade_values=(.103515625,.5,.896484375)
                if (not completion_fade_profile or not distance_fade_profile
                        or any(not math.isclose(float(completion_fade_profile.group(i)),expected_fade_values[i-1],
                                                rel_tol=0.0,abs_tol=1.0e-9) for i in range(1,4))
                        or any(not math.isclose(float(distance_fade_profile.group(i)),expected_fade_values[i-1],
                                                rel_tol=0.0,abs_tol=1.0e-9) for i in range(1,4))):
                    print("WORLD_CAPTURE_FAIL: beacon aura C2 fade profiles were not verified",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_AURA_RENDERED cages=2 edgeRibbons=24 rotation=counterphase loadedOnly=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: rotating violet physical beacon containment aura was not drawn from the loaded beacon block",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST BEACON_AURA_RESTORATION_ACTIVE_RENDERED progress=" not in log_after:
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
                if "BLACK_FREQUENCY_TEST GRAVITY_ZONE_SERVER_PLAYER_RAMP_VERIFIED" not in log_after:
                    print("WORLD_CAPTURE_FAIL: real ServerPlayer lift/sink activation ramps were not verified",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST GRAVITY_TRAJECTORY_PROBE_POSITION_VERIFIED" not in log_after:
                    print("WORLD_CAPTURE_FAIL: trajectory probe did not stay inside an already-loaded chunk",flush=True)
                    return False
                if "BLACK_FREQUENCY_TEST GRAVITY_ZONE_SERVER_PLAYER_TRAJECTORY_VERIFIED liftSteps=8 sinkSteps=8 integratedPosition=true loadedChunksOnly=true" not in log_after:
                    print("WORLD_CAPTURE_FAIL: integrated real ServerPlayer lift/sink trajectories were not verified",flush=True)
                    return False
                trajectory_modes={}
                for match in GRAVITY_TRAJECTORY_MODE_RE.finditer(log_after):
                    mode,steps,max_horizontal,max_vertical,horizontal_cap,vertical_cap=match.groups()
                    measured=(float(max_horizontal),float(max_vertical),float(horizontal_cap),float(vertical_cap))
                    expected_vertical=(GRAVITY_TRAJECTORY_LIFT_VERTICAL_CAP if mode=="lift"
                        else GRAVITY_TRAJECTORY_SINK_VERTICAL_CAP)
                    if (mode in trajectory_modes or int(steps)!=8
                            or not all(math.isfinite(value) for value in measured)
                            or abs(measured[2]-GRAVITY_TRAJECTORY_HORIZONTAL_CAP)>1.0e-9
                            or abs(measured[3]-expected_vertical)>1.0e-9
                            or measured[0]>GRAVITY_TRAJECTORY_HORIZONTAL_CAP+1.0e-9
                            or measured[1]>expected_vertical+1.0e-9):
                        print(f"WORLD_CAPTURE_FAIL: {mode} gravity trajectory exceeded its bounded velocity contract: {measured}",flush=True)
                        return False
                    trajectory_modes[mode]=measured
                if set(trajectory_modes)!={"lift","sink"}:
                    print("WORLD_CAPTURE_FAIL: bounded velocity telemetry is missing for lift or sink trajectory",flush=True)
                    return False
                collision_modes={}
                for match in GRAVITY_COLLISION_PATH_RE.finditer(log_after):
                    mode,steps,samples=match.groups()
                    if mode in collision_modes or int(steps)!=8 or int(samples)!=9:
                        print(f"WORLD_CAPTURE_FAIL: {mode} gravity collision path was not checked at all nine positions",flush=True)
                        return False
                    collision_modes[mode]=(int(steps),int(samples))
                if set(collision_modes)!={"lift","sink"}:
                    print("WORLD_CAPTURE_FAIL: loaded-only collision checks are missing for lift or sink trajectory",flush=True)
                    return False
                if not GRAVITY_WORLD_BORDER_RE.search(log_after):
                    print("WORLD_CAPTURE_FAIL: gravity probe did not reject outside and border-straddling player boxes",flush=True)
                    return False
            if "BLACK_FREQUENCY_TEST CLIENT_CAMERA_ROTATION" not in log_after:
                print("WORLD_CAPTURE_FAIL: actual client camera rotation telemetry was not recorded",flush=True)
                return False
            if not sky_shot:
                print("WORLD_CAPTURE_FAIL: sky shader pixels were not visible in the upper-sky frame",flush=True)
                return False
            if os.environ.get("BF_TEST_GREEN_PHASE_PREVIEW")=="1":
                green_capture=shots/"sky-rift-green-phase.png"
                if (not green_capture.is_file() or green_capture.stat().st_size==0
                        or "BLACK_FREQUENCY_CI_GREEN_PHASE_PREVIEW_ACTIVE" not in log_after):
                    print("WORLD_CAPTURE_FAIL: real green-phase screenshot and preview marker were not both produced",flush=True)
                    return False
                print("WORLD_CAPTURE: dedicated toxic-green phase image and rendered uniform marker confirmed",flush=True)
            if "BLACK_FREQUENCY_RENDER_ACTIVE" not in log_after:
                print("WORLD_CAPTURE_FAIL: no actual sky renderer invocation observed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SKY_RENDER_THRESHOLD_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: client sky-render threshold regression was not executed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SKY_ZENITH_SPOKE_WIDTH_PROFILE_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: asymmetric zenith spoke width profile was not executed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SKY_SHADER_READY_STATE_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: shader readiness lifecycle was not verified",flush=True)
                return False
            if log_after.count("BLACK_FREQUENCY_TEST SKY_SHADER_UNIFORM_CACHE_VERIFIED strength=true green=true time=true")<2:
                print("WORLD_CAPTURE_FAIL: shader uniform cache was not populated both before and after resource reload",flush=True)
                return False
            if (not shader_reload_verified
                    or "BLACK_FREQUENCY_TEST SKY_SHADER_RELOAD_INVALIDATION_VERIFIED oldUniformsCleared=true readyDisabled=true" not in log_after
                    or "BLACK_FREQUENCY_TEST SKY_SHADER_RELOAD_REBOUND_VERIFIED uniforms=true ready=true" not in log_after
                    or "BLACK_FREQUENCY_TEST SKY_SHADER_RELOAD_POST_DRAW_VERIFIED stage=after_weather depthRestored=true" not in log_after):
                print("WORLD_CAPTURE_FAIL: actual F3+T resource reload did not invalidate, rebind, and draw the sky shader",flush=True)
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
            if "BLACK_FREQUENCY_TEST VANILLA_CLOUD_EFFECTS_REGISTERED dimension=minecraft:overworld implementation=RiftOverworldEffects" not in log_after:
                print("WORLD_CAPTURE_FAIL: custom Overworld cloud effects were not registered",flush=True)
                return False
            cloud_runtime=re.search(
                r"BLACK_FREQUENCY_TEST VANILLA_CLOUD_RUNTIME_STATE dimension=(\S+) cloudStatus=(\S+) effectsClass=(\S+) cloudHeight=([-+0-9.eE]+) ciTestMode=(true|false)",
                log_after)
            if not cloud_runtime:
                print("WORLD_CAPTURE_FAIL: runtime cloud status and active dimension effects were not observed",flush=True)
                return False
            cloud_dimension,cloud_status,cloud_effects,cloud_height,cloud_ci_mode=cloud_runtime.groups()
            try:
                cloud_height_value=float(cloud_height)
            except (TypeError,ValueError,OverflowError):
                cloud_height_value=float("nan")
            if (cloud_dimension!="minecraft:overworld"
                    or cloud_status!="FANCY"
                    or cloud_effects!="RiftOverworldEffects"
                    or not math.isfinite(cloud_height_value)
                    or cloud_height_value<=0.0
                    or cloud_ci_mode!="true"):
                print("WORLD_CAPTURE_FAIL: runtime cloud configuration is not the expected active Overworld profile",
                      {"dimension":cloud_dimension,"cloudStatus":cloud_status,
                       "effectsClass":cloud_effects,"cloudHeight":cloud_height,
                       "ciTestMode":cloud_ci_mode},flush=True)
                return False
            if "BLACK_FREQUENCY_TEST VANILLA_CLOUD_RENDER_CALLBACK" not in log_after:
                print("WORLD_CAPTURE_FAIL: registered Overworld cloud callback was not invoked despite its runtime profile",
                      {"cloudStatus":cloud_status,"effectsClass":cloud_effects},flush=True)
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
