# Track 3: MurmurHash3 32-bit seeded variant

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/2 complete)
- [ ] Track-level code review

## Base commit
`dc731e6307`

## Reviews completed
- [x] Technical

## Steps
- [x] Step: Implement `hash32WithSeed(byte[], int, int, int)` in MurmurHash3
  > **What was done:** Implemented standard MurmurHash3_x86_32 algorithm with
  > offset parameter in `MurmurHash3.hash32WithSeed()`. Uses `Integer.rotateLeft()`
  > for cleaner rotation intrinsics. Added 11 known-value tests covering empty
  > input, all tail lengths (1-3 bytes), single block, multi-block, offset
  > correctness, seed variation, and determinism. Verified against canonical
  > reference test vectors (`empty/seed=0→0`, `{0,0,0,0}/seed=0→0x2362f9de`).
  > Code review fix: split bounds assertion to prevent integer overflow.
  >
  > **Key files:** `MurmurHash3.java` (modified), `MurmurHash3Test.java` (modified)

- [ ] Step: Add comprehensive known-value tests for `hash32WithSeed`
  Add test methods to `MurmurHash3Test` covering: empty input (seed=0 and
  seed!=0), all tail lengths (1-3 bytes), exact 4-byte block, multi-block
  inputs (5-8+ bytes), seed variation produces different output, offset
  parameter correctness (hashing a subarray matches hashing the extracted
  subarray). Verify test vectors against reference C implementation output.
