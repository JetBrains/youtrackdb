#!/usr/bin/env python3
"""Start the ready summary from trusted base code once TeamCity lists the PR branch."""

import http.client
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


ORIGIN = "https://youtrackdb.teamcity.com"
BUILD_TYPE = "YouTrackDB_SummaryReadyForReview"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def request(opener, token, path, timeout, payload=None):
    phase = "checking the PR branch list" if payload is None else "requesting a build"
    req = urllib.request.Request(
        ORIGIN + path, data=None if payload is None else json.dumps(payload).encode(),
        headers={"Authorization": "Bearer " + token, "Accept": "application/json",
                 "Content-Type": "application/json"})
    try:
        with opener.open(req, timeout=timeout) as response:
            result = json.load(response)
        if not isinstance(result, dict):
            raise ValueError
        return result
    except urllib.error.HTTPError as error:
        raise ValueError(f"TeamCity returned HTTP {error.code} while {phase}.") from None
    except (OSError, http.client.HTTPException):
        raise ValueError(f"TeamCity connection failed while {phase}.") from None
    except ValueError:
        raise ValueError(f"TeamCity returned an unreadable response while {phase}.") from None


def start(branch, token):
    opener = urllib.request.build_opener(NoRedirect)
    # Require the summary to list the branch directly. Build history does not prove membership.
    query = urllib.parse.urlencode({
        "locator": f"name:{branch},policy:vcs_branches,changesFromDependencies:false",
        "fields": "count,branch(name,default)",
    })
    path = f"/app/rest/buildTypes/id:{BUILD_TYPE}/branches?{query}"
    deadline = time.monotonic() + 120
    while (remaining := deadline - time.monotonic()) > 0:
        page = request(opener, token, path, min(10, remaining))
        branches = page.get("branch", [])
        if (type(page.get("count")) is not int or not isinstance(branches, list)
                or page["count"] != len(branches)
                or any(not isinstance(item, dict) or item.get("name") != branch
                       or type(item.get("default", False)) is not bool for item in branches)):
            raise ValueError("TeamCity returned an unreadable PR branch list.")
        if any(not item.get("default", False) for item in branches):
            if time.monotonic() >= deadline:
                break
            # An ambiguous POST may already have started work. Do not repeat it.
            build = request(opener, token, "/app/rest/buildQueue", 10,
                            {"buildType": {"id": BUILD_TYPE}, "branchName": branch})
            if build.get("buildTypeId") != BUILD_TYPE or build.get("branchName") != branch:
                raise ValueError("TeamCity returned a build for the wrong configuration or branch.")
            build_id = build.get("id")
            if type(build_id) is not int or build_id <= 0:
                raise ValueError("TeamCity returned an invalid or missing build id.")
            return build_id
        time.sleep(min(5, max(0, deadline - time.monotonic())))
    raise ValueError("Timed out waiting for TeamCity to list the PR branch for the ready summary.")


def main():
    number = os.environ.get("PR_NUMBER", "")
    token = os.environ.get("TEAMCITY_READY_TRIGGER_TOKEN", "")
    try:
        if not re.fullmatch(r"[1-9][0-9]*", number):
            raise ValueError("PR_NUMBER must be a positive integer.")
        if not token.strip():
            raise ValueError("TEAMCITY_READY_TRIGGER_TOKEN is missing.")
        build_id = start("pull/" + number, token)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    print(f"TeamCity accepted the ready summary request as build {build_id}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
