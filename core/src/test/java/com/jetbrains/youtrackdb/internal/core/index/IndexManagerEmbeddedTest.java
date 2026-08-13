package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexLifecycle;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link IndexManagerEmbedded} covering collection management, index lifecycle,
 * crash-recovery flag logic, and property-index lookup methods.
 */
public class IndexManagerEmbeddedTest extends DbTestBase {

  private static final String CLS = "ImeTestCls";
  private static final String IDX = CLS + ".val";

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();
    // Schema setup must happen outside an active transaction.
    var cls = session.getMetadata().getSchema().createClass(CLS);
    cls.createProperty("val", PropertyType.INTEGER);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex(IDX, SchemaClass.INDEX_TYPE.UNIQUE, "val");
  }

  /**
   * Existing descriptors start usable, and a destructive manager reload attaches the replacement
   * handle to the same storage-scoped lifecycle cell instead of resetting handle-local state.
   */
  @Test
  public void reloadPreservesStorageScopedLifecycleCell() {
    var manager = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var original = (IndexAbstract) manager.getIndex(IDX);
    var cell = original.getLifecycleCell();

    assertNotNull("an existing descriptor must have a lifecycle cell", cell);
    assertEquals("existing indexes default to usable", IndexLifecycle.USABLE, cell.get());
    assertEquals("the engine must bind to the loaded descriptor", original.getIdentity(),
        original.getEngineReference().ownerDescriptorIdentity());
    cell.set(IndexLifecycle.INVALID);

    manager.reload(session);

    var replacement = (IndexAbstract) manager.getIndex(IDX);
    assertTrue("reload must replace the Java handle", original != replacement);
    assertSame("reload must retain the storage-scoped cell", cell, replacement.getLifecycleCell());
    assertEquals("reload must not reset the lifecycle value", IndexLifecycle.INVALID,
        replacement.getLifecycleCell().get());
  }

  /** A successful drop removes the descriptor's registry entry without mutating its retained cell. */
  @Test
  public void dropRemovesLifecycleRegistryEntry() {
    var manager = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var index = (IndexAbstract) manager.getIndex(IDX);
    var descriptorIdentity = index.getIdentity();
    var cell = index.getLifecycleCell();

    manager.dropIndex(session, IDX);

    var storage = (AbstractStorage) session.getStorage();
    assertNull("the dropped descriptor must leave the storage registry",
        storage.getIndexLifecycle(descriptorIdentity));
    assertSame("an already retained cell remains valid for old holders", cell,
        index.getLifecycleCell());
  }

  // -----------------------------------------------------------------------
  //  addCollectionToIndex
  // -----------------------------------------------------------------------

  /**
   * Calling addCollectionToIndex with requireEmpty=false when the collection is already
   * linked to the index must be a no-op (the early-return branch inside the shared-lock
   * block is taken). Verified by checking the collection appears exactly once.
   */
  @Test
  public void addCollectionToIndex_collectionAlreadyPresent_isIdempotent() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var extraColl = "imeColl1";
    session.addCollection(extraColl);

    mgr.addCollectionToIndex(session, extraColl, IDX, false);
    // Second call — collection is already present, so the method returns early.
    mgr.addCollectionToIndex(session, extraColl, IDX, false);

    var idx = mgr.getIndex(IDX);
    long countMatching = idx.getCollections().stream()
        .filter(c -> c.equals(extraColl))
        .count();
    assertEquals("Collection should appear exactly once after two idempotent adds",
        1, countMatching);
  }

  /**
   * Calling addCollectionToIndex for an index name that does not exist throws
   * NullPointerException because the manager looks up the index by name (which returns null)
   * and then dereferences {@code index.getCollections()}. We pin the exact exception type
   * rather than the broader RuntimeException so a future hardening pass that adds a
   * defensive null-check (and changes the contract to IndexException) is forced to update
   * this test deliberately. WHEN-FIXED: tighten to the new defensive-check exception type
   * if/when the manager guards the null index lookup.
   */
  @Test(expected = NullPointerException.class)
  public void addCollectionToIndex_unknownIndexName_throwsNullPointerException() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    session.addCollection("imeColl2");
    // "NoSuchIdx" does not exist → index lookup returns null → NPE
    mgr.addCollectionToIndex(session, "imeColl2", "NoSuchIdx", false);
  }

  // -----------------------------------------------------------------------
  //  removeCollectionFromIndex
  // -----------------------------------------------------------------------

  /**
   * Calling removeCollectionFromIndex when the collection is NOT in the index must be a
   * no-op (the early-return branch inside the shared-lock block is taken).
   * Verified by asserting no exception is thrown and no collection was removed.
   */
  @Test
  public void removeCollectionFromIndex_collectionNotPresent_isIdempotent() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var notLinked = "imeCollNotLinked";
    session.addCollection(notLinked);

    // notLinked was never added to IDX — removeCollectionFromIndex must return early.
    mgr.removeCollectionFromIndex(session, notLinked, IDX);

    // IDX should still be in the manager.
    assertNotNull("Index should still exist after no-op remove", mgr.getIndex(IDX));
  }

  // -----------------------------------------------------------------------
  //  getIndexesConfiguration
  // -----------------------------------------------------------------------

  /**
   * getIndexesConfiguration must return one config map per registered index. Each map
   * must contain at least the 'type' key.
   */
  @Test
  public void getIndexesConfiguration_returnsConfigForAllIndexes() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var configs = mgr.getIndexesConfiguration(session);

    assertFalse("Configuration list must not be empty", configs.isEmpty());
    for (var cfg : configs) {
      assertNotNull("Each config map must have a 'type' entry", cfg.get(Index.CONFIG_TYPE));
    }
  }

  // -----------------------------------------------------------------------
  //  autoRecreateIndexesAfterCrash
  // -----------------------------------------------------------------------

  /**
   * For a freshly created in-memory database there was no crash, so
   * autoRecreateIndexesAfterCrash must return false.
   */
  @Test
  public void autoRecreateIndexesAfterCrash_freshMemoryDb_returnsFalse() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    // A fresh memory DB: wereDataRestoredAfterOpen() = false → returns false.
    assertFalse("No crash recovery needed for a fresh memory database",
        mgr.autoRecreateIndexesAfterCrash(session));
  }

  // -----------------------------------------------------------------------
  //  waitTillIndexRestore
  // -----------------------------------------------------------------------

  /**
   * waitTillIndexRestore must return immediately when no recreate-indexes thread is running
   * (recreateIndexesThread is null). This covers the early-return path.
   */
  @Test
  public void waitTillIndexRestore_noActiveThread_returnsImmediately() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    // No thread was started — the null-check branch is taken and the method returns at once.
    mgr.waitTillIndexRestore();
    // If we reach here without hanging, the test passes.
  }

  // -----------------------------------------------------------------------
  //  areIndexed
  // -----------------------------------------------------------------------

  /**
   * areIndexed with a single property that has an index on it must return true.
   */
  @Test
  public void areIndexed_singleIndexedProperty_returnsTrue() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    assertTrue("'val' has a UNIQUE index — areIndexed must return true",
        mgr.areIndexed(session, CLS, "val"));
  }

  /**
   * areIndexed with a property that has no index on it must return false.
   */
  @Test
  public void areIndexed_nonIndexedProperty_returnsFalse() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    assertFalse("'name' has no index — areIndexed must return false",
        mgr.areIndexed(session, CLS, "name"));
  }

  /**
   * areIndexed with a class that has no indexes at all must return false (propertyIndex == null
   * branch).
   */
  @Test
  public void areIndexed_classWithNoIndexes_returnsFalse() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    assertFalse("A class not in the classPropertyIndex map must return false",
        mgr.areIndexed(session, "NonExistentClass", "someField"));
  }

  // -----------------------------------------------------------------------
  //  getClassInvolvedIndexes
  // -----------------------------------------------------------------------

  /**
   * getClassInvolvedIndexes with the exact set of indexed fields must return the index.
   */
  @Test
  public void getClassInvolvedIndexes_indexedField_returnsIndex() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var involved = mgr.getClassInvolvedIndexes(session, CLS, Collections.singletonList("val"));
    assertFalse("getClassInvolvedIndexes must return at least one index for 'val'",
        involved.isEmpty());
    assertTrue("The returned index must be IDX",
        involved.stream().anyMatch(i -> IDX.equals(i.getName())));
  }

  /**
   * getClassInvolvedIndexes for a class not in the map must return an empty set.
   */
  @Test
  public void getClassInvolvedIndexes_unknownClass_returnsEmpty() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var involved =
        mgr.getClassInvolvedIndexes(session, "NoSuchCls", Collections.singletonList("f"));
    assertTrue("Unknown class must produce an empty set", involved.isEmpty());
  }

  // -----------------------------------------------------------------------
  //  getClassIndex
  // -----------------------------------------------------------------------

  /**
   * getClassIndex returns the index when the index's class name matches the requested class.
   */
  @Test
  public void getClassIndex_knownClassAndIndex_returnsIndex() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var idx = mgr.getClassIndex(session, CLS, IDX);
    assertNotNull("getClassIndex must return non-null for a known class+index pair", idx);
    assertEquals("Returned index name must match IDX", IDX, idx.getName());
  }

  /**
   * getClassIndex returns null when the index exists but belongs to a different class.
   */
  @Test
  public void getClassIndex_wrongClass_returnsNull() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    // IDX belongs to CLS, not "WrongClass".
    var idx = mgr.getClassIndex(session, "WrongClass", IDX);
    assertNull("getClassIndex must return null when the class does not match", idx);
  }

  /**
   * The deferred-handle membership mutators fail loudly on a null or blank collection
   * name — a null reaching them means an unresolved collection (a resolver miss) that would
   * otherwise fold a null placeholder into the covered set the commit persists. An
   * IllegalArgumentException, not a bare assert: production runs with assertions disabled.
   */
  @Test
  public void deferredMembershipMutatorsRejectNullOrBlankNames() {
    session.begin();
    session.getMetadata().getSchema().getClass(CLS)
        .createIndex(CLS + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var overlay = session.getTxSchemaState().getIndexOverlay();
    var handle = (IndexAbstract) overlay.getTxCreated(CLS + ".name");
    assertNotNull("precondition: the tx-created deferred handle exists", handle);

    assertThrows(IllegalArgumentException.class, () -> handle.addCollectionToDeferred(null));
    assertThrows(IllegalArgumentException.class, () -> handle.addCollectionToDeferred("  "));
    assertThrows(IllegalArgumentException.class,
        () -> handle.removeCollectionFromDeferred(null));
    assertThrows(IllegalArgumentException.class, () -> handle.removeCollectionFromDeferred(""));
    session.rollback();
  }

  // -----------------------------------------------------------------------
  //  getClassIndex (overlay-routed)
  // -----------------------------------------------------------------------

  /**
   * An index created inside the open transaction is visible through getClassIndex within that
   * transaction — the lookup answers from the tx-effective view, not the committed-only registry.
   */
  @Test
  public void getClassIndex_txCreatedIndex_visibleWithinTx() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var createdName = CLS + ".name";

    session.begin();
    session.getMetadata().getSchema().getClass(CLS)
        .createIndex(createdName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var inTx = mgr.getClassIndex(session, CLS, createdName);
    assertNotNull("a tx-created index must be visible via getClassIndex within the tx", inTx);
    assertEquals(createdName, inTx.getName());
    assertNull("the tx-created index must not answer for a different class",
        mgr.getClassIndex(session, "WrongClass", createdName));
    session.rollback();

    assertNull("the rolled-back create must leave nothing",
        mgr.getClassIndex(session, CLS, createdName));
  }

  /**
   * An index dropped inside the open transaction is invisible through getClassIndex within that
   * transaction, even though the shared committed registry still holds it until commit.
   */
  @Test
  public void getClassIndex_txDroppedIndex_invisibleWithinTx() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();

    session.begin();
    mgr.dropIndex(session, IDX);
    assertNull("a tx-dropped index must be invisible via getClassIndex within the tx",
        mgr.getClassIndex(session, CLS, IDX));
    session.rollback();

    assertNotNull("the rolled-back drop must leave the committed index visible again",
        mgr.getClassIndex(session, CLS, IDX));
  }

  /**
   * The same-transaction drop-then-recreate REPLACE flow — the recreated (tx-created)
   * index must be visible through getClassIndex, consistent with every sibling lookup
   * (existsIndex, getIndexes, getClassIndexes). The dropped committed name stays recorded (the
   * commit deletes the old engine) while the replacement handle shadows it in the tx view, so
   * the tx-created probe must run BEFORE the tx-dropped one.
   */
  @Test
  public void getClassIndex_replaceFlow_txRecreatedIndexVisible() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();

    session.begin();
    mgr.dropIndex(session, IDX);
    session.getMetadata().getSchema().getClass(CLS)
        .createIndex(IDX, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");
    var replacement = mgr.getClassIndex(session, CLS, IDX);
    assertNotNull("the same-tx replacement must be visible via getClassIndex", replacement);
    assertTrue("the visible handle must be the tx-created replacement (deferred engine)",
        replacement.getIndexId() < 0);
    session.rollback();

    assertNotNull("the rolled-back replace must leave the committed index visible",
        mgr.getClassIndex(session, CLS, IDX));
    assertTrue("and it must be the committed engine again",
        mgr.getClassIndex(session, CLS, IDX).getIndexId() >= 0);
  }

  /**
   * The session-aware {@code getIndex} family member resolves the transaction's effective view:
   * a tx-dropped name answers absent, an untouched committed name falls through to the committed
   * registry, and outside any transaction the base (committed) behaviour serves unchanged.
   */
  @Test
  public void getIndexSessionAware_resolvesEffectiveView() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var otherName = CLS + ".name";
    session.getMetadata().getSchema().getClass(CLS)
        .createIndex(otherName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.begin();
    mgr.dropIndex(session, IDX);
    assertNull("a tx-dropped name must answer absent", mgr.getIndex(session, IDX));
    assertNotNull("an untouched committed name must fall through to the committed registry",
        mgr.getIndex(session, otherName));
    session.rollback();

    assertNotNull("outside a transaction the committed behaviour serves unchanged",
        mgr.getIndex(session, IDX));
  }

  /**
   * The swap-shaped rename {X→Y, Z→X} through getClassIndex — the rename-source arm
   * under a renamed-away class name. Committed X's index answers only under Y; the name X, though
   * renamed away, answers for committed Z's index (Z was renamed TO X); and X's own committed
   * index no longer answers under X.
   */
  @Test
  public void getClassIndex_swapShapedRename_resolvesEachSideExactly() {
    var otherCls = session.getMetadata().getSchema().createClass("ImeSwapZ");
    otherCls.createProperty("zval", PropertyType.STRING);
    otherCls.createIndex("ImeSwapZ.zval", SchemaClass.INDEX_TYPE.NOTUNIQUE, "zval");
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();

    session.begin();
    session.getMetadata().getSchema().getClass(CLS).setName("ImeSwapY");
    session.getMetadata().getSchema().getClass("ImeSwapZ").setName(CLS);

    assertNotNull("committed X's index must answer under Y",
        mgr.getClassIndex(session, "ImeSwapY", IDX));
    assertNull("committed X's index must not answer under the vacated X",
        mgr.getClassIndex(session, CLS, IDX));
    assertNotNull("committed Z's index must answer under X (Z renamed TO it)",
        mgr.getClassIndex(session, CLS, "ImeSwapZ.zval"));
    assertNull("committed Z's index must not answer under its old name",
        mgr.getClassIndex(session, "ImeSwapZ", "ImeSwapZ.zval"));
    session.rollback();
  }

  /**
   * A class renamed inside the open transaction resolves its committed index through
   * getClassIndex under the NEW class name (the overlay's class-rename map) and no longer under
   * the old one.
   */
  @Test
  public void getClassIndex_renamedClass_resolvesUnderNewNameOnly() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();

    session.begin();
    session.getMetadata().getSchema().getClass(CLS).setName("ImeRenamed");
    var underNew = mgr.getClassIndex(session, "ImeRenamed", IDX);
    assertNotNull("the committed index must resolve under the renamed class name", underNew);
    assertEquals(IDX, underNew.getName());
    assertNull("the old class name must no longer resolve the index",
        mgr.getClassIndex(session, CLS, IDX));
    session.rollback();

    assertNotNull("the rolled-back rename must restore the old-name resolution",
        mgr.getClassIndex(session, CLS, IDX));
  }

  // -----------------------------------------------------------------------
  //  Composite-index security checks
  // -----------------------------------------------------------------------

  /**
   * A composite index with no filtered-property rules is created successfully. The direct,
   * narrowly scoped invocation of the security check proves that the check itself never asks for
   * schema metadata on this fast path; unrelated work in a cold security cache is outside the
   * assertion.
   */
  @Test
  public void compositeIndexWithoutFilteredPropertiesSkipsSecuritySnapshot() {
    var indexName = CLS + ".compositeNoRules";
    session.getMetadata().getSchema().getClass(CLS)
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val", "name");

    assertNotNull(session.getSharedContext().getIndexManager().getIndex(indexName));
    assertSecurityCheckSkipsSnapshot(Collections.emptySet());
  }

  /**
   * A filtered-property rule for a field outside the composite index does not block creation, and
   * the security check returns before touching schema metadata after applying its property-name
   * pre-filter.
   */
  @Test
  public void compositeIndexWithUnrelatedFilteredPropertySkipsSecuritySnapshot() {
    var cls = session.getMetadata().getSchema().getClass(CLS);
    cls.createProperty("unindexed", PropertyType.STRING);
    setFilteredPropertyRule(
        "unrelatedPropertyPolicy", "database.class." + CLS + ".unindexed");

    var indexName = CLS + ".compositeUnrelatedRule";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val", "name");

    assertNotNull(session.getSharedContext().getIndexManager().getIndex(indexName));
    assertSecurityCheckSkipsSnapshot(
        Set.of(filteredProperty(CLS, "unindexed")));
  }

  /**
   * A rule on an unrelated class may share an indexed field's name and reach the slow path, but it
   * must not block composite-index creation after the indexed class family is resolved.
   */
  @Test
  public void compositeIndexAllowsSameNamedFilteredPropertyOnUnrelatedClass() {
    var unrelatedClassName = "ImeUnrelatedSecurityClass";
    var unrelated = session.getMetadata().getSchema().createClass(unrelatedClassName);
    unrelated.createProperty("name", PropertyType.STRING);
    setFilteredPropertyRule(
        "unrelatedClassPropertyPolicy",
        "database.class." + unrelatedClassName + ".name");

    var indexName = CLS + ".compositeUnrelatedClassRule";
    session.getMetadata().getSchema().getClass(CLS)
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val", "name");

    assertNotNull(session.getSharedContext().getIndexManager().getIndex(indexName));
  }

  /**
   * A class-specific rule naming an indexed field still rejects composite creation with the exact
   * pre-optimization exception type and message.
   */
  @Test
  public void compositeIndexOnFilteredPropertyKeepsExactRejection() {
    setFilteredPropertyRule(
        "indexedPropertyPolicy", "database.class." + CLS + ".name");

    var exception = assertThrows(
        IndexException.class,
        () -> session.getMetadata().getSchema().getClass(CLS)
            .createIndex(CLS + ".compositeRejected", SchemaClass.INDEX_TYPE.NOTUNIQUE,
                "val", "name"));

    assertEquals(expectedSecurityMessage(CLS, "name"), exception.getMessage());
  }

  /**
   * A rule attached to a superclass remains effective for a composite index created on its
   * subclass, proving superclass traversal is unchanged on the slow path.
   */
  @Test
  public void compositeIndexRejectsFilteredPropertyInheritedFromSuperclass() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("ImeSecurityParent");
    parent.createProperty("secret", PropertyType.STRING);
    parent.createProperty("other", PropertyType.STRING);
    var child = schema.createClass("ImeSecurityChild", parent);
    setFilteredPropertyRule(
        "superclassPropertyPolicy", "database.class.ImeSecurityParent.secret");

    var exception = assertThrows(
        IndexException.class,
        () -> child.createIndex("ImeSecurityChild.compositeRejected",
            SchemaClass.INDEX_TYPE.NOTUNIQUE, "secret", "other"));

    assertEquals(expectedSecurityMessage("ImeSecurityChild", "secret"), exception.getMessage());
  }

  /**
   * A rule attached to a subclass remains effective for a composite index created on its
   * superclass, proving subclass traversal is unchanged on the slow path.
   */
  @Test
  public void compositeIndexRejectsFilteredPropertyDeclaredBySubclass() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("ImeSecurityBase");
    parent.createProperty("secret", PropertyType.STRING);
    parent.createProperty("other", PropertyType.STRING);
    schema.createClass("ImeSecurityDerived", parent);
    setFilteredPropertyRule(
        "subclassPropertyPolicy", "database.class.ImeSecurityDerived.secret");

    var exception = assertThrows(
        IndexException.class,
        () -> parent.createIndex("ImeSecurityBase.compositeRejected",
            SchemaClass.INDEX_TYPE.NOTUNIQUE, "secret", "other"));

    assertEquals(expectedSecurityMessage("ImeSecurityBase", "secret"), exception.getMessage());
  }

  /**
   * Property-name matching remains case-sensitive: a rule for {@code Name} is unrelated to an
   * index on {@code name}, so composite creation succeeds and the fast path skips metadata.
   */
  @Test
  public void compositeIndexPropertyRuleMatchingRemainsCaseSensitive() {
    setFilteredPropertyRule(
        "caseSensitivePropertyPolicy", "database.class." + CLS + ".Name");

    var indexName = CLS + ".compositeCaseSensitive";
    session.getMetadata().getSchema().getClass(CLS)
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val", "name");

    assertNotNull(session.getSharedContext().getIndexManager().getIndex(indexName));
    assertSecurityCheckSkipsSnapshot(Set.of(filteredProperty(CLS, "Name")));
  }

  /**
   * A wildcard-class filtered-property rule still rejects every class and preserves the exact
   * exception contract.
   */
  @Test
  public void compositeIndexAllClassesRuleKeepsExactRejection() {
    setFilteredPropertyRule("allClassesPropertyPolicy", "database.class.*.name");

    var exception = assertThrows(
        IndexException.class,
        () -> session.getMetadata().getSchema().getClass(CLS)
            .createIndex(CLS + ".compositeWildcardRejected",
                SchemaClass.INDEX_TYPE.NOTUNIQUE, "val", "name"));

    assertEquals(expectedSecurityMessage(CLS, "name"), exception.getMessage());
  }

  /**
   * Single-property indexes retain their original unconditional early return even when a rule
   * names the indexed field; neither filtered rules nor schema metadata are consulted by the
   * check.
   */
  @Test
  public void singlePropertyIndexSecurityCheckRemainsUnchanged() {
    setFilteredPropertyRule(
        "singlePropertyPolicy", "database.class." + CLS + ".name");
    var indexName = CLS + ".singleFiltered";
    session.getMetadata().getSchema().getClass(CLS)
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    assertNotNull(session.getSharedContext().getIndexManager().getIndex(indexName));

    var fixture = securityCheckFixture(Set.of(filteredProperty("Indexed", "first")));
    when(fixture.definition().getProperties()).thenReturn(List.of("first"));
    invokeSecurityCheck(fixture.database(), fixture.definition());
    verify(fixture.security(), never()).getAllFilteredProperties(fixture.database());
    verify(fixture.database(), never()).getMetadata();
  }

  /**
   * When a relevant property rule reaches the slow path but the indexed class is absent, the
   * existing early return remains in place and no second, decision-making rule read occurs.
   */
  @Test
  public void compositeIndexSecurityCheckKeepsAbsentClassEarlyReturn() {
    var fixture = securityCheckFixture(Set.of(filteredProperty("Missing", "first")));
    var metadata = mock(MetadataDefault.class);
    var schema = mock(ImmutableSchema.class);
    when(fixture.database().getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);
    when(schema.getClass("Missing")).thenReturn(null);
    when(fixture.definition().getClassName()).thenReturn("Missing");

    invokeSecurityCheck(fixture.database(), fixture.definition());

    verify(fixture.security(), times(1)).getAllFilteredProperties(fixture.database());
    verify(schema).getClass("Missing");
  }

  /**
   * The pre-filter is only a necessary-condition check. Once class-family resolution is required,
   * the post-resolution rule read remains the effective decision point: removing the rule between
   * the two reads allows creation exactly as the old decision point would.
   */
  @Test
  public void compositeIndexSecuritySlowPathDecidesFromSecondRuleRead() {
    var fixture = securityCheckFixture(Set.of(filteredProperty("Indexed", "first")));
    when(fixture.security().getAllFilteredProperties(fixture.database()))
        .thenReturn(Set.of(filteredProperty("Indexed", "first")), Collections.emptySet());
    var metadata = mock(MetadataDefault.class);
    var schema = mock(ImmutableSchema.class);
    var clazz = mock(SchemaClass.class);
    when(fixture.database().getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);
    when(schema.getClass("Indexed")).thenReturn(clazz);
    when(clazz.getAllSubclasses()).thenReturn(Collections.emptyList());
    when(clazz.getAllSuperClasses()).thenReturn(Collections.emptyList());

    invokeSecurityCheck(fixture.database(), fixture.definition());

    verify(fixture.security(), times(2)).getAllFilteredProperties(fixture.database());
  }

  private void setFilteredPropertyRule(String policyName, String resource) {
    var security = session.getSharedContext().getSecurity();
    session.begin();
    var policy = security.createSecurityPolicy(session, policyName);
    policy.setActive(true);
    policy.setReadRule("name = 'allowed'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), resource, policy);
    session.commit();
  }

  private String expectedSecurityMessage(String className, String propertyName) {
    return "Cannot create index on "
        + className
        + "["
        + propertyName
        + " because of existing column security rules\r\n\tDB Name=\""
        + session.getDatabaseName()
        + "\"";
  }

  private static void assertSecurityCheckSkipsSnapshot(
      Set<SecurityResourceProperty> filteredProperties) {
    var fixture = securityCheckFixture(filteredProperties);

    invokeSecurityCheck(fixture.database(), fixture.definition());

    verify(fixture.database(), never()).getMetadata();
  }

  private static SecurityCheckFixture securityCheckFixture(
      Set<SecurityResourceProperty> filteredProperties) {
    var database = mock(DatabaseSessionEmbedded.class);
    var sharedContext = mock(SharedContext.class);
    var security = mock(SecurityInternal.class);
    var definition = mock(IndexDefinition.class);
    when(database.getSharedContext()).thenReturn(sharedContext);
    when(sharedContext.getSecurity()).thenReturn(security);
    when(security.getAllFilteredProperties(database)).thenReturn(filteredProperties);
    when(definition.getClassName()).thenReturn("Indexed");
    when(definition.getProperties()).thenReturn(List.of("first", "second"));
    return new SecurityCheckFixture(database, security, definition);
  }

  private static SecurityResourceProperty filteredProperty(
      String className, String propertyName) {
    return new SecurityResourceProperty(
        "database.class." + className + "." + propertyName, className, propertyName);
  }

  private static void invokeSecurityCheck(
      DatabaseSessionEmbedded database, IndexDefinition definition) {
    try {
      var method = IndexManagerEmbedded.class.getDeclaredMethod(
          "checkSecurityConstraintsForIndexCreate",
          DatabaseSessionEmbedded.class,
          IndexDefinition.class);
      method.setAccessible(true);
      method.invoke(null, database, definition);
    } catch (InvocationTargetException exception) {
      if (exception.getCause() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new AssertionError("Unexpected checked exception from security check",
          exception.getCause());
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("Cannot invoke composite-index security check", exception);
    }
  }

  private record SecurityCheckFixture(
      DatabaseSessionEmbedded database,
      SecurityInternal security,
      IndexDefinition definition) {
  }

  // -----------------------------------------------------------------------
  //  dropIndex
  // -----------------------------------------------------------------------

  /**
   * dropIndex removes the index from the manager and from the class-property index map.
   * After drop, existsIndex must return false and areIndexed must return false.
   */
  @Test
  public void dropIndex_existingIndex_removesFromManager() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    assertTrue("Index must exist before drop", mgr.existsIndex(IDX));

    mgr.dropIndex(session, IDX);

    assertFalse("Index must not exist after drop", mgr.existsIndex(IDX));
    assertFalse("areIndexed must return false after index is dropped",
        mgr.areIndexed(session, CLS, "val"));
  }
}
