<!-- MANIFEST
findings: 1   severity: {Critical: 0, Recommended: 1, Minor: 0}
index:
  - {id: WH1, sev: Recommended, loc: ".claude/hooks/house-style-write-reminder.sh:254-262", anchor: "### WH1 ", cert: n/a, basis: "tier_b_body grew to 545 chars, over the documented per-body 500-char cap the comment claims is test-validated; no test asserts it"}
evidence_base: {section: "## Evidence base", certs: 0}
flags: [CONTRACT_OK]
-->

## Findings

### WH1 [Recommended] tier_b_body exceeds the documented 500-char per-body cap; the comment's "validated by the test runner" claim is false

- **File:** `.claude/hooks/house-style-write-reminder.sh` (lines 254-262; the comment is 254-259, the body is 262); cross-references `.claude/scripts/tests/test_house_style_hook.py`.
- **Axis:** error handling / self-documenting contract (hook performance budget the hook documents in its own comments).
- **Cost:** the hook's own contract comment is now untrue, and the per-body length budget it claims is enforced has no guard at all, so the next body growth can overrun silently. No runtime failure today.
- **Issue:** the comment at lines 254-259 states each reminder body is "≤500 chars; concatenated ≤1500 chars including the JSON envelope (validated by the test runner)." This change extends `tier_b_body` (line 262) with the `§ Orientation` slug plus a one-sentence restatement, taking it to **545 chars** (555 bytes) — past the documented 500-char per-body cap. Verified by extracting the body and measuring, and by running the live hook (jq path emits a 545-char `additionalContext`). A grep of the entire test file for any length/budget assertion returns nothing beyond the 3.0s wall-clock `assert_within_budget` machinery — there is **no** character-count assertion on either the per-body 500 cap or the concatenated 1500 cap. So the "validated by the test runner" claim was already untrue for the length budget, and this change pushes a body over the stated cap with nothing to catch it. The scope note asks specifically whether the test gates what its comment claims; it does not. The concatenated total stays safe (913 chars / 926 bytes, well under 1500), and the emitted JSON is valid on both the jq and Python-fallback paths (verified), so there is no truncation, JSON-size, or envelope failure now — this is a documentation-versus-guard gap, not a runtime break, hence Recommended rather than Critical.
- **Suggestion:** pick one of two fixes. (a) Make the comment true: add a length check to the test runner — read both `tier_*_body` assignments from the hook source (or assert on the emitted `additionalContext` length) and fail if any single body exceeds 500 chars or the concatenated body exceeds 1500. This also guards future growth. (b) If 500 was a soft target and 545 is acceptable, correct the comment's per-body figure and drop "(validated by the test runner)" from the per-body clause so the comment no longer claims a guard that does not exist. Option (a) is preferable because the comment's stated intent — a real ceiling on reminder size injected into every matching Write/Edit — is worth enforcing.

## Evidence base
