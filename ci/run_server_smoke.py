import pathlib, subprocess, sys, time, os, signal

ROOT = pathlib.Path(__file__).resolve().parents[1]
server = ROOT / "server"
log = server / "logs" / "latest.log"

(server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
(server / "server.properties").write_text(
    "online-mode=false\n"
    "motd=EHTID Horror CI smoke test\n"
    "view-distance=4\n"
    "simulation-distance=4\n"
    "max-tick-time=-1\n"
    "enable-command-block=false\n",
    encoding="utf-8"
)
(server / "user_jvm_args.txt").write_text(
    "-Xms1G\n-Xmx5G\n-XX:+UseG1GC\n",
    encoding="utf-8"
)

cmd = ["bash", "./run.sh", "nogui"]
print("Starting Forge dedicated server:", " ".join(cmd))
p = subprocess.Popen(cmd, cwd=server, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)

deadline = time.time() + 300
captured = []
success = False

while time.time() < deadline:
    if p.poll() is not None:
        break
    line = p.stdout.readline()
    if line:
        print(line, end="")
        captured.append(line)
        if "Done (" in line and 'For help, type "help"' in line:
            success = True
            break
    elif log.exists():
        text = log.read_text(encoding="utf-8", errors="replace")
        if "Done (" in text and 'For help, type "help"' in text:
            success = True
            break
        time.sleep(0.5)
    else:
        time.sleep(0.5)

if success:
    print("\nSERVER_SMOKE_TEST_PASS: Forge reached Done().")
    try:
        p.stdin.write("stop\n") if p.stdin else None
    except Exception:
        pass
    p.terminate()
    try:
        p.wait(timeout=20)
    except subprocess.TimeoutExpired:
        p.kill()
    sys.exit(0)

print(f"\nSERVER_SMOKE_TEST_FAIL: process exit={p.poll()} before reaching Done().", file=sys.stderr)
if p.poll() is None:
    p.terminate()
    try:
        p.wait(timeout=10)
    except subprocess.TimeoutExpired:
        p.kill()

if log.exists():
    print("\n===== tail server/logs/latest.log =====", file=sys.stderr)
    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
    print("\n".join(lines[-180:]), file=sys.stderr)
sys.exit(1)
