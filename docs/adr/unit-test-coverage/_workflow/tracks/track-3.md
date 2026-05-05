# Track 3: Common I/O, Parser & Logging

## Description

Write unit tests for common infrastructure classes that handle I/O,
parsing, and logging. Most of these are pure utilities but some have
external dependencies (file system, native libraries).

> **What**: Tests for `common/parser` (string/variable parsers),
> `common/io` (IOUtils, FileUtils), `common/profiler/metrics`
> (MetricsRegistry), and `common/log` (LogManager).
>
> **How**: Standalone tests where possible; `TemporaryFolder` JUnit
> rule for `common/io` tests that touch the file system.
> `@SequentialTest` for `MetricsRegistryTest` (JMX isolation).
>
> **Constraints**: In-scope only the four listed packages. Native /
> JNI paths in `IOUtils` are out of unit-test scope.
> `common/serialization` deferred to Track 12.
>
> **Interactions**: Depends on Track 1. No downstream impact — all
> changes localized to `common`.

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [x] Track-level code review (2/3 iterations)

## Base commit
`49fc317cbc`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Tests for common/parser — StringParser, VariableParser, SystemVariableResolver
  - [x] Context: safe
  > **What was done:** Created 3 new test files with 96 tests total covering
  > StringParser (67 tests), VariableParser (14 tests), SystemVariableResolver
  > (15 tests). All standalone JUnit 4 tests without DbTestBase. Review added
  > 13 boundary/error-path tests and removed 2 duplicate tests.
  >
  > **What was discovered:** `indexOfOutsideStrings` backward search has a
  > pre-existing bug — the loop condition `--i < iFrom` exits after checking
  > only the start position, making backward search effectively single-position.
  > `VariableParser.resolveVariables` loses the default value during recursive
  > resolution (only the rightmost variable gets the default). Both documented
  > in test assertions.
  >
  > **Key files:**
  > - `core/src/test/java/.../common/parser/StringParserTest.java` (new)
  > - `core/src/test/java/.../common/parser/VariableParserTest.java` (new)
  > - `core/src/test/java/.../common/parser/SystemVariableResolverTest.java` (new)

  Create 3 new test files for the parser package's static/standalone utilities:

  **New test files:**
  - `StringParserTest` — getWords (3 overloads: simple split, with trim,
    with custom separators, empty input, quoted strings, escape sequences),
    split (by delimiter, with string quoting, empty segments),
    indexOfOutsideStrings (found/not-found, inside quotes, boundary
    positions), jumpWhiteSpaces (leading, trailing, all-whitespace, no
    whitespace), jump (skip given chars, mixed chars, empty string),
    readUnicode (2 overloads: valid 4-digit hex, invalid hex, end of
    string), replaceAll (simple, no match, multiple occurrences),
    startsWithIgnoreCase (match, mismatch, case variation, prefix longer
    than string).
  - `VariableParserTest` — resolveVariables: simple variable `${var}`,
    nested `${a${b}}`, no variables (passthrough), variable with default
    value, undefined variable (null from listener), recursive resolution,
    empty begin/end markers, partial markers (no close).
  - `SystemVariableResolverTest` — resolveVariable: known system property
    (`java.version`), unknown property (returns null), with custom default,
    resolveSystemVariables: string with `${VARIABLE}`, string without
    variables (passthrough), mixed text and variables.

  **Exclusions:** ContextVariableResolver (requires CommandContext mock,
  no Mockito in core — T3), VariableParserListener (interface only).

- [x] Step 2: Tests for common/parser — BaseParser
  - [x] Context: info
  > **What was done:** Created BaseParserTest with 86 tests using a minimal
  > TestableBaseParser subclass. Covers static methods (nextWord, getWordStatic),
  > instance methods (word/keyword parsing, position management, escape sequences,
  > quoted strings, bracket/brace/parenthesis nesting, parserNextChars). Review
  > added 14 boundary tests and strengthened assertions on 10+ existing tests.
  >
  > **What was discovered:** (1) parserNextWord does NOT treat backticks as string
  > delimiters (only single/double quotes); backtick stripping happens post-parse
  > in parseOptionalWord/parserRequiredWord, so backtick identifiers with spaces
  > are truncated. (2) Parenthesis '(' is a default separator, so the nesting
  > counter only applies with custom separators. (3) Escape inside braces follows
  > a distinct literal-treatment path (escapePos counting) unlike normal escapes.
  > (4) The original parserNextChars test with ">=rest" never reached the FOUND
  > branch — fixed to use separator-terminated inputs.
  >
  > **Key files:**
  > - `core/src/test/java/.../common/parser/BaseParserTest.java` (new)
  Create a minimal test-only concrete subclass (`TestableBaseParser`) and
  a comprehensive test class for BaseParser's instance and static methods.

  **New test files:**
  - `BaseParserTest` — uses a `TestableBaseParser` inner class that
    implements `throwSyntaxErrorException` as `throw new
    IllegalArgumentException(iText)`.

    Static methods: `nextWord` (word with separators, quoted word, end of
    string), `getWordStatic` (normal word, with custom separator chars,
    bracket skipping, parenthesis skipping).

    Instance methods — word parsing: `parserNextWord` (simple word,
    separator handling, quoted strings with single/double quotes, escaped
    quotes inside strings, bracket nesting, end of text, custom additional
    separators), `parserOptionalWord` (word found, end of text returns
    null), `parseOptionalWord` (match from keyword set, no match returns
    null), `parserRequiredWord` (word found, end of text triggers syntax
    error).

    Instance methods — keyword parsing: `parserRequiredKeyword` (match,
    mismatch triggers syntax error, multiple allowed keywords),
    `parserOptionalKeyword` (match returns true, mismatch returns false
    and resets position).

    Instance methods — position management: `parserGetCurrentPosition`,
    `parserSetCurrentPosition` (valid, beyond end), `parserGoBack`,
    `parserMoveCurrentPosition`, `parserIsEnded`, `parserGetCurrentChar`,
    `parserSkipWhiteSpaces`, `parserSetEndOfText`, `parserGetLastWord`,
    `parserGetLastSeparator`/`parserSetLastSeparator`.

    Instance methods — complex parsing: `parserNextChars` (multi-char
    tokens, case sensitivity toggle, lookahead from valid keywords).

- [x] Step 3: Tests for common/io — IOUtils and FileUtils
  - [x] Context: info
  > **What was done:** Extended IOUtilsTest with 57 new tests and created
  > FileUtilsTest with 45 tests (102 total). IOUtils covers getTimeAsString,
  > encode, java2unicode, encodeJsonString, isStringContent, getStringContent,
  > wrapStringContent, equals, isLong, getUnixFileName, getRelativePathIfAny,
  > writeFile, copyStream, readFully, BOM stripping. FileUtils covers
  > getSizeAsNumber, string2number, getSizeAsString, getDirectory/getPath,
  > checkValidName, deleteRecursively, deleteFolderIfEmpty, copyFile,
  > copyDirectory, renameFile, delete, prepareForFileCreation,
  > atomicMoveWithFallback.
  >
  > **What was discovered:** `IOUtils.isLong("")` returns `true` due to a
  > vacuous-truth bug — the loop body never executes for empty input. Callers
  > like `BinaryComparatorV0` would then get `NumberFormatException` from
  > `Long.parseLong("")`. Documented in test; potential fix for Track 22.
  > Also: `getTimeAsString` and `getSizeAsString` use strict `>` comparisons,
  > meaning exactly 1 second = "1000ms", exactly 1KB = "1024b".
  >
  > **Key files:**
  > - `core/src/test/java/.../common/io/IOUtilsTest.java` (modified)
  > - `core/src/test/java/.../common/io/FileUtilsTest.java` (new)
  Extend the existing `IOUtilsTest` and create a new `FileUtilsTest`.

  **Extend existing test file:**
  - `IOUtilsTest` — add tests for untested pure utility methods:
    `getTimeAsString` (milliseconds to string, negative, zero),
    `encode` (special characters, null), `java2unicode` (ASCII passthrough,
    non-ASCII conversion, mixed), `encodeJsonString` (quotes, backslash,
    control chars, newline, tab, unicode), `isStringContent` (with quotes,
    without quotes, empty, null), `getStringContent` (strip quotes, no
    quotes), `wrapStringContent` (add quotes), `equals(byte[], byte[])`
    (equal, different length, different content, both null, one null),
    `isLong` (valid long, not a long, boundary values, null),
    `getUnixFileName` (backslash to forward slash, already unix),
    `getRelativePathIfAny` (with root prefix, without, null root).
    Add I/O method tests with temp files: `writeFile` (write and read
    back), `copyStream` (input to output), `readFully` (byte array from
    stream).

  **New test file:**
  - `FileUtilsTest` — pure methods: `getSizeAsNumber` (bytes, KB, MB, GB,
    TB, with/without decimal, already-number input, null), `string2number`
    (integer, float, negative, scientific notation), `getSizeAsString`
    (bytes, KB, MB, GB, TB boundary formatting), `getDirectory` (with
    separator, without separator, root), `getPath` (extract path portion),
    `checkValidName` (valid name, name with null bytes throws).
    File operations (using JUnit `@Rule TemporaryFolder`):
    `createDirectoryTree` (nested dirs), `deleteRecursively` (non-empty
    dir, empty dir, single file), `deleteFolderIfEmpty` (empty deletes,
    non-empty keeps), `copyFile` (content preserved), `copyDirectory`
    (recursive copy), `renameFile` (rename and verify), `delete` (single
    file, non-existent returns false), `prepareForFileCreationOrReplacement`
    (clears existing), `atomicMoveWithFallback` (move with fallback).

    **Dead code note (T2):** `useOldFileAPI=true` branches are unreachable
    on JDK 21 — do not target.

- [x] Step 4: Tests for common/log + common/profiler/metrics + coverage verification
  - [x] Context: warning
  > **What was done:** Created 5 new test files with 50 tests total:
  > AnsiCodeTest (9), LogFormatterTest (10), AnsiLogFormatterTest (7),
  > MetricsRegistryTest (10), MetricFactoryTest (14). Coverage results:
  > parser 95.4%/90.4%, io 87.7%/79.2%, profiler/metrics 95.2%/75.8%,
  > log 68.1%/51.1% (SLF4JLogManager/ShutdownLogManager excluded per plan).
  >
  > **What was discovered:** Stopwatch noop returns 0.0 not null (differs
  > from Gauge noop which returns null). AnsiLogFormatter output depends on
  > static AnsiCode.isSupportsColors() initialized at class-load time.
  > CoreMetrics class metrics are all disabled by default.
  >
  > **Key files:**
  > - `core/src/test/java/.../common/log/AnsiCodeTest.java` (new)
  > - `core/src/test/java/.../common/log/LogFormatterTest.java` (new)
  > - `core/src/test/java/.../common/log/AnsiLogFormatterTest.java` (new)
  > - `core/src/test/java/.../common/profiler/metrics/MetricsRegistryTest.java` (new)
  > - `core/src/test/java/.../common/profiler/metrics/MetricFactoryTest.java` (new)
  Cover the logging subsystem and profiler metrics, then run the coverage
  build and fix any remaining gaps.

  **New test files:**
  - `AnsiCodeTest` — enum values: verify all codes have non-null toString,
    NULL code is empty. `format(String, boolean)`: with colors enabled
    (template substitution for `$ANSI{code text}`), with colors disabled
    (strips ANSI markers), no markers (passthrough), nested markers,
    unknown code name.
  - `LogFormatterTest` — `format(LogRecord)`: INFO level message, WARNING
    with exception (stack trace in output), message with parameters
    (MessageFormat substitution), null message, record with source class
    name extraction.
  - `AnsiLogFormatterTest` — `customFormatMessage(LogRecord)`: INFO level
    formatting, SEVERE level with error coloring, WARNING level, message
    with parameters.

  **Profiler metrics coverage (extend or create):**
  - `MetricsRegistryTest` — create registry with StubTicker, register
    Meter/Gauge/TimeRate, verify MBean registration via platform
    MBeanServer, shutdown and verify unregistration,
    `MetricsMBean.getAttribute`/`getAttributes`/`getMBeanInfo`,
    `invoke`/`setAttribute` throw UnsupportedOperationException.
  - `MetricFactoryTest` (or extend individual tests) — `Gauge.create()`
    with supplier, `Stopwatch.create()` with ticker, `MetricDefinition`
    name/scope/type getters, `MetricType` enum values and factory methods,
    NOOP instances (Meter.NOOP, etc.) don't throw on operations,
    `CoreMetrics` constant definitions.

  **Exclusions:** `Profiler.java` (DB-coupled except `threadDump` — T4),
  `SLF4JLogManager` (most methods work standalone but `fetchDbName` DB
  paths add complexity — defer to Track 22 if needed),
  `LogManager`/`ShutdownLogManager` (JUL integration, environment-
  dependent — low value).

  **Verification:**
  Run `./mvnw -pl core -am clean package -P coverage` and check
  per-package coverage with `coverage-analyzer.py`. Fix any packages
  that fall below 85% line / 70% branch by adding targeted tests.
