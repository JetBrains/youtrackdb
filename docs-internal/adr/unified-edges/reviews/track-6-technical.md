# Track 6 Technical Review

## Findings

### Finding T1 [resolved — not a blocker]
**Location**: Track 6 grammar extension; `IndexDefinitionFactory.java:238-239`,
`extractMapIndexSpecifier()` at line 249
**Issue**: Plan proposes new `BY_VERTEX`/`BY_EDGE` grammar tokens, but the
existing `BY KEY`/`BY VALUE` mechanism already works. `extractMapIndexSpecifier()`
parses these from the field name string, passes them to
`createSingleFieldIndexDefinition()` via the `indexBy` parameter — which is
already present but **ignored** for LINKBAG.
**Resolution**: Reuse `BY KEY`/`BY VALUE`. No grammar changes or parser
regeneration needed. The LINKBAG branch at line 238-239 just needs to check
the `indexBy` parameter and route to the secondary definition when `VALUE`.

### Finding T2 [resolved — not a blocker]
**Location**: Default behavior for LINKBAG indexes
**Issue**: What happens when no `BY KEY`/`BY VALUE` is specified?
**Resolution**: `extractMapIndexSpecifier()` returns `KEY` by default (line 253).
So omitting the specifier defaults to `PropertyLinkBagIndexDefinition` (indexes
primaryRid/edge). Backward compatible.

### Finding T3 [confirmed — not a change]
**Location**: `AbstractLinkBag.addEvent()`, `removeEvent()`, `MultiValueChangeEvent`
**Issue**: Technical review confirmed that change events already carry both
RIDs: `getKey()` = primaryRid, `getValue()` = secondaryRid. No changes to
`AbstractLinkBag` or event infrastructure needed.

### Finding T4 [should-fix]
**Location**: Serialization/DDL for `PropertyLinkBagSecondaryIndexDefinition`
**Issue**: Index definitions are deserialized via `INDEX_DEFINITION_CLASS` +
reflection + `fromMap()`. The secondary definition needs:
- `getFieldsToIndex()` returning `field + " by value"`
- `toCreateIndexDDL()` including `by value`
- No-arg constructor for reflection
**Proposed fix**: Include these in the `PropertyLinkBagSecondaryIndexDefinition`
class implementation step.

### Finding T5 [suggestion]
**Location**: Component diagram
**Issue**: Diagram oversimplifies the SQL → index creation path.
**Resolution**: Not blocking — the actual implementation will follow the code
path naturally.

## Summary

Track 6 is **significantly simpler** than planned. The existing `BY KEY`/
`BY VALUE` grammar and `extractMapIndexSpecifier()` eliminate the need for SQL
grammar changes and parser regeneration. The change event infrastructure already
carries both RIDs. The track reduces to: (1) create the secondary index
definition class, (2) wire it into the factory, (3) integration tests.
