# SPDX-License-Identifier: GPL-3.0-or-later
import json
from pathlib import Path
import tempfile
import unittest

from native_notices import collect


class NativeNoticesTest(unittest.TestCase):
    def test_resolved_dependency_notices_and_license_metadata_are_retained(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            crate = root / "crate"
            crate.mkdir()
            (crate / "LICENSE-APACHE").write_text("synthetic license notice")
            (crate / "NOTICE").write_text("synthetic attribution")
            (crate / "COPYING").mkdir()
            (crate / "COPYING" / "third-party.txt").write_text("synthetic nested notice")
            metadata = root / "metadata.json"
            metadata.write_text(json.dumps({"packages": [{
                "name": "synthetic", "version": "1.0.0", "manifest_path": str(crate / "Cargo.toml"),
                "license": "Apache-2.0", "repository": "https://example.test/source", "source": None,
            }]}))
            output = root / "notices"
            collect(metadata, output)
            copied = output / "synthetic-1.0.0"
            self.assertEqual("synthetic license notice", (copied / "LICENSE-APACHE").read_text())
            self.assertEqual("synthetic attribution", (copied / "NOTICE").read_text())
            self.assertTrue((copied / "COPYING" / "third-party.txt").is_file())
            self.assertEqual("Apache-2.0", json.loads((output / "inventory.json").read_text())[0]["license"])

    def test_explicit_notice_outside_dependency_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            crate = root / "crate"
            crate.mkdir()
            (root / "outside").write_text("not a dependency notice")
            metadata = root / "metadata.json"
            metadata.write_text(json.dumps({"packages": [{
                "name": "synthetic", "version": "1.0.0", "manifest_path": str(crate / "Cargo.toml"),
                "license_file": "../outside",
            }]}))
            with self.assertRaises(ValueError):
                collect(metadata, root / "notices")


if __name__ == "__main__":
    unittest.main()
