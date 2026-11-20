#!/usr/bin/env python3
"""Exercise the ready event handoff without network access."""

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
READY = {"count": 1, "branch": [{"name": BRANCH, "default": False, "active": True}]}
ACCEPTED = {"buildTypeId": "YouTrackDB_SummaryReadyForReview", "branchName": BRANCH}


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

    def test_waits_for_active_nondefault_branch_then_posts_exactly_once(self):
        code, _, sent = self.invoke(
            [{"count": 0}, READY | {"branch": [READY["branch"][0] | {"active": False}]},
             READY | {"branch": [READY["branch"][0] | {"default": True}]}, READY, ACCEPTED])
        self.assertEqual(code, 0)
        self.assertEqual([req.get_method() for req in sent], ["GET"] * 4 + ["POST"])
        for req in sent:
            url = urllib.parse.urlsplit(req.full_url)
            self.assertEqual((url.scheme, url.netloc), ("https", "youtrackdb.teamcity.com"))
            self.assertEqual(req.get_header("Authorization"), "Bearer placeholder")
        for req in sent[:-1]:
            url = urllib.parse.urlsplit(req.full_url)
            self.assertEqual(url.path, "/app/rest/buildTypes/id:YouTrackDB_SummaryReadyForReview/branches")
            self.assertEqual(urllib.parse.parse_qs(url.query)["locator"], ["name:pull/1454"])
        self.assertEqual(sent[-1].selector, "/app/rest/buildQueue")
        self.assertEqual(json.loads(sent[-1].data), {
            "buildType": {"id": "YouTrackDB_SummaryReadyForReview"}, "branchName": "pull/1454"})

    def test_timeout_does_not_post_even_if_late_poll_finds_the_branch(self):
        for reply in ({"count": 0}, READY):
            with self.subTest(reply=reply):
                code, output, sent = self.invoke([reply], times=[0, 0, 120, 120])
                self.assertEqual(code, 1)
                self.assertIn("Timed out", output)
                self.assertEqual([req.get_method() for req in sent], ["GET"])

    def test_refusal_invalid_or_wrong_reply_and_ambiguous_post_fail_without_retry(self):
        for reply, diagnostic in (
            (urllib.error.HTTPError("", 403, "placeholder", {}, None), "HTTP 403"),
            (TimeoutError("placeholder"), "connection failed"),
            (b"<html>", "unreadable"),
            (ACCEPTED | {"branchName": "develop"}, "wrong configuration or branch"),
            (ACCEPTED | {"buildTypeId": "other"}, "wrong configuration or branch"),
        ):
            with self.subTest(diagnostic=diagnostic):
                code, output, sent = self.invoke([READY, reply])
                self.assertEqual(code, 1)
                self.assertIn(diagnostic, output)
                self.assertEqual([req.get_method() for req in sent], ["GET", "POST"])

    def test_bad_branch_list_and_invalid_event_input_fail_without_post(self):
        code, output, sent = self.invoke([{"count": 1}])
        self.assertEqual(code, 1)
        self.assertIn("unreadable ready branch list", output)
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
