"""Bind a native AAR to this project's pinned sources and patches."""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AAR = ROOT / "app/libs/libcore.aar"
STAMP = AAR.with_suffix(".aar.provenance.json")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inputs(root=ROOT):
    files = []
    for path in (root / "libcore").rglob("*"):
        relative = path.relative_to(root / "libcore")
        if any(part in {".build", "build", "gomobile"} for part in relative.parts):
            continue
        if path.is_file() and (path.suffix == ".go" and not path.name.endswith("_test.go")
                               or path.name in {"go.mod", "go.sum"}
                               or path.suffix == ".sh" and not path.name.startswith("env_")):
            files.append(path)
    files += [p for p in (root / "buildScript").rglob("*") if p.is_file() and p.suffix in {".sh", ".patch"}]
    files.append(root / "scripts/native-provenance.py")
    result = hashlib.sha256()
    for path in sorted(files, key=lambda item: item.relative_to(root).as_posix()):
        result.update(path.relative_to(root).as_posix().encode() + b"\0" + path.read_bytes().replace(b"\r\n", b"\n") + b"\0")
    return result.hexdigest()


def git(directory, *args):
    return subprocess.run(["git", "-C", str(directory), *args], check=True, capture_output=True).stdout


def source_diff(directory, *paths):
    # Abbreviated object IDs depend on clone depth/object count; user diff
    # preferences must not change the native provenance representation either.
    return git(directory, "diff", "--binary", "--full-index", "--no-ext-diff", "--no-color",
               "--no-renames", "--diff-algorithm=myers", "--no-indent-heuristic", "--unified=3",
               "HEAD", "--", *paths)


def check_sources():
    pins = dict(re.findall(r'export (\w+)="([^"]+)"', (ROOT / "buildScript/lib/core/get_source_env.sh").read_text()))
    for name, key in (("sing-box", "COMMIT_SING_BOX"), ("libneko", "COMMIT_LIBNEKO")):
        directory = ROOT / ".deps" / name
        if git(directory, "rev-parse", "HEAD").decode().strip() != pins[key]:
            raise ValueError(f"Unpinned {name} source")
        if name == "sing-box":
            actual = source_diff(directory, ".", ":(exclude)**/*_test.go")
            expected = (ROOT / "buildScript/lib/core/patches/egox-core.patch").read_bytes().replace(b"\r\n", b"\n")
            if actual != expected:
                raise ValueError("sing-box modifications differ from the tracked patch")
            untracked = git(directory, "ls-files", "--others", "--exclude-standard").decode().splitlines()
            if any(not path.endswith("_test.go") for path in untracked):
                raise ValueError("Unexpected untracked sing-box source")
        elif git(directory, "status", "--porcelain").strip():
            raise ValueError("Modified libneko source")
    return pins


def verify(root=ROOT):
    aar = root / "app/libs/libcore.aar"
    stamp = aar.with_suffix(".aar.provenance.json")
    metadata = json.loads(stamp.read_text())
    if metadata["inputs_sha256"] != inputs(root) or metadata["aar_sha256"] != digest(aar.read_bytes()):
        raise ValueError("Native AAR is stale or changed; rebuild the native core")


def main():
    command = sys.argv[1]
    if command == "inputs":
        check_sources()
        print(inputs())
    elif command == "record":
        pins = check_sources()
        current = inputs()
        if current != sys.argv[2]:
            raise ValueError("Native inputs changed during compilation")
        STAMP.write_text(json.dumps({"inputs_sha256": current, "aar_sha256": digest(AAR.read_bytes()),
                                     "sing_box_revision": pins["COMMIT_SING_BOX"],
                                     "sing_box_version": pins["SING_BOX_VERSION"]}, indent=2) + "\n")
    elif command == "verify":
        verify()
        print("Native AAR provenance verified")
    else:
        raise ValueError("Expected inputs, record, or verify")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
