"""Download the pinned translation-only NCNN models; verify size and SHA-256."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / "yakuyomi-engine/models.json").read_text(encoding="utf-8"))
destination = ROOT / ".translation-models"
destination.mkdir(exist_ok=True)
for model in manifest["models"]:
    if model["role"] not in {"detector", "ocr", "inpainter"}:
        continue
    target = destination / model["name"]
    def valid(path):
        if not path.exists() or path.stat().st_size != model["size"]:
            return False
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest() == model["sha256"]
    if not valid(target):
        temporary = target.with_suffix(target.suffix + ".part")
        print("Downloading", model["name"], flush=True)
        with urllib.request.urlopen(model["url"], timeout=120) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        if not valid(temporary):
            raise RuntimeError(f"Checksum/size mismatch: {model['name']}")
        temporary.replace(target)
    print("Verified", model["name"], flush=True)
print(destination)
