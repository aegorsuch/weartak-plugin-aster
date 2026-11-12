"""Retain each resolved Rust package's licensing metadata and supplied notices."""
# SPDX-License-Identifier: GPL-3.0-or-later
import json
from pathlib import Path
import shutil
import sys


def collect(metadata_path, output):
    packages = json.loads(metadata_path.read_text())["packages"]
    output.mkdir(parents=True, exist_ok=False)
    inventory = []
    for package in packages:
        root = Path(package["manifest_path"]).parent
        destination = output / f"{package['name']}-{package['version']}"
        destination.mkdir(exist_ok=True)
        paths = {
            path for path in root.iterdir()
            if path.name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE"))
            or path.name.upper() == "THIRD_PARTY_NOTICES.MD"
        }
        if package.get("license_file"):
            explicit = (root / package["license_file"]).resolve()
            if not explicit.is_file() or not explicit.is_relative_to(root.resolve()):
                raise ValueError(f"Invalid license file for {package['name']}")
            paths.add(explicit)
        for path in paths:
            if path.is_symlink():
                raise ValueError(f"Symlinked notice for {package['name']}")
            if path.is_file():
                shutil.copyfile(path, destination / path.name)
            elif path.is_dir():
                # Do not dereference nested symlinks into arbitrary build-host files.
                if any(child.is_symlink() for child in path.rglob("*")):
                    raise ValueError(f"Symlinked notice directory for {package['name']}")
                shutil.copytree(path, destination / path.name, dirs_exist_ok=True)
        inventory.append({
            key: package.get(key)
            for key in ("name", "version", "license", "repository", "source")
        })
    (output / "inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")


if __name__ == "__main__":
    collect(Path(sys.argv[1]), Path(sys.argv[2]))
