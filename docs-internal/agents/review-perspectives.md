# YTDB review perspectives

Project-supplied review charters, composed per change exactly like the
built-in perspectives (shipped review rules §3). Each charter declares
its own stable finding-ID prefix (§4).

- **Crash safety / durability** (prefix `CS`) — any change touching
  WAL, storage engine, page cache, recovery, or atomic operations;
  reasoning about persistence ordering, fsync boundaries, torn writes,
  and crash-recovery invariants.
- **Design-decision documentation** (prefix `DD`) — any change touching
  code that carries design-decision JavaDoc, or introducing a
  non-obvious design choice; checks that decisions are documented at
  the correct scope level per
  `docs-internal/dev-workflow/design-decisions.md`, that existing
  design-decision entries in the diff's blast radius are not left
  stale, and that cross-package rationale is referenced via
  `{@link}`/`@see` rather than duplicated.
