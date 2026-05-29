#!/usr/bin/env python3
"""Per-worktree token-usage telemetry for the Phase 4 ADR.

Computes a *percentages-only* snapshot of where this worktree's Claude
Code session context went, broken down by tool, plus a top-files table
for the Read tool. The output is a Markdown ``## Token usage telemetry``
section ready to embed verbatim in ``adr.md``.

Why percentages-only: the published ADR ships in a
public repository. Absolute token counts are non-comparable across
worktrees of different durations and carry no information the
percentage split doesn't, so the script never prints them. The only
absolute values in the output are the session count and transcript-file
count, both metadata about *how much* was measured rather than per-bucket
content.

Scope and detection
--------------------
The script measures ``~/.claude/projects/<encoded-cwd>/**/*.jsonl``
recursively. Sub-agent transcripts live under
``<transcript-stem>/subagents/`` and account for the majority of jsonl
files on most worktrees, so a non-recursive glob would silently
under-count. ``<encoded-cwd>`` is the cwd with ``/`` replaced by ``-``;
the cwd is canonicalised via ``Path.cwd().resolve()`` first so symlinked
worktree roots map to the right encoded folder, falling back to the raw
``Path.cwd()`` encoding when the resolved-path folder is missing.

Worktree-vs-main detection routes through the ``.git`` file-vs-directory
shape (documented by ``gitrepository-layout(5)`` and stable across git
versions), not ``git worktree list`` ordering:

  * ``.git`` is a **file** (linked worktree) -> measure.
  * ``.git`` is a **directory** (main checkout) -> emit the
    main-checkout skip notice, exit 0.
  * ``.git`` is **missing** (not in a checkout) -> emit the no-checkout
    skip notice, exit 0.

Measurement methodology
-----------------------
For each jsonl transcript: read every line as JSON; build a
``tool_use_id -> (tool_name, file_path)`` index from ``assistant``
records' ``tool_use`` content blocks; then classify every content block
against the index and tally an approximate token count (char-count / 4,
the standard heuristic shared with ``session-stats.py`` and ``ccusage``)
into one of six buckets. Records are deduped by ``uuid`` so a record that
appears in both an orchestrator transcript and a sub-agent transcript is
counted once. The ``(message.id, requestId)`` key ``session-stats.py``
uses is assistant-only and absent on ``tool_result`` records, so it is
deliberately *not* reused here.

Rendering discipline: the full Markdown section is buffered in memory and
printed atomically on success. On a parse failure mid-walk the script
emits a skip notice naming the offending file and exits 0, so the ADR
commit still succeeds rather than failing on a corrupt transcript line.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

PROJECTS_ROOT = Path.home() / ".claude" / "projects"

# Token-count approximation: 1 token ~= 4 characters. Consistent with
# session-stats.py and ccusage. Absolute counts are never published, so
# the approximation error cancels out of the percentage split.
CHARS_PER_TOKEN = 4

# The six buckets, in the order they render in the tool-mix table. The
# four named tools each get their own bucket; every other tool result
# folds into "Other tool results"; assistant/user text + thinking +
# attachments fold into "Prompts and output".
BUCKET_READ = "Read"
BUCKET_BASH = "Bash"
BUCKET_GREP = "Grep"
BUCKET_EDIT = "Edit"
BUCKET_OTHER = "Other"
BUCKET_PROMPTS = "Prompts"

# Render order + display labels for the tool-mix table. Fixed so the
# section format is stable across ADRs.
BUCKET_ROWS: List[Tuple[str, str]] = [
    (BUCKET_READ, "`Read` tool results"),
    (BUCKET_BASH, "`Bash` tool results"),
    (BUCKET_GREP, "`Grep` tool results"),
    (BUCKET_EDIT, "`Edit` tool results"),
    (BUCKET_OTHER, "Other tool results"),
    (BUCKET_PROMPTS, "Prompts and output"),
]

# Tool name -> bucket for the four named tools. Anything not in this map
# that still resolves to a tool_use index entry goes to BUCKET_OTHER.
NAMED_TOOL_BUCKET: Dict[str, str] = {
    "Read": BUCKET_READ,
    "Bash": BUCKET_BASH,
    "Grep": BUCKET_GREP,
    "Edit": BUCKET_EDIT,
}

# Record types whose content the script tallies. Every other record type
# on disk (mode / permission-mode / last-prompt / pr-link /
# file-history-snapshot / system / custom-title / agent-name / ...) is
# skipped: it carries no token-bearing content block the methodology
# buckets. Using an allow-list rather than the design's skip-list keeps
# the classifier correct when the harness adds new metadata record types.
TALLIED_RECORD_TYPES = frozenset({"assistant", "user", "attachment"})


# ---------------------------------------------------------------------------
# Worktree detection.
# ---------------------------------------------------------------------------

# Sentinels distinguishing the three detection outcomes. The two skip
# cases carry distinct notice strings so an ADR reader can tell which
# trigger fired.
DETECT_WORKTREE = "worktree"
DETECT_MAIN = "main"
DETECT_NO_CHECKOUT = "no-checkout"


def detect_checkout_kind(cwd: Path) -> str:
    """Classify the cwd via the ``.git`` file-vs-directory shape.

    Returns one of DETECT_WORKTREE / DETECT_MAIN / DETECT_NO_CHECKOUT.
    A linked worktree's ``.git`` is a *file* pointing at
    ``.git/worktrees/<name>``; the main checkout's ``.git`` is a
    *directory*; neither shape present means the cwd is not a checkout.
    """
    git_path = cwd / ".git"
    if git_path.is_file():
        return DETECT_WORKTREE
    if git_path.is_dir():
        return DETECT_MAIN
    return DETECT_NO_CHECKOUT


# ---------------------------------------------------------------------------
# Transcript folder resolution.
# ---------------------------------------------------------------------------


def encode_cwd(path: Path) -> str:
    """Encode an absolute path the way Claude Code names its project folder.

    The harness replaces ``/`` with ``-`` in the absolute cwd. A leading
    slash therefore produces a leading dash (e.g. ``/home/u/x`` ->
    ``-home-u-x``), matching the on-disk folder names.
    """
    return str(path).replace("/", "-")


def resolve_transcript_dir(cwd: Path, projects_root: Path = PROJECTS_ROOT) -> Optional[Path]:
    """Return the worktree's transcript folder, or None if neither candidate exists.

    Tries the canonicalised cwd first (``Path.cwd().resolve()`` follows
    symlinks, so a symlinked worktree root maps to the real path the
    harness recorded its transcripts under), then falls back to the raw
    cwd encoding. The fallback handles the inverse case where the harness
    recorded under the symlink name rather than its target.
    """
    resolved = projects_root / encode_cwd(cwd.resolve())
    if resolved.is_dir():
        return resolved
    raw = projects_root / encode_cwd(cwd)
    if raw.is_dir():
        return raw
    return None


def find_transcripts(transcript_dir: Path) -> List[Path]:
    """Recursively collect every ``*.jsonl`` under the transcript folder.

    Recursive so sub-agent transcripts under
    ``<transcript-stem>/subagents/`` are included. Sorted for
    deterministic output ordering.
    """
    return sorted(transcript_dir.rglob("*.jsonl"))


# ---------------------------------------------------------------------------
# Per-file parsing and tally.
# ---------------------------------------------------------------------------


def _approx_tokens(text: str) -> int:
    """Approximate token count from a character count (char / 4)."""
    return len(text) // CHARS_PER_TOKEN


def _block_text_len(block: dict) -> int:
    """Character length of a single content block's text payload.

    Handles assistant ``text`` / ``thinking`` blocks and the ``text``
    blocks nested inside a ``tool_result``'s list content. Image blocks
    (and any other non-textual block) contribute zero — there is no
    char-count to approximate and the methodology counts textual blocks
    only.
    """
    btype = block.get("type")
    if btype == "text":
        return len(block.get("text") or "")
    if btype == "thinking":
        return len(block.get("thinking") or "")
    return 0


def _tool_result_text_len(content) -> int:
    """Character length of a ``tool_result`` block's content.

    ``content`` is either a string (the common case) or a list of blocks
    (e.g. a list of ``text`` blocks, or ``image`` + ``text`` for a Read on
    a binary). The string and list-of-text cases are summed uniformly;
    image blocks contribute zero.
    """
    if isinstance(content, str):
        return len(content)
    if isinstance(content, list):
        total = 0
        for block in content:
            if isinstance(block, dict):
                total += _block_text_len(block)
        return total
    return 0


class TranscriptParseError(Exception):
    """Raised when a transcript line is not parseable JSON.

    Carries the file and line number so the atomic-render skip notice can
    name the offending location.
    """

    def __init__(self, path: Path, line_no: int, detail: str) -> None:
        super().__init__(f"{path}:{line_no}: {detail}")
        self.path = path
        self.line_no = line_no
        self.detail = detail


def tally_file(
    path: Path,
    buckets: Dict[str, int],
    read_files: Dict[str, int],
    seen_uuids: set,
) -> None:
    """Tally one transcript file's token-bearing blocks into the shared accumulators.

    ``buckets`` maps bucket key -> approximate token total. ``read_files``
    maps repo-relative-or-raw file path -> approximate Read token total.
    ``seen_uuids`` dedups records across the recursive walk (a record in
    both an orchestrator transcript and a sub-agent transcript is counted
    once).

    A first pass over the file builds the ``tool_use_id`` index so that
    out-of-order lines (a ``tool_result`` appearing before its
    ``tool_use`` in file order) still resolve. The index is rebuilt per
    file, so a cross-file ``tool_result`` (one whose ``tool_use`` lives in
    a different transcript) does not resolve and folds into the OTHER
    bucket; in practice tool_use/tool_result pairs always stay in one file.
    Raises TranscriptParseError on a malformed JSON line so the caller can
    emit the atomic-render skip notice.
    """
    try:
        raw_lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise TranscriptParseError(path, 0, f"unreadable: {exc}") from exc

    records: List[dict] = []
    for line_no, raw in enumerate(raw_lines, start=1):
        if not raw.strip():
            continue
        try:
            obj = json.loads(raw)
        except ValueError as exc:
            raise TranscriptParseError(path, line_no, f"invalid JSON: {exc}") from exc
        if isinstance(obj, dict):
            records.append(obj)

    # First pass: build the tool_use_id -> (tool_name, file_path) index
    # from assistant tool_use blocks. Done up front so a tool_result that
    # precedes its tool_use in file order still resolves (out-of-order
    # lines).
    index: Dict[str, Tuple[str, Optional[str]]] = {}
    for obj in records:
        if obj.get("type") != "assistant":
            continue
        content = (obj.get("message") or {}).get("content")
        if not isinstance(content, list):
            continue
        for block in content:
            if not isinstance(block, dict) or block.get("type") != "tool_use":
                continue
            tool_use_id = block.get("id")
            if not tool_use_id:
                continue
            name = block.get("name") or ""
            tool_input = block.get("input") or {}
            file_path = tool_input.get("file_path") if isinstance(tool_input, dict) else None
            index[tool_use_id] = (name, file_path)

    # Second pass: classify and tally every block, deduping per record.
    for obj in records:
        rtype = obj.get("type")
        if rtype not in TALLIED_RECORD_TYPES:
            continue

        # Dedup by uuid. Records without a uuid (rare; should not happen
        # for the tallied types) are counted once each — there is no
        # stable cross-record key to dedup them, and dropping them would
        # under-count rather than over-count.
        uuid = obj.get("uuid")
        if uuid is not None:
            if uuid in seen_uuids:
                continue
            seen_uuids.add(uuid)

        if rtype == "attachment":
            # Attachment payloads are context the model saw; fold their
            # JSON-serialised size into Prompts and output.
            att = obj.get("attachment")
            if att is not None:
                buckets[BUCKET_PROMPTS] += _approx_tokens(json.dumps(att))
            continue

        content = (obj.get("message") or {}).get("content")
        if isinstance(content, str):
            # Plain text turn (e.g. a user prompt). Prompts and output.
            buckets[BUCKET_PROMPTS] += _approx_tokens(content)
            continue
        if not isinstance(content, list):
            continue

        for block in content:
            if not isinstance(block, dict):
                continue
            btype = block.get("type")
            if btype in ("text", "thinking"):
                # Assistant prose + reasoning, or a user text block.
                # Both shape the context, so both fold into Prompts and
                # output.
                buckets[BUCKET_PROMPTS] += _block_text_len(block) // CHARS_PER_TOKEN
            elif btype == "tool_use":
                # Indexed in the first pass; not bucketed.
                continue
            elif btype == "tool_result":
                tokens = _tool_result_text_len(block.get("content")) // CHARS_PER_TOKEN
                tool_use_id = block.get("tool_use_id")
                name, file_path = index.get(tool_use_id, ("", None))
                bucket = NAMED_TOOL_BUCKET.get(name, BUCKET_OTHER)
                buckets[bucket] += tokens
                if bucket == BUCKET_READ and file_path:
                    read_files[file_path] = read_files.get(file_path, 0) + tokens


# ---------------------------------------------------------------------------
# Rendering.
# ---------------------------------------------------------------------------


def largest_remainder_percentages(values: List[int]) -> List[float]:
    """Round shares to one decimal so they sum to exactly 100.0.

    Independent ``round()`` per row drops the sum-to-100 invariant on most
    distributions (rounding errors accumulate). The largest-remainder
    method rounds every share down to one decimal, then distributes the
    leftover tenths to the rows with the largest fractional remainders
    until the column sums to exactly 100.0%.

    An all-zero input returns all zeros (no context measured); the caller
    decides whether that is a skip case.
    """
    total = sum(values)
    if total <= 0:
        return [0.0] * len(values)

    # Work in tenths of a percent (integers) to avoid float drift.
    # Each row's exact share in tenths is value / total * 1000.
    exact = [v / total * 1000 for v in values]
    floored = [int(x) for x in exact]
    remainder = 1000 - sum(floored)
    # Distribute the leftover tenths to the largest fractional parts.
    order = sorted(
        range(len(values)),
        key=lambda i: (exact[i] - floored[i]),
        reverse=True,
    )
    for i in range(remainder):
        floored[order[i % len(floored)]] += 1
    return [t / 10 for t in floored]


def normalise_path(file_path: str, repo_root: Path) -> str:
    """Repo-relative path for the top-files table, never an absolute path.

    Uses ``Path.relative_to`` against the worktree root. A path that
    resolves outside the worktree is tagged ``<outside-worktree>`` rather
    than published verbatim — the publication-safety rule forbids
    absolute ``/home/...`` paths in the ADR.
    """
    try:
        return str(Path(file_path).relative_to(repo_root))
    except ValueError:
        return "<outside-worktree>"


def render_section(
    buckets: Dict[str, int],
    read_files: Dict[str, int],
    session_count: int,
    file_count: int,
    repo_root: Path,
    encoded_dir_name: str,
    top_n: int,
) -> str:
    """Render the full ``## Token usage telemetry`` Markdown section."""
    values = [buckets[key] for key, _ in BUCKET_ROWS]
    pcts = largest_remainder_percentages(values)

    lines: List[str] = []
    lines.append("## Token usage telemetry")
    lines.append("")
    lines.append(
        f"Snapshot from this worktree's sessions over its lifetime "
        f"(N={session_count} sessions across {file_count} transcripts)."
    )
    lines.append("")
    lines.append("### Tool mix — share of total session context")
    lines.append("")
    lines.append("| Component             | Share |")
    lines.append("|-----------------------|------:|")
    for (key, label), pct in zip(BUCKET_ROWS, pcts):
        lines.append(f"| {label:<21} | {pct:.1f}% |")
    lines.append("")
    lines.append("### Top files by share of `Read` token consumption")
    lines.append("")
    lines.append("| File                                            | Share of Read |")
    lines.append("|-------------------------------------------------|--------------:|")

    read_total = buckets[BUCKET_READ]
    # Aggregate by normalised path first, so two absolute paths that map to
    # the same repo-relative path (or both to <outside-worktree>) merge.
    agg: Dict[str, int] = {}
    for fp, tok in read_files.items():
        norm = normalise_path(fp, repo_root)
        agg[norm] = agg.get(norm, 0) + tok
    ranked = sorted(agg.items(), key=lambda kv: (-kv[1], kv[0]))[:top_n]
    if read_total > 0 and ranked:
        for norm, tok in ranked:
            share = tok / read_total * 100
            lines.append(f"| {norm:<47} | {share:.1f}% |")
    else:
        lines.append("| (no Read tool calls recorded)                   |          0.0% |")
    lines.append("")
    lines.append(
        f"Generated by `.claude/scripts/measure-read-share.py` against\n"
        f"`~/.claude/projects/{encoded_dir_name}/`."
    )
    lines.append("")
    return "\n".join(lines)


SKIP_MAIN_CHECKOUT = (
    "## Token usage telemetry\n"
    "\n"
    "Skipped: Phase 4 ran from the main checkout, not a dedicated worktree.\n"
    "Per-feature telemetry only applies when each plan is executed in its own worktree.\n"
)

SKIP_NO_CHECKOUT = (
    "## Token usage telemetry\n"
    "\n"
    "Skipped: not running inside a git checkout, so no worktree transcript\n"
    "folder could be resolved.\n"
)

SKIP_NO_TRANSCRIPTS = (
    "## Token usage telemetry\n"
    "\n"
    "Skipped: no transcripts found under this worktree's transcript folder.\n"
    "The worktree may have been used from an IDE without a Claude Code session log.\n"
)


def _skip_parse_error(detail: str) -> str:
    """Skip notice for a mid-walk parse failure (atomic-render discipline)."""
    return (
        "## Token usage telemetry\n"
        "\n"
        f"Skipped: a transcript could not be parsed ({detail}).\n"
        "The telemetry snapshot is omitted so the ADR commit can still succeed.\n"
    )


# ---------------------------------------------------------------------------
# Orchestration.
# ---------------------------------------------------------------------------


def build_output(cwd: Path, top_n: int, projects_root: Path = PROJECTS_ROOT) -> str:
    """Resolve the worktree, walk its transcripts, and return the Markdown section.

    Returns a skip notice for every non-measurement outcome (main
    checkout, no checkout, no transcript folder, empty folder, parse
    failure). Never raises on a transcript problem — the atomic-render
    contract is that the ADR commit always gets a section.
    """
    kind = detect_checkout_kind(cwd)
    if kind == DETECT_MAIN:
        return SKIP_MAIN_CHECKOUT
    if kind == DETECT_NO_CHECKOUT:
        return SKIP_NO_CHECKOUT

    transcript_dir = resolve_transcript_dir(cwd, projects_root)
    if transcript_dir is None:
        return SKIP_NO_TRANSCRIPTS
    transcripts = find_transcripts(transcript_dir)
    if not transcripts:
        return SKIP_NO_TRANSCRIPTS

    buckets: Dict[str, int] = {key: 0 for key, _ in BUCKET_ROWS}
    read_files: Dict[str, int] = {}
    seen_uuids: set = set()

    # A "session" is one top-level transcript (a transcript directly under
    # the worktree's project folder); sub-agent transcripts under
    # <stem>/subagents/ belong to a parent session and are not counted as
    # separate sessions. file_count is every jsonl walked.
    session_count = sum(1 for p in transcripts if p.parent == transcript_dir)
    file_count = len(transcripts)

    try:
        for path in transcripts:
            tally_file(path, buckets, read_files, seen_uuids)
    except TranscriptParseError as exc:
        return _skip_parse_error(str(exc))

    if sum(buckets.values()) == 0:
        # Transcripts existed but carried no token-bearing content.
        return SKIP_NO_TRANSCRIPTS

    return render_section(
        buckets=buckets,
        read_files=read_files,
        session_count=session_count,
        file_count=file_count,
        # Resolve the root so it matches the resolved absolute file_path
        # values stored in transcripts. resolve_transcript_dir already
        # canonicalises via cwd.resolve(); aligning the two here keeps
        # normalise_path from tagging every Read <outside-worktree> on a
        # symlinked worktree root.
        repo_root=cwd.resolve(),
        encoded_dir_name=transcript_dir.name,
        top_n=top_n,
    )


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Per-worktree token-usage telemetry for the Phase 4 ADR."
    )
    parser.add_argument(
        "--top",
        type=int,
        default=10,
        metavar="N",
        help="number of rows in the top-files table (default 10)",
    )
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    top_n = args.top if args.top and args.top > 0 else 10
    # Buffer the full section in memory and print atomically on success;
    # build_output never raises on a transcript problem (it returns a skip
    # notice instead), so a single print is the whole output.
    output = build_output(Path.cwd(), top_n=top_n)
    sys.stdout.write(output)
    if not output.endswith("\n"):
        sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
