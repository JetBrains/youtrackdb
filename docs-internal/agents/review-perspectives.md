# YTDB review perspectives

Project-supplied review charters, composed per change exactly like the
built-in perspectives (shipped review rules §3). Each charter declares
its own stable finding-ID prefix (§4).

- **Crash safety / durability** (prefix `CS`) — any change touching
  WAL, storage engine, page cache, recovery, or atomic operations;
  reasoning about persistence ordering, fsync boundaries, torn writes,
  and crash-recovery invariants.
