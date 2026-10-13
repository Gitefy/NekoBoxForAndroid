import importlib.util
import json
import os
import shlex
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("provenance", Path(__file__).with_name("native-provenance.py"))
provenance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provenance)


class NativeProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("libcore", "buildScript/lib/core/patches", "scripts", "app/libs"):
            (self.root / name).mkdir(parents=True)
        (self.root / "libcore/core.go").write_text("package libcore\n")
        (self.root / "libcore/go.mod").write_text("module libcore\n")
        (self.root / "scripts/native-provenance.py").write_text("# builder\n")
        (self.root / "buildScript/lib/core/patches/core.patch").write_text("patch\n")
        self.aar = self.root / "app/libs/libcore.aar"
        self.aar.write_bytes(b"native library")
        self.aar.with_suffix(".aar.provenance.json").write_text(json.dumps({
            "inputs_sha256": provenance.inputs(self.root),
            "aar_sha256": provenance.digest(self.aar.read_bytes()),
        }))

    def test_matching_build_is_accepted(self):
        provenance.verify(self.root)

    def test_changed_source_is_rejected(self):
        (self.root / "libcore/core.go").write_text("package changed\n")
        with self.assertRaises(ValueError):
            provenance.verify(self.root)

    def test_changed_patch_is_rejected(self):
        (self.root / "buildScript/lib/core/patches/core.patch").write_text("new patch\n")
        with self.assertRaises(ValueError):
            provenance.verify(self.root)

    def test_changed_aar_is_rejected(self):
        self.aar.write_bytes(b"old native library")
        with self.assertRaises(ValueError):
            provenance.verify(self.root)

    def test_unrelated_docs_do_not_invalidate_build(self):
        (self.root / "libcore/README.md").write_text("documentation\n")
        provenance.verify(self.root)

    def test_windows_line_endings_do_not_invalidate_build(self):
        (self.root / "libcore/core.go").write_bytes(b"package libcore\r\n")
        provenance.verify(self.root)



class CoreFetchScriptTest(unittest.TestCase):
    def test_fresh_clone_checks_out_an_already_matching_head(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(source), *args], stderr=subprocess.DEVNULL, text=True).strip()
            git("init")
            (source / "source.txt").write_text("fixture")
            git("add", "source.txt")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture")
            revision = git("rev-parse", "HEAD")
            script = (provenance.ROOT / "buildScript/lib/core/get_source.sh").read_text()
            function = "fetch_source() {" + script.split("fetch_source() {", 1)[1].split("\n}\n", 1)[0] + "\n}\n"
            bash = shutil.which("bash")
            if os.name == "nt":
                bash = str(Path(shutil.which("git")).resolve().parents[1] / "bin/bash.exe")
            target = root / "target"
            command = "set -e\n" + function + "fetch_source " + " ".join(shlex.quote(str(value).replace("\\", "/")) for value in (target, source, revision)) + "\n"
            result = subprocess.run([bash, "-s"], input=command, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("fixture", (target / "source.txt").read_text())


if __name__ == "__main__":
    unittest.main()
