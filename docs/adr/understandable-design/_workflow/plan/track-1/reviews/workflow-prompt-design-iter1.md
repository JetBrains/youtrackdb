<!--MANIFEST
dimension: workflow-prompt-design
target: "Track 1: four agent defs + edit-design/SKILL.md rework + design-review.md de-warm (staged)"
commit_range: 4d3962c97441218d8a78272e92f18b83955bef37..HEAD
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - { id: WP1, sev: Critical, anchor: "wp1-comprehension-review-no-params-file-mandate", loc: "comprehension-review.md:49-51; edit-design/SKILL.md:505,575-577", cert: n/a, basis: judgment }
  - { id: WP2, sev: Recommended, anchor: "wp2-skill-claims-all-mandate-params-read", loc: "edit-design/SKILL.md:505", cert: n/a, basis: judgment }
  - { id: WP3, sev: Minor, anchor: "wp3-agent-descriptions-over-length", loc: "comprehension-review.md:3; readability-auditor.md:3; absorption-check.md:3; design-author.md:3", cert: n/a, basis: judgment }
  - { id: WP4, sev: Minor, anchor: "wp4-descriptions-omit-dispatching-parent", loc: "readability-auditor.md:3; absorption-check.md:3; comprehension-review.md:3", cert: n/a, basis: judgment }
  - { id: WP5, sev: Minor, anchor: "wp5-warmup-wait-mechanism-unspecified", loc: "edit-design/SKILL.md:509-527,937", cert: n/a, basis: judgment }
-->

## Findings

### WP1 [Critical] comprehension-review's `## Inputs` never tells it to read the params file, breaking the shared byte-identical-prompt contract

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/comprehension-review.md` (line 49-51); `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 505, 575-577)
- Axis: clean-context invocation
- Cost: the most-spawned review role (the gate on every creation kind plus the sole reviewer on every interactive kind) is handed a params-file path it is never told to open, so a fresh spawn looks for inline inputs that are not in its byte-identical prompt and cannot reliably locate `design_path` / `mutation_kind` / `scope`.

The SKILL's shared spawn contract makes the params-file-plus-byte-identical-prompt mechanism load-bearing for all four roles. At `SKILL.md:505` it asserts, of every review role: *"Each agent reads its params file as its first action (its agent definition mandates this)."* The comprehension-gate spawn block (`SKILL.md:575-577`) then passes *"a byte-identical body that names the params file … The params file carries the `## Inputs` block the `comprehension-review` agent forwards to `prompts/design-review.md`."* So the orchestrator writes a params file, passes only its path, and relies on the agent to open it.

But the `comprehension-review` agent definition does **not** mandate that read. Its `## Inputs` (line 51) says only: *"The spawn passes the design path(s) and the mutation kind exactly as `prompts/design-review.md § Inputs` specifies."* The word "params file" appears nowhere in the file, there is no "read it as your **first action**" instruction, and `prompts/design-review.md § Inputs` (line 62-102 of the staged copy) lists the inputs as a flat field list with no params-file framing either. The other three agent defs all carry the explicit mandate — `design-author.md:60`, `readability-auditor.md:75`, `absorption-check.md:58` each open with *"Per-agent parameters arrive in a params file whose path the spawn prompt names; read it as your **first action** …"*. The comprehension-review def is the lone role missing it, so the SKILL's blanket claim "its agent definition mandates this" is false for exactly this role.

Spawned fresh with no conversation history, the comprehension-review agent reads its definition, sees "inputs arrive as `design-review.md § Inputs` specifies," and expects them inline in the prompt — but the byte-identical prompt deliberately carries no inline inputs, only a file path. The agent has a path it has not been told is a params file to open. The intended pattern (proven by the three siblings) is an explicit first-action read mandate; without it the spawn contract is unsound for this role.

Suggestion: give `comprehension-review.md § Inputs` the same opening the other three carry — *"Per-agent parameters arrive in a params file whose path the spawn prompt names; read it as your first action so the spawn prompt stays byte-identical across the fan-out. The params file carries the `## Inputs` block forwarded to `prompts/design-review.md` (design path(s), `mutation_kind`, `scope`, …), with no `research_log_path`."* This makes the def's contract match the SKILL's `:505` claim and the byte-identical-prompt cache lever (D13/D14).

### WP2 [Recommended] SKILL asserts all four agent defs mandate the params-file read; one does not

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 505)
- Axis: sub-agent delegation
- Cost: the orchestrator reading `SKILL.md:505` believes every spawned role will open its params file, so it will not add a defensive inline restatement for the one role (comprehension-review) whose def omits the mandate — the delegation annotation over-promises on behalf of a target it does not control.

`SKILL.md:505` states the params-file read is universal: *"Each agent reads its params file as its first action (its agent definition mandates this)."* This is the assurance the orchestrator leans on to keep the spawn prompt body byte-identical (carrying only the path, no inline inputs). The assurance holds for `design-author`, `readability-auditor`, and `absorption-check`, whose defs each carry the mandate verbatim, but not for `comprehension-review` (see WP1). This is the SKILL-side surface of the same gap: even once WP1 is fixed in the agent def, the SKILL's universal claim is the contract an implementer trusts, so the two must be repaired together. If for any reason the comprehension-review def is intentionally left without the mandate (e.g. it is meant to receive inputs inline after all), then `SKILL.md:575-577` must stop routing it through the params-file path and instead inline the `## Inputs` block into its (then non-byte-identical) spawn prompt — but that would forfeit the cache lever for the most-spawned role, so aligning the def to the shared contract (WP1) is the correct direction.

Suggestion: after applying WP1, the `:505` claim becomes true with no SKILL edit. If WP1 is declined, narrow `:505` to "the author, auditor, and absorption check read their params file first" and give the comprehension-gate spawn block its own inline-inputs contract. Do not leave `:505` asserting a universal mandate that one target does not honor.

### WP3 [Minor] all four agent descriptions exceed the system-reminder length budget

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/comprehension-review.md` (line 3); `readability-auditor.md` (line 3); `absorption-check.md` (line 3); `design-author.md` (line 3)
- Axis: description discriminability
- Cost: agent-def `description:` loads into every system reminder; the four together add ~1.6 KB of standing context, and the comprehension-review one (~517 chars) is roughly double the ≤250-char target.

Measured description lengths (chars incl. quotes): design-author 368, readability-auditor 392, absorption-check 391, comprehension-review 519. All exceed the 350-char flag threshold; comprehension-review is the worst. These are sub-agents picked by explicit `subagent_type`, not by orchestrator description-matching, so discriminability is not at risk — but the per-reminder context cost is real and the comprehension-review description spends most of its length re-stating the keep/drop split (`Keeps the comprehension questions, the structural findings …`; the parenthetical `(Grep only to resolve `**Full design**` link targets …)`) that the agent body already covers.

Suggestion: trim each to a one-line discriminator plus the dispatcher. For comprehension-review, drop the keep-list and the Grep parenthetical from the description (they live in the body): e.g. *"De-warmed cold comprehension-and-structure gate for `design.md` and track files: reads only the document, never the research log, runs no prose AI-tell axis. Runs once as the outer gate after the dual-clean inner loop converges. Spawned by edit-design. Read, Grep only."*

### WP4 [Minor] three agent descriptions omit the dispatching parent

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/readability-auditor.md` (line 3); `absorption-check.md` (line 3); `comprehension-review.md` (line 3)
- Axis: frontmatter
- Cost: a reader (or the orchestrator) scanning the agent roster cannot tell from the description alone that these three are launched by `edit-design` / `create-plan`, departing from the repo convention every other sub-agent follows.

The repo convention for sub-agent descriptions is to name the dispatcher: every `/code-review` reviewer ends "Dispatched by /code-review", and `dr-audit` ends "Dispatched by /review-workflow-pr". Among the four new agents only `design-author` follows it (*"Spawned by edit-design (design authoring) and create-plan Step 4b …"*). `readability-auditor`, `absorption-check`, and `comprehension-review` describe their job but never say who spawns them.

Suggestion: append a dispatcher clause to the three, matching `design-author`'s phrasing — e.g. "Spawned by edit-design and create-plan Step 4b." This also folds cleanly into the WP3 trim.

### WP5 [Minor] fan-out warm-up names a wait but no wait mechanism (previously adjudicated at step level)

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 509-527, 937)
- Axis: deterministic decision rules
- Cost: an orchestrator at `SKILL.md:509` reading "spawn one auditor, wait a short fixed delay … then spawn the rest" has no instruction for *how* to wait, and the `Tools used` section (line 937) routes the warm-up delay to `Bash`, which cannot hold a non-blocking foreground delay in this harness.

The warm-up paragraph (line 509-527) describes the lever and the no-block-until-finish rule but leaves the wait mechanism unstated, and it explicitly defers it: *"The wait mechanism is an implementation choice deferred to wiring (gate A7): use whatever non-blocking fixed delay the harness offers …; if no such delay mechanism is available, disable the warm-up and pay N cold prefixes."* The disabled-fallback escape hatch plus the framing of the warm-up as a tunable cost lever (not a correctness dependency) means an implementer who cannot realize the wait still produces correct dual-clean output. This finding was raised and accepted as Minor at the Step-2 step-level review (`workflow-prompt-design-step2-iter1.md` WP2), with the same escape-hatch reasoning; it is recorded here only because the cumulative track diff still carries it. No new action needed beyond what step 2 already accepted.

## Evidence base
