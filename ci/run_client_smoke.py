import os, pathlib, subprocess, sys, time, threading
import minecraft_launcher_lib

ROOT=pathlib.Path(__file__).resolve().parents[1]
MC=ROOT/"client"
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
    "jvmArguments":["-Xms1G","-Xmx6G","-Djava.awt.headless=false","-Dblackfrequency.ciTestMode=true","-Dblackfrequency.ciTickStep=2400"],
    "customResolution":True,
    "resolutionWidth":"1280",
    "resolutionHeight":"720",
    "launcherName":"EHTID-GitHub-Client-Smoke",
    "launcherVersion":"1.0",
})
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
        subprocess.run(["xdotool","mousemove",str(x),str(y),"click","1"],check=False)
    shots=ROOT/"client-smoke-artifacts"
    os.makedirs(shots,exist_ok=True)
    window=subprocess.run(["xdotool","search","--onlyvisible","--name","Minecraft"],capture_output=True,text=True).stdout.splitlines()
    if window:
        subprocess.run(["xdotool","windowactivate","--sync",window[-1]],check=False)
        subprocess.run(["xdotool","windowsize",window[-1],"1280","720"],check=False)
        subprocess.run(["xdotool","windowmove",window[-1],"0","0"],check=False)
    def snap(name):
        subprocess.run(["scrot","-a","0,0,1280,720",str(shots/name)],check=False)
    snap("01-main-menu.png")
    # EHTID/STALKER custom title screen: Singleplayer sits on the LEFT, not center.
    click(640,300) if os.environ.get("BF_ISOLATED_TEST")=="1" else click(225,328)
    time.sleep(8)
    snap("02-after-singleplayer.png")
    # EHTID title screen opens world creation directly; bottom-left button at x400.
    if os.environ.get("BF_ISOLATED_TEST")=="1":
        click(640,665)
        time.sleep(5)
        snap("02b-world-selection.png")
        click(640,665)
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
            time.sleep(8)
            # Capture the accelerated stages only after actual player spawn.
            for stage in range(16):
                if p.poll() is not None:
                    break
                subprocess.run(["scrot","-a","0,0,1280,720",
                    str(ROOT/"client-smoke-artifacts"/f"world-stage-{stage:02d}.png")],check=False)
                time.sleep(2)
            subprocess.run(["scrot","-a","0,0,1280,720",
                str(ROOT/"client-smoke-artifacts"/"world.png")],check=False)
            log_after=latest.read_text(encoding="utf-8",errors="replace") if latest.exists() else ""
            if "BLACK_FREQUENCY_TEST FINALE_STARTED" not in log_after:
                print("WORLD_CAPTURE_FAIL: screenshots exist, but accelerated finale not observed",flush=True)
                return False
            print("WORLD_CAPTURE: accelerated finale confirmed by server marker",flush=True)
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
