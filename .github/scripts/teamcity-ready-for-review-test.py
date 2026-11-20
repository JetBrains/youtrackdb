#!/usr/bin/env python3
"""Test the ready event handoff and PR branch list check without network access."""

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import unittest
import urllib.error
import urllib.parse
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "bridge", pathlib.Path(__file__).with_name("teamcity-ready-for-review.py"))
bridge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bridge)

BRANCH = "pull/1454"
LISTED = {"count": 1, "branch": [{"name": BRANCH, "default": False}]}
ACCEPTED = {"buildTypeId": "YouTrackDB_SummaryReadyForReview", "branchName": BRANCH, "id": 12345}


class ReadyBridgeTest(unittest.TestCase):
    def invoke(self, replies, times=None, **env):
        output = io.StringIO()
        values = {"PR_NUMBER": "1454", "TEAMCITY_READY_TRIGGER_TOKEN": "placeholder"} | env
        responses = [item if isinstance(item, Exception) else io.BytesIO(
            item if isinstance(item, bytes) else json.dumps(item).encode()) for item in replies]
        with patch.dict(os.environ, values, clear=True), \
             patch.object(bridge.urllib.request.OpenerDirector, "open", side_effect=responses) as send, \
             patch.object(bridge.time, "monotonic", side_effect=times, return_value=0), \
             patch.object(bridge.time, "sleep"), contextlib.redirect_stderr(output), \
             contextlib.redirect_stdout(output), patch.object(
                 bridge.urllib.request, "build_opener", wraps=bridge.urllib.request.build_opener
             ) as opener:
            code = bridge.main()
        if responses:
            opener.assert_called_once_with(bridge.NoRedirect)
        self.assertNotIn("placeholder", output.getvalue())
        return code, output.getvalue(), [call.args[0] for call in send.call_args_list]

    def test_waits_for_listed_nondefault_pr_branch_then_posts_even_when_inactive(self):
        default = LISTED | {"branch": [LISTED["branch"][0] | {"default": True}]}
        inactive = LISTED | {"branch": [LISTED["branch"][0] | {"active": False}]}
        code, output, sent = self.invoke([{"count": 0}, default, inactive, ACCEPTED])
        self.assertEqual(code, 0)
        self.assertIn("build 12345", output)
        self.assertEqual([req.get_method() for req in sent], ["GET"] * 3 + ["POST"])
        for req in sent:
            url = urllib.parse.urlsplit(req.full_url)
            self.assertEqual((url.scheme, url.netloc), ("https", "youtrackdb.teamcity.com"))
            self.assertEqual(req.get_header("Authorization"), "Bearer placeholder")
        for req in sent[:-1]:
            url = urllib.parse.urlsplit(req.full_url)
            self.assertEqual(url.path, "/app/rest/buildTypes/id:YouTrackDB_SummaryReadyForReview/branches")
            self.assertEqual(urllib.parse.parse_qs(url.query), {
                "locator": ["name:pull/1454,policy:vcs_branches,changesFromDependencies:false"],
                "fields": ["count,branch(name,default)"],
            })
        self.assertEqual(sent[-1].selector, "/app/rest/buildQueue")
        self.assertEqual(json.loads(sent[-1].data), {
            "buildType": {"id": "YouTrackDB_SummaryReadyForReview"}, "branchName": "pull/1454"})

    def test_unlisted_pr_branch_keeps_polling_until_timeout_without_post(self):
        code, output, sent = self.invoke(
            [{"count": 0}, {"count": 0, "branch": []}], times=[0, 0, 0, 5, 115, 120])
        self.assertEqual([req.get_method() for req in sent], ["GET", "GET"])
        self.assertEqual(code, 1)
        self.assertIn("Timed out waiting for TeamCity to list the PR branch", output)

    def test_timeout_does_not_post_even_if_late_poll_lists_the_pr_branch(self):
        code, output, sent = self.invoke([LISTED], times=[0, 0, 120])
        self.assertEqual(code, 1)
        self.assertIn("Timed out waiting for TeamCity to list the PR branch", output)
        self.assertEqual([req.get_method() for req in sent], ["GET"])

    def test_missing_or_unusable_build_id_fails_without_retry(self):
        no_id = {key: value for key, value in ACCEPTED.items() if key != "id"}
        for reply in (no_id, *(ACCEPTED | {"id": value} for value in
                               (None, "not-a-build", "12345", 1.5, 0, -1, True, False))):
            with self.subTest(reply=reply):
                code, output, sent = self.invoke([LISTED, reply])
                self.assertEqual(code, 1)
                self.assertIn("invalid or missing build id", output)
                self.assertEqual([req.get_method() for req in sent], ["GET", "POST"])

    def test_refusal_invalid_or_wrong_reply_and_ambiguous_post_fail_without_retry(self):
        for reply, diagnostic in (
            (urllib.error.HTTPError("", 403, "placeholder", {}, None), "HTTP 403"),
            (TimeoutError("placeholder"), "connection failed"),
            (b"<html>", "unreadable"),
            (ACCEPTED | {"branchName": "develop"}, "wrong configuration or branch"),
            (ACCEPTED | {"buildTypeId": "other"}, "wrong configuration or branch"),
        ):
            with self.subTest(diagnostic=diagnostic):
                code, output, sent = self.invoke([LISTED, reply])
                self.assertEqual(code, 1)
                self.assertIn(diagnostic, output)
                self.assertEqual([req.get_method() for req in sent], ["GET", "POST"])

    def test_bad_branch_list_and_invalid_event_input_fail_without_post(self):
        code, output, sent = self.invoke([{"count": 1}])
        self.assertEqual(code, 1)
        self.assertIn("unreadable PR branch list", output)
        self.assertEqual([req.get_method() for req in sent], ["GET"])
        for env, diagnostic in (
            ({"PR_NUMBER": number}, "positive integer") for number in ("", "0", "-1", "1,default:any")
        ):
            code, output, sent = self.invoke([], **env)
            self.assertEqual(code, 1)
            self.assertIn(diagnostic, output)
            self.assertFalse(sent)
        code, output, sent = self.invoke([], TEAMCITY_READY_TRIGGER_TOKEN="")
        self.assertEqual(code, 1)
        self.assertIn("TEAMCITY_READY_TRIGGER_TOKEN is missing", output)
        self.assertFalse(sent)

    def test_redirect_handler_refuses_to_forward_the_bearer(self):
        handler = bridge.NoRedirect()
        request = urllib.request.Request(bridge.ORIGIN, headers={"Authorization": "Bearer placeholder"})
        self.assertIsNone(handler.redirect_request(request, None, 302, "", {}, "https://example.test"))


if __name__ == "__main__":
    unittest.main()
