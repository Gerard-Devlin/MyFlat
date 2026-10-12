"""Install and run real Android integration tests, with a failing exit code on failures."""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--serial")
args = parser.parse_args()
adb = shutil.which("adb")
if not adb:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        adb = str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
if not adb:
    raise SystemExit("Set ANDROID_HOME or add adb to PATH")
command = [adb] + (["-s", args.serial] if args.serial else [])
def run(*values):
    subprocess.run(command + list(values), check=True)
run("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
run("install", "-r", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
run("shell", "rm", "-f", "/sdcard/Android/data/dev.myflat.app/files/integration-report.json")
run("shell", "am", "instrument", "-w", "dev.myflat.app.test/dev.myflat.app.SmokeInstrumentation")
with tempfile.TemporaryDirectory(prefix="myflat-test-") as directory:
    file = Path(directory) / "report.json"
    run("pull", "/sdcard/Android/data/dev.myflat.app/files/integration-report.json", str(file))
    report = json.loads(file.read_text(encoding="utf-8"))
    if report.get("status") != "passed":
        raise SystemExit(report.get("error", "Device tests failed"))
    print("PASS:", len(report["checks"]), "device integration checks")
