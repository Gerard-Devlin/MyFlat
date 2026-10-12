"""Check locally supplied model files against the project manifest."""
from pathlib import Path
import argparse
import hashlib
import json

parser = argparse.ArgumentParser()
parser.add_argument("--allow-missing", action="store_true", help="Check available files without requiring private model weights.")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / "models/myflat/manifest.json").read_text(encoding="utf-8"))
missing = []
verified = 0
for item in manifest["models"]:
    file = root / "models/myflat" / item["file"]
    if not file.is_file():
        missing.append(file.name)
        continue
    data = file.read_bytes()
    if data[4:8] != b"TFL3":
        raise SystemExit(f"Invalid TFLite header: {file.name}")
    if len(data) != item["bytes"]:
        raise SystemExit(f"Size mismatch: {file.name}")
    if hashlib.sha256(data).hexdigest() != item["sha256"]:
        raise SystemExit(f"Hash mismatch: {file.name}")
    verified += 1
    print(f"OK {file.name}")
print(f"Verified {verified} model files.")
if missing:
    print(f"Model files not present: {len(missing)}. Model weights are supplied separately.")
    if not args.allow_missing:
        raise SystemExit("Place the model files listed in models/myflat/manifest.json in models/myflat/ before running scan tests.")
