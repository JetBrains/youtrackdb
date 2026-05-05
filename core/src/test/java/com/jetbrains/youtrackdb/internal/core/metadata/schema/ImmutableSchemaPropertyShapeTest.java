/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty.ATTRIBUTES;
import org.junit.Test;

/**
 * Behavioural shape pin for {@link ImmutableSchemaProperty}, the thread-safe immutable snapshot
 * built by {@link SchemaImmutableClass} when the schema is frozen for read access. Snapshot
 * properties are reachable from the session via
 * {@code session.getMetadata().getImmutableSchemaSnapshot().getClass(name).getProperty(prop)} —
 * the call returns an {@code ImmutableSchemaProperty} cast to the public {@link SchemaProperty}
 * interface.
 *
 * <p>The contract this test pins:
 * <ul>
 *   <li>Every "live" mutator on {@code SchemaProperty} (setName / setType / setMandatory / set /
 *   setCustom / removeCustom / clearCustom / setLinkedClass / createIndex …) throws
 *   {@code UnsupportedOperationException} on the immutable snapshot.</li>
 *   <li>The read-side accessors return the values captured at snapshot time, even if the live
 *   property is mutated afterwards.</li>
 *   <li>{@code equals} / {@code hashCode} use {@code (name, owner.name)} as the identity tuple
 *   and reject snapshots from different sessions per the {@code getBoundToSession()} contract.
 *   </li>
 *   <li>The {@code ATTRIBUTES} bulk-get switch returns the captured values for every arm.</li>
 *   <li>The min/max comparable lazy initialiser produces a non-null comparable for the relevant
 *   types (here STRING and INTEGER) and ignores it for types where min / max are unset.</li>
 * </ul>
 *
 * <p>Snapshot freshness — calling {@code getImmutableSchemaSnapshot()} after a schema mutation
 * returns a NEW snapshot object, so a captured snapshot reference does NOT auto-refresh; the
 * tests below rely on this to assert "captured at snapshot time" semantics.
 */
public class ImmutableSchemaPropertyShapeTest extends DbTestBase {

  /**
   * Convenience: build a class + property + snapshot, return the snapshot property cast to the
   * public {@link SchemaProperty} interface.
   */
  private SchemaProperty snapshotProperty(String className, String propName, PropertyType type) {
    Schema live = session.getMetadata().getSchema();
    var cls = live.createClass(className);
    cls.createProperty(propName, type);
    return session.getMetadata().getImmutableSchemaSnapshot()
        .getClass(className).getProperty(propName);
  }

  @Test
  public void readSidePassThroughCaptureMatchesLiveProperty() {
    // Set up a live property with a non-default value in every category, then take a snapshot
    // and assert each read-side accessor returns the captured value.
    Schema live = session.getMetadata().getSchema();
    var cls = live.createClass("Frozen");
    var liveProp = cls.createProperty("p", PropertyType.STRING);
    liveProp.setMin("3");
    liveProp.setMax("32");
    liveProp.setRegexp("rx");
    liveProp.setDefaultValue("def");
    liveProp.setMandatory(true);
    liveProp.setReadonly(true);
    liveProp.setNotNull(true);
    liveProp.setDescription("desc");
    liveProp.setCustom("k", "v");

    var snapshot =
        session.getMetadata().getImmutableSchemaSnapshot().getClass("Frozen").getProperty("p");
    assertEquals("p", snapshot.getName());
    assertEquals("Frozen.p", snapshot.getFullName());
    assertEquals(PropertyType.STRING, snapshot.getType());
    assertEquals("3", snapshot.getMin());
    assertEquals("32", snapshot.getMax());
    assertEquals("rx", snapshot.getRegexp());
    assertEquals("def", snapshot.getDefaultValue());
    assertTrue(snapshot.isMandatory());
    assertTrue(snapshot.isReadonly());
    assertTrue(snapshot.isNotNull());
    assertEquals("desc", snapshot.getDescription());
    assertEquals("v", snapshot.getCustom("k"));
    assertTrue(snapshot.getCustomKeys().contains("k"));
    assertNotNull("snapshot owner class must reach back through the immutable schema",
        snapshot.getOwnerClass());
    assertEquals("Frozen", snapshot.getOwnerClass().getName());
    assertNotNull("snapshot id mirrors the live globalRef.id",
        snapshot.getId());
    assertNotNull("snapshot collate is non-null (defaults to DefaultCollate)",
        snapshot.getCollate());
  }

  @Test
  public void snapshotIsFrozenAcrossSubsequentLiveMutation() {
    // Take a snapshot, mutate the live property, then verify the snapshot still reports the
    // pre-mutation value (the snapshot constructor copied each field into a final).
    Schema live = session.getMetadata().getSchema();
    var liveProp = live.createClass("Freeze").createProperty("p", PropertyType.STRING);
    liveProp.setMandatory(false);
    liveProp.setDefaultValue("orig");

    var snapshot =
        session.getMetadata().getImmutableSchemaSnapshot().getClass("Freeze").getProperty("p");
    assertFalse(snapshot.isMandatory());
    assertEquals("orig", snapshot.getDefaultValue());

    // Mutate live; snapshot stays put.
    liveProp.setMandatory(true);
    liveProp.setDefaultValue("changed");
    assertFalse("captured snapshot must remain frozen", snapshot.isMandatory());
    assertEquals("orig", snapshot.getDefaultValue());

    // A fresh snapshot reflects the new state — sanity check the freshness contract.
    var freshSnapshot =
        session.getMetadata().getImmutableSchemaSnapshot().getClass("Freeze").getProperty("p");
    assertTrue(freshSnapshot.isMandatory());
    assertEquals("changed", freshSnapshot.getDefaultValue());
  }

  @Test
  public void everyMutatorThrowsUnsupportedOperation() {
    // Every "live" mutator on the SchemaProperty interface must reject in-place modification on
    // an immutable snapshot. Walk every method that should throw.
    var snapshot = snapshotProperty("ImmMut", "p", PropertyType.STRING);

    assertThrows(UnsupportedOperationException.class, () -> snapshot.setName("renamed"));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.setDescription("new description"));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.set(ATTRIBUTES.NAME, "renamed"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setLinkedClass(null));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.setLinkedType(PropertyType.STRING));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setNotNull(true));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setCollate("ci"));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot
            .setCollate(new com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate()));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setMandatory(true));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setReadonly(true));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setMin("1"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setMax("10"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setDefaultValue("d"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setRegexp("rx"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setType(PropertyType.LONG));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setCustom("k", "v"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.removeCustom("k"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.clearCustom());
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.createIndex(INDEX_TYPE.UNIQUE));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.createIndex("UNIQUE"));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.createIndex("UNIQUE", new java.util.HashMap<>()));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.createIndex(INDEX_TYPE.UNIQUE, new java.util.HashMap<>()));
  }

  @Test
  public void getAttributesSwitchExercisesEveryArm() {
    // The bulk-get path on ImmutableSchemaProperty is a sibling 13-arm switch — drive each arm
    // with a captured value.
    Schema live = session.getMetadata().getSchema();
    var cls = live.createClass("ImmAttrs");
    var linked = live.createClass("ImmAttrsLinked");
    var prop = cls.createProperty("p", PropertyType.LINKLIST, linked);
    prop.setMin("0");
    prop.setMax("10");
    prop.setMandatory(true);
    prop.setReadonly(true);
    prop.setDefaultValue("d");
    prop.setNotNull(true);
    prop.setRegexp("rx");
    prop.setDescription("desc");
    prop.setCollate("ci");

    var snapshot =
        session.getMetadata().getImmutableSchemaSnapshot().getClass("ImmAttrs").getProperty("p");
    assertNotNull(snapshot.get(ATTRIBUTES.LINKEDCLASS));
    // LINKEDTYPE returns the cached PropertyTypeInternal directly (or null when unset).
    assertNull(snapshot.get(ATTRIBUTES.LINKEDTYPE));
    assertEquals("0", snapshot.get(ATTRIBUTES.MIN));
    assertEquals(true, snapshot.get(ATTRIBUTES.MANDATORY));
    assertEquals(true, snapshot.get(ATTRIBUTES.READONLY));
    assertEquals("10", snapshot.get(ATTRIBUTES.MAX));
    assertEquals("d", snapshot.get(ATTRIBUTES.DEFAULT));
    assertEquals("p", snapshot.get(ATTRIBUTES.NAME));
    assertEquals(true, snapshot.get(ATTRIBUTES.NOTNULL));
    assertEquals("rx", snapshot.get(ATTRIBUTES.REGEXP));
    // Note: ImmutableSchemaProperty.get(TYPE) returns the internal enum, not the public one — the
    // contract here is "captured-at-snapshot-time identity", not interchangeability with the
    // mutable property's get(TYPE).
    assertEquals(PropertyTypeInternal.LINKLIST, snapshot.get(ATTRIBUTES.TYPE));
    assertNotNull(snapshot.get(ATTRIBUTES.COLLATE));
    assertEquals("desc", snapshot.get(ATTRIBUTES.DESCRIPTION));
  }

  @Test
  public void getAttributesNullArgumentThrows() {
    var snapshot = snapshotProperty("ImmAttrNull", "p", PropertyType.STRING);
    assertThrows(IllegalArgumentException.class, () -> snapshot.get(null));
  }

  @Test
  public void equalsAndHashCodeUseNamePlusOwnerName() {
    // hashCode is name.hashCode() + 31 * owner.name.hashCode(); equals matches on (name,
    // owner.name) when the other object is a SchemaPropertyInternal whose getBoundToSession is
    // null (i.e. another snapshot). Two snapshots of the same class+property are equal; two
    // snapshots of different properties are not.
    var snapshotA = snapshotProperty("EqA", "x", PropertyType.STRING);
    var snapshotASecond =
        session.getMetadata().getImmutableSchemaSnapshot().getClass("EqA").getProperty("x");
    var snapshotB = snapshotProperty("EqB", "x", PropertyType.STRING);

    // Two snapshots for the same class+property are .equals each other and share a hashCode.
    assertEquals(snapshotA, snapshotASecond);
    assertEquals(snapshotA.hashCode(), snapshotASecond.hashCode());
    // A snapshot is reflexively equal.
    assertEquals(snapshotA, snapshotA);
    // Snapshots from different owner classes (or property names) are not equal.
    assertFalse(snapshotA.equals(snapshotB));
    // A snapshot is not equal to a non-property object.
    assertFalse(snapshotA.equals("not a property"));
    assertFalse(snapshotA.equals(null));
  }

  @Test
  public void liveProxyDiffersFromImmutableSnapshotPerBoundToSession() {
    // The live proxy returns the session in getBoundToSession(); the immutable snapshot returns
    // null. The equals contract on ImmutableSchemaProperty rejects a counterparty whose
    // getBoundToSession is non-null — so live.equals(snapshot) must return false even when the
    // (name, owner.name) tuple matches, because the live SchemaPropertyProxy.equals
    // short-circuits on session-mismatch via its own (different) implementation.
    Schema live = session.getMetadata().getSchema();
    var cls = live.createClass("BoundDiff");
    var liveProp = cls.createProperty("p", PropertyType.STRING);

    var snapshot = session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("BoundDiff").getProperty("p");

    // Live proxy is bound to a session, snapshot is not.
    assertFalse("live property must not equal a frozen snapshot of itself",
        liveProp.equals(snapshot));
    assertFalse("frozen snapshot must not equal a live property of itself",
        snapshot.equals(liveProp));
  }

  @Test
  public void minMaxComparableInitializedForStringAndNumeric() {
    // For STRING properties, min / max wire ValidationStringComparable; for INTEGER they wire
    // the natural-ordered Comparable on Integer. Both produce a non-null comparable.
    Schema live = session.getMetadata().getSchema();
    var stringProp = live.createClass("MinMaxStr").createProperty("p", PropertyType.STRING);
    stringProp.setMin("3");
    stringProp.setMax("32");

    var stringSnap = (ImmutableSchemaProperty) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("MinMaxStr").getProperty("p");
    assertNotNull("string snapshot must have a min comparable", stringSnap.getMinComparable());
    assertNotNull("string snapshot must have a max comparable", stringSnap.getMaxComparable());

    var intProp = live.createClass("MinMaxInt").createProperty("n", PropertyType.INTEGER);
    intProp.setMin("0");
    intProp.setMax("10");

    var intSnap = (ImmutableSchemaProperty) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("MinMaxInt").getProperty("n");
    assertNotNull("integer snapshot must have a min comparable", intSnap.getMinComparable());
    assertNotNull("integer snapshot must have a max comparable", intSnap.getMaxComparable());
  }

  @Test
  public void minMaxComparableNullWhenUnset() {
    // When min / max are not set on the live property, the snapshot's comparables are null —
    // the constructor's null-arm short-circuit.
    var snapshot =
        (ImmutableSchemaProperty) snapshotProperty("MinMaxNone", "p", PropertyType.STRING);
    assertNull(snapshot.getMinComparable());
    assertNull(snapshot.getMaxComparable());
  }

  @Test
  public void linkedClassResolvesLazilyWhenAvailable() {
    // ImmutableSchemaProperty captures linkedClassName but resolves the SchemaClass lazily on
    // first getLinkedClass(). Verify the lazy resolution succeeds against the schema attached to
    // the snapshot's owner.
    Schema live = session.getMetadata().getSchema();
    var linked = live.createClass("LazyLinked");
    live.createClass("LazyOwner").createProperty("link", PropertyType.LINK, linked);

    var snapshot = session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("LazyOwner").getProperty("link");
    assertNotNull("lazy resolution returns a non-null linked class", snapshot.getLinkedClass());
    assertEquals("LazyLinked", snapshot.getLinkedClass().getName());
    // Calling a second time returns the cached value (the lazy field is filled on first hit).
    assertEquals("LazyLinked", snapshot.getLinkedClass().getName());
  }

  @Test
  public void linkedClassNullWhenUnset() {
    // No linkedClassName captured ⇒ getLinkedClass() returns null on the early-exit arm.
    var snapshot = snapshotProperty("NoLink", "p", PropertyType.STRING);
    assertNull(snapshot.getLinkedClass());
    assertNull(snapshot.getLinkedType());
  }

  @Test
  public void allIndexesReflectsSnapshotMoment() {
    // getAllIndexes() on the immutable snapshot is captured at construction time. Create an
    // index, then snapshot — the index is visible. (This indirectly exercises
    // SchemaPropertyImpl.getAllIndexesInternal via the ImmutableSchemaProperty constructor.)
    Schema live = session.getMetadata().getSchema();
    var prop = live.createClass("ImmIdx").createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.UNIQUE);

    // Force a fresh snapshot so the new index is captured.
    var snapshot = (SchemaPropertyInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("ImmIdx").getProperty("name");
    var allIndexes = snapshot.getAllIndexes();
    assertNotNull(allIndexes);
    assertTrue("captured snapshot must list the new property index",
        allIndexes.contains("ImmIdx.name"));
    assertNotNull("getAllIndexesInternal returns the underlying Index collection",
        snapshot.getAllIndexesInternal());
  }

  @Test
  public void boundToSessionAndTypeInternalAreExposed() {
    // Pin the two internal-interface accessors that ImmutableSchemaProperty implements directly
    // — getBoundToSession() must return null on a snapshot, and getTypeInternal() must return
    // the captured PropertyTypeInternal.
    var snapshot = (SchemaPropertyInternal) snapshotProperty("BoundType", "p", PropertyType.LONG);
    assertNull("snapshot has no bound session by contract", snapshot.getBoundToSession());
    assertEquals(PropertyTypeInternal.LONG, snapshot.getTypeInternal());
  }

  @Test
  public void toStringEmitsNamePlusType() {
    // toString format is "name (type=TYPE)" — pin the wire format because external code (e.g.
    // log statements) may rely on it.
    var snapshot = snapshotProperty("ToStr", "p", PropertyType.STRING);
    assertEquals("p (type=STRING)", snapshot.toString());
  }
}
