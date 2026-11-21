"""Pack the authors' linear interpolation variant, preserving row-major float32 bytes."""
from pathlib import Path
import sys, zipfile, hashlib

out = Path(__file__).resolve().parents[1] / "app/src/main/assets/pfnn"
out.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(sys.argv[1]) as archive:
    def get(suffix):
        matches = [x for x in archive.namelist() if x.lower().endswith(suffix.lower())]
        assert len(matches) == 1, (suffix, matches)
        return archive.read(matches[0])

    data = bytearray()
    def add(name, floats):
        values = get("demo/network/pfnn/" + name + ".bin")
        assert len(values) == floats * 4, name
        data.extend(values)

    for prefix, n in (("X", 342), ("Y", 311)):
        for stat in ("mean", "std"):
            add(prefix + stat, n)
    for phase in range(0, 50, 5):
        for i, (cols, rows) in enumerate(((342,512), (512,512), (512,311))):
            for symbol, count in (("W", rows*cols), ("b", rows)):
                add(f"{symbol}{i}_{phase:03}", count)
    (out / "linear.pfnn").write_bytes(data)
    (out / "parents.bin").write_bytes(get("character_parents.bin"))
    (out / "LICENSE.txt").write_bytes(get("pfnn/LICENSE.md"))
    print(hashlib.sha256(data).hexdigest())
