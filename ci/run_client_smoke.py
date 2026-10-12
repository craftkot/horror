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
    "jvmArguments":["-Xms1G","-Xmx6G","-Djava.awt.headless=false"],
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

latest=MC/"logs"/"latest.log"
deadline=time.time()+420
window_seen=False
ready_marker=False
ready_since=None
fatal_patterns=("Mod Loading has failed","LoadingFailedException","ResolutionException",
                "Mixin apply failed","Exception in thread \"Render thread\"","FATAL")

while time.time()<deadline:
    code=p.poll()
    text=""
    if latest.exists():
        text=latest.read_text(encoding="utf-8",errors="replace")
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
    if window_seen and ready_marker and ready_since and time.time()-ready_since>=30 and p.poll() is None:
        subprocess.run(["bash","-lc","mkdir -p client-smoke-artifacts; scrot -a 0,0,1280,720 client-smoke-artifacts/client.png || true"])
        print("CLIENT_SMOKE_PASS: client window alive after full resource initialization.",flush=True)
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
