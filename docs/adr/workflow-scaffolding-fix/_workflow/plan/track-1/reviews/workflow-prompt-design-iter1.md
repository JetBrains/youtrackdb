<!--MANIFEST
dimension: workflow-prompt-design
iter: 1
findings: 1
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WP1
    sev: Recommended
    anchor: "### WP1 [Recommended]"
    loc: "staged-workflow/.claude/workflow/prompts/create-final-design.md:609-611; workflow.md:770-773"
    cert: n/a
    basis: judgment
-->

## Findings

### WP1 [Recommended] — Shell-comment rationale mis-scopes untracked remnants "under staged-workflow/"

- **File:** `docs/adr/workflow-scaffolding-fix/_workflow/staged-workflow/.claude/workflow/prompts/create-final-design.md` (line 609-611); mirror `.../workflow.md` (line 770-773)
- **Axis:** deterministic decision rules (shell-comment rationale correctness / non-misleading)
- **Cost:** a future editor reading the rationale could narrow the `rm -rf` target and silently reintroduce the bug the fix closes.
- **Issue:** The Step 6 shell comment says the follow-up `rm -rf` "clears the untracked cold-read output, per-round params, and `.pyc` remnants **under `staged-workflow/`** that `git rm` never reaches." Grammatically "under `staged-workflow/`" attaches to the whole list, so it asserts all three untracked kinds live under `staged-workflow/`. The track file's own account contradicts this: the cold-read `output_path` files "land under `_workflow/` untracked" (track-1.md:75), and in D1 (track-1.md:33) "under `staged-workflow/`" scopes only the `.pyc` caches — the cold-read output and per-round params files sit directly under `_workflow/`. The command itself (`rm -rf docs/adr/<dir-name>/_workflow/`) sweeps the whole subtree regardless, so runtime behavior is correct; the defect is a misleading rationale. Its latent risk is that the comment implies the untracked remnants are confined to `staged-workflow/`, inviting a later editor to shrink the delete to `rm -rf …/_workflow/staged-workflow/` and thereby leave the directly-under-`_workflow/` cold-read/params files behind — exactly the untracked-remnant failure this track fixes. The `workflow.md` mirror carries the identical mis-attachment. Note the prose-body sentence directly below the comment (create-final-design.md:622-623, "removes the untracked cold-read output and params remnants `git rm` cannot reach") is correctly unscoped — so the comment and its own adjacent prose disagree on scope.
- **Suggestion:** Re-scope the `staged-workflow/` qualifier to the `.pyc` caches only, matching the track file. e.g.: `# the follow-up rm -rf clears the untracked cold-read output and per-round params directly under _workflow/, plus any .pyc caches under staged-workflow/, that git rm never reaches.` Apply the same correction to the `workflow.md` § Final Artifacts mirror (line 770-773) to keep the documented mirrors in step.

## Evidence base
