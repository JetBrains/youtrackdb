# Zero-Copy Record Deserialization via PageFrame References — Final Design

## Overview

This feature eliminates byte[] copying on the record read path by keeping a
reference to the disk cache PageFrame in EntityImpl. For single-page records on
the optimistic read path, the storage layer returns a `RawPageBuffer` carrying
PageFrame coordinates instead of copying bytes. EntityImpl stores these
coordinates and deserializes directly from the PageFrame's ByteBuffer at
property-access time. A StampedLock stamp captured during the optimistic read is
validated after speculative deserialization; on invalidation, a one-shot fallback
re-reads the record through the pinned storage path into byte[].

The implementation touches four layers:
1. **Deserialization container** — `ReadBytesContainer` replaces byte[]-backed
   `BytesContainer` for all reads
2. **Guard allocations** — `CorruptedRecordException` prevents OOM from
   corrupted size fields
3. **Storage read result** — sealed `StorageReadResult` interface with
   `RawBuffer` and `RawPageBuffer` variants
4. **EntityImpl lifecycle** — PageFrame fields, speculative deserialization,
   stamp validation, and fallback

Deviations from the original design are minor: `getInternedString` accepts
`StringCache` instead of `DatabaseSessionEmbedded`, the byte[] `fromStream`
path was wired through `ReadBytesContainer` internally (making all 566 existing
tests exercise the new path), and link bag guards use `< 0` only (not
`> remaining()`) because BTree-based bags store element counts as metadata, not
buffer-consuming data.

## Class Design

### Deserialization Container Split

```mermaid
classDiagram
    class ReadBytesContainer {
        -ByteBuffer buffer
        +ReadBytesContainer(ByteBuffer)
        +ReadBytesContainer(byte[])
        +ReadBytesContainer(byte[], int offset)
        +getByte() byte
        +peekByte(int relativeOffset) byte
        +getBytes(byte[], int, int) void
        +getStringBytes(int) String
        +getInternedString(StringCache, int) String
        +getInt() int
        +getLong() long
        +remaining() int
        +offset() int
        +skip(int) void
        +setOffset(int) void
        +slice(int) ReadBytesContainer
    }
    class BytesContainer {
        +byte[] bytes
        +int offset
        +alloc(int) int
        +allocExact(int) int
        +skip(int) BytesContainer
        +fitBytes() byte[]
    }
    class RecordSerializerBinaryV1 {
        +deserialize(session, entity, ReadBytesContainer)
        +deserializePartial(session, entity, ReadBytesContainer, String[])
        +deserializeValue(session, ReadBytesContainer, type, entity, ...)
    }
    class VarIntSerializer {
        +readAsInteger(ReadBytesContainer) int
        +readAsLong(ReadBytesContainer) long
        +readSignedVarLong(ReadBytesContainer) long
        +readUnsignedVarLong(ReadBytesContainer) long
    }
    class HelperClasses {
        +readBinary(ReadBytesContainer) byte[]
        +readString(ReadBytesContainer) String
        +readLinkCollection(ReadBytesContainer, ...) T
        +readLinkMap(ReadBytesContainer, ...) Map
        +readOptimizedLink(ReadBytesContainer, ...) RecordIdInternal
        +readInteger(ReadBytesContainer) int
        +readLong(ReadBytesContainer) long
    }
    class RecordSerializerBinary {
        +fromStream(session, byte[], record, fields)
        +fromStream(session, version, ReadBytesContainer, record, fields)
    }

    RecordSerializerBinary --> RecordSerializerBinaryV1 : dispatches to
    RecordSerializerBinaryV1 --> ReadBytesContainer : reads from
    VarIntSerializer --> ReadBytesContainer : reads from
    HelperClasses --> ReadBytesContainer : reads from
    RecordSerializerBinaryV1 ..> BytesContainer : no longer uses for reads
```

`ReadBytesContainer` is a `final` class wrapping a `ByteBuffer` (direct or
heap). Three constructors support the zero-copy path (`ByteBuffer` from
PageFrame), the fallback path (`byte[]`), and the legacy entry point
(`byte[], int offset`). The byte[] `fromStream` in `RecordSerializerBinary`
creates a `ReadBytesContainer(source, 1)` (skipping the version byte), so both
paths converge to the same deserialization code.

`BytesContainer` remains unchanged and is used exclusively by the
serialization (write) path.

Key API methods replace direct array access patterns:
- `getByte()` replaces `bytes.bytes[bytes.offset++]`
- `peekByte(relativeOffset)` replaces `bytes.bytes[bytes.offset + j]` for
  field name matching without advancing position
- `getInt()`/`getLong()` provide zero-allocation big-endian reads
- `getInternedString(StringCache, len)` replaces
  `stringFromBytesIntern(session, bytes.bytes, offset, len)` — accepts
  `StringCache` directly for decoupling from `DatabaseSessionEmbedded`
- `remaining()` enables guard allocation checks against buffer capacity

### Storage Read Result Hierarchy

```mermaid
classDiagram
    class StorageReadResult {
        <<sealed interface>>
        +recordVersion() long
        +recordType() byte
        +toRawBuffer() RawBuffer
    }
    class RawBuffer {
        <<record>>
        +byte[] buffer
        +long version
        +byte recordType
        +recordVersion() long
    }
    class RawPageBuffer {
        <<record>>
        +PageFrame pageFrame
        +long stamp
        +int contentOffset
        +int contentLength
        +long recordVersion
        +byte recordType
        +sliceContent() ByteBuffer
    }

    StorageReadResult <|.. RawBuffer
    StorageReadResult <|.. RawPageBuffer
    RawPageBuffer --> PageFrame : references
```

`StorageReadResult` is a sealed interface permitting `RawBuffer` and
`RawPageBuffer`. The `toRawBuffer()` default method uses pattern matching to
either return itself (`RawBuffer`) or extract bytes from the PageFrame
(`RawPageBuffer.sliceContent()` → `new RawBuffer(...)`). This is used by
callers that always need byte[] (storage config, database compare, non-EntityImpl
records like Blob).

`RawPageBuffer`'s compact constructor validates: non-null pageFrame,
non-negative contentOffset and contentLength, overflow-safe bounds check
(`Math.addExact(contentOffset, contentLength) <= pageFrame.getBuffer().capacity()`).

### EntityImpl State Extensions

```mermaid
classDiagram
    class EntityImpl {
        -PageFrame pageFrame
        -long pageStamp
        -int pageContentOffset
        -int pageContentLength
        +fillFromPage(version, recordType, pageFrame, stamp, offset, length)
        +clearPageFrame()
        +deserializeProperties(propertyNames) boolean
        -deserializeFromPageFrame(propertyNames) boolean
        -reReadFromStorage()
        -checkDeserializedProperties(propertyNames) boolean
        +checkForProperties(properties) boolean
        +sourceIsParsedByProperties() boolean
        +toStream() byte[]
    }
    class RecordSerializerBinary {
        +fromStream(session, version, ReadBytesContainer, record, fields)
    }

    EntityImpl --> PageFrame : optional reference
    EntityImpl --> RecordSerializerBinary : deserializes via
```

EntityImpl holds four PageFrame-related fields (all on EntityImpl, not
RecordAbstract, since only EntityImpl uses `deserializeProperties()`).
`fillFromPage()` sets these fields instead of `source`; `clearPageFrame()` nulls
them. The PageFrame is cleared in all lifecycle transitions: `internalReset()`,
`fill()`, `fromStream()`, `clearSource()`, `setDirty()`, `setDirtyNoChanged()`.

### CollectionPage Layout Constants

```mermaid
classDiagram
    class CollectionPage {
        +RECORD_METADATA_HEADER_SIZE$ int
        +RECORD_TAIL_SIZE$ int
        +getRecordContentOffset(recordPosition) int
        +getRecordContentLength(recordPosition) int
    }
    class PaginatedCollectionV2 {
        -doReadRecordOptimisticInner(collectionPos, atomicOp) StorageReadResult
    }

    PaginatedCollectionV2 --> CollectionPage : uses layout constants
```

`RECORD_METADATA_HEADER_SIZE` (13 bytes: recordType + contentSize +
collectionPosition) and `RECORD_TAIL_SIZE` (9 bytes: firstRecordFlag +
nextPagePointer) are public constants on `CollectionPage`.
`getRecordContentOffset()` computes the absolute byte offset to content within
the page buffer. `getRecordContentLength()` derives content length from record
size minus header and tail. Both have assertion preconditions for non-deleted,
first-chunk records.

## Workflow

### Optimistic Read Path (Zero-Copy)

```mermaid
sequenceDiagram
    participant DSE as DatabaseSessionEmbedded
    participant AS as AbstractStorage
    participant PC2 as PaginatedCollectionV2
    participant CP as CollectionPage
    participant PF as PageFrame

    DSE->>AS: readRecord(rid, atomicOp)
    AS->>PC2: readRecord(collectionPos, atomicOp)
    PC2->>PC2: doReadRecordOptimisticInner()
    PC2->>PF: loadPageOptimistic()
    PF-->>PC2: PageView(pageFrame, stamp)
    PC2->>CP: getRecordVersion(pos)
    PC2->>PC2: visibility + deletion checks
    PC2->>CP: getRecordContentOffset(pos)
    CP-->>PC2: contentOffset
    PC2->>CP: getRecordContentLength(pos)
    CP-->>PC2: contentLength
    PC2-->>AS: RawPageBuffer(pageFrame, stamp, offset, length, version, type)
    AS-->>DSE: RawPageBuffer
    DSE->>DSE: pattern match StorageReadResult
    DSE->>DSE: entity.fillFromPage(version, type, frame, stamp, offset, length)
```

On the optimistic single-page path, `PaginatedCollectionV2.doReadRecordOptimisticInner()`
validates record status, visibility, deletion, minimum size, first-chunk flag,
and single-page constraint (nextPagePointer < 0). It then constructs a
`RawPageBuffer` with page coordinates instead of copying bytes.
`DatabaseSessionEmbedded.executeReadRecord()` pattern-matches: `RawBuffer` takes
the existing fill+fromStream path; `RawPageBuffer` with `EntityImpl` calls
`fillFromPage()` for lazy deserialization; `RawPageBuffer` with non-EntityImpl
(Blob) extracts bytes via `toRawBuffer()`.

### Property Deserialization with Stamp Validation

```mermaid
sequenceDiagram
    participant Caller
    participant EI as EntityImpl
    participant PF as PageFrame
    participant RBC as ReadBytesContainer
    participant RS as RecordSerializerBinaryV1
    participant Storage

    Caller->>EI: getProperty("name")
    EI->>EI: deserializeProperties("name")
    alt pageFrame != null && source == null
        EI->>EI: capture locals (frame, stamp, offset, length)
        EI->>PF: getBuffer()
        PF-->>EI: ByteBuffer
        EI->>RBC: new ReadBytesContainer(buffer.slice(offset+1, length-1))
        EI->>RS: fromStream(session, versionByte, container, this, ["name"])
        RS->>RBC: getByte(), getInt(), ...
        alt RuntimeException thrown
            EI->>EI: clearPageFrame()
            EI->>EI: reReadFromStorage()
            EI->>EI: deserializeProperties("name") via byte[] path
        else deserialization succeeds
            EI->>PF: validate(stamp)
            alt stamp valid
                Note over EI: Keep PageFrame (partial) or clear (full)
                EI-->>Caller: property value
            else stamp invalid
                EI->>EI: clearPageFrame()
                EI->>EI: reReadFromStorage()
                EI->>EI: deserializeProperties("name") via byte[] path
            end
        end
    else source != null (byte[] path)
        EI->>RBC: new ReadBytesContainer(source, 1)
        EI->>RS: fromStream(session, source, this, ["name"])
        RS-->>EI: properties populated
    end
    EI-->>Caller: property value
```

The deserialization flow captures PageFrame fields in local variables before
calling the serializer (because `clearSource()` during full deserialization
triggers `clearPageFrame()`, zeroing the entity's fields). The serializer
version byte is read from the absolute buffer position; the remaining content is
sliced into a `ReadBytesContainer`. On `RuntimeException` (torn page from
concurrent modification) or stamp invalidation, the fallback path calls
`reReadFromStorage()` which re-reads via the pinned storage path, calls
`fill()` + `fromStream()` to populate `source`, and re-enters
`deserializeProperties()` on the byte[] path.

### Guard Allocation Check Flow

```mermaid
flowchart TD
    A["Read size from stream via VarInt"] --> B{"size >= 0?"}
    B -->|No| E["throw CorruptedRecordException"]
    B -->|Yes| C{"consumes bytes?"}
    C -->|"Yes (arrays, strings,\ncollections, headers)"| D{"size <= remaining()?"}
    C -->|"No (link bag counts:\nBTree metadata)"| F["Proceed — size is metadata only"]
    D -->|No| E
    D -->|Yes| G["Proceed with allocation/loop"]
```

Guard checks are applied at 17 sites across `HelperClasses` (4 sites) and
`RecordSerializerBinaryV1` (13 sites). The pattern is
`if (size < 0 || size > bytes.remaining()) throw new CorruptedRecordException(...)`.
Link bag sizes (`readLinkSet`, `readLinkBag`) use only `< 0` checks because
BTree-based bags store element counts as metadata — the actual elements live in
the BTree, not in the buffer.

## Stamp Validation and Memory Safety

The StampedLock optimistic read protocol guarantees that `validate(stamp)`
returns `false` if any exclusive lock was acquired since the stamp was issued.
This covers page modification, eviction, and frame reuse. `PageFramePool`
recycles frames (never deallocates), so stale frames always point to valid
mapped memory.

**Critical ordering**: `validate(stamp)` is called AFTER reading all data from
the PageFrame buffer — the same pattern as `executeOptimisticStorageRead` in
`DurableComponent`. The speculative deserialization completes fully before
validation. If a concurrent write produced torn data, the guard allocation
checks (via `CorruptedRecordException`) catch it before stamp validation.

**Local variable capture**: PageFrame fields must be captured in locals before
calling the serializer because `RecordSerializerBinaryV1.deserialize()` calls
`clearSource()` → `clearPageFrame()` as part of full deserialization. Without
local capture, the stamp validation after deserialization would read zeroed
fields.

## Partial Deserialization and Fallback Interaction

EntityImpl supports partial deserialization — requesting specific properties by
name. The PageFrame lifecycle mirrors the existing `source` (byte[]) lifecycle:

- **Partial call (stamp valid)**: Deserialize requested properties from
  PageFrame. Keep PageFrame reference for subsequent calls — `deserializePartial()`
  does not call `clearSource()`, so PageFrame is retained.
- **Subsequent partial call (stamp still valid)**: Deserialize additional
  properties from the same PageFrame.
- **Partial call (stamp invalid)**: Clear PageFrame, re-read into byte[], store
  as `source`. Future partial calls use byte[] path.
- **Full deserialization (stamp valid)**: `deserialize()` calls
  `clearSource()` → `clearPageFrame()`. Properties are fully populated.
- **Full deserialization (stamp invalid)**: Clear PageFrame, re-read, the byte[]
  `deserialize()` calls `clearSource()` after parsing.

The `checkDeserializedProperties()` method validates that partial deserialization
found at least one requested property — extracted from duplicated inline logic
into a shared method during Track 5.

## Guard Allocation Strategy

`CorruptedRecordException` extends `DatabaseException` with four constructor
signatures (copy, message-only, dbName+message, session+message). Guards are
placed at every site where a VarInt-decoded size drives an allocation or loop:

| Category | Guard pattern | Sites |
|---|---|---|
| Byte-consuming sizes (arrays, strings, collections, headers) | `size < 0 \|\| size > remaining()` | 15 sites |
| Metadata-only counts (link bag element counts for BTree bags) | `size < 0` | 2 sites |

The distinction exists because BTree-based link bags store the element count as
metadata — elements live in the BTree, not in the serialized buffer. A bag with
45 elements may need only ~6 bytes in the buffer (fileId + linkBagId pointers),
so `45 > remaining=6` would be a false positive.
