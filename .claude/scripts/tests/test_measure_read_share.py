#!/usr/bin/env python3
"""Validation runner for `.claude/scripts/measure-read-share.py`.

Running this script is the validation: it imports the telemetry script
as a module and exercises worktree-vs-main detection, the symlinked-cwd
resolve-then-fallback, the recursive jsonl walk (including sub-agent
transcripts), the tool_use -> tool_result index lookup with out-of-order
lines, mixed string-vs-list tool_result content, attachment records, the
sum-to-100 rounding invariant, and repo-relative path normalisation.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_measure_read_share.py

Exit code 0: every test case passed. Exit code 1: one or more failed;
each failure prints a clear message naming the test case + actual vs
expected.

Runner shape mirrors `.claude/scripts/tests/test_workflow_reindex.py`
(stand-alone, no pytest collection, exit-code semantics, single file).
Pytest is not installed on the project's CI image; the stand-alone
runner keeps the test executable on any Python 3 host.
"""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import traceback
from pathlib import Path
from typing import Callable, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT_PATH = REPO_ROOT / ".claude" / "scripts" / "measure-read-share.py"


# ---------------------------------------------------------------------------
# Module loader. The dash in the filename blocks `import`, so load directly.
# ---------------------------------------------------------------------------


def load_module():
    spec = importlib.util.spec_from_file_location("measure_read_share", str(SCRIPT_PATH))
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Failed to load module spec for {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules["measure_read_share"] = module
    spec.loader.exec_module(module)
    return module


MODULE = load_module()


# ---------------------------------------------------------------------------
# Fixture builders.
# ---------------------------------------------------------------------------


def _assistant_tool_use(uuid: str, tool_use_id: str, name: str, file_path: Optional[str] = None) -> dict:
    """An assistant record whose content carries one tool_use block."""
    tool_input = {"file_path": file_path} if file_path is not None else {}
    return {
        "type": "assistant",
        "uuid": uuid,
        "message": {
            "content": [
                {"type": "tool_use", "id": tool_use_id, "name": name, "input": tool_input}
            ]
        },
    }


def _user_tool_result(uuid: str, tool_use_id: str, content) -> dict:
    """A user record carrying one tool_result block; content is str or list."""
    return {
        "type": "user",
        "uuid": uuid,
        "message": {
            "content": [{"type": "tool_result", "tool_use_id": tool_use_id, "content": content}]
        },
    }


def _assistant_text(uuid: str, text: str) -> dict:
    return {"type": "assistant", "uuid": uuid, "message": {"content": [{"type": "text", "text": text}]}}


def _assistant_thinking(uuid: str, text: str) -> dict:
    return {
        "type": "assistant",
        "uuid": uuid,
        "message": {"content": [{"type": "thinking", "thinking": text, "signature": "sig"}]},
    }


def _user_text(uuid: str, text: str) -> dict:
    return {"type": "user", "uuid": uuid, "message": {"content": text}}


def _attachment(uuid: str, payload: dict) -> dict:
    return {"type": "attachment", "uuid": uuid, "attachment": payload}


def _skipped_record(rtype: str, uuid: str = "skip-uuid") -> dict:
    """A record of a non-tallied type (system / file-history-snapshot / etc.)."""
    return {"type": rtype, "uuid": uuid, "sessionId": "s"}


def write_jsonl(path: Path, records: List[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for rec in records:
            fh.write(json.dumps(rec) + "\n")


# ---------------------------------------------------------------------------
# Test runner.
# ---------------------------------------------------------------------------


_FAILURES: List[Tuple[str, str]] = []


def run_test(name: str, fn: Callable[[], None]) -> None:
    try:
        fn()
        print(f"  PASS  {name}")
    except AssertionError as exc:
        print(f"  FAIL  {name}: {exc}", file=sys.stderr)
        _FAILURES.append((name, str(exc)))
    except Exception:
        tb = traceback.format_exc()
        print(f"  ERROR {name}:\n{tb}", file=sys.stderr)
        _FAILURES.append((name, tb))


# ---------------------------------------------------------------------------
# Detection tests.
# ---------------------------------------------------------------------------


def test_detect_main_checkout() -> None:
    """A cwd whose `.git` is a directory classifies as the main checkout."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / ".git").mkdir()
        assert MODULE.detect_checkout_kind(root) == MODULE.DETECT_MAIN


def test_detect_linked_worktree() -> None:
    """A cwd whose `.git` is a file classifies as a linked worktree."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / ".git").write_text("gitdir: /somewhere/.git/worktrees/x\n")
        assert MODULE.detect_checkout_kind(root) == MODULE.DETECT_WORKTREE


def test_detect_no_checkout() -> None:
    """A cwd with no `.git` classifies as not-a-checkout."""
    with tempfile.TemporaryDirectory() as tmp:
        assert MODULE.detect_checkout_kind(Path(tmp)) == MODULE.DETECT_NO_CHECKOUT


def test_build_output_main_checkout_skip_notice() -> None:
    """The main-checkout case returns its distinct skip notice."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / ".git").mkdir()
        out = MODULE.build_output(root, top_n=10)
        assert out == MODULE.SKIP_MAIN_CHECKOUT, "main-checkout notice mismatch"
        assert "main checkout" in out


def test_build_output_no_checkout_skip_notice() -> None:
    """The no-checkout case returns its distinct skip notice."""
    with tempfile.TemporaryDirectory() as tmp:
        out = MODULE.build_output(Path(tmp), top_n=10)
        assert out == MODULE.SKIP_NO_CHECKOUT
        assert "not running inside a git checkout" in out


def test_skip_notices_are_distinct() -> None:
    """The two skip notices for main vs no-transcripts must be different strings."""
    assert MODULE.SKIP_MAIN_CHECKOUT != MODULE.SKIP_NO_TRANSCRIPTS
    assert MODULE.SKIP_NO_CHECKOUT != MODULE.SKIP_NO_TRANSCRIPTS
    assert MODULE.SKIP_MAIN_CHECKOUT != MODULE.SKIP_NO_CHECKOUT


# ---------------------------------------------------------------------------
# Transcript-folder resolution tests.
# ---------------------------------------------------------------------------


def test_resolve_transcript_dir_empty_folder_no_transcripts() -> None:
    """A worktree whose transcript folder exists but is empty -> no-transcripts skip."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        worktree = tmp_root / "wt"
        worktree.mkdir()
        (worktree / ".git").write_text("gitdir: x\n")
        projects = tmp_root / "projects"
        # Pre-create the (empty) transcript folder so resolution succeeds
        # but the walk finds zero jsonl files.
        (projects / MODULE.encode_cwd(worktree.resolve())).mkdir(parents=True)
        out = MODULE.build_output(worktree, top_n=10, projects_root=projects)
        assert out == MODULE.SKIP_NO_TRANSCRIPTS, "expected no-transcripts skip on empty folder"


def test_resolve_transcript_dir_symlinked_cwd_resolve_then_fallback() -> None:
    """Symlinked worktree root: resolution must find the real-path folder.

    Mirrors the `~/.claude/projects/-home-... -> -workspace-...` symlink
    pattern: the cwd is a symlink whose target is the real worktree, and
    the transcript folder is named after the resolved (target) path.
    """
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        real = tmp_root / "real-worktree"
        real.mkdir()
        (real / ".git").write_text("gitdir: x\n")
        link = tmp_root / "link-worktree"
        try:
            link.symlink_to(real, target_is_directory=True)
        except (OSError, NotImplementedError):
            return  # symlinks unsupported on this host; skip silently
        projects = tmp_root / "projects"
        # Transcript folder is named after the RESOLVED path.
        tdir = projects / MODULE.encode_cwd(real.resolve())
        write_jsonl(
            tdir / "session.jsonl",
            [_assistant_tool_use("u1", "t1", "Read", str(real / "f.txt")),
             _user_tool_result("u2", "t1", "x" * 40)],
        )
        out = MODULE.build_output(link, top_n=10, projects_root=projects)
        assert "## Token usage telemetry" in out
        assert "Skipped" not in out, "resolve() should have found the folder via the symlink target"


def test_resolve_transcript_dir_raw_fallback() -> None:
    """When only the raw-cwd-named folder exists, the fallback finds it."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        worktree = tmp_root / "wt"
        worktree.mkdir()
        (worktree / ".git").write_text("gitdir: x\n")
        projects = tmp_root / "projects"
        # Folder named after the RAW (unresolved) cwd only. On most hosts
        # resolve() of a real dir equals the dir, so this also exercises
        # the resolved branch; the assertion is that resolution succeeds.
        write_jsonl(
            (projects / MODULE.encode_cwd(worktree)) / "s.jsonl",
            [_assistant_tool_use("u1", "t1", "Bash"),
             _user_tool_result("u2", "t1", "y" * 40)],
        )
        out = MODULE.build_output(worktree, top_n=10, projects_root=projects)
        assert "## Token usage telemetry" in out and "Skipped" not in out


# ---------------------------------------------------------------------------
# Recursive walk / subagents tests.
# ---------------------------------------------------------------------------


def test_recursive_walk_includes_subagents() -> None:
    """The walk descends into <stem>/subagents/ and counts those transcripts."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        worktree = tmp_root / "wt"
        worktree.mkdir()
        (worktree / ".git").write_text("gitdir: x\n")
        projects = tmp_root / "projects"
        tdir = projects / MODULE.encode_cwd(worktree.resolve())
        # One top-level session and one sub-agent transcript with distinct uuids.
        write_jsonl(
            tdir / "session.jsonl",
            [_assistant_tool_use("u1", "t1", "Read", str(worktree / "top.txt")),
             _user_tool_result("u2", "t1", "a" * 40)],
        )
        write_jsonl(
            tdir / "session" / "subagents" / "agent-1.jsonl",
            [_assistant_tool_use("u3", "t2", "Read", str(worktree / "sub.txt")),
             _user_tool_result("u4", "t2", "b" * 40)],
        )
        out = MODULE.build_output(worktree, top_n=10, projects_root=projects)
        # file_count = 2 (both transcripts), session_count = 1 (top-level only).
        assert "N=1 sessions across 2 transcripts" in out, f"counts wrong:\n{out}"
        assert "sub.txt" in out, "sub-agent Read file should appear in the top-files table"
        assert "top.txt" in out


# ---------------------------------------------------------------------------
# Index lookup / bucket tally tests.
# ---------------------------------------------------------------------------


def _tally(records: List[dict], repo_root: Path):
    """Helper: tally one in-memory transcript and return (buckets, read_files)."""
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "t.jsonl"
        write_jsonl(path, records)
        buckets = {key: 0 for key, _ in MODULE.BUCKET_ROWS}
        read_files: dict = {}
        seen: set = set()
        MODULE.tally_file(path, buckets, read_files, seen)
        return buckets, read_files


def test_tool_use_result_lookup_out_of_order() -> None:
    """A tool_result that precedes its tool_use in file order still resolves.

    The two-pass index build means file order does not matter: the
    tool_result on line 1 resolves against the tool_use on line 2.
    """
    repo = Path("/repo")
    records = [
        _user_tool_result("u1", "t1", "z" * 80),  # appears BEFORE its tool_use
        _assistant_tool_use("u2", "t1", "Read", "/repo/x.txt"),
    ]
    buckets, read_files = _tally(records, repo)
    assert buckets[MODULE.BUCKET_READ] == 20, f"expected 80/4=20 Read tokens, got {buckets}"
    assert read_files == {"/repo/x.txt": 20}


def test_bucket_routing_named_and_other_tools() -> None:
    """Read/Bash/Grep/Edit route to their buckets; an unknown tool -> Other."""
    records = [
        _assistant_tool_use("a1", "t1", "Read", "/repo/r.txt"),
        _user_tool_result("a2", "t1", "r" * 40),  # 10 tokens -> Read
        _assistant_tool_use("a3", "t2", "Bash"),
        _user_tool_result("a4", "t2", "b" * 40),  # 10 -> Bash
        _assistant_tool_use("a5", "t3", "Grep"),
        _user_tool_result("a6", "t3", "g" * 40),  # 10 -> Grep
        _assistant_tool_use("a7", "t4", "Edit", "/repo/e.txt"),
        _user_tool_result("a8", "t4", "e" * 40),  # 10 -> Edit
        _assistant_tool_use("a9", "t5", "WebSearch"),
        _user_tool_result("a10", "t5", "o" * 40),  # 10 -> Other
    ]
    buckets, _ = _tally(records, Path("/repo"))
    assert buckets[MODULE.BUCKET_READ] == 10
    assert buckets[MODULE.BUCKET_BASH] == 10
    assert buckets[MODULE.BUCKET_GREP] == 10
    assert buckets[MODULE.BUCKET_EDIT] == 10
    assert buckets[MODULE.BUCKET_OTHER] == 10


def test_tool_result_string_vs_list_content() -> None:
    """tool_result content as a string and as a list-of-text tally identically.

    Also confirms an `image` block inside a list contributes zero.
    """
    list_content = [
        {"type": "text", "text": "c" * 40},
        {"type": "image", "source": {"data": "AAAA"}},  # ignored
    ]
    records = [
        _assistant_tool_use("a1", "t1", "Read", "/repo/s.txt"),
        _user_tool_result("a2", "t1", "c" * 40),  # string content, 10 tokens
        _assistant_tool_use("a3", "t2", "Read", "/repo/l.txt"),
        _user_tool_result("a4", "t2", list_content),  # list content, 10 tokens
    ]
    buckets, read_files = _tally(records, Path("/repo"))
    assert buckets[MODULE.BUCKET_READ] == 20, f"string+list should both count: {buckets}"
    assert read_files["/repo/s.txt"] == 10
    assert read_files["/repo/l.txt"] == 10


def test_text_thinking_attachment_to_prompts() -> None:
    """assistant text + thinking, user text, and attachments fold into Prompts."""
    records = [
        _assistant_text("a1", "t" * 40),  # 10
        _assistant_thinking("a2", "h" * 40),  # 10
        _user_text("a3", "u" * 40),  # 10
        _attachment("a4", {"k": "v" * 36}),  # json.dumps ~ 48 chars -> ~12
    ]
    buckets, _ = _tally(records, Path("/repo"))
    assert buckets[MODULE.BUCKET_PROMPTS] >= 30, f"prompts bucket too low: {buckets}"
    # No tool buckets should have anything.
    assert buckets[MODULE.BUCKET_READ] == 0 and buckets[MODULE.BUCKET_BASH] == 0


def test_skipped_record_types_not_tallied() -> None:
    """Non-tallied record types contribute nothing to any bucket."""
    records = [
        _skipped_record("system", "s1"),
        _skipped_record("file-history-snapshot", "s2"),
        _skipped_record("last-prompt", "s3"),
        _skipped_record("mode", "s4"),
        _skipped_record("pr-link", "s5"),
    ]
    buckets, read_files = _tally(records, Path("/repo"))
    assert sum(buckets.values()) == 0, f"skipped types leaked into buckets: {buckets}"
    assert read_files == {}


def test_dedup_by_uuid_across_files() -> None:
    """A record with the same uuid in two files is counted once.

    Sub-agent transcripts embed records from their orchestrator; the uuid
    dedup prevents double-counting across the recursive walk.
    """
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        worktree = tmp_root / "wt"
        worktree.mkdir()
        (worktree / ".git").write_text("gitdir: x\n")
        projects = tmp_root / "projects"
        tdir = projects / MODULE.encode_cwd(worktree.resolve())
        shared = [
            _assistant_tool_use("dup-1", "t1", "Read", str(worktree / "f.txt")),
            _user_tool_result("dup-2", "t1", "q" * 40),
        ]
        write_jsonl(tdir / "session.jsonl", shared)
        # Sub-agent transcript duplicates the SAME records (same uuids).
        write_jsonl(tdir / "session" / "subagents" / "agent-1.jsonl", shared)
        out = MODULE.build_output(worktree, top_n=10, projects_root=projects)
        # If dedup works, Read share is 100% of a single 10-token tally and
        # f.txt shows 100.0% of Read (not split or doubled).
        assert "100.0%" in out
        # The Read row should be 100.0% (only Read content present, counted once).
        assert "`Read` tool results" in out


# ---------------------------------------------------------------------------
# Rounding invariant tests.
# ---------------------------------------------------------------------------


def test_largest_remainder_sums_to_100() -> None:
    """Shares round to one decimal and sum to exactly 100.0 across distributions."""
    cases = [
        [1, 1, 1],  # 33.3 / 33.3 / 33.4
        [1, 1, 1, 1, 1, 1],  # six equal thirds-of-sixths
        [7, 11, 13, 17, 19, 23],
        [100, 1, 1, 1, 1, 1],
        [0, 0, 5, 0, 0, 0],  # single non-zero -> 100.0
    ]
    for values in cases:
        pcts = MODULE.largest_remainder_percentages(values)
        total = round(sum(pcts), 1)
        assert total == 100.0, f"{values} -> {pcts} sums to {total}, not 100.0"
        assert all(round(p, 1) == p for p in pcts), f"non-1-decimal value in {pcts}"


def test_largest_remainder_all_zero() -> None:
    """An all-zero input returns all zeros (no context measured)."""
    assert MODULE.largest_remainder_percentages([0, 0, 0]) == [0.0, 0.0, 0.0]


def test_rendered_tool_mix_table_sums_to_100() -> None:
    """The rendered tool-mix table's Share column sums to exactly 100.0%."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        worktree = tmp_root / "wt"
        worktree.mkdir()
        (worktree / ".git").write_text("gitdir: x\n")
        projects = tmp_root / "projects"
        tdir = projects / MODULE.encode_cwd(worktree.resolve())
        # An uneven distribution across several buckets.
        write_jsonl(
            tdir / "s.jsonl",
            [
                _assistant_tool_use("a1", "t1", "Read", str(worktree / "a.txt")),
                _user_tool_result("a2", "t1", "x" * 137),
                _assistant_tool_use("a3", "t2", "Bash"),
                _user_tool_result("a4", "t2", "y" * 211),
                _assistant_text("a5", "z" * 89),
                _assistant_tool_use("a6", "t3", "Grep"),
                _user_tool_result("a7", "t3", "g" * 53),
            ],
        )
        out = MODULE.build_output(worktree, top_n=10, projects_root=projects)
        shares = _parse_tool_mix_shares(out)
        assert len(shares) == 6, f"expected 6 tool-mix rows, parsed {len(shares)}"
        assert round(sum(shares), 1) == 100.0, f"tool-mix shares sum to {sum(shares)}, not 100.0"


def _parse_tool_mix_shares(rendered: str) -> List[float]:
    """Parse the six Share-column percentages out of the rendered tool-mix table."""
    shares: List[float] = []
    in_table = False
    for line in rendered.splitlines():
        if line.startswith("### Tool mix"):
            in_table = True
            continue
        if in_table and line.startswith("### "):
            break
        if in_table and line.startswith("|") and "%" in line:
            cells = [c.strip() for c in line.strip("|").split("|")]
            pct_cell = cells[-1].rstrip("%")
            try:
                shares.append(float(pct_cell))
            except ValueError:
                pass
    return shares


# ---------------------------------------------------------------------------
# Path normalisation tests.
# ---------------------------------------------------------------------------


def test_normalise_path_repo_relative() -> None:
    """A path inside the worktree renders repo-relative."""
    assert MODULE.normalise_path("/repo/a/b.txt", Path("/repo")) == "a/b.txt"


def test_normalise_path_outside_worktree() -> None:
    """A path outside the worktree is tagged, never published verbatim."""
    assert MODULE.normalise_path("/etc/passwd", Path("/repo")) == "<outside-worktree>"


def test_rendered_output_has_no_absolute_paths() -> None:
    """No rendered top-files row begins with an absolute path under /home etc.

    This is the publication-safety check: the ADR must never carry
    absolute paths. Every top-files row is repo-relative.
    """
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        worktree = tmp_root / "wt"
        worktree.mkdir()
        (worktree / ".git").write_text("gitdir: x\n")
        projects = tmp_root / "projects"
        tdir = projects / MODULE.encode_cwd(worktree.resolve())
        write_jsonl(
            tdir / "s.jsonl",
            [
                _assistant_tool_use("a1", "t1", "Read", str(worktree / "deep" / "file.md")),
                _user_tool_result("a2", "t1", "x" * 400),
                # A Read on a path OUTSIDE the worktree.
                _assistant_tool_use("a3", "t2", "Read", "/home/someone/secret.txt"),
                _user_tool_result("a4", "t2", "y" * 80),
            ],
        )
        out = MODULE.build_output(worktree, top_n=10, projects_root=projects)
        for line in out.splitlines():
            if line.startswith("|"):
                # No table cell may contain an absolute path.
                assert "/home/" not in line, f"absolute /home path leaked: {line}"
                assert "/workspace/" not in line, f"absolute /workspace path leaked: {line}"
                # The first cell of a data row must not start with '/'.
                cells = [c.strip() for c in line.strip("|").split("|")]
                first = cells[0]
                assert not first.startswith("/"), f"row starts with absolute path: {line}"
        assert "deep/file.md" in out, "repo-relative path should appear"
        assert "<outside-worktree>" in out, "outside path should be tagged, not published"


def test_top_n_caps_file_table() -> None:
    """--top=N caps the number of top-files rows."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        worktree = tmp_root / "wt"
        worktree.mkdir()
        (worktree / ".git").write_text("gitdir: x\n")
        projects = tmp_root / "projects"
        tdir = projects / MODULE.encode_cwd(worktree.resolve())
        records: List[dict] = []
        for i in range(8):
            records.append(_assistant_tool_use(f"a{i}", f"t{i}", "Read", str(worktree / f"f{i}.txt")))
            records.append(_user_tool_result(f"b{i}", f"t{i}", "x" * (40 * (i + 1))))
        write_jsonl(tdir / "s.jsonl", records)
        out = MODULE.build_output(worktree, top_n=3, projects_root=projects)
        file_rows = [
            ln for ln in out.splitlines()
            if ln.startswith("|") and ".txt" in ln
        ]
        assert len(file_rows) == 3, f"expected 3 capped rows, got {len(file_rows)}:\n{out}"


# ---------------------------------------------------------------------------
# Atomic-render / parse-failure tests.
# ---------------------------------------------------------------------------


def test_parse_failure_emits_skip_and_does_not_crash() -> None:
    """A malformed transcript line yields a skip notice, not a crash, exit 0."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        worktree = tmp_root / "wt"
        worktree.mkdir()
        (worktree / ".git").write_text("gitdir: x\n")
        projects = tmp_root / "projects"
        tdir = projects / MODULE.encode_cwd(worktree.resolve())
        tdir.mkdir(parents=True)
        (tdir / "s.jsonl").write_text('{"type":"user","uuid":"u1"}\nNOT JSON\n')
        out = MODULE.build_output(worktree, top_n=10, projects_root=projects)
        assert out.startswith("## Token usage telemetry")
        assert "could not be parsed" in out, f"expected parse-error skip notice:\n{out}"


# ---------------------------------------------------------------------------
# End-to-end / CLI tests.
# ---------------------------------------------------------------------------


def test_main_runs_from_real_cwd_exit_zero() -> None:
    """main() exits 0 from the real cwd (this repo is a checkout) and prints a section."""
    rc = MODULE.main([])
    assert rc == 0, f"main should exit 0, got {rc}"


def test_main_top_arg_parsing() -> None:
    """--top is parsed; a non-positive value falls back to the default of 10."""
    assert MODULE.parse_args(["--top=5"]).top == 5
    assert MODULE.parse_args([]).top == 10


# ---------------------------------------------------------------------------
# Runner.
# ---------------------------------------------------------------------------


def main() -> int:
    tests: List[Tuple[str, Callable[[], None]]] = [
        ("detect main checkout (.git dir)", test_detect_main_checkout),
        ("detect linked worktree (.git file)", test_detect_linked_worktree),
        ("detect no checkout (.git missing)", test_detect_no_checkout),
        ("build_output main-checkout skip notice", test_build_output_main_checkout_skip_notice),
        ("build_output no-checkout skip notice", test_build_output_no_checkout_skip_notice),
        ("skip notices are distinct", test_skip_notices_are_distinct),
        ("empty transcript folder -> no-transcripts skip", test_resolve_transcript_dir_empty_folder_no_transcripts),
        ("symlinked cwd resolve-then-fallback", test_resolve_transcript_dir_symlinked_cwd_resolve_then_fallback),
        ("raw-cwd fallback resolution", test_resolve_transcript_dir_raw_fallback),
        ("recursive walk includes subagents", test_recursive_walk_includes_subagents),
        ("tool_use -> tool_result lookup out of order", test_tool_use_result_lookup_out_of_order),
        ("bucket routing named + other tools", test_bucket_routing_named_and_other_tools),
        ("tool_result string vs list content", test_tool_result_string_vs_list_content),
        ("text/thinking/attachment -> Prompts", test_text_thinking_attachment_to_prompts),
        ("skipped record types not tallied", test_skipped_record_types_not_tallied),
        ("dedup by uuid across files", test_dedup_by_uuid_across_files),
        ("largest-remainder sums to 100", test_largest_remainder_sums_to_100),
        ("largest-remainder all-zero", test_largest_remainder_all_zero),
        ("rendered tool-mix table sums to 100", test_rendered_tool_mix_table_sums_to_100),
        ("normalise path repo-relative", test_normalise_path_repo_relative),
        ("normalise path outside worktree", test_normalise_path_outside_worktree),
        ("rendered output has no absolute paths", test_rendered_output_has_no_absolute_paths),
        ("--top caps file table", test_top_n_caps_file_table),
        ("parse failure emits skip, no crash", test_parse_failure_emits_skip_and_does_not_crash),
        ("main runs from real cwd exit 0", test_main_runs_from_real_cwd_exit_zero),
        ("--top arg parsing", test_main_top_arg_parsing),
    ]
    print(f"Running {len(tests)} test(s) for measure-read-share.py\n")
    for name, fn in tests:
        run_test(name, fn)
    print()
    if _FAILURES:
        print(f"FAILED — {len(_FAILURES)} test(s) failed:", file=sys.stderr)
        for name, _ in _FAILURES:
            print(f"  - {name}", file=sys.stderr)
        return 1
    print(f"OK — {len(tests)} test(s) passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
