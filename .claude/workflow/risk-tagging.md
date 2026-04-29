# Risk-Tagging for Step Decomposition

Each step in a track gets a risk tag — `low`, `medium`, or `high` —
assigned by the decomposer at Phase A and locked once the step is
implemented. The tag controls whether Phase B runs step-level dimensional
review for that step (`high` → yes; `low`/`medium` → no). Track-level
review (Phase C) always runs against the cumulative track diff regardless
of the risk distribution.

The point of the tag is to spend review attention where tests can't easily
catch the issue — concurrency, durability, public API surface, security,
load-bearing architecture, performance hot path. For mechanical changes
well-covered by tests, step-level dimensional review is largely redundant
with tests plus track-level review, and the Phase B context cost isn't
justified.

## Where this file is loaded

- **Phase A (`track-review.md`)** — loaded when the decomposer assigns
  risk per step. Primary reader.
- **Phase B (`step-implementation.md`)** — loaded only on the rare
  upgrade path, when implementation reveals a step is more invasive
  than the plan suggested. Normal Phase B execution does NOT load this
  file; it reads the per-step `**Risk:**` line from the step file and
  gates sub-step 4 on the tag value alone.
- **Phase C (`track-code-review.md`)** — does NOT load this file. The
  Phase C synthesizer reads the per-step risk tags from the step file
  and treats `medium` and `high` step ranges as focal points; no
  knowledge of the underlying criteria is needed.

## Risk levels — quick reference

| Level | Step-level review (sub-step 4) | Track-level review treatment |
|---|---|---|
| `high` | Full dimensional review (4 baseline + conditional, up to 3 iterations) | Focal point |
| `medium` | None | Focal point |
| `low` | None | Default coverage |

## HIGH-risk triggers

A step is `high` if it does ANY of the following.

### Concurrency
- Introduces or modifies synchronization (locks, atomics, volatile,
  memory barriers)
- Changes lock acquisition order, lock scope, or which thread holds a lock
- Adds new thread spawning, executor submission, or async callback
- Touches shared mutable state (static caches, shared collections,
  singletons)
- Modifies code in or around `*StampedLock*`, `*ConcurrentHashMap*`,
  `Atomic*`, or other synchronization classes
- Modifies happens-before relationships or publication ordering

### Crash-safety / Durability
- Modifies WAL records, WAL replay, or recovery code
- Changes page-level operations, atomic operation boundaries, or
  page-level consistency rules
- Touches storage components (`DiskStorage`, `AbstractStorage`
  subclasses, `StorageComponent` and its durable subclasses)
- Changes durability ordering (when `fsync` is called, when WAL is
  flushed, when checksums are validated)
- Modifies on-disk format or record serialization
- Adds or modifies double-write log behavior

### Public API
- Adds, removes, or changes signatures of types in
  `com.jetbrains.youtrackdb.api.*`
- Changes interfaces or abstract classes that have public-API
  implementers
- Modifies SPI interfaces (`META-INF/services/*`)
- Changes the serialized form of any public type

### Security
- Touches authentication, authorization, or permission logic
- Handles user-supplied input at a system boundary (network, file path,
  query string)
- Modifies query construction (SQL or Gremlin), especially code that
  builds query strings dynamically
- Changes file path resolution or symlink handling
- Modifies cryptographic operations or key handling

### Architecture / cross-component coordination
- Changes interfaces between major modules (core ↔ server, core ↔ driver)
- Modifies Component Map relationships listed in the plan
- Introduces a new abstraction layer or moves a load-bearing one
- Adds a new SPI registration or modifies how an existing SPI is loaded

### Performance hot path
- Changes the record-read or index-read path
- Changes the query-execution inner loop
- Introduces or removes allocation in a known hot path
- Modifies cache lookup, hashing, or eviction logic

## MEDIUM-risk triggers

A step is `medium` if no HIGH trigger fires AND it has any of:

- New non-public methods or classes that change observable behavior of
  one component (i.e., not pure refactoring)
- Logic changes touching more than ~5 files within one module
- Changes to test infrastructure or shared test fixtures
- New Maven dependencies, version bumps, or non-trivial build-config
  changes
- Logging changes that affect operational behavior (introduces a new
  log channel, changes log levels of known signals)
- Changes to error-handling code (exception types, retry logic, fallback
  paths) that aren't covered by a HIGH trigger

## LOW-risk default

A step is `low` if no HIGH or MEDIUM trigger fires. Typical cases:
- Pure refactoring with provable behavior preservation (extract method,
  rename, move type — no semantic change)
- Adding new unit tests for existing code
- Updating Javadoc, in-line comments, or `docs/`
- One-line bug fixes with clearly isolated scope (e.g., null check on a
  reference that is documented as nullable)
- Extracting helpers without changing their behavior
- Adding configuration constants or new enum values that aren't yet
  wired to behavior
- Spotless / formatting fixes

## Tests-only steps

A step that ONLY adds or modifies tests (no production code change) is
at most `medium`. It is `medium` only if it touches shared test
infrastructure or test fixtures (which can hide bugs across many tests).
Otherwise `low`.

If a step adds production code AND its tests in one commit, rate by the
production code.

## Override rules

### Decomposer-time override
The decomposer applies the criteria above and may override the result
with a written reason in the step's risk note. Two specific cases:

- **Upgrade to `high` (safe direction):** when the decomposer is
  uncertain whether a step matches a HIGH category. "When in doubt,
  high" — the cost of an extra step-level review is much lower than
  the cost of missing a concurrency or durability bug.
- **Downgrade from a HIGH category (cautious direction):** when the
  step technically touches a HIGH category but the change is provably
  trivial (e.g., Javadoc-only edit inside a `*ConcurrentHashMap*`
  class). Requires a written justification in the risk note.

### User override at Phase A end
After the decomposer writes the step file, the user reviews the step
list and may change any risk tag before approving. This is the primary
safety net for criteria-application errors.

### Phase B upgrade
If implementing a step reveals that the change is more invasive than
the plan suggested (e.g., the "trivial refactor" turned out to require
lock ordering changes), the Phase B agent upgrades the risk to `high`
BEFORE running the dimensional review for that step. Upgrades are
recorded in the step's risk note. Downgrades are NOT permitted
mid-Phase B — once the step has been planned at a given risk level,
the implementer cannot self-relax review pressure.

### Risk locking
After a step is implemented (committed + episode written), the risk tag
is locked. Track-level review reads the locked risk tags and treats
`medium` and `high` as focal points when reviewing the cumulative track
diff.

## Step-file format

Each step entry in `tracks/track-N.md` carries a `**Risk:**` line in
its description blockquote, naming the level and the triggering
category (or `default` / `override: <reason>`).

```markdown
- [ ] Step: Add StampedLock acquisition path for histogram updates
  > **Risk:** high — concurrency (introduces optimistic-read-then-upgrade
  > pattern in PageFrame)

- [ ] Step: Extract HistogramHeader struct from BTreePage
  > **Risk:** low — default (pure refactoring; no semantic change)

- [ ] Step: Wire histogram counter through tx-finalization path
  > **Risk:** medium — multi-file logic in core (no HIGH triggers)

- [ ] Step: Update Javadoc on AtomicLongFieldUpdater usage
  > **Risk:** low — override (touches a HIGH category file but the
  > change is Javadoc-only with no behavioral impact)
```

After implementation the risk line stays in place; the episode appends
below it as today.
