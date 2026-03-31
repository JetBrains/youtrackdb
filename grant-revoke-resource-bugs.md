# GRANT/REVOKE: `mapLegacyResourceToGenericResource` fails for certain resource paths

## Summary

`Rule.mapLegacyResourceToGenericResource()` uses `TreeMap.floorEntry()` to resolve a legacy resource string (e.g. `database.command.select`) to a `ResourceGeneric` enum. This approach breaks when a more-specific key in the map falls alphabetically between the generic key and the queried resource, causing a `NullPointerException` in `Role.grant()` / `Role.revoke()`.

## Affected resources

| Resource | Result | Reason |
|---|---|---|
| `database.command.create` | Works | `'c' < 'g'` — `floorEntry` returns `"database.command"` (correct) |
| `database.command.delete` | Works | `'d' < 'g'` — `floorEntry` returns `"database.command"` (correct) |
| `database.command.select` | **NPE** | `'s' > 'g'` — `floorEntry` returns `"database.command.gremlin"` (wrong) |
| `database.command.update` | **NPE** | `'u' > 'g'` — `floorEntry` returns `"database.command.gremlin"` (wrong) |
| `database.query` | **NPE** | No `"database.query"` entry exists — `floorEntry` returns an unrelated key |

## Root cause

`Rule.mapLegacyResourceToGenericResource()` (`core/.../metadata/security/Rule.java:161`):

```java
public static ResourceGeneric mapLegacyResourceToGenericResource(final String resource) {
    final var found =
        ResourceGeneric.legacyToGenericMap.floorEntry(resource.toLowerCase(Locale.ENGLISH));
    // ...
    if (resource.substring(0, found.getKey().length()).equalsIgnoreCase(found.getKey())) {
        return found.getValue();
    }
    return null;
}
```

The `legacyToGenericMap` TreeMap contains both `"database.command"` (COMMAND) and `"database.command.gremlin"` (COMMAND_GREMLIN). For a query like `"database.command.select"`:

1. `floorEntry("database.command.select")` returns `"database.command.gremlin"` because `'g' < 's'` lexicographically, making `"database.command.gremlin"` the greatest key <= `"database.command.select"`.
2. The length check `resource.length() < found.getKey().length()` evaluates to `22 < 24` → returns `null`.
3. The caller `Role.grant()` passes this `null` to `ConcurrentHashMap.get()`, causing the NPE.

The same pattern applies to `mapLegacyResourceToSpecificResource()`.

## Impact

- `GRANT` and `REVOKE` commands fail with NPE for any `database.command.<X>` where `X` is alphabetically after `"gremlin"`.
- `database.query` is completely unusable as a resource in GRANT/REVOKE.

## Reproduction

```sql
-- Fails with NullPointerException
GRANT READ ON database.command.select TO writer
GRANT UPDATE ON database.command.update TO writer
GRANT READ ON database.query TO reader

-- Works (alphabetically before "gremlin")
GRANT CREATE ON database.command.create TO writer
GRANT DELETE ON database.command.delete TO writer
```

## Suggested fix

The `floorEntry` approach is fragile when the map contains keys that are prefixes of other keys (e.g. `database.command` vs `database.command.gremlin`). A correct fix should ensure that the matched key is actually a prefix of the queried resource (i.e. the query starts with `key + "."` or equals the key exactly), and fall back to the next-lower entry if it isn't.
