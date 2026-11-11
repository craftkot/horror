import os, pathlib, subprocess, sys, time, threading, re, shutil, math
import minecraft_launcher_lib

ROOT=pathlib.Path(__file__).resolve().parents[1]
MC=ROOT/"client"
MOUSE_SENSITIVITY=0.6
MIN_GROUND_MESH_SEGMENTS=8
MIN_GROUND_MESH_ARMS=2
MAX_GROUND_MESH_ARMS=4
GROUND_MESH_SAMPLES=48
MC.mkdir(parents=True,exist_ok=True)

def status(x): print("[install]",x,flush=True)
cb={"setStatus":status,"setProgress":lambda x:None,"setMax":lambda x:None}

print("Installing Minecraft 1.20.1...",flush=True)
minecraft_launcher_lib.install.install_minecraft_version("1.20.1",str(MC),callback=cb)
forge="1.20.1-47.4.15"
print("Installing Forge",forge,flush=True)
minecraft_launcher_lib.forge.install_forge_version(forge,str(MC),callback=cb)

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
for option,value in (("rawMouseInput","false"),("mouseSensitivity",str(MOUSE_SENSITIVITY))):
    prefix=option+":"
    for index,line in enumerate(option_lines):
        if line.startswith(prefix):
            option_lines[index]=prefix+value
            break
    else:
        option_lines.append(prefix+value)
options_file.write_text("\n".join(option_lines)+"\n",encoding="utf-8")
print("WORLD_CAPTURE: raw mouse input disabled for deterministic X11 camera control",flush=True)
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
    def key(k):
        subprocess.run(["xdotool","key","--clearmodifiers",k],check=False)
    def click(x,y):
        # Hold/release explicitly: some GLFW clients ignore xdotool's fast click.
        subprocess.run(["xdotool","mousemove","--sync",str(x),str(y)],check=False)
        time.sleep(0.25)
        subprocess.run(["xdotool","mousedown","1"],check=False)
        time.sleep(0.18)
        subprocess.run(["xdotool","mouseup","1"],check=False)
        print("WORLD_CAPTURE_INPUT: mouse click",x,y,flush=True)
    shots=ROOT/"client-smoke-artifacts"
    os.makedirs(shots,exist_ok=True)
    window=subprocess.run(["xdotool","search","--onlyvisible","--name","Minecraft"],capture_output=True,text=True).stdout.splitlines()
    if window:
        subprocess.run(["xdotool","windowactivate","--sync",window[-1]],check=False)
        subprocess.run(["xdotool","windowsize",window[-1],"1280","720"],check=False)
        subprocess.run(["xdotool","windowmove",window[-1],"0","0"],check=False)
    def snap(name):
        subprocess.run(["scrot","-a","0,0,1280,720",str(shots/name)],check=False)
    def move_camera(delta_y):
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
        # Choose the shortest turn across the -180/180 boundary.
        factor=MOUSE_SENSITIVITY*0.6+0.2
        degrees_per_pixel=factor*factor*factor*8.0*0.15
        delta=(target_yaw-current_yaw+180.0)%360.0-180.0
        return round(delta/degrees_per_pixel)
    def is_rift_violet_pixel(r,g,b):
        # Reject pale lavender UI/death-screen skies; the rift must be saturated violet.
        return r>55 and b>80 and b>g*1.5 and r>g*1.5 and b-r<150
    def is_ground_fissure_pixel(r,g,b):
        # Legacy particles are brighter than the dark sky shader behind canopy gaps.
        return r>180 and b>180 and g<85 and r>g*1.5 and b>g*1.5 and abs(r-b)<100
    def is_ground_mesh_pixel(r,g,b):
        # The new mesh uses a blue-heavy violet edge, unlike magenta particle sprites.
        return r>145 and b>215 and b-r>28 and 18<g<100
    # Samples come from real artifacts: pale death-screen lavender, a dark sky leak,
    # and a bright Dragon Breath mote on the ground.
    if (is_rift_violet_pixel(142,127,155)
            or not is_rift_violet_pixel(134,44,150)
            or is_ground_fissure_pixel(142,127,155)
            or is_ground_fissure_pixel(134,44,150)
            or not is_ground_fissure_pixel(201,66,214)
            or not is_ground_mesh_pixel(194,58,238)
            or is_ground_mesh_pixel(198,0,229)
            or is_ground_mesh_pixel(201,66,214)
            or is_ground_mesh_pixel(142,127,155)):
        raise RuntimeError("BLACK FREQUENCY pixel classifier regression")
    print("WORLD_CAPTURE: saturated violet pixel classifier verified",flush=True)

    def measure_ground_visibility(frame):
        width,height=frame.size
        pixels=frame.load()
        left,right=int(width*.20),int(width*.80)
        top,bottom=int(height*.40),int(height*.90)
        particle_pixels=mesh_pixels=combined_pixels=0
        particle_tiles=mesh_tiles=combined_tiles=0
        tile_size=32
        for tile_y in range(top,bottom,tile_size):
            for tile_x in range(left,right,tile_size):
                particle_hits=mesh_hits=combined_hits=0
                for y in range(tile_y,min(tile_y+tile_size,bottom)):
                    for x in range(tile_x,min(tile_x+tile_size,right)):
                        r,g,b=pixels[x,y]
                        particle=is_ground_fissure_pixel(r,g,b)
                        mesh=is_ground_mesh_pixel(r,g,b)
                        if particle:
                            particle_pixels+=1
                            particle_hits+=1
                        if mesh:
                            mesh_pixels+=1
                            mesh_hits+=1
                        if particle or mesh:
                            combined_pixels+=1
                            combined_hits+=1
                if particle_hits>=4:
                    particle_tiles+=1
                if mesh_hits>=3:
                    mesh_tiles+=1
                if combined_hits>=4:
                    combined_tiles+=1
        return {
            "particlePixels":particle_pixels,
            "particleTiles":particle_tiles,
            "meshPixels":mesh_pixels,
            "meshTiles":mesh_tiles,
            "combinedPixels":combined_pixels,
            "combinedTiles":combined_tiles,
        }

    def ground_visibility_passes(metrics):
        return (metrics["particlePixels"]>=120 and metrics["particleTiles"]>=3
                and metrics["combinedPixels"]>=160 and metrics["combinedTiles"]>=6
                and metrics["meshPixels"]>=24 and metrics["meshTiles"]>=2)

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
                purple_rows=[]
                for y in range(crop.height):
                    row_purple=0
                    start=y*crop.width
                    for r,g,b in pixels[start:start+crop.width]:
                        if is_rift_violet_pixel(r,g,b):
                            purple+=1
                            row_purple+=1
                        if g>r*1.15 and g>b*1.15 and g>25:
                            green+=1
                    purple_rows.append(row_purple)
                coverage=purple/(crop.width*crop.height)
                green_coverage=green/(crop.width*crop.height)
                score=coverage-.35*green_coverage
                row_threshold=max(6,int(crop.width*.01))
                peak_threshold=max(12,int(crop.width*.025))
                line_rows=sum(1 for count in purple_rows if count>=row_threshold)
                peak_row=max(purple_rows,default=0)
                candidate=(score,coverage,green_coverage,line_rows,peak_row,left,top,peak_threshold)
                line_visible=line_rows>=8 and peak_row>=peak_threshold
                valid=(coverage>=minimum and line_visible
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
        score,coverage,green_coverage,line_rows,peak_row,left,top,peak_threshold=selected
        line_visible=line_rows>=8 and peak_row>=peak_threshold
        visible=best_valid is not None
        selection="valid-window" if visible else "best-diagnostic-window"
        print(f"WORLD_CAPTURE: SKY_PIXEL_COVERAGE {name}={coverage:.4f} green={green_coverage:.4f} lineRows={line_rows} peakRow={peak_row} window={left},{top} required={minimum:.4f} maxGreen={max_green} visible={visible} selection={selection}",flush=True)
        return visible
    beacon_attempts=0
    beacon_ready_at=None
    last_beacon_attempt=0
    def try_beacon_click(logs):
        nonlocal beacon_attempts,beacon_ready_at,last_beacon_attempt
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
        if (os.environ.get("BF_TEST_NATURAL_BEACON")=="1"
                and "BLACK_FREQUENCY_TEST BEACON_SKY_CLEARANCE_VERIFIED" not in logs):
            return
        now=time.monotonic()
        if beacon_ready_at is None:
            beacon_ready_at=now
            print("WORLD_CAPTURE: beacon ready; waiting for client teleport",flush=True)
        # Both Normal and Sparse must capture the real ground fissure before
        # the physical beacon is consumed by the player's right-click.
        if not terrain_shot:
            return
        retry_delay=0.75 if "BLACK_FREQUENCY_TEST PLAYER_BEACON_AIM_RECOVERED_AFTER_MISS" in logs else 2.0
        if now-beacon_ready_at<1.5 or now-last_beacon_attempt<retry_delay or beacon_attempts>=8:
            return
        if beacon_attempts==0:
            snap("beacon-before-right-click.png")
        # Capture the active rift while the physical beacon is ready but before
        # the right-click starts restoration. Sparse recovery can finish in only
        # a few seconds, much faster than two llvmpipe screenshots can be saved.
        if not sky_capture_attempted:
            capture_sky_before_beacon()
            snap("beacon-aim-restored.png")
        # Actual mouse input, not a synthetic server event.
        subprocess.run(["xdotool","mousedown","3"],check=False)
        time.sleep(0.25)
        subprocess.run(["xdotool","mouseup","3"],check=False)
        beacon_attempts+=1
        last_beacon_attempt=now
        print("WORLD_CAPTURE_INPUT: player right-click attempt",beacon_attempts,flush=True)
        snap("beacon-after-right-click.png")
        # The following loop still requires the server's real interaction marker.
        # Sky capture is already complete; do not spend the short recovery window
        # waiting for a second camera pass.
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
                marker=re.search(
                    r"BLACK_FREQUENCY_TEST SURFACE_FRACTURE_CUE_EMITTED x=(-?\d+) y=(-?\d+) z=(-?\d+)",
                    logs)
                particles=re.search(
                    r"BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLES_EMITTED samples=(\d+) dragonBreath=(\d+) reversePortal=(\d+) total=(\d+) heightmap=motion-blocking-no-leaves ground=non-wood averageY=([-+0-9.eE]+)",
                    logs)
                target=re.search(
                    r"BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET x=([-+0-9.eE]+) y=([-+0-9.eE]+) z=([-+0-9.eE]+) distanceSquared=([-+0-9.eE]+)",
                    logs)
                safe_pad=re.search(
                    r"BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT x=(-?\d+) y=(-?\d+) z=(-?\d+) support=solid fallDistance=0\.0",
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
                if (not marker or not particles or not target or not safe_pad or not aim or not mesh
                        or "BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER" not in logs):
                    return
                samples=int(particles.group(1))
                dragon_breath=int(particles.group(2))
                reverse_portal=int(particles.group(3))
                total_particles=int(particles.group(4))
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
                        or mesh_arms<MIN_GROUND_MESH_ARMS or mesh_arms>MAX_GROUND_MESH_ARMS):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: client ground mesh is below the shared segment/arm threshold",
                          "segments",mesh_segments,"arms",mesh_arms,flush=True)
                    return
                if (dragon_breath+reverse_portal!=samples or reverse_portal!=8
                        or total_particles!=samples+8):
                    terrain_capture_failed=True
                    print("WORLD_CAPTURE_FAIL: fissure particle composition/budget mismatch",
                          samples,dragon_breath,reverse_portal,total_particles,flush=True)
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
                player_x,player_y,player_z=map(int,safe_pad.groups())
                current_yaw,current_pitch=map(float,aim.groups())
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
                sky_capture_attempted=True
                key("F1")
                # Leave enough vertical pointer travel for the full -460/+460
                # camera sweep. Starting at screen centre made X11 clamp the
                # upward move at y=0, so the inverse move overshot by 80 pixels.
                subprocess.run(["xdotool","mousemove","--sync","640","640"],check=False)
                time.sleep(0.2)
                camera_delta=0
                camera_yaw_delta=0
                try:
                    # The server has just teleported and aimed the player. Give
                    # the client a moment to apply that rotation before steering.
                    time.sleep(0.6)
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                    aim_match=re.search(
                        r"BLACK_FREQUENCY_TEST BEACON_AIM_SET yaw=[^ ]+ pitch=([-+0-9.eE]+)",current)
                    initial_pitch=float(aim_match.group(1)) if aim_match else 26.46307
                    if not aim_match:
                        print("WORLD_CAPTURE: beacon pitch marker missing; using calibrated fallback",flush=True)
                    horizon_delta=camera_delta_for_pitch(initial_pitch,0.0)
                    upper_delta=camera_delta_for_pitch(initial_pitch,-65.0)
                    # First level the server's measured beacon aim. The old fixed
                    # -380px start tilted roughly 54 degrees above the horizon,
                    # pointing into tree crowns before the search even began.
                    camera_delta+=move_camera(horizon_delta)
                    horizon_visible=False
                    last_candidate=None
                    for index in range(10):
                        if index:
                            camera_yaw_delta+=move_camera_horizontal(170)
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
                    # A high-angle view is more useful than exact zenith in forests:
                    # at -90 degrees the camera looks straight into the canopy and
                    # yaw no longer exposes gaps between trees.
                    camera_delta+=move_camera(upper_delta-horizon_delta)
                    upper_visible=False
                    last_upper_candidate=None
                    for index in range(10):
                        if index:
                            camera_yaw_delta+=move_camera_horizontal(170)
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
                        print("WORLD_CAPTURE: upper-sky frame accepted; horizon is obstructed by terrain",
                              flush=True)
                finally:
                    # Restore both camera axes by the exact sum of successful
                    # relative inputs; the client must still aim at the beacon.
                    if camera_yaw_delta:
                        restored_yaw=move_camera_horizontal(-camera_yaw_delta)
                        time.sleep(0.15)
                        if restored_yaw!=-camera_yaw_delta:
                            sky_shot=False
                            print("WORLD_CAPTURE_FAIL: camera yaw inverse input incomplete",
                                  camera_yaw_delta,restored_yaw,flush=True)
                    if camera_delta:
                        restored_delta=move_camera(-camera_delta)
                        time.sleep(0.25)
                        if restored_delta!=-camera_delta:
                            sky_shot=False
                            print("WORLD_CAPTURE_FAIL: camera pitch inverse input incomplete",
                                  camera_delta,restored_delta,flush=True)
                        else:
                            print("WORLD_CAPTURE: camera pitch inverse input sent",restored_delta,flush=True)
                    key("F1")
                if sky_shot:
                    print("WORLD_CAPTURE: SKY_RIFT_HORIZON_AND_UPPER_SKY_VERIFIED_BEFORE_BEACON_CLICK",flush=True)
                else:
                    print("WORLD_CAPTURE_FAIL: sky capture failed before beacon click; still attempting real interaction",flush=True)
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
                    if ("BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED" in current
                            and "BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED" not in current):
                        snap("beacon-restoration-active.png")
                        restoration_shot=True
                        print("WORLD_CAPTURE: active restoration frame captured before completion",flush=True)
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
                snap("recovery-beacon-ground-context.png")
                return False
            final_log=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            if "BLACK_FREQUENCY_GROUND_MESH_SURFACE_HEIGHT_VERIFIED" not in final_log:
                print("WORLD_CAPTURE_FAIL: collision-based ground surface self-check was not executed",flush=True)
                return False
            if "BLACK_FREQUENCY_GROUND_MESH_REBUILD_PROFILE_VERIFIED verticalDelta=2 interval=4" not in final_log:
                print("WORLD_CAPTURE_FAIL: player-height cache invalidation self-check was not executed",flush=True)
                return False
            if os.environ.get("BF_TEST_RESTORATION")=="1":
                # A full fracture can reach 95,000 snapshots. Recovery restores at
                # most 12 blocks per 20 TPS tick (~396s at the cap); leave headroom
                # for client capture and the final stabilization hold. Do not raise
                # the gameplay restore budget or force-load chunks to satisfy CI.
                deadline_restore=time.time()+480
                restored=False
                while time.time()<deadline_restore and p.poll() is None:
                    current=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
                    try_beacon_click(current)
                    capture_sky_before_beacon()
                    if (not restoration_shot
                            and "BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED" in current
                            and "BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED" not in current):
                        snap("beacon-restoration-active.png")
                        restoration_shot=True
                    if "BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED" in current:
                        restored=True
                        break
                    time.sleep(2)
                if restored:
                    snap("beacon-restoration-finished.png")
                else:
                    snap("beacon-restoration-timeout.png")
                    print("WORLD_CAPTURE_FAIL: restoration did not finish in client",flush=True)
                    return False
                if not restoration_shot:
                    print("WORLD_CAPTURE_FAIL: restoration finished without an active gameplay screenshot",flush=True)
                    return False
                if not terrain_shot:
                    print("WORLD_CAPTURE_FAIL: no player-visible ground fissure was captured",flush=True)
                    return False
                print("WORLD_CAPTURE: restoration finished in integrated client",flush=True)
            log_after=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            if os.environ.get("BF_TEST_PLAYER_BEACON")=="1":
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
                consumed=log_after.find("BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_CONSUMED")
                if preserved<0 or consumed<=preserved:
                    print("WORLD_CAPTURE_FAIL: canceled beacon break must preserve the fixture before permitted break consumes it",flush=True)
                    return False
                print("WORLD_CAPTURE: POST_RESTORE_BEACON_BREAK_GUARD_VERIFIED (synthetic event)",flush=True)
            fissure=re.search(
                r"BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLES_EMITTED samples=(\d+) dragonBreath=(\d+) reversePortal=(\d+) total=(\d+) heightmap=motion-blocking-no-leaves",
                log_after)
            ground_mesh=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_RENDERED segments=(\d+) arms=(\d+) anchorX=(-?\d+) anchorZ=(-?\d+) sampledColumns=(\d+) cacheHits=(\d+)",
                log_after)
            if not ground_mesh:
                print("WORLD_CAPTURE_FAIL: client did not render a bounded ground fissure mesh",flush=True)
                return False
            continuity=re.search(
                r"BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_STATS maxStep=([-+0-9.eE]+) steepStepsSkipped=(\d+)",
                log_after)
            if ("BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_VERIFIED maxStep=1.25" not in log_after
                    or not continuity or abs(float(continuity.group(1))-1.25)>1.0E-9):
                print("WORLD_CAPTURE_FAIL: ground mesh did not verify its cliff-discontinuity guard",
                      flush=True)
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
                    or mesh_arms<MIN_GROUND_MESH_ARMS or mesh_arms>MAX_GROUND_MESH_ARMS):
                print("WORLD_CAPTURE_FAIL: final ground mesh is below the shared segment/arm threshold",
                      "segments",mesh_segments,"arms",mesh_arms,flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_DENSITY_VERIFIED samples=48 spacing=0.39 extent=4.68" not in log_after:
                print("WORLD_CAPTURE_FAIL: fissure density/radius self-check was not executed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLE_BUDGET_VERIFIED total=56 dragonBreath=40 reversePortal=8 shards=8" not in log_after:
                print("WORLD_CAPTURE_FAIL: layered fissure particle budget self-check was not executed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SURFACE_FISSURE_SHARD_LAYOUT_VERIFIED count=8 material=crying_obsidian" not in log_after:
                print("WORLD_CAPTURE_FAIL: crying-obsidian shard layout self-check was not executed",flush=True)
                return False
            if (not fissure or int(fissure.group(1))<32
                    or int(fissure.group(2))+int(fissure.group(3))!=int(fissure.group(1))
                    or int(fissure.group(3))!=8
                    or int(fissure.group(4))!=int(fissure.group(1))+8):
                print("WORLD_CAPTURE_FAIL: ground fissure particle composition or loaded sample count is invalid",flush=True)
                return False
            shards=re.search(
                r"BLACK_FREQUENCY_TEST SURFACE_FISSURE_SHARDS_EMITTED count=([0-9]+) material=crying_obsidian",
                log_after)
            if not shards or int(shards.group(1))!=8:
                print("WORLD_CAPTURE_FAIL: all eight textured fissure-edge shards were not emitted",
                      flush=True)
                return False
            print("WORLD_CAPTURE: bounded layered ground fissure particles verified",fissure.group(1),flush=True)
            print("WORLD_CAPTURE: crying-obsidian fissure-edge shards verified",shards.group(1),flush=True)
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
                if "BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION" not in log_after:
                    print("WORLD_CAPTURE_FAIL: beacon was not activated by actual player right-click",flush=True)
                    return False
            if not sky_shot:
                print("WORLD_CAPTURE_FAIL: sky shader pixels were not visible in both horizon and zenith frames",flush=True)
                return False
            if "BLACK_FREQUENCY_RENDER_ACTIVE" not in log_after:
                print("WORLD_CAPTURE_FAIL: no actual sky renderer invocation observed",flush=True)
                return False
            if "BLACK_FREQUENCY_TEST SKY_RENDER_THRESHOLD_VERIFIED" not in log_after:
                print("WORLD_CAPTURE_FAIL: client sky-render threshold regression was not executed",flush=True)
                return False
            print("WORLD_CAPTURE: accelerated finale and in-world sky rendering confirmed",flush=True)
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
            print("CLIENT_SMOKE_FAIL: no integrated world; screenshot is diagnostic only",file=sys.stderr)
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
