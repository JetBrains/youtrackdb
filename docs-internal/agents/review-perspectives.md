# YTDB review perspectives

- **Crash safety / durability** (prefix `CS`) — any change touching
  WAL, storage engine, page cache, recovery, or atomic operations;
  reasoning about persistence ordering, fsync boundaries, torn writes,
  and crash-recovery invariants.
