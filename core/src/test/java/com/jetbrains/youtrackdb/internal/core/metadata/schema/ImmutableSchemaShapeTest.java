package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Test;

/**
 * Shape pin for {@link ImmutableSchema}, the thread-safe snapshot of the live schema.
 *
 * <p>The ImmutableSchema's read methods (lookups, version, identity, indexes,
 * collections-rely-on-collection, blob collections) must reflect the schema state at the time the
 * snapshot was captured and stay frozen across subsequent live mutations. The mutator methods
 * (createClass, dropClass, createGlobalProperty, etc.) must throw {@link
 * UnsupportedOperationException} — the snapshot is a read-only contract.
 *
 * <p>This test pins:
 * <ul>
 *   <li>class-list freeze: a snapshot taken before a {@code createClass} reports the pre-mutation
 *       class list; a fresh snapshot reflects the post-mutation state;</li>
 *   <li>global-property-list freeze: same invariant for {@code createGlobalProperty};</li>
 *   <li>version round-trip: the snapshot's version equals {@code SchemaShared.getVersion()} at
 *       capture time and stays put across live mutations;</li>
 *   <li>identity round-trip: snapshot identity equals {@code SchemaShared.getIdentity()};</li>
 *   <li>every mutator method on {@code ImmutableSchema} (10 overloads) throws
 *       {@code UnsupportedOperationException};</li>
 *   <li>{@code makeSnapshot()} on an ImmutableSchema returns the same instance (idempotent);</li>
 *   <li>{@code getClassByCollectionId} returns the correct {@code SchemaImmutableClass} entry
 *       and {@code null} for unknown ids;</li>
 *   <li>{@code getClassesRelyOnCollection} returns classes whose polymorphic collection ids
 *       include the collection;</li>
 *   <li>{@code getClass(Class&lt;?&gt;)} resolves by simple name (not full name);</li>
 *   <li>{@code indexExists}, {@code getIndexes}, and {@code getIndexDefinition} round-trip via
 *       a freshly-created index visible in a new snapshot.</li>
 * </ul>
 */
public class ImmutableSchemaShapeTest extends DbTestBase {

  @Test
  public void snapshotClassListIsFrozenAcrossLiveMutation() {
    // A snapshot taken BEFORE a createClass call must NOT contain the new class. A fresh
    // snapshot taken AFTER must contain it.
    var schema = session.getMetadata().getSchema();
    var beforeSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
    assertFalse("ImmFreezePre must not exist in a fresh database",
        beforeSnapshot.existsClass("ImmFreezePre"));

    schema.createClass("ImmFreezePre");

    // The original snapshot is frozen — still does NOT see the new class even though the live
    // schema does.
    assertFalse("snapshot must remain frozen across live createClass",
        beforeSnapshot.existsClass("ImmFreezePre"));
    assertTrue("live schema must see the new class immediately",
        schema.existsClass("ImmFreezePre"));

    // A fresh snapshot picks up the new class.
    var afterSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
    assertTrue("fresh snapshot must see the new class",
        afterSnapshot.existsClass("ImmFreezePre"));
  }

  @Test
  public void snapshotGlobalPropertyListIsFrozenAcrossLiveMutation() {
    // Pin the same freeze invariant for global properties. A snapshot's getGlobalProperties()
    // must reflect the size at capture time, not at read time.
    var schema = session.getMetadata().getSchema();
    var beforeSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
    int sizeBefore = beforeSnapshot.getGlobalProperties().size();

    schema.createGlobalProperty("immFreezeProp", PropertyType.STRING, 800);

    int sizeStillFrozen = beforeSnapshot.getGlobalProperties().size();
    assertEquals("snapshot global-properties list must be frozen at capture-time size",
        sizeBefore, sizeStillFrozen);

    var afterSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
    assertTrue("fresh snapshot must include the newly created global property",
        afterSnapshot.getGlobalProperties().size() > sizeBefore);
    var prop = afterSnapshot.getGlobalPropertyById(800);
    assertNotNull(prop);
    assertEquals("immFreezeProp", prop.getName());
  }

  @Test
  public void snapshotVersionMatchesSchemaSharedAtCaptureTime() {
    // Version is captured into the immutable snapshot at construction time. It must equal the
    // SchemaShared version at the point of capture, and must NOT change after subsequent
    // mutations.
    var schemaShared = session.getSharedContext().getSchema();
    var snapshotA = session.getMetadata().getImmutableSchemaSnapshot();
    int snapshotAVersion = snapshotA.getVersion();
    assertEquals("snapshot version must equal SchemaShared version at capture",
        schemaShared.getVersion(), snapshotAVersion);

    session.getMetadata().getSchema().createClass("VersionBumpClass");

    // snapshotA version stays put — it's an int field captured at construction.
    assertEquals("snapshot version must NOT change across live mutation",
        snapshotAVersion, snapshotA.getVersion());
  }

  @Test
  public void snapshotIdentityMatchesSchemaSharedIdentity() {
    var schemaShared = session.getSharedContext().getSchema();
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();
    assertSame("snapshot identity must be the same RID instance as SchemaShared identity",
        schemaShared.getIdentity(), snapshot.getIdentity());
  }

  @Test
  public void mutatorOverloadsAllRejectWithUnsupportedOperation() {
    // Pin the read-only contract: every mutator on ImmutableSchema must throw
    // UnsupportedOperationException. The snapshot must NEVER persist a mutation.
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();

    assertUOE(() -> snapshot.createClass("X"));
    assertUOE(() -> snapshot.createClass("X", session.getMetadata().getSchema()
        .getClass("OUser")));
    assertUOE(() -> snapshot.createClass("X", new SchemaClass[0]));
    assertUOE(() -> snapshot.createClass("X", null, new int[] {1}));
    assertUOE(() -> snapshot.createClass("X", 1, new SchemaClass[0]));
    assertUOE(() -> snapshot.createClass("X", new int[] {1}));
    assertUOE(() -> snapshot.createAbstractClass("X"));
    assertUOE(() -> snapshot.createAbstractClass("X",
        session.getMetadata().getSchema().getClass("OUser")));
    assertUOE(() -> snapshot.createAbstractClass("X", new SchemaClass[0]));
    assertUOE(() -> snapshot.dropClass("X"));
    assertUOE(() -> snapshot.getOrCreateClass("X"));
    assertUOE(() -> snapshot.getOrCreateClass("X",
        session.getMetadata().getSchema().getClass("OUser")));
    assertUOE(() -> snapshot.getOrCreateClass("X", new SchemaClass[0]));
    assertUOE(() -> snapshot.createGlobalProperty("p", PropertyType.STRING, 0));
  }

  @Test
  public void makeSnapshotOnImmutableSchemaIsIdempotent() {
    // ImmutableSchema.makeSnapshot() returns 'this' — the idempotency arm. Pin: snapshot on the
    // snapshot is the same instance.
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();
    var snapshotSnapshot = snapshot.makeSnapshot();
    assertSame("ImmutableSchema.makeSnapshot must be the identity function",
        snapshot, snapshotSnapshot);
  }

  @Test
  public void getClassByJavaClassResolvesBySimpleName() {
    // ImmutableSchema.getClass(Class<?>) routes through getClass(iClass.getSimpleName()). Pin:
    // OUser.class resolves to the OUser system class. Null input returns null.
    session.getMetadata().getSchema().createClass(LocalShape.class.getSimpleName());
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();

    assertNotNull("snapshot.getClass(LocalShape.class) must resolve by simple name",
        snapshot.getClass(LocalShape.class));
    assertEquals("LocalShape", snapshot.getClass(LocalShape.class).getName());
    assertNull("null Class<?> argument must return null",
        snapshot.getClass((Class<?>) null));
  }

  @Test
  public void getClassByCollectionIdReturnsImmutableEntry() {
    // ImmutableSchema.getClassByCollectionId looks up the collectionsToClasses map captured at
    // snapshot time. Pin: known id returns the SchemaImmutableClass; unknown id returns null.
    var cls = session.getMetadata().getSchema().createClass("CollLookup");
    int collectionId = cls.getCollectionIds()[0];

    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();
    var byColl = snapshot.getClassByCollectionId(collectionId);
    assertNotNull("getClassByCollectionId must return the class for a known collection id",
        byColl);
    assertEquals("CollLookup", byColl.getName());
    assertTrue("snapshot's class must be a SchemaImmutableClass: " + byColl.getClass(),
        byColl instanceof SchemaImmutableClass);

    assertNull("unknown collection id must return null",
        snapshot.getClassByCollectionId(-99999));
  }

  @Test
  public void getClassesRelyOnCollectionFindsPolymorphicMembers() {
    // ImmutableSchema.getClassesRelyOnCollection iterates classes and matches against
    // getPolymorphicCollectionIds. Pin: a single class created on its own collection is found
    // by its primary collection name.
    var cls = session.getMetadata().getSchema().createClass("RelyOnColl");
    int collId = cls.getCollectionIds()[0];
    var collName = session.getCollectionNameById(collId);
    assertNotNull("Collection name lookup must succeed for the test class", collName);

    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();
    var matching = snapshot.getClassesRelyOnCollection(collName, session);
    assertTrue("RelyOnColl class must rely on its own primary collection",
        matching.stream().anyMatch(c -> "RelyOnColl".equals(c.getName())));
  }

  @Test
  public void blobCollectionsRoundTripThroughSnapshot() {
    // ImmutableSchema captures blogCollections (IntSet) at construction time. Pin: a collection
    // added to blob collections via SchemaShared appears in the post-mutation snapshot's
    // getBlobCollections result.
    var schemaShared = session.getSharedContext().getSchema();
    var snapshotBefore = session.getMetadata().getImmutableSchemaSnapshot();
    int sizeBefore = snapshotBefore.getBlobCollections().size();

    int newCollId = session.addCollection("blobLikeColl");
    schemaShared.addBlobCollection(session, newCollId);

    var snapshotAfter = session.getMetadata().getImmutableSchemaSnapshot();
    assertTrue("blob-collection set in fresh snapshot must include the new id",
        snapshotAfter.getBlobCollections().contains(newCollId));
    assertTrue("blob-collection set must have grown",
        snapshotAfter.getBlobCollections().size() > sizeBefore);
  }

  @Test
  public void snapshotCountClassesEqualsClassListSize() {
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();
    assertEquals("countClasses must equal getClasses().size()",
        snapshot.getClasses().size(), snapshot.countClasses());
  }

  @Test
  public void getClassInternalAndGetClassReturnSameImmutableEntry() {
    // ImmutableSchema.getClass(String) delegates to getClassInternal(String). Pin: both return
    // the same SchemaImmutableClass instance.
    session.getMetadata().getSchema().createClass("DualLookup");
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();
    var viaPublic = snapshot.getClass("DualLookup");
    var viaInternal = snapshot.getClassInternal("DualLookup");
    assertSame("getClass and getClassInternal must return the same snapshot entry",
        viaPublic, viaInternal);
    assertNull("null name must return null on getClassInternal",
        snapshot.getClassInternal(null));
    assertNull("unknown name must return null on getClass",
        snapshot.getClass("DoesNotExist"));
  }

  @Test
  public void indexLookupRoundTripsAfterFreshSnapshot() {
    // ImmutableSchema captures the IndexManager's index list at snapshot time. Pin: an index
    // created via the live schema is visible in the next snapshot via indexExists,
    // getIndexDefinition, and getIndexes.
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("IdxRoundTrip");
    cls.createProperty("f", PropertyType.STRING);
    cls.createIndex("IdxRoundTrip.fIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "f");

    // Force a fresh snapshot — indexes are captured at construction, not on demand.
    session.getSharedContext().getSchema().forceSnapshot();
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();

    assertTrue("indexExists must report the freshly created index",
        snapshot.indexExists("IdxRoundTrip.fIdx"));
    assertTrue("getIndexes must include the freshly created index",
        snapshot.getIndexes().contains("IdxRoundTrip.fIdx"));

    var def = snapshot.getIndexDefinition("IdxRoundTrip.fIdx");
    assertNotNull(def);
    assertEquals("IdxRoundTrip", def.className());
    assertEquals(SchemaClass.INDEX_TYPE.NOTUNIQUE, def.type());

    // Unknown index → IllegalArgumentException.
    try {
      snapshot.getIndexDefinition("DoesNotExist");
      fail("getIndexDefinition must throw for unknown index");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("DoesNotExist"));
    }
  }

  @Test
  public void getCollectionSelectionFactoryExposesTheLiveFactory() {
    // ImmutableSchema captures the SchemaShared collection-selection factory reference at
    // construction. Pin: the snapshot returns a non-null factory.
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();
    assertNotNull(snapshot.getCollectionSelectionFactory());
  }

  @Test
  public void globalPropertyByIdReturnsNullForOutOfRangeId() {
    // ImmutableSchema.getGlobalPropertyById(id) returns null when id >= properties.size().
    // Pin: a clearly-out-of-range id returns null without throwing.
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();
    assertNull("out-of-range global-property id must return null",
        snapshot.getGlobalPropertyById(Integer.MAX_VALUE));
  }

  /**
   * Helper: assert that a given runnable throws UnsupportedOperationException — used to keep the
   * mutator-rejection test's body compact.
   */
  private static void assertUOE(Runnable r) {
    try {
      r.run();
      fail("expected UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {
      // pass
    }
  }

  /** Marker class for the {@code getClass(Class<?>)} simple-name resolution test. */
  private static final class LocalShape {
  }
}
