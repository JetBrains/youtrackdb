You are reviewing the full diff of a completed track for systematic issues
that per-step review cannot catch.

Inputs:
- Base commit: {base_commit} (commit before the track's first step)
- Track description: {track_description}
- Step file: {step_file_path} (for context — step episodes explain why
  certain choices were made)

Review the full track diff (`git diff {base_commit}..HEAD`) for:

SYSTEMATIC PATTERNS
- The same mistake repeated across steps
- Inconsistent patterns for the same thing across steps

ACCUMULATED TECHNICAL DEBT
- Individually acceptable compromises that are collectively problematic

INTEGRATION ISSUES
- Steps that compile independently but combine with subtle interactions
- State management across step boundaries

CROSS-STEP CONSISTENCY
- Naming consistency across files modified in different steps
- Error handling patterns that should be uniform

For each issue found, produce a finding:

### Finding C<N> [blocker|should-fix|suggestion]
**Location**: <file(s) and line(s)>
**Issue**: <what's wrong — include cross-step context if applicable>
**Proposed fix**: <concrete change>

Severity guide:
- blocker: Will cause runtime failures or data corruption
- should-fix: Inconsistency or debt that should be addressed now
- suggestion: Improvement that could be deferred
