# Defensive push check

Loaded by Phase B `on_success` ([`step-implementation.md`](step-implementation.md))
and Phase C `on_iteration_success`
([`track-code-review.md`](track-code-review.md)) immediately after
the implementer returns `RESULT: SUCCESS`. The implementer contract
([`implementer-rules.md`](implementer-rules.md) §Return contract)
already requires that `SUCCESS` implies `result.COMMIT` has been
pushed to `origin`; this check is the orchestrator-side assertion of
that invariant.

## Why it exists

A clean-context sub-agent can drop a step (run the commit, forget the
push) without intending to violate the contract. Pushing the orphan
locally is a side-effect, not a content decision, so auto-recovery
here is safe — the commit's content has already passed the
implementer's tests and (for fix commits) the dimensional review.
Surfacing every orphan to the user instead would block on a
correctable mechanical slip.

## Procedure

The orchestrator inserts `result.COMMIT` from the implementer's
return block where the recipe says `$COMMIT`. The current branch
must have an upstream — Phase B and Phase C always run after the
branch's first `git push -u`, but the probe below guards against
edge cases (fresh worktree, detached HEAD recovery).

```bash
# 1. Probe upstream. If unset, the implementer's own push would have
#    surfaced the problem; nothing for the orchestrator to verify.
git rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1 \
  || { echo "no upstream — skipping defensive push check"; exit 0; }

# 2. Assert result.COMMIT is reachable from origin.
if git merge-base --is-ancestor "$COMMIT" '@{u}'; then
  : # invariant holds
else
  # Contract violation: implementer returned SUCCESS but the commit
  # is not on origin. Push the orphan and continue.
  git push
fi
```

The check uses `merge-base --is-ancestor` rather than asking whether
`@{u}..HEAD` is empty: the ancestor form directly asserts the
contract ("`result.COMMIT` is on `origin`") instead of the weaker
"no local commits are ahead of `origin`," which can be spuriously
true when `HEAD` happens to match `origin` at a different SHA.

## Recovery push failure

If the orchestrator's recovery `git push` fails, route per
[`commit-conventions.md`](commit-conventions.md) § Push failure
handling: `non-fast-forward` → load
[`branch-divergence-check.md`](branch-divergence-check.md) (gated
to the first per-session rejection, as already specified in
`step-implementation.md`'s established push-failure block); other
shapes (network, auth, pre-receive hook, large-file rejection) →
record the failure in the session log and continue with the next
phase action per the conventions doc's record-and-continue rule.
Do not silently continue with an unpushed commit beyond the
record-and-continue allowance defined there.

## Caller framing

Callers vary the surrounding prose to name the commit class:

- `step-implementation.md` § `on_success` — the implementer's primary
  step commit.
- `track-code-review.md` § `on_iteration_success` — the `Review fix:`
  commit applied on top of the track diff.

Otherwise the recipe is identical at both sites. Update this file,
not the call sites, when the procedure changes.
