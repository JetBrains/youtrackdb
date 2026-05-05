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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Behavioural shape pin for {@link SchemaImmutableClass}, the thread-safe immutable snapshot built
 * by {@link SchemaShared#makeSnapshot} when the schema is frozen for read access. Snapshot classes
 * are reachable from the session via
 * {@code session.getMetadata().getImmutableSchemaSnapshot().getClass(name)} — the call returns a
 * {@code SchemaImmutableClass} cast to the public {@link SchemaClass} interface.
 *
 * <p>The contract this test pins:
 * <ul>
 *   <li>Every "live" mutator on {@code SchemaClass} (setName / setAbstract / setStrictMode /
 *       setDescription / setSuperClasses / addSuperClass / removeSuperClass / setCustom /
 *       removeCustom / clearCustom / set / dropProperty / createProperty / createIndex / truncate)
 *       throws {@code UnsupportedOperationException} on the immutable snapshot.</li>
 *   <li>The read-side accessors return values captured at snapshot time — even if the live class
 *       is mutated afterwards.</li>
 *   <li>{@code equals} / {@code hashCode} use the class name as identity; the snapshot's
 *       {@code getBoundToSession()} returns {@code null} (the snapshot is detached from any
 *       session), and {@code equals} accepts a peer only if it too has a null {@code BoundToSession}
 *       — so a session-bound proxy and a snapshot of the same class are NOT equal under the
 *       snapshot's equals contract.</li>
 *   <li>The 6-arm {@code get(ATTRIBUTES)} bulk-get returns the captured value for every arm.</li>
 *   <li>{@code isSubClassOf} / {@code isSuperClassOf} walk the snapshot's frozen super-class chain
 *       correctly.</li>
 *   <li>{@code init} is idempotent: calling it twice does not double-wire properties or
 *       superclasses.</li>
 *   <li>The {@code isVertexType} / {@code isEdgeType} / {@code isFunction} / {@code isUser} /
 *       {@code isRole} / {@code isSequence} / {@code isScheduler} / {@code isSecurityPolicy}
 *       lazy-resolved flags are computed on first {@code init()} from the static superclass chain
 *       and remain stable.</li>
 * </ul>
 *
 * <p>Snapshot freshness — calling {@code getImmutableSchemaSnapshot()} after a schema mutation
 * returns a NEW snapshot object, so a captured snapshot reference does NOT auto-refresh; the tests
 * below rely on this to assert "captured at snapshot time" semantics.
 */
public class SchemaImmutableClassShapeTest extends DbTestBase {

  /**
   * Convenience: build a class + property + snapshot, return the snapshot class cast to the public
   * {@link SchemaClass} interface.
   */
  private SchemaClass snapshotOf(String className) {
    return session.getMetadata().getImmutableSchemaSnapshot().getClass(className);
  }

  @Test
  public void readSidePassThroughCaptureMatchesLiveClass() {
    // Set up a live class with non-default values in every attribute, then take a snapshot and
    // assert each read-side accessor returns the captured value.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("FrozenCls");
    cls.setStrictMode(true);
    cls.setDescription("frozen-desc");
    cls.setCustom("k", "v");
    cls.createProperty("p", PropertyType.STRING);

    var snapshot = snapshotOf("FrozenCls");
    assertEquals("FrozenCls", snapshot.getName());
    // getStreamableName lives on the SchemaClassInternal interface (not the public SchemaClass).
    assertEquals("FrozenCls", ((SchemaClassInternal) snapshot).getStreamableName());
    assertEquals("frozen-desc", snapshot.getDescription());
    assertTrue(snapshot.isStrictMode());
    assertFalse(snapshot.isAbstract());
    assertEquals("v", snapshot.getCustom("k"));
    assertEquals(1, snapshot.getCustomKeys().size());
    assertNotNull(snapshot.getProperty("p"));
    assertTrue(snapshot.existsProperty("p"));
    assertFalse(snapshot.existsProperty("missing"));
  }

  @Test
  public void mutatorsUniformlyThrowUnsupportedOperation() {
    // Every mutator on SchemaImmutableClass throws UnsupportedOperationException — pin the
    // contract for each method individually so a future regression that silently relaxes one of
    // the throws is caught.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("FrozenMut");

    var snapshot = snapshotOf("FrozenMut");
    var snapshotInternal = (SchemaClassInternal) snapshot;

    assertThrows(UnsupportedOperationException.class, () -> snapshot.setName("renamed"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setAbstract(true));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setStrictMode(true));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.setDescription("d"));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.setSuperClasses(List.of()));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.addSuperClass(snapshot));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.removeSuperClass(snapshot));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setCustom("k", "v"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.removeCustom("k"));
    assertThrows(UnsupportedOperationException.class, snapshot::clearCustom);
    // truncate + set(ATTRIBUTES) live on SchemaClassInternal (not the public SchemaClass).
    assertThrows(UnsupportedOperationException.class, snapshotInternal::truncate);
    assertThrows(UnsupportedOperationException.class,
        () -> snapshotInternal.set(ATTRIBUTES.NAME, "x"));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.createProperty("np", PropertyType.STRING));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.createProperty("np2", PropertyType.STRING, snapshot));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.createProperty("np3", PropertyType.EMBEDDEDLIST, PropertyType.STRING));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.dropProperty("p"));
    // The createIndex overloads with a null ProgressListener / metadata / algorithm all match
    // multiple signatures — disambiguate via explicit ProgressListener-typed local nulls.
    final ProgressListener nullListener = null;
    final Map<String, Object> nullMetadata = null;
    final String nullAlgorithm = null;
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.createIndex("ix1", INDEX_TYPE.NOTUNIQUE, "p"));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.createIndex("ix2", "NOTUNIQUE", "p"));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.createIndex("ix3", INDEX_TYPE.NOTUNIQUE, nullListener, "p"));
    // The (name, type, listener, metadata, fields...) and (name, type, listener, metadata,
    // algorithm, fields...) overloads share a String-varargs tail, making them indistinguishable
    // at any call site that passes literal null + String args. Drive both via the impl directly,
    // where the explicit method signature is unambiguous (the snapshot exposes the same
    // SchemaImmutableClass instance for both impl-level overloads).
    var snapshotImpl = (SchemaImmutableClass) snapshot;
    assertThrows(UnsupportedOperationException.class,
        () -> snapshotImpl.createIndex("ix4", "NOTUNIQUE", nullListener, nullMetadata,
            new String[] {"p"}));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshotImpl.createIndex("ix5", "NOTUNIQUE", nullListener, nullMetadata,
            "MY_ALG", new String[] {"p"}));

    // The internal-API createProperty(String, PropertyTypeInternal, ...) overloads also throw.
    assertThrows(UnsupportedOperationException.class,
        () -> snapshotInternal.createProperty("np6", PropertyTypeInternal.STRING,
            (PropertyTypeInternal) null, false));
    assertThrows(UnsupportedOperationException.class,
        () -> snapshotInternal.createProperty("np7", PropertyTypeInternal.STRING,
            (SchemaClass) null, false));
  }

  @Test
  public void capturedValuesDoNotChaseLiveMutationsAfterSnapshot() {
    // The snapshot is detached from the live class — mutating the live class after capturing the
    // snapshot must NOT update the snapshot's accessors. This is the core "immutable" guarantee.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DetachedFrozen");
    cls.setDescription("orig-desc");

    var snapshot = snapshotOf("DetachedFrozen");
    assertEquals("orig-desc", snapshot.getDescription());

    // Mutate the live class.
    cls.setDescription("changed-desc");
    cls.setStrictMode(true);

    // The snapshot still reports the original values.
    assertEquals("captured snapshot must not chase the live description",
        "orig-desc", snapshot.getDescription());
    assertFalse("captured snapshot must not chase the live strictMode flag",
        snapshot.isStrictMode());

    // A fresh snapshot picks up the new state.
    var freshSnapshot = snapshotOf("DetachedFrozen");
    assertEquals("changed-desc", freshSnapshot.getDescription());
    assertTrue(freshSnapshot.isStrictMode());

    // The original snapshot reference is not the same instance as the fresh one — snapshot objects
    // are rebuilt on each call to getImmutableSchemaSnapshot.
    assertNotEquals(System.identityHashCode(snapshot), System.identityHashCode(freshSnapshot));
  }

  @Test
  public void getAttributesBulkGetCoversEveryArm() {
    // Pin SchemaImmutableClass.get(ATTRIBUTES) — six arms: NAME, SUPERCLASSES, STRICT_MODE,
    // ABSTRACT, CUSTOM, DESCRIPTION. The CUSTOM arm returns an unmodifiable map.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("FrozenAttrParent");
    var cls = schema.createClass("FrozenAttrCls", parent);
    cls.setStrictMode(true);
    cls.setDescription("d");
    cls.setCustom("k", "v");

    var snapshotImpl = (SchemaImmutableClass) snapshotOf("FrozenAttrCls");

    assertEquals("FrozenAttrCls", snapshotImpl.get(ATTRIBUTES.NAME));
    @SuppressWarnings("unchecked")
    List<SchemaClass> superclasses = (List<SchemaClass>) snapshotImpl.get(ATTRIBUTES.SUPERCLASSES);
    assertEquals(1, superclasses.size());
    assertEquals("FrozenAttrParent", superclasses.getFirst().getName());
    assertEquals(Boolean.TRUE, snapshotImpl.get(ATTRIBUTES.STRICT_MODE));
    assertEquals(Boolean.FALSE, snapshotImpl.get(ATTRIBUTES.ABSTRACT));
    @SuppressWarnings("unchecked")
    Map<String, String> customMap = (Map<String, String>) snapshotImpl.get(ATTRIBUTES.CUSTOM);
    assertEquals("v", customMap.get("k"));
    assertEquals("d", snapshotImpl.get(ATTRIBUTES.DESCRIPTION));

    // Null-arg arm.
    assertThrows(IllegalArgumentException.class, () -> snapshotImpl.get(null));
  }

  @Test
  public void equalsAndHashCodeUseNameIdentityAndSessionDetachment() {
    // SchemaImmutableClass.equals returns true only for SchemaClassInternal peers whose
    // getBoundToSession() returns null (i.e., another snapshot) AND whose name matches; hashCode
    // uses name.hashCode().
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("EqFrozenA");
    schema.createClass("EqFrozenB");

    var sA1 = snapshotOf("EqFrozenA");
    var sA2 = snapshotOf("EqFrozenA");
    var sB = snapshotOf("EqFrozenB");

    // Same-name snapshots equal each other (both have null BoundToSession).
    assertEquals(sA1, sA2);
    assertEquals(sA1.hashCode(), sA2.hashCode());

    // Different-name snapshots are not equal.
    assertNotEquals(sA1, sB);

    // The session-bound live class (proxy) is NOT equal to the snapshot — the snapshot's equals
    // requires the peer to also have a null BoundToSession.
    var liveProxy = session.getMetadata().getSchema().getClass("EqFrozenA");
    assertTrue("live class must be session-bound for this assertion to be meaningful",
        ((SchemaClassInternal) liveProxy).getBoundToSession() == session);
    assertFalse("snapshot.equals(live-proxy) must be false (different BoundToSession)",
        sA1.equals(liveProxy));

    // Reflexive identity short-circuit.
    assertEquals(sA1, sA1);

    // Non-SchemaClassInternal peer (e.g., raw String) — false.
    assertNotEquals(sA1, "EqFrozenA");
    assertNotEquals(sA1, null);

    // Hash code is stable across calls (uses name.hashCode()).
    assertEquals("EqFrozenA".hashCode(), sA1.hashCode());

    // toString returns the class name.
    assertEquals("EqFrozenA", sA1.toString());
  }

  @Test
  public void getBoundToSessionReturnsNullOnSnapshot() {
    // The snapshot's getBoundToSession is permanently null — it's detached from any session.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("FrozenBound");
    var snapshot = (SchemaClassInternal) snapshotOf("FrozenBound");
    assertNull("snapshot must report a null BoundToSession", snapshot.getBoundToSession());
  }

  @Test
  public void isSubClassOfWalksFrozenSuperClassChain() {
    // SchemaImmutableClass.isSubClassOf(String) and isSubClassOf(SchemaClass) walk the snapshot's
    // frozen super-class chain. Self-arm + transitive parent + miss + null-arm.
    Schema schema = session.getMetadata().getSchema();
    var grand = schema.createClass("FrozenGrand");
    var parent = schema.createClass("FrozenParent", grand);
    schema.createClass("FrozenChild", parent);

    var sChild = snapshotOf("FrozenChild");
    var sParent = snapshotOf("FrozenParent");
    var sGrand = snapshotOf("FrozenGrand");

    // By name.
    assertFalse(sChild.isSubClassOf((String) null));
    assertTrue(sChild.isSubClassOf("FrozenChild"));
    assertTrue(sChild.isSubClassOf("FrozenParent"));
    assertTrue(sChild.isSubClassOf("FrozenGrand"));
    assertFalse(sChild.isSubClassOf("Missing"));

    // By reference.
    assertFalse(sChild.isSubClassOf((SchemaClass) null));
    assertTrue(sChild.isSubClassOf(sChild));
    assertTrue(sChild.isSubClassOf(sParent));
    assertTrue(sChild.isSubClassOf(sGrand));
    assertFalse(sGrand.isSubClassOf(sChild));

    // isSuperClassOf is the inverse.
    assertTrue(sGrand.isSuperClassOf(sChild));
    assertTrue(sParent.isSuperClassOf(sChild));
    assertFalse(sChild.isSuperClassOf(sParent));
    assertFalse(sChild.isSuperClassOf(null));
  }

  @Test
  public void getSuperClassesReturnsFrozenList() {
    // The snapshot's getSuperClasses returns an unmodifiable list (built once at init).
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("FrozenSupParent");
    schema.createClass("FrozenSupChild", parent);

    var sChild = snapshotOf("FrozenSupChild");
    var supers = sChild.getSuperClasses();
    assertEquals(1, supers.size());
    assertEquals("FrozenSupParent", supers.getFirst().getName());
    assertThrows(UnsupportedOperationException.class, () -> supers.add(sChild));
  }

  @Test
  public void getDeclaredVsAllPropertiesReflectInheritance() {
    // The snapshot tracks declared vs all-walk properties; getDeclaredProperties is the child's
    // own only, getProperties / getPropertiesMap include inherited.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("FrozenPropParent");
    parent.createProperty("p1", PropertyType.STRING);
    var child = schema.createClass("FrozenPropChild", parent);
    child.createProperty("p2", PropertyType.INTEGER);

    var sChild = snapshotOf("FrozenPropChild");
    assertEquals(1, sChild.getDeclaredProperties().size());
    assertEquals("p2", sChild.getDeclaredProperties().iterator().next().getName());

    var allProps = sChild.getProperties();
    assertEquals(2, allProps.size());

    var pMap = sChild.getPropertiesMap();
    assertNotNull(pMap.get("p1"));
    assertNotNull(pMap.get("p2"));
  }

  @Test
  public void getCollectionIdsAndPolymorphicAreReadable() {
    // Snapshot exposes the raw collection ids array and a defensive-copy polymorphic ids array.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("FrozenCollParent");
    schema.createClass("FrozenCollChild", parent);

    var sParent = snapshotOf("FrozenCollParent");
    int[] ids = sParent.getCollectionIds();
    assertNotNull(ids);
    assertTrue(ids.length >= 1);
    assertTrue(sParent.hasCollectionId(ids[0]));

    int[] poly = sParent.getPolymorphicCollectionIds();
    // hasPolymorphicCollectionId returns true for the parent's own id and the child's id.
    assertTrue(sParent.hasPolymorphicCollectionId(ids[0]));

    // Mutating the returned defensive-copy array must not affect the snapshot.
    int original = poly[0];
    poly[0] = -42;
    assertEquals(original, sParent.getPolymorphicCollectionIds()[0]);
  }

  @Test
  public void getImplementationReturnsLiveImplBackingTheSnapshot() {
    // SchemaImmutableClass.getImplementation returns the original SchemaClassImpl that the
    // snapshot was built from. The same live impl instance must back two consecutive snapshots
    // taken without any schema mutation in between (subject to ImmutableSchema cache semantics —
    // the underlying SchemaShared map is unchanged).
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("FrozenImpl");

    var snapshot1 = (SchemaImmutableClass) snapshotOf("FrozenImpl");
    var snapshot2 = (SchemaImmutableClass) snapshotOf("FrozenImpl");

    var impl1 = snapshot1.getImplementation();
    var impl2 = snapshot2.getImplementation();
    assertNotNull(impl1);
    assertEquals("FrozenImpl", impl1.getName());
    assertSame("two snapshots taken without intervening schema mutation must back to the "
        + "same live impl instance", impl1, impl2);
  }

  @Test
  public void initIsIdempotent() {
    // SchemaImmutableClass.init is idempotent — calling it a second time after the snapshot is
    // built must not double-wire. A naive impl could append the parent twice; this test guards
    // against that regression.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("InitParent");
    parent.createProperty("p", PropertyType.STRING);
    schema.createClass("InitChild", parent);

    var snapshot = (SchemaImmutableClass) snapshotOf("InitChild");
    int superCountBefore = snapshot.getSuperClasses().size();
    int allPropsBefore = snapshot.getProperties().size();

    snapshot.init(session);
    snapshot.init(session);

    assertEquals(superCountBefore, snapshot.getSuperClasses().size());
    assertEquals(allPropsBefore, snapshot.getProperties().size());
  }

  @Test
  public void builtInTypeFlagsAreCorrectlyComputed() {
    // The snapshot's lazy-resolved flags isVertexType / isEdgeType / isFunction / isUser /
    // isRole / isSequence / isScheduler / isSecurityPolicy come from a static superclass walk
    // performed inside init(...). Pin the V / E roots and a custom subclass.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("MyV", schema.getClass("V"));
    schema.createClass("MyE", schema.getClass("E"));
    schema.createClass("Plain");

    var sV = (SchemaImmutableClass) snapshotOf("V");
    var sE = (SchemaImmutableClass) snapshotOf("E");
    var sMyV = (SchemaImmutableClass) snapshotOf("MyV");
    var sMyE = (SchemaImmutableClass) snapshotOf("MyE");
    var sPlain = (SchemaImmutableClass) snapshotOf("Plain");

    assertTrue(sV.isVertexType());
    assertFalse(sV.isEdgeType());
    assertTrue(sE.isEdgeType());
    assertFalse(sE.isVertexType());

    assertTrue(sMyV.isVertexType());
    assertFalse(sMyV.isEdgeType());
    assertTrue(sMyE.isEdgeType());
    assertFalse(sMyE.isVertexType());

    assertFalse(sPlain.isVertexType());
    assertFalse(sPlain.isEdgeType());

    // Non-special subclasses default to false on every domain flag.
    assertFalse(sPlain.isFunction());
    assertFalse(sPlain.isUser());
    assertFalse(sPlain.isRole());
    assertFalse(sPlain.isSequence());
    assertFalse(sPlain.isScheduler());
    assertFalse(sPlain.isSecurityPolicy());
  }

  @Test
  public void allSuperAndSubclassesWalkTransitively() {
    // The snapshot's getAllSuperClasses / getAllSubclasses walk the full closure.
    Schema schema = session.getMetadata().getSchema();
    var grand = schema.createClass("AllFrozenGrand");
    var parent = schema.createClass("AllFrozenParent", grand);
    schema.createClass("AllFrozenChild", parent);

    var sGrand = snapshotOf("AllFrozenGrand");
    var sChild = snapshotOf("AllFrozenChild");

    var allSupers = sChild.getAllSuperClasses();
    assertEquals(2, allSupers.size());

    var allSubs = sGrand.getAllSubclasses();
    assertEquals(2, allSubs.size());
  }

  @Test
  public void hasSuperClassesAndSuperClassesNamesReflectInheritance() {
    // hasSuperClasses + getSuperClassesNames are independent reads of the captured chain.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("HasSupParent");
    schema.createClass("HasSupChild", parent);

    var sChild = snapshotOf("HasSupChild");
    var sParent = snapshotOf("HasSupParent");

    assertTrue(sChild.hasSuperClasses());
    assertFalse(sParent.hasSuperClasses());
    assertEquals(List.of("HasSupParent"), sChild.getSuperClassesNames());
    assertTrue(sParent.getSuperClassesNames().isEmpty());
  }
}
