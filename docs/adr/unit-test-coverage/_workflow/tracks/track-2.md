# Track 2: Common Pure Utilities

## Description

Write unit tests for the `common` package's pure utility classes that
require no database session. These are self-contained classes with
clear inputs/outputs, making them ideal first targets.

> **What**: Tests for `common/util` (Pair / Triple / RawPair variants /
> ArrayUtils / MultiKey / MultiValue / Streams), `common/types`
> (Modifiable*), `common/collection` (LRUCache / IdentityChangedListener /
> filter helpers), and the `ErrorCode` enum surface.
>
> **How**: Standalone (no base class) JUnit 4 tests preferred — the
> classes have no DB dependency.
>
> **Constraints**: In-scope: `common/util`, `common/types`,
> `common/collection`, `common/exception`. Out-of-scope: parser, I/O,
> concurrency (Tracks 3 / 4).
>
> **Interactions**: Depends on Track 1 (coverage measurement). No
> downstream impact — utility-class tests are self-contained.

## Progress
- [x] Review + decomposition
- [x] Step implementation (5/5 complete)
- [x] Track-level code review (1/3 iterations — PASS, 0 blockers, 0 should-fix remaining, 13 suggestions deferred)

## Base commit
`cf630a7632`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Tests for common/util value holders and ArrayUtils
  - [x] Context: safe
  > **What was done:** Created 3 new test files and extended 1 existing
  > test file, adding 164 tests total. Created `PairTest` (38 tests)
  > covering Pair equals/hashCode/toString/compareTo/init/convert and
  > Triple equals/hashCode/compareTo/toString/setValue/setSubValue.
  > Created `RawPairVariantsTest` (76 tests) covering all 13 RawPair and
  > Pair variant types (RawPair, RawTriple, RawPairIntegerBoolean,
  > RawPairIntegerInteger, RawPairIntegerObject, RawPairLongInteger,
  > RawPairLongLong, RawPairLongObject, RawPairObjectInteger,
  > PairIntegerObject, PairLongObject) with accessors, equals identity,
  > equals inequality, hashCode consistency, compareTo, and toString.
  > Created `ArrayUtilsTest` (37 tests) covering all copyOf/copyOfRange
  > variants, contains variants, hash, plus boundary tests (zero-size,
  > empty-range, end-beyond-source, null-target search). Extended
  > `MultiKeyTest` (+3 tests: reflexive equality, hashCode order
  > independence, toString).
  >
  > **What was discovered:** Found and fixed a genuine bug in
  > `RawPairLongObject.equals()` — line 32 cast to
  > `RawPairIntegerObject<?>` instead of `RawPairLongObject<?>`,
  > causing ClassCastException when comparing two RawPairLongObject
  > instances. Verified no other RawPair variants have the same bug.
  >
  > **Key files:** `PairTest.java` (new), `RawPairVariantsTest.java`
  > (new), `ArrayUtilsTest.java` (new), `MultiKeyTest.java` (modified),
  > `RawPairLongObject.java` (bug fix)

- [x] Step 2: Tests for common/util remaining classes + common/types
  - [x] Context: info
  > **What was done:** Created 6 new test files with 100 tests total.
  > `MemoryTest` (3 tests) — getCappedRuntimeMaxMemory normal path,
  > cap-ignored-when-finite, fixCommonConfigurationProblems verifies
  > disk cache size unchanged on 64-bit. `CollectionsTest` (18 tests) —
  > all 3 indexOf overloads (found, not-found, empty, first-match,
  > custom comparator) plus toString (empty, single, multi, integers).
  > `BinaryTest` (11 tests) — compareTo (equal, less, greater, first/
  > last byte, single byte, empty arrays, different-length arrays
  > documenting pre-existing limitations). `ModifiableIntegerTest`
  > (33 tests) — constructors, increment/decrement/clamping, compareTo,
  > equals/hashCode, all Number methods, toInteger/toString, negative
  > values, overflow/underflow boundaries, max-clamp-overflow bypass.
  > `ModifiableLongTest` (29 tests) — same pattern plus negative and
  > boundary tests. `ModifiableBooleanTest` (6 tests) — constructors,
  > getValue/setValue, toggle.
  >
  > **What was discovered:** Binary.compareTo has pre-existing
  > limitations with different-length arrays — throws AIOOBE when this
  > is longer, returns misleading 0 when this is shorter. Class is
  > deprecated with minimal production usage. ModifiableInteger's
  > increment(int, int) max-clamp is bypassed by integer overflow.
  > Both documented by tests.
  >
  > **Key files:** `MemoryTest.java` (new), `CollectionsTest.java`
  > (new), `BinaryTest.java` (new), `ModifiableIntegerTest.java` (new),
  > `ModifiableLongTest.java` (new), `ModifiableBooleanTest.java` (new)

  Cover Memory, Collections, ClassLoaderHelper, and the types package.

  **New test files:**
  - `MemoryTest` — getCappedRuntimeMaxMemory (normal vs Long.MAX_VALUE
    path), fixCommonConfigurationProblems (test with default config).
    Skip checkCacheMemoryConfiguration (requires Native JNI).
  - `CollectionsTest` — indexOf(List, T, Comparator) found/not-found,
    indexOf(Object[], Comparable) found/not-found/empty, indexOf(int[],
    int), toString(Iterable) empty/single/multi.
  - `BinaryTest` — compareTo: equal arrays, first-less, first-greater,
    element-by-element comparison.
  - `ModifiableIntegerTest` — increment (with/without max clamping),
    decrement, compareTo (less/equal/greater), equals (same type, wrong
    type), all Number methods (byteValue, shortValue, etc.).
  - `ModifiableLongTest` — same pattern as ModifiableInteger.
  - `ModifiableBooleanTest` — getValue/setValue, two constructors.

  **Exclusions:** HeapDumper (OS side-effect, low value per T5).
  CommonConst, PatternConst (constants only, no logic).
  UncaughtExceptionHandler, CallableConsumer, CallableFunction, Service,
  Sizeable, Resettable, SupportsContains (interfaces/functional, no
  standalone logic).

- [x] Step 3: Tests for common/collection gaps
  - [x] Context: info
  > **What was done:** Created 3 new test files and extended 1 existing
  > file, adding 67 tests total. `LRUCacheTest` (8 tests) — put/get,
  > eviction, access-order, small capacity, cacheSize=1 boundary.
  > `IterableObjectTest` (14 tests) — single-value iteration, reset,
  > for-each, array iteration with primitives, empty array, independent
  > iterators. `LazyIteratorListWrapperTest` (7 tests) — delegation,
  > update, empty list. Extended `IdentifiableMultiValueTest` (+23 tests)
  > — map/null/empty getSize, isEmpty with null/Map/empty, first/last
  > value for maps/null/empty arrays, add single value to array, add
  > multiple, remove all/first occurrence.
  >
  > **What was discovered:** LRUCache.removeEldestEntry uses `size() >=
  > cacheSize` making effective capacity `cacheSize - 1`. With
  > cacheSize=1, the cache can never hold any entry. TC review found a
  > pre-existing bug in MultiValue.add(array, array) where
  > System.arraycopy targets `iObject` instead of `copy` — not fixed
  > (test-only plan) but documented. MultiValue.getLastValue has no
  > explicit isEmpty guard for arrays (relies on exception catch).
  >
  > **Key files:** `LRUCacheTest.java` (new), `IterableObjectTest.java`
  > (new), `LazyIteratorListWrapperTest.java` (new),
  > `IdentifiableMultiValueTest.java` (modified)

  Fill coverage gaps in the collection package. Extend existing test
  files where they exist; create new ones where needed.

  **New test files:**
  - `LRUCacheTest` — put/get, eviction when exceeding capacity,
    eldest entry removal, size limit.
  - `IterableObjectTest` — IterableObject: hasNext/next (single-value
    iteration), next past end (NoSuchElementException), reset,
    remove (UnsupportedOperationException). IterableObjectArray:
    hasNext/next through array, past-end exception, empty array, remove
    exception.
  - `LazyIteratorListWrapperTest` — hasNext/next/remove/update
    delegation to list iterator.

  **Extend existing test files:**
  - `IdentifiableMultiValueTest` — add tests for: getSize with Map,
    array, null; isEmpty with empty/non-empty collections/arrays;
    getFirstValue/getLastValue with arrays, maps, single values;
    add/remove methods for Collection and Map targets.

  **Excluded:** SortedMultiIterator (requires DatabaseSessionEmbedded
  per T1 — defer to a track that uses DbTestBase).
  ClosableLinkedContainer, ClosableLRUList (already have dedicated
  test files with reasonable coverage).
  ConcurrentLongIntHashMap (already has extensive test coverage).

- [x] Step 4: Tests for common/comparator + common/factory
  - [x] Context: warning
  > **What was done:** Extended 2 existing test files and created 4 new
  > test files, adding 56 tests total. Extended `DefaultComparatorTest`
  > (+7 tests: both-null, null-first/second, same-reference, Comparable,
  > non-Comparable via factory, non-Comparable exception). Extended
  > `UnsafeComparatorTest` (+13 tests: equal arrays, ByteArrayComparator
  > variants, V2 variants, empty array boundary tests for all 3
  > comparators). Created `CaseInsensitiveComparatorTest` (5 tests).
  > Created `ComparatorFactoryTest` (4 tests: unsafe enabled/disabled,
  > non-byte-array types). Created `ConfigurableStatefulFactoryTest`
  > (13 tests: register/unregister, newInstance, default, error
  > wrapping, unregistered-no-default). Created
  > `ConfigurableStatelessFactoryTest` (9 tests: register/get,
  > default, overwrite, unregisterAll).
  >
  > **What was discovered:** UnsafeByteArrayComparatorV2 has known
  > asymmetry — iterates using arrayOne.length, so calling
  > compare(longer, shorter) with equal prefix would read out of
  > bounds. Tests only exercise the safe direction. ByteArrayComparator
  > compares lengths first, ignoring content when lengths differ.
  >
  > **Key files:** `DefaultComparatorTest.java` (modified),
  > `UnsafeComparatorTest.java` (modified),
  > `CaseInsensitiveComparatorTest.java` (new),
  > `ComparatorFactoryTest.java` (new),
  > `ConfigurableStatefulFactoryTest.java` (new),
  > `ConfigurableStatelessFactoryTest.java` (new)

  Cover comparator implementations and configurable factory classes.

  **Extend existing test files:**
  - `DefaultComparatorTest` — add: both null, one null, same reference,
    Comparable path, non-Comparable with factory, non-Comparable
    without factory (exception).
  - `UnsafeComparatorTest` — add: equal arrays case, ByteArrayComparator
    (same tests), UnsafeByteArrayComparatorV2 (different-length
    handling edge case).

  **New test files:**
  - `CaseInsensitiveComparatorTest` — equal strings, case-insensitive
    ordering, reversed comparison.
  - `ComparatorFactoryTest` — getComparator(byte[].class) with unsafe
    enabled, with unsafe disabled, with non-byte[] class (returns null).
  - `ConfigurableStatefulFactoryTest` — register/unregister,
    newInstance with valid key, null key with default, null key
    without default (exception), instantiation failure wrapping,
    newInstanceOfDefaultClass null default, getRegisteredNames.
  - `ConfigurableStatelessFactoryTest` — register/unregister,
    getImplementation with valid key, null key returns default,
    unregistered key returns null.

- [x] Step 5: Tests for common/exception + common/stream + coverage verification
  - [x] Context: info
  > **What was done:** Created 3 new test files with 45 tests total.
  > `ErrorCodeTest` (18 tests) — getCode for all 6 codes, getErrorCode
  > static lookup (valid/zero/negative/beyond-max), newException success
  > path (QUERY_PARSE_ERROR: type check, message format, cause chain),
  > newException failure paths (abstract BaseException, missing String
  > constructor), throwException 4 overloads with assertEquals on
  > deterministic messages, NPE from throw-null known limitation,
  > ErrorCategory codes and uniqueness checks. `StreamsTest` (20 tests)
  > — mergeSortedSpliterators: both empty, one-empty, interleaved,
  > first/second exhausted first, deduplication (comparator==0 with
  > equals true/false), null-comparator Comparable path (no dedup
  > asymmetry documented), non-Comparable error, composedClose (both
  > clean, first throws, second throws, both throw with suppression),
  > single elements, partial overlap. `BreakingForEachTest` (7 tests) —
  > iterate to end, stop after first, stop mid-stream, empty stream,
  > single element, stop on last element boundary, stop on condition.
  >
  > **What was discovered:** Most ErrorCode members cannot create
  > exceptions via reflection: BackupInProgressException,
  > ConcurrentModificationException, LinksConsistencyException lack
  > String-only constructors; BaseException (used by VALIDATION_ERROR
  > and GENERIC_ERROR) is abstract. Only QUERY_PARSE_ERROR succeeds.
  > throwException NPEs via 'throw null' for failed codes — a missing
  > null check. Streams.mergeSortedSpliterators has a behavioral
  > asymmetry: the explicit-comparator path deduplicates equal elements
  > via equals(), but the null-comparator Comparable fallback does not
  > — both copies are emitted.
  >
  > **Key files:** `ErrorCodeTest.java` (new), `StreamsTest.java` (new),
  > `BreakingForEachTest.java` (new)

  Cover exception utilities and stream utilities, then run coverage
  build and fix any remaining gaps.

  **New test files:**
  - `ErrorCodeTest` — getCode, getErrorCode (valid code, boundary),
    throwException (all 4 overloads), newException (success path,
    reflection failure returns null).
  - `StreamsTest` — mergeSortedSpliterators: both empty, first
    exhausted first, second exhausted first, equal values (comparator
    == 0 with equals true/false), comparator < 0 and > 0, null
    comparator with Comparable objects, null comparator with
    non-Comparable (IllegalArgumentException). composedClose: both
    clean, first throws, both throw (suppressed exception).
  - `BreakingForEachTest` — forEach: normal iteration to end, stop
    after first element, stop mid-stream, empty stream.

  **Verification:**
  Run `./mvnw -pl core -am clean package -P coverage` and check
  per-package coverage with `coverage-analyzer.py`. Fix any packages
  that fall below 85% line / 70% branch by adding targeted tests.
