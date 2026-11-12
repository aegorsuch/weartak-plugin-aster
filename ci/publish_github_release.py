"""Mirror only verified canonical Aster evaluation APKs; never a stable release."""
# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = "aegorsuch/weartak-plugin-aster"
TAG = "TAK.gov-Forge-CI-CD-Evaluation"
MARKER = "aster-evaluation-v1"
OUTPUT = Path("app/build/outputs/aster/release")
SIGNER = "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"


class PublishError(Exception):
    pass


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise PublishError("Refusing non-HTTPS asset redirect.")
        redirect = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirect is not None:
            redirect.remove_header("Authorization")
        return redirect


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(SafeRedirect())

    def request(self, method, path, data=None, *, binary=False, missing_ok=False):
        host = "https://uploads.github.com" if binary else "https://api.github.com"
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "Aster-TAK.gov-evaluation-release",
        }
        if data is not None:
            headers["Content-Type"] = (
                "application/vnd.android.package-archive" if binary else "application/json"
            )
            if not binary:
                data = json.dumps(data).encode()
        request = urllib.request.Request(
            f"{host}/repos/{REPO}/{path}", data=data, headers=headers, method=method,
        )
        try:
            with self.opener.open(request, timeout=120) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as error:
            if missing_ok and error.code == 404:
                return None
            raise PublishError(f"GitHub request failed: HTTP {error.code}.") from None
        except urllib.error.URLError:
            raise PublishError("GitHub HTTPS/DNS unavailable; no release was verified.") from None

    def assets(self, release_id):
        assets = []
        page = 1
        while True:
            batch = self.request("GET", f"releases/{release_id}/assets?per_page=100&page={page}")
            assets.extend(batch)
            if len(batch) < 100:
                return assets
            page += 1

    def download_digest(self, asset_id):
        request = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/releases/assets/{asset_id}",
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/octet-stream"},
        )
        try:
            with self.opener.open(request, timeout=120) as response:
                digest = hashlib.sha256()
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    digest.update(chunk)
                return size, digest.hexdigest()
        except (urllib.error.HTTPError, urllib.error.URLError):
            raise PublishError("Uploaded asset download verification failed.") from None


def configuration(env):
    expected = {
        "CI_SERVER_HOST": "git.tak.gov",
        "CI_PROJECT_PATH": "weartak-plugins/weartak-plugin-aster",
        "CI_PROJECT_ID": "6988",
        "CI_DEFAULT_BRANCH": "main",
        "CI_COMMIT_BRANCH": "main",
        "CI_COMMIT_REF_PROTECTED": "true",
        "CI_PIPELINE_SOURCE": "push",
    }
    if any(env.get(key) != value for key, value in expected.items()):
        raise PublishError("Publishing requires the protected canonical main push pipeline.")
    if not env.get("ASTER_GITHUB_TOKEN"):
        raise PublishError("Missing masked/protected ASTER_GITHUB_TOKEN (Aster Contents:write).")
    for key in ("CI_PIPELINE_ID", "CI_PIPELINE_IID"):
        if not re.fullmatch(r"[1-9][0-9]*", env.get(key, "")):
            raise PublishError(f"Invalid {key}.")
    if not re.fullmatch(r"[0-9a-f]{40}", env.get("CI_COMMIT_SHA", "")):
        raise PublishError("Invalid source SHA.")
    return {
        "source_sha": env["CI_COMMIT_SHA"],
        "pipeline_id": env["CI_PIPELINE_ID"],
        "pipeline_iid": int(env["CI_PIPELINE_IID"]),
    }


def artifact(root, state):
    name = f"WearTAK-Plugin-Aster-0.1.0-evaluation-p{state['pipeline_iid']}.apk"
    directory = root / OUTPUT
    apk = directory / name
    if list(directory.glob("*.apk")) != [apk] or apk.is_symlink() or not apk.is_file():
        raise PublishError("Expected one canonical signed evaluation APK.")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if (directory / f"{name}.sha256").read_text().strip() != f"{digest}  {name}":
        raise PublishError("APK checksum differs from the signature-verified artifact.")
    provenance = dict(
        line.split("=", 1) for line in (directory / f"{name}.provenance.txt").read_text().splitlines()
    )
    expected = dict(state, pipeline_iid=str(state["pipeline_iid"]),
                    package="com.weartak.plugin.aster", version_name="0.1.0-evaluation",
                    version_code=str(state["pipeline_iid"]), signer_sha256=SIGNER,
                    channel="evaluation")
    if any(provenance.get(key) != str(value) for key, value in expected.items()):
        raise PublishError("Signed artifact provenance differs from the publishing pipeline.")
    if not re.fullmatch(r"[1-9][0-9]*", provenance.get("job_id", "")):
        raise PublishError("Missing canonical signing job identity.")
    return apk, digest


def publish(api, state, apk, digest):
    repo = api.request("GET", "")
    if (repo.get("full_name") != REPO or repo.get("archived") is not False
            or repo.get("disabled") is not False):
        raise PublishError("Destination is not the active, approved Aster mirror.")
    if not api.request("GET", f"git/ref/tags/{TAG}", missing_ok=True):
        raise PublishError(f"Pre-create {TAG} on the GitHub mirror; no source refs are modified.")
    release = api.request("GET", f"releases/tags/{TAG}", missing_ok=True)
    if release:
        markers = re.findall(rf"<!-- {MARKER} (.*?) -->", release.get("body") or "")
        if len(markers) != 1 or release.get("tag_name") != TAG or release.get("prerelease") is not True:
            raise PublishError("Existing release is not an automation-owned evaluation prerelease.")
        previous = json.loads(markers[0])
        if not isinstance(previous, dict) or type(previous.get("pipeline_iid")) is not int:
            raise PublishError("Invalid previous release provenance.")
        if previous["pipeline_iid"] > state["pipeline_iid"]:
            print("Newer evaluation pipeline already published; skipping older pipeline.")
            return
        if previous["pipeline_iid"] == state["pipeline_iid"] and (
            previous.get("source_sha") != state["source_sha"] or previous.get("sha256") != digest
        ):
            raise PublishError("Same pipeline cannot replace a different source/artifact.")
    state = dict(state, sha256=digest)
    pipeline_url = f"https://git.tak.gov/weartak-plugins/weartak-plugin-aster/-/pipelines/{state['pipeline_id']}"
    body = (
        "**Evaluation only. Not a working WearTAK transport or production-ready watch release.**\n\n"
        "Synthetic local Event checks only; no protected provisioning, persistent peer service, "
        "or host chat/CoT forwarding. Signing establishes APK identity, not operational readiness.\n\n"
        f"Canonical pipeline: {pipeline_url}\nSource SHA: `{state['source_sha']}`\n"
        f"SHA256: `{digest}`\n\n"
        "GitHub source archives use the fixed anchor tag, not necessarily this build. "
        "The canonical source SHA above and its pinned upstream dependencies are authoritative.\n\n"
        f"<!-- {MARKER} {json.dumps(state, sort_keys=True)} -->"
    )
    if release is None:
        release = api.request("POST", "releases", {
            "tag_name": TAG, "name": "TAK.gov Aster evaluation", "body": body,
            "draft": True, "prerelease": True, "make_latest": "false",
        })
    release_id = release["id"]
    matches = [item for item in api.assets(release_id) if item.get("name") == apk.name]
    if len(matches) > 1:
        raise PublishError("Duplicate release asset names.")
    if matches:
        uploaded = matches[0]
    else:
        query = urllib.parse.urlencode({"name": apk.name, "label": apk.name})
        uploaded = api.request(
            "POST", f"releases/{release_id}/assets?{query}", apk.read_bytes(), binary=True,
        )
    size = apk.stat().st_size
    if (uploaded.get("state") != "uploaded" or uploaded.get("size") != size
            or api.download_digest(uploaded["id"]) != (size, digest)):
        raise PublishError("Uploaded APK failed size/SHA256 verification; previous assets retained.")
    api.request("PATCH", f"releases/{release_id}", {
        "name": f"TAK.gov Aster evaluation (0.1.0-evaluation-p{state['pipeline_iid']})",
        "body": body, "draft": False, "prerelease": True, "make_latest": "false",
    })
    print(f"Verified evaluation prerelease: https://github.com/{REPO}/releases/tag/{TAG}")


def main():
    try:
        state = configuration(os.environ)
        apk, digest = artifact(Path.cwd(), state)
        publish(GitHub(os.environ["ASTER_GITHUB_TOKEN"]), state, apk, digest)
    except (PublishError, OSError, ValueError, KeyError) as error:
        # Only known local errors/protocol checks, never raw HTTP bodies or request credentials.
        if isinstance(error, PublishError):
            print(f"Evaluation publication failed: {error}", file=sys.stderr)
        else:
            print("Evaluation publication failed: invalid/missing artifact metadata.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
