# Track 3 Technical Review — MurmurHash3 32-bit seeded variant

## Summary

Track 3 adds a `hash32WithSeed(byte[] data, int offset, int len, int seed)` method
to the existing `MurmurHash3` class using the standard MurmurHash3_x86_32 algorithm.
The existing class contains only 128-bit (x64_64) implementations.

## Findings

### Finding T1 [blocker]
**Location**: Track 3 algorithm specification
**Issue**: The plan states "Standard MurmurHash3_x86_32" but provides no constants
or pseudocode. The x86_32 algorithm uses entirely different constants and mixing
logic from the existing x64_64 code (`c1=0xcc9e2d51`, `c2=0x1b873593`, rotation
constants 15/13, finalization constants `0x85ebca6b`/`0xc2b2ae35`). This is not
a simple adaptation of the existing code — it requires a fresh implementation.
**Proposed fix**: Use the canonical MurmurHash3_x86_32 from SMHasher
(aappleby/smhasher MurmurHash3.cpp) as the definitive reference. This is
execution-time guidance, not a plan change.

### Finding T2 [should-fix]
**Location**: Track 3 test vector coverage
**Issue**: The plan requires tests "match the reference C implementation" but
provides no specific test vectors. Known-value assertions are critical for
hash table correctness.
**Proposed fix**: During implementation, compute or verify test vectors against
the reference C implementation. Cover: empty input, single byte, all tail lengths
(1-3), exact 4-byte blocks, multi-block inputs, seed=0 and seed!=0.

### Finding T3 [suggestion]
**Location**: Method signature — `offset` parameter
**Issue**: The proposed `offset` parameter is absent from existing x64_64 overloads,
creating an API inconsistency. However, it is justified by Track 4's use case
(hashing substrings of UTF-8 property names from `BytesContainer`).
**Proposed fix**: Keep the offset parameter; add JavaDoc explaining the parameter.

### Finding T4 [suggestion]
**Location**: Helper method pattern
**Issue**: Existing code uses `getblock()` for 8-byte extraction. The x86_32
algorithm needs 4-byte extraction. Either a `getblock32()` helper or inline
extraction works.
**Proposed fix**: Implementer discretion — either approach is acceptable.

### Finding T5 [suggestion]
**Location**: Hash quality validation
**Issue**: Track 4's perfect hash seed search relies on good avalanche properties.
A faulty port could produce systematic collisions.
**Proposed fix**: Reference SMHasher avalanche test results for MurmurHash3_x86_32.
The algorithm is well-studied; a correct port inherits its properties.

## Conclusion

**PASS** — No true blockers. T1 is addressable during execution by consulting the
SMHasher reference. T2 is standard test engineering. The track is executable.
