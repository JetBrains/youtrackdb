# YTDB review perspectives

Slate loads these project review charters through `reviewPerspectivesPath`.
The installed package's `review-rules.md` § Reviewer sets, merge rule and charters
explains how they join the built-in perspectives. Each charter declares its own
stable finding identifier prefix under § Findings and output.

- **Crash safety / durability** (prefix `CS`) — any change touching
  WAL, storage engine, page cache, recovery, or atomic operations;
  reasoning about persistence ordering, fsync boundaries, torn writes,
  and crash-recovery invariants.
