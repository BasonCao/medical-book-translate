#!/usr/bin/env python3
from pathlib import Path
import sys
import tempfile
import zipfile

SIGNATURE_SUFFIXES = (".RSA", ".DSA", ".EC", ".SF")

def is_signature_entry(name: str) -> bool:
    upper = name.upper()
    return upper.startswith("META-INF/") and (
        upper.endswith("MANIFEST.MF")
        or any(upper.endswith(s) for s in SIGNATURE_SUFFIXES)
    )

def main():
    if len(sys.argv) != 5:
        print("usage: inject_offline_engine.py INPUT_APK ENGINE MODEL OUTPUT_APK", file=sys.stderr)
        return 2

    src = Path(sys.argv[1])
    engine = Path(sys.argv[2])
    model = Path(sys.argv[3])
    dst = Path(sys.argv[4])

    if not src.is_file():
        raise SystemExit(f"input APK not found: {src}")
    if not engine.is_file():
        raise SystemExit(f"engine not found: {engine}")
    if not model.is_file():
        raise SystemExit(f"model not found: {model}")
    if engine.stat().st_size < 10_000_000:
        raise SystemExit(f"engine is unexpectedly small: {engine.stat().st_size} bytes")
    if model.stat().st_size < 450 * 1024 * 1024:
        raise SystemExit(f"model is unexpectedly small: {model.stat().st_size} bytes")

    arc = "assets/offline-engine/nllb-simple"
    model_arc = "assets/offline-model/nllb-600m-Q4_0.gguf"

    with tempfile.NamedTemporaryFile(prefix="mbt-apk-", suffix=".apk", delete=False, dir=dst.parent) as tmp:
        tmp_path = Path(tmp.name)

    try:
        with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp_path, "w") as zout:
            for info in zin.infolist():
                if info.filename in (arc, model_arc) or is_signature_entry(info.filename):
                    continue
                data = zin.read(info.filename)
                out_info = zipfile.ZipInfo(info.filename, info.date_time)
                out_info.compress_type = info.compress_type
                out_info.external_attr = info.external_attr
                out_info.create_system = info.create_system
                zout.writestr(out_info, data)

            out_info = zipfile.ZipInfo(arc)
            out_info.compress_type = zipfile.ZIP_STORED
            out_info.external_attr = 0o100755 << 16
            out_info.create_system = 3
            with engine.open("rb") as f:
                zout.writestr(out_info, f.read())

            model_info = zipfile.ZipInfo(model_arc)
            model_info.compress_type = zipfile.ZIP_STORED
            model_info.external_attr = 0
            model_info.create_system = 3
            with model.open("rb") as f:
                zout.writestr(model_info, f.read())

        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp_path.replace(dst)
    finally:
        tmp_path.unlink(missing_ok=True)

    print(f"Embedded engine {engine.stat().st_size} bytes and model {model.stat().st_size} bytes into {dst}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
