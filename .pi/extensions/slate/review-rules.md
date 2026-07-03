# Slate review rules

How the orchestrator reviews work produced by worker threads. Review
threads are created dynamically like any other thread; there is no fixed
roster. These rules govern how reviews are composed, not who performs
them. Worker threads do not automatically load this document. When
dispatching a reviewer, either embed in the task text the perspective's
charter (§3), the reasoning obligations (§7), and the output contract
(§4: the perspective's ID prefix, the severity vocabulary
blocker/should-fix/suggestion, and a closing compact findings block —
one `ID` | `severity` | `location` | `one-line summary` |
`counterexample gist` line per finding, see §5), or direct the
reviewer to read the relevant sections of this file itself (worker
threads can read files; they just do not receive this one
automatically).

## 1. When to review

- Every non-trivial change gets reviewed before it is declared done.
- Rigor scales with risk of the change, never with effort already spent.
  When in doubt, review more.
- For long multi-step work streams, review high-risk steps in isolation
  right after they land — defects localized to one step get buried once
  the diff folds into the cumulative result — and always review the
  cumulative diff at the end regardless of per-step reviews.

## 2. Reviewers are fresh threads

- A review perspective is always a NEW thread. Never reuse the
  implementing thread and never review substantial work yourself: fresh
  eyes are the point.
- Do not pass implementer episodes or implementer reasoning to
  reviewers. Give a reviewer the artifact/diff, the intent of the
  change, its in-scope files, and its charter plus reasoning/output
  obligations (see the intro).
- Independent perspectives run in PARALLEL on the same artifact.

## 3. Choose perspectives by what the change touches

There is no fixed roster — compose perspectives per change. Each
bullet below is that perspective's charter:

- **Code baseline** (any production/test code change): correctness &
  bugs (logic, null safety, resource leaks, lifecycle); code quality
  (readability, duplication, error handling); test quality (do tests
  verify behavior, cover the change, and would they fail on
  regression?).
- **Specialists**, added when the change touches their domain:
  - concurrency — any defect requiring reasoning about thread
    interleavings (locks, shared state, atomicity);
  - crash safety / durability — WAL, storage engine, page cache,
    recovery, atomic operations;
  - security — public API surface, authentication, user input,
    network, (de)serialization;
  - performance — hot paths, lock contention, caching, algorithmic
    complexity, large data structures;
  - test structure — complex fixtures, shared state between tests,
    isolation and lifecycle concerns.
- **Non-code artifacts** (docs, prompts, process rules, extension
  guidance like this file) get their own perspectives INSTEAD of the
  code baseline: internal consistency & cross-references; instruction
  completeness (can a reader execute this without guessing?); context
  budget (what becomes always-loaded vs on-demand); writing style;
  and safety of any scripts/hooks/config touched.
- **Mixed diffs**: scope each reviewer to its in-scope files. Code
  reviewers never review process/docs files, and vice versa.
- Selection is a judgment call, not a rigid filter — when in doubt,
  include the perspective. Risk/complexity changes how deep iteration
  goes (§6), never which perspectives run.

## 4. Findings

- Each reviewer emits findings with stable IDs prefixed by perspective
  (BG = bugs, CQ = code quality, TQ = test quality, CN = concurrency,
  CS = crash safety, SE = security, PF = performance, TS = test
  structure, WC = consistency, WI = completeness, WB = context budget,
  WS = writing style, WH = script/hook/config safety, RG = regression
  (filed by gate threads)). IDs are cumulative across iterations and
  are never renumbered — they are the sole addressing key for fixes
  and verification.
- The orchestrator owns the cumulative finding-ID ledger: when
  spawning a later-iteration reviewer or gate thread, tell it the next
  free number for its prefix.
- Severity: **blocker** (must fix before done), **should-fix** (fix
  unless explicitly justified and reported), **suggestion** (optional).
- Synthesize across reviewers: deduplicate by location/root cause,
  keep all contributing IDs on the merged finding, and take the
  highest severity when duplicates disagree (upgrade-only — never
  downgrade a severity during synthesis).

## 5. Reviewers find, implementers fix

- Review threads never edit files; fix work is dispatched to an
  implementation thread (usually the one that produced the change, or
  a fresh one if it is gone or compromised — context-poisoned or
  repeatedly failing). The orchestrator never edits files itself.
- Episodes are compressed summaries (~300–800 words), so full finding
  prose does not reliably survive them. Reviewers must therefore end
  their final response with a self-contained compact findings block —
  one `ID` | `severity` | `location` | `one-line summary` |
  `counterexample gist` line per finding — sized to survive episode
  compression. Route fixes by passing the reviewer's episode reference
  (`context`) plus the compact finding index (the orchestrator's
  synthesized list of all open findings, one `ID` | `severity` |
  `location` | `one-line summary` line each); fixers re-read the
  affected code themselves instead of relying on reviewer prose.

## 6. Iteration and termination

- After fixes, re-verify each addressed finding — in a gate thread,
  not by trusting the fixer's claim. A gate thread is a fresh thread
  whose sole job is verdicts; it receives the compact finding index
  (§5) and the fix diff, but not the fixer's or implementer's
  episodes. Verdict per finding:
  - **VERIFIED** — the fix resolves the finding;
  - **REJECTED** — the change claimed as the fix does not address the
    finding;
  - **STILL OPEN** — the finding was not addressed, or the attempted
    fix leaves the defect in place;
  - **MOOT** — the finding is obsoleted by other changes;
  - **REGRESSION** — the fix broke something else; file the breakage
    as a new blocker (RG prefix).
- Blockers loop until clear. Should-fix findings get up to 3
  iterations for normal changes; go deeper only for high-risk changes.
- No-progress rule: if an iteration clears nothing and surfaces no new
  fixable finding, stop and escalate to the user instead of spinning.
- Done = no blockers remain, addressed findings VERIFIED, and any
  remaining should-fix/suggestions explicitly reported to the user.

## 7. Semi-formal reasoning (how reviewers argue)

Reviewers use semi-formal reasoning — structured argument between
free-form chain-of-thought and formal proof (Ugare & Chandra, "Agentic
Code Reasoning", arXiv:2603.01896). Embed these obligations in every
review dispatch:

- State the decision criteria (definitions) and numbered premises
  before reaching any verdict.
- Trace, don't guess: cite evidence from code actually read (file:line
  or diff hunk) for every claim about behavior. Read third-party /
  library code instead of assuming its semantics.
- Enumerate cases exhaustively along the changed execution paths
  (branches, error paths, boundary values); mark each case as checked
  or explicitly out of scope.
- Back every defect claim (blocker or should-fix) with a concrete
  counterexample: the input, state, or interleaving that triggers the
  defect, traced through the code.
- Back every correctness claim ("no issue here") with a justification
  for why no counterexample exists — not merely the absence of one
  found.
- Before finalizing, run an alternative-hypothesis check: "if the
  opposite verdict were true, what evidence would exist?" — then look
  for that evidence.
- While exploring, log hypotheses explicitly (hypothesis → evidence
  sought → confirmed / refuted / refined) instead of wandering.
- Derive the final verdict explicitly from the stated definitions and
  claims, not from overall impression.
