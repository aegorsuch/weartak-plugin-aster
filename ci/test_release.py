# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import urllib.request
from unittest.mock import patch

import publish_github_release as release


STATE = {"source_sha": "b" * 40, "pipeline_id": "10", "pipeline_iid": 5}


class FakeGitHub:
    def __init__(self, apk, digest, existing=None):
        self.apk = apk
        self.digest = digest
        self.existing = existing
        self.writes = []
        self.corrupt_download = False

    def request(self, method, path, data=None, **kwargs):
        if method == "GET":
            if path == "":
                return {"full_name": release.REPO, "archived": False, "disabled": False}
            if path == f"git/ref/tags/{release.TAG}":
                return {"ref": f"refs/tags/{release.TAG}"}
            if path == f"releases/tags/{release.TAG}":
                return self.existing
            raise AssertionError(path)
        self.writes.append((method, path, data))
        if path == "releases":
            return {"id": 123}
        if path.startswith("releases/123/assets?"):
            return {"id": 456, "state": "uploaded", "size": self.apk.stat().st_size}
        if path == "releases/123" and method == "PATCH":
            return data
        raise AssertionError((method, path))

    def assets(self, release_id):
        return []

    def download_digest(self, asset_id):
        return self.apk.stat().st_size, "0" * 64 if self.corrupt_download else self.digest


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        printer = patch("builtins.print")
        printer.start()
        self.addCleanup(printer.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        output = self.root / release.OUTPUT
        output.mkdir(parents=True)
        self.apk = output / "WearTAK-Plugin-Aster-0.1.0-evaluation-p5.apk"
        self.apk.write_bytes(b"synthetic publisher fixture, not an APK")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.apk.with_name(self.apk.name + ".sha256").write_text(
            f"{self.digest}  {self.apk.name}\n",
        )
        self.apk.with_name(self.apk.name + ".provenance.txt").write_text(
            f"source_sha={STATE['source_sha']}\npipeline_id=10\npipeline_iid=5\njob_id=20\n"
            "package=com.weartak.plugin.aster\nversion_name=0.1.0-evaluation\nversion_code=5\n"
            f"signer_sha256={release.SIGNER}\nchannel=evaluation\n",
        )

    def test_only_canonical_protected_main_can_publish(self):
        env = {
            "CI_SERVER_HOST": "git.tak.gov",
            "CI_PROJECT_PATH": "weartak-plugins/weartak-plugin-aster",
            "CI_PROJECT_ID": "6988",
            "CI_DEFAULT_BRANCH": "main",
            "CI_COMMIT_BRANCH": "main",
            "CI_COMMIT_REF_PROTECTED": "true",
            "CI_PIPELINE_SOURCE": "push",
            "CI_PIPELINE_ID": "10",
            "CI_PIPELINE_IID": "5",
            "CI_COMMIT_SHA": STATE["source_sha"],
            "ASTER_GITHUB_TOKEN": "synthetic-not-a-credential",
        }
        self.assertEqual(STATE, release.configuration(env))
        for key, invalid in (
            ("CI_SERVER_HOST", "github.com"),
            ("CI_PROJECT_PATH", "fork/aster"),
            ("CI_COMMIT_REF_PROTECTED", "false"),
            ("CI_PIPELINE_SOURCE", "merge_request_event"),
            ("ASTER_GITHUB_TOKEN", ""),
        ):
            with self.subTest(key=key), self.assertRaises(release.PublishError):
                release.configuration(dict(env, **{key: invalid}))

    def test_modified_binary_or_wrong_source_is_rejected(self):
        self.assertEqual((self.apk, self.digest), release.artifact(self.root, STATE))
        with self.assertRaises(release.PublishError):
            release.artifact(self.root, dict(STATE, source_sha="c" * 40))
        self.apk.write_bytes(b"modified binary")
        with self.assertRaises(release.PublishError):
            release.artifact(self.root, STATE)

    def test_publication_stays_draft_until_download_verified_and_is_always_prerelease(self):
        api = FakeGitHub(self.apk, self.digest)
        release.publish(api, STATE, self.apk, self.digest)
        self.assertEqual(("POST", "releases"), api.writes[0][:2])
        self.assertTrue(api.writes[0][2]["draft"])
        self.assertTrue(api.writes[0][2]["prerelease"])
        final = api.writes[-1]
        self.assertEqual(("PATCH", "releases/123"), final[:2])
        self.assertFalse(final[2]["draft"])
        self.assertTrue(final[2]["prerelease"])
        self.assertEqual("false", final[2]["make_latest"])
        self.assertIn("Not a working WearTAK transport", final[2]["body"])

    def test_corrupt_remote_download_does_not_publish_or_delete_any_assets(self):
        api = FakeGitHub(self.apk, self.digest)
        api.corrupt_download = True
        with self.assertRaises(release.PublishError):
            release.publish(api, STATE, self.apk, self.digest)
        self.assertFalse(any(method in ("PATCH", "DELETE") for method, _, _ in api.writes))

    def test_unmanaged_or_stable_release_is_never_overwritten(self):
        for existing in (
            {"tag_name": release.TAG, "prerelease": True, "body": "user-authored release"},
            self.existing(prerelease=False),
        ):
            api = FakeGitHub(self.apk, self.digest, existing)
            with self.assertRaises(release.PublishError):
                release.publish(api, STATE, self.apk, self.digest)
            self.assertEqual([], api.writes)

    def test_older_pipeline_and_same_pipeline_substitution_never_write(self):
        api = FakeGitHub(self.apk, self.digest, self.existing(pipeline_iid=6))
        release.publish(api, STATE, self.apk, self.digest)
        self.assertEqual([], api.writes)
        api = FakeGitHub(self.apk, self.digest, self.existing(sha256="0" * 64))
        with self.assertRaises(release.PublishError):
            release.publish(api, STATE, self.apk, self.digest)
        self.assertEqual([], api.writes)

    def test_redirect_never_forwards_credential_or_downgrades_https(self):
        request = urllib.request.Request(
            "https://api.github.com/asset", headers={"Authorization": "Bearer synthetic"},
        )
        handler = release.SafeRedirect()
        redirected = handler.redirect_request(
            request, None, 302, "Found", {}, "https://storage.example/asset",
        )
        self.assertIsNone(redirected.get_header("Authorization"))
        with self.assertRaises(release.PublishError):
            handler.redirect_request(request, None, 302, "Found", {}, "http://storage.example/asset")

    def existing(self, prerelease=True, **changes):
        state = {**STATE, "sha256": self.digest, **changes}
        return {
            "id": 123, "tag_name": release.TAG, "prerelease": prerelease,
            "body": f"<!-- {release.MARKER} {json.dumps(state)} -->",
        }


if __name__ == "__main__":
    unittest.main()
