# Track 2: Index name and IndexManager — case-sensitive names

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [ ] Track-level code review

## Base commit
`0eed0e98`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Remove toLowerCase() from ImmutableSchema index map and IndexManager classPropertyIndex
  - [x] Context: safe
  > **What was done:** Removed all `toLowerCase(Locale.ROOT)` calls from
  > index-name map keys in ImmutableSchema (constructor, indexExists,
  > getIndexDefinition) and from class-name keys in classPropertyIndex
  > (IndexManagerAbstract: getIndexOnProperty, getClassIndex,
  > addIndexInternalNoLock; IndexManagerEmbedded:
  > removeClassPropertyIndexInternal — get/remove/put). Removed unused
  > Locale imports from ImmutableSchema and IndexManagerAbstract. Added 8
  > tests to CaseSensitiveClassNameTest covering: exact-case index lookup
  > via ImmutableSchema, classPropertyIndex lookup, index removal cleanup,
  > getClassIndex, areIndexed, getClassIndexes, composite (multi-property)
  > index prefix lookup, and case-variant class index collision prevention.
  > **Key files:** ImmutableSchema.java (modified), IndexManagerAbstract.java
  > (modified), IndexManagerEmbedded.java (modified),
  > CaseSensitiveClassNameTest.java (modified)

- [x] Step 2: Switch equalsIgnoreCase → equals in index-layer security and import code
  - [x] Context: info
  > **What was done:** Changed 3 `equalsIgnoreCase()` calls to `equals()`:
  > Index.isLabelSecurityDefined() className filter,
  > IndexManagerEmbedded.checkSecurityConstraintsForIndexCreate() className
  > filter, and DatabaseImport.importIndexes() index name comparison with
  > EXPORT_IMPORT_INDEX_NAME constant.
  > **What was discovered:** Code review found that
  > SecurityResourceProperty.getClassName() returns null when allClasses is
  > true (wildcard policy like database.class.*.<prop>). Both the old
  > equalsIgnoreCase and new equals calls had a latent NPE on this path.
  > Fixed by adding isAllClasses() guard and reversing the equals receiver.
  > **Key files:** Index.java (modified), IndexManagerEmbedded.java
  > (modified), DatabaseImport.java (modified)
