# Stored import compatibility dumps

A dump is a compressed JSON file written by `DatabaseExport`. Each file records one export format version. `DatabaseImportStoredDumpTest` imports these files with the current importer. It checks the schema, indexes, records, and links.

## Rebuild a dump

Use Java 21 or later. Run these commands from the repository root. Set `ROOT` to the absolute path of that checkout.

`ImportCompatibilityDumpGenerator` is a test utility. It runs only when selected by name. It creates a small in-memory database with the default users and synthetic data. Both formats contain a parent class, a child class, two indexes, and two linked records. Format 15 also contains the `Compat.Note` class and its record. Neither format contains broken links.

Each dump's `info.engine-build` field records the exporter commit that created it. A scratch checkout is a separate copy of the repository at that commit. Use the matching pair of values below before running the common commands:

| Format | Exporter commit (`COMMIT`) |
| --- | --- |
| 14 | `0901b0236e20ba9f2e33ca34709685e126127274` |
| 15 | `21ad40d94a7522ad3d54178b9f02252d76a687c8` |

```bash
ROOT=/absolute/path/to/youtrackdb
FORMAT=14
COMMIT=0901b0236e20ba9f2e33ca34709685e126127274
# For format 15, instead set FORMAT=15 and COMMIT=21ad40d94a7522ad3d54178b9f02252d76a687c8.
WORKTREE="/tmp/ytdb-format-$FORMAT"
git worktree add --detach "$WORKTREE" "$COMMIT"
cp "$ROOT/core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/tool/ImportCompatibilityDumpGenerator.java" "$WORKTREE/core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/tool/"
(cd "$WORKTREE" && ./mvnw -pl core -am test -Dtest=ImportCompatibilityDumpGenerator -Dsurefire.failIfNoSpecifiedTests=false -DimportCompat.format="$FORMAT" -DimportCompat.output="$ROOT/core/src/test/resources/import-compat/format-$FORMAT.json.gz")
git worktree remove --force "$WORKTREE"
```

Run one Maven command at a time. Do not run `mvn install` or `mvn deploy` in the scratch checkout. The generator checks that the exporter version matches `FORMAT`. Normal test runs read the stored files. They do not rebuild the dumps.

**Version rule:** When the exporter gets a new format version, add a stored dump and a test that imports it and checks its content. Update the generator for the new format. Use the exporter commit for that format in the same scratch-checkout steps. Record that commit in the dump's `info.engine-build` field and in this table. When the importer can no longer read an old format, remove that dump and its test. State the lost support in the release notes.
