<!-- MANIFEST
dimension: workflow-prompt-design
scope: track
target: "Track 2 (87f40db9af..HEAD) — reuse the loop at track authoring and Phase 4; collapse the 4a/4b boundary"
findings:
  - {id: WP1, sev: Recommended, loc: "_workflow/staged-workflow/.claude/workflow/prompts/create-final-design.md:229-247", anchor: "### WP1 ", cert: n/a, basis: "create-final-design tells the caller to pass episodes_path + output_path as named edit-design inputs, but edit-design declares neither in its ## Inputs table and derives episodes_path from plan_dir — the one key create-final-design tells the caller to omit"}
  - {id: WP2, sev: Minor, loc: "_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md:826-837", anchor: "### WP2 ", cert: n/a, basis: "Step-4b absorption-check spawn passes draft_path = the plan/ directory while the absorption-check agent's draft_path is documented as the design.md draft or the plan_dir; the directory-vs-file overload is not called out at the spawn site"}
  - {id: WP3, sev: Minor, loc: "_workflow/staged-workflow/.claude/agents/fidelity-check.md:3", anchor: "### WP3 ", cert: n/a, basis: "fidelity-check description is 478 chars — over the 350 hard-flag threshold for a frontmatter description loaded into every system reminder"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### WP1 [Recommended] `create-final-design.md` threads `episodes_path` / `output_path` as `edit-design` inputs the skill does not declare, and tells the caller to omit the key the skill derives `episodes_path` from

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/workflow/prompts/create-final-design.md` (lines 229-247)
- **Axis:** sub-agent delegation / clean-context invocation
- **Cost:** the caller (the final-designer running Phase 4) follows the prompt to pass two inputs the receiving skill has no slot for, and to omit the input the skill actually reads `episodes_path` from — so the fidelity check can silently spawn with an empty or wrong `episodes_path` and match `design-final.md` against nothing.
- **Issue:** Sub-step B's new block (lines 229-247) tells the caller "The fidelity check needs two more inputs threaded through to it... so pass both when invoking the skill," then names the **episodes path** (`docs/adr/<dir-name>/_workflow/plan/`) and the **`output_path`**. But the `edit-design` `## Inputs` table (`edit-design/SKILL.md` lines 105-113) declares neither `episodes_path` nor a top-level `output_path` — they are not skill parameters. `edit-design` instead *derives* the fidelity check's `episodes_path` internally from `plan_dir` (`edit-design/SKILL.md:704`, `episodes_path=<plan_dir>`) and *constructs* the comprehension gate's `output_path` itself for `phase4-creation` (`edit-design/SKILL.md:617-624`). Worse, the same Sub-step B explicitly tells the caller to **omit `plan_dir`** (line 218) — the one declared key `edit-design` reads `episodes_path` from. So the caller is told to pass a non-existent `episodes_path` parameter while withholding the real `plan_dir` it maps to. The prose at line 237-239 ("distinct from the omitted `--plan-dir` flag, which governs only the cross-file `**Full design**` ref check") shows the author noticed `plan_dir` does double duty as both the script flag and the fidelity `episodes_path`, but the resolution it lands on — "pass a separate `episodes_path`" — is not wired into `edit-design`'s input surface.
- **Suggestion:** Reconcile the two prompts on one contract. Either (a) make `create-final-design.md` pass `plan_dir` set to the episodes directory (and have `edit-design` use it both for the fidelity `episodes_path` and skip the `**Full design**` ref check by some other signal), or (b) add `episodes_path` (and, if genuinely caller-supplied, `output_path`) as named rows in `edit-design`'s `## Inputs` table so the threaded inputs land in real slots. As written, the caller-side instruction names inputs the receiving skill cannot consume.

### WP2 [Minor] Step-4b `absorption-check` spawn passes `draft_path` = the `plan/` directory; the directory-vs-file overload is not flagged at the spawn site

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 826-837, the per-round `absorption-check` bullet)
- **Axis:** sub-agent delegation
- **Cost:** mild ambiguity for the spawning orchestrator — `draft_path` reads as a single-file path on the design path but is a directory on the track path, and the spawn site does not say which the agent expects.
- **Issue:** The Step-4b loop spawns `absorption-check` with "`draft_path` set to the `plan/` directory" (line 830). The `absorption-check` agent definition does document this overload (`absorption-check.md:62`: "`draft_path` — the `design.md` draft, or the `plan_dir` of track files"), so the contract resolves correctly. But the `create-plan` spawn site states the directory value without the one-clause reminder that `draft_path` is a directory here, not a file — unlike the auditor bullet just above it, which is careful to spell out "`target_path` set to that one track file." A reader cross-checking the spawn against the `design-author`/`edit-design` design-path convention (where `draft_path=<design_path>`, a file) could mis-set it to a single track file. The contract is sound; the spawn-site self-documentation is thinner than its sibling bullet.
- **Suggestion:** Add a half-clause at line 830, e.g. "`draft_path` set to the `plan/` *directory* (the absorption check reads every `plan/track-N.md` `## Decision Log` under it, per `absorption-check.md § Inputs`)," so the directory semantics are explicit at the spawn site and match the auditor bullet's precision.

### WP3 [Minor] `fidelity-check` frontmatter `description:` is ~478 chars — over the per-reminder budget threshold

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/fidelity-check.md` (line 3)
- **Axis:** frontmatter / description discriminability
- **Cost:** the description loads into every system reminder for the life of any session that can spawn this agent; at ~478 chars it is past the ≤250 target and the 350 hard-flag bound, a small standing context cost.
- **Issue:** The `description:` packs the full mechanism — "matching it against the step and track episodes, falling back to PSI for the diagram, signature, and no-episode-trace residual. Reads no research log. Runs every round of the phase4-creation dual-clean inner loop in place of the absorption check. Spawned by edit-design on phase4-creation." It is discriminative (the trailing "Spawned by edit-design on phase4-creation" correctly marks it a sub-agent, and the "in place of the absorption check" line distinguishes it from its sibling), which is the important property. The length is the only issue. The sibling `absorption-check.md:3` runs shorter while keeping the same discriminators, so the budget is reachable without losing signal.
- **Suggestion:** Trim to the discriminating core, e.g.: *"Phase 4 per-round fidelity check for `design-final.md`: matches the final design against the step/track episodes, PSI for the residual; reads no research log. Runs in place of the absorption check on phase4-creation. Spawned by edit-design."* — preserves caller, slot, and the no-log/episodes discriminators under the budget.

## Evidence base
