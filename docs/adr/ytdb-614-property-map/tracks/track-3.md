# Track 3: MurmurHash3 32-bit seeded variant

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/2 complete)
- [ ] Track-level code review

## Base commit
`dc731e6307`

## Reviews completed
- [x] Technical

## Steps
- [ ] Step: Implement `hash32WithSeed(byte[], int, int, int)` in MurmurHash3
  Standard MurmurHash3_x86_32 algorithm (reference: SMHasher MurmurHash3.cpp).
  Single 32-bit state, 4-byte block processing, tail handling (0-3 bytes),
  fmix32 finalization. Constants: c1=0xcc9e2d51, c2=0x1b873593, rotations 15/13,
  fmix constants 0x85ebca6b/0xc2b2ae35. Include offset parameter for hashing
  substrings without allocation (needed by Track 4 for BytesContainer property
  names). Add JavaDoc referencing the algorithm.

- [ ] Step: Add comprehensive known-value tests for `hash32WithSeed`
  Add test methods to `MurmurHash3Test` covering: empty input (seed=0 and
  seed!=0), all tail lengths (1-3 bytes), exact 4-byte block, multi-block
  inputs (5-8+ bytes), seed variation produces different output, offset
  parameter correctness (hashing a subarray matches hashing the extracted
  subarray). Verify test vectors against reference C implementation output.
