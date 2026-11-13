#!/usr/bin/env python3
"""Decide TeamCity build scope without reading pull request (PR) draft state.

Arguments: --root checkout, --commit-type pr|merge-queue|develop, --head revision
(default HEAD), --base target revision for PR and merge queue. Develop requires
--changes with a NUL-delimited (zero-byte-separated) path file or --full.
--same-repository enables
the PR subject marker. The caller must set it only for a same-repository PR.
The checkout must contain the head revision and its source tree. For a PR, pass
its head commit as --head when the checkout is not that commit.

Set these TeamCity build parameters on every successful invocation:
  detect.should_build: true or false
  detect.run_integration_tests: true or false
  detect.integration_skip_reason: empty when running, otherwise no-it-tests,
    markdown-only, no-java-changes, no-it-modules, merge-queue or develop
  detect.integration_scope: selected when running selected modules, otherwise full
  detect.integration_modules: comma-separated validated reactor paths for selected
    scope, otherwise empty

Decision failures emit a TeamCity WARNING service message with the cause, choose
the full build and exit zero. Caller usage errors exit nonzero. Merge queue and
develop never run integration tests, even on failure.
"""

import argparse
import importlib.util
import os
import pathlib
import re
import subprocess


def escape(value):
    """Escape TeamCity values and log text, using Unicode UTF-16 code units."""
    replacements = {"|": "||", "'": "|'", "[": "|[", "]": "|]", "\n": "|n", "\r": "|r"}
    result = []
    for char in str(value):
        if char in replacements:
            result.append(replacements[char])
        elif not 32 <= ord(char) < 127:
            data = char.encode("utf-16-be", errors="surrogatepass")
            result.extend(f"|0x{int.from_bytes(data[i:i + 2], 'big'):04X}"
                          for i in range(0, len(data), 2))
        else:
            result.append(char)
    return "".join(result)


class Parser(argparse.ArgumentParser):
    def error(self, message):
        """Keep even malformed caller arguments from forging service messages."""
        self.print_usage()
        self.exit(2, f"error: {escape(message)}\n")


def git(root, *args):
    """Capture Git output so paths and errors never reach the log unescaped."""
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True)
    if result.returncode:
        raise ValueError(f"Git {args[0]} failed: "
                         + result.stderr.decode("utf-8", errors="surrogateescape"))
    return result.stdout


def check_source_trees(root, modules, paths):
    """Raise if a changed Java owner's test source tree cannot be listed fully."""
    owners = {next((name for name in modules if path.startswith(name + "/")), None)
              for path in paths if path.endswith(".java")}

    def scan(source):
        # Classify every entry without suppressing errors, including symlink loops.
        # Broken links are not directories and need no scan. Follow directory links
        # so a cycle raises a path error or RecursionError for the caller to report.
        with os.scandir(source) as entries:
            for entry in entries:
                if entry.is_dir():
                    scan(entry.path)

    for module in sorted(owners - {None}):
        source = root / module / "src/test/java"
        try:
            source.stat()
        except FileNotFoundError:
            # Modules without a test source tree have no IT sources.
            continue
        # Path.glob can hide scan errors. Check every directory before trusting it.
        scan(source)


def decide(args):
    """Return build relevance, integration skip reason and selected modules."""
    if args.full:
        return True, "", []
    spec = importlib.util.spec_from_file_location(
        "select_it_modules", pathlib.Path(__file__).with_name("select-it-modules.py"))
    selector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(selector)
    modules = selector.reactor_modules(args.root)
    if any(not re.match(r"[A-Za-z0-9]", name) for name in modules):
        raise ValueError("Reactor module names must start with a letter or digit")
    if args.commit_type == "develop":
        data = args.changes.read_bytes()
    else:
        if not args.base or set(args.base) == {"0"}:
            raise ValueError("Missing or zero comparison base")
        base = git(args.root, "rev-parse", "--verify", "--end-of-options",
                   args.base + "^{commit}").strip().decode("ascii")
        head = git(args.root, "rev-parse", "--verify", "--end-of-options",
                   args.head + "^{commit}").strip().decode("ascii")
        merge_base = git(args.root, "merge-base", base, head).strip().decode("ascii")
        data = git(args.root, "diff", "--no-renames", "--name-only", "-z",
                   merge_base, head, "--")
    paths = selector.changed_paths(data)
    if any(not path or path.startswith("/") or
           any(part in ("", ".", "..") for part in path.split("/")) for path in paths):
        raise ValueError("Invalid relative path in changed-file list")
    prefixes = [".mvn/", ".github/workflows/", ".idea/inspectionProfiles/",
                ".teamcity/", ".github/scripts/"] + [name + "/" for name in modules]
    relevant = any(not path.endswith(".md") and
                   (path in ("pom.xml", "mvnw", "mvnw.cmd") or
                    any(path.startswith(prefix) for prefix in prefixes)) for path in paths)
    if args.commit_type != "pr":
        return relevant, "", []
    if args.same_repository:
        # Read the full message because Git's %s can join lines in a paragraph.
        message = git(args.root, "show", "-s", "--format=%B", head).decode(
            "utf-8", errors="surrogateescape")
        if "[no-it-tests]" in message.split("\n", 1)[0]:
            return relevant, "no-it-tests", []
    if paths and all(path.endswith(".md") for path in paths):
        return relevant, "markdown-only", []
    check_source_trees(args.root, modules, paths)
    has_java, selected = selector.select(args.root, paths)
    if not has_java:
        return relevant, "no-java-changes", []
    if not selected:
        return relevant, "no-it-modules", []
    if any(name not in modules for name in selected):
        raise ValueError("Selector returned a module outside the validated reactor")
    return relevant, "", selected


def main(argv=None):
    parser = Parser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", required=True, type=pathlib.Path)
    parser.add_argument("--commit-type", required=True, choices=("pr", "merge-queue", "develop"))
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--base")
    parser.add_argument("--same-repository", action="store_true")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--changes", type=pathlib.Path)
    source.add_argument("--full", action="store_true")
    args = parser.parse_args(argv)
    if args.commit_type == "develop":
        if not (args.changes or args.full) or args.base or args.same_repository:
            parser.error("develop requires --changes or --full, not PR arguments")
    elif args.changes or args.full:
        parser.error("--changes and --full apply only to develop")
    try:
        relevant, reason, selected = decide(args)
    except Exception as error:
        # Unknown failures must not turn a required build into a quiet skip.
        warning = escape(f"Full build fallback: {type(error).__name__}: {error}")
        print(f"##teamcity[message text='{warning}' status='WARNING']")
        relevant, reason, selected = True, "", []
    if args.commit_type != "pr":
        reason, selected = args.commit_type, []
    values = {
        "should_build": str(relevant).lower(),
        "run_integration_tests": str(not reason).lower(),
        "integration_skip_reason": reason,
        "integration_scope": "selected" if selected else "full",
        "integration_modules": ",".join(selected),
    }
    for name, value in values.items():
        print(f"##teamcity[setParameter name='{escape('detect.' + name)}' value='{escape(value)}']")
    print("Decision: " + " ".join(f"{name}={escape(value)}" for name, value in values.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
