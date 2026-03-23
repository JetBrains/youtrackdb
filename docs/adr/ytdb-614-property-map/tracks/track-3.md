# Track 3: MurmurHash3 32-bit seeded variant

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
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

- [x] Step: Add comprehensive known-value tests for `hash32WithSeed`
  > **What was done:** Added 22 test methods (17 initial + 5 from code review)
  > forming the full regression safety net. Coverage includes: negative and extreme
  > seeds (MIN_VALUE, MAX_VALUE, -1), all tail lengths with non-zero seed,
  > multi-block inputs (12/13/14/15/16 bytes), high-byte-value data exercising
  > `& 0xff` masking in both block and tail paths, offset with tail bytes, and
  > typical property name strings locking in exact hash values for V2 serializer.
  >
  > **Key files:** `MurmurHash3Test.java` (modified)
