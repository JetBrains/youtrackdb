<!--MANIFEST
dimension: workflow-hook-safety
prefix: WH
findings: 0
evidence_base:
  certs: 0
cert_index: []
flags:
  evidence_trail_exempt: true
  exempt_reason: "(a) no refutation or certificate phase to persist"
index: []
-->

## Findings

No findings. The track is operationally safe.

The hook-relevant change across the full cumulative track diff is confined to
one PreToolUse hook (`.claude/hooks/house-style-write-reminder.sh`) and its pin
test (`.claude/scripts/tests/test_house_style_hook.py`). The hook edit changes
only the literal content of one single-quoted bash string (`tier_b_body`) and
one count word in a comment (`five` to `six`). The test edit adds one element
(`## Plain language`) to the `TIER_B_HEADINGS` Python list. Nothing in scope
touches `/tmp` path construction, external-call timeouts, lock-file logic,
idempotency, secret handling, or JSON-envelope structure. The other changed
files in the track are Markdown only and carry no hook, script, or settings
machinery.

Verified in context against the live hook, live test, and live settings.json:

- **Char-cap budgets hold (SD1 / D2).** Parsing the bodies exactly as
  `_BODY_ASSIGN_RE` does, `tier_b_body` measures 491 chars, under the 500-char
  `PER_BODY_CHAR_CAP`; `tier_a_body` is 366; concatenated bodies measure 857
  chars, under the 1500-char `CONCAT_CHAR_CAP`. The cap constants are unchanged
  (D2: enumeration sync, not a new check).
- **Single-quoted bash literal is intact.** `tier_b_body` contains no single
  quote, so the literal is not prematurely terminated and the test's
  `_BODY_ASSIGN_RE` (`'([^']*)'`) parse stays unambiguous. `bash -n` passes.
- **JSON envelope round-trips on both paths.** The body has no double-quote or
  backslash needing escaping beyond what jq and the Python fallback handle.
  `jq -n --arg msg` and `json.loads(json.dumps(...))` both return the body
  byte-identical (verified live; jq exit 0).
- **Anchor-drift guard is satisfied.** All six cited `§ ...` slugs exist
  verbatim as headings in `house-style.md` (`## Orientation` line 54,
  `## Plain language` line 78, `## Banned vocabulary` line 98,
  `## Banned sentence patterns` line 136, `## Banned analysis patterns`
  line 149, `### Em-dash discipline` line 326), so `test_16_section_name_guard`
  holds and the new `TIER_B_HEADINGS` entry is backed by a real heading.
- **No `/tmp` regression.** The only `/tmp` paths in the hook
  (`${TMPDIR:-/tmp}/house-style-reminder-${session_id}.{txt,lock}`) are
  untouched by this track and already carry the unique `${session_id}` suffix
  with the `$$` fallback documented in the header.
- **Hook wiring and permissions intact.** The git mode stays `100755`
  (executable bit preserved); settings.json is valid JSON and still wires the
  hook as `bash ${CLAUDE_PROJECT_DIR}/.claude/hooks/house-style-write-reminder.sh`
  — the explicit `bash` interpreter means a missing execute bit would not even
  break it.
- **Full suite green within budget.** `bash -n` and `python3 ast.parse` pass;
  the full 18-case suite (including `test_18_reminder_body_length_budget` and
  `test_16_section_name_guard`) reports PASSED within the 3 s per-call budget on
  disk.

The Plain-language carve wording in the hook (`§ Plain language is word-choice
only (common word, expand acronyms, no idioms)`) matches the conventions.md
§1.5 Tier-B restatement from Step 1 and correctly omits the short-sentence /
clause-nesting move that does not carry to comment scale, so producer and
consumer agree.

## Evidence base
