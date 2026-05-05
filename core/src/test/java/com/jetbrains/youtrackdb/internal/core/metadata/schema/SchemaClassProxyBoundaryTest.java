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
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import org.junit.Test;

/**
 * Boundary-case pin for {@link SchemaClassProxy}, the session-bound thin wrapper that
 * {@link SchemaShared} returns from {@code getMetadata().getSchema().getClass(...)} on the live
 * (non-snapshot) read path. The proxy delegates almost every call to the underlying
 * {@link SchemaClassImpl} after asserting the session is active — but the proxy *itself* defines
 * its own {@code equals} / {@code hashCode} / {@code toString} contract, and child results that
 * return new {@code SchemaClass} / {@code SchemaProperty} objects must always be re-wrapped in a
 * proxy bound to the same session.
 *
 * <p>This test pins the proxy-level invariants that the dispatch through
 * {@code session.getMetadata().getSchema()} cannot be lifted into the impl class:
 * <ul>
 *   <li>{@code getDeclaredProperties} / {@code getProperties} / {@code getPropertiesMap} /
 *       {@code getProperty} return {@link SchemaPropertyProxy} instances, NOT the raw
 *       {@code SchemaPropertyImpl}.</li>
 *   <li>{@code getSubclasses} / {@code getAllSubclasses} / {@code getAllSuperClasses} /
 *       {@code getSuperClasses} return fresh {@code SchemaClassProxy} instances, NOT the raw
 *       {@code SchemaClassImpl}, and each proxy is bound to the same session.</li>
 *   <li>{@code createProperty} returns a proxy that re-wraps a fresh {@code SchemaPropertyImpl}.
 *   </li>
 *   <li>{@code equals} returns true only for {@code SchemaClassInternal} peers whose
 *       {@code getBoundToSession()} matches and whose name matches; the snapshot's
 *       {@code SchemaImmutableClass} (with {@code BoundToSession == null}) is NOT equal to the
 *       proxy.</li>
 *   <li>{@code hashCode} caches its computed value (both before and after a call to
 *       {@code setName} that resets the cache).</li>
 *   <li>{@code toString} returns the delegate's name when the session is active on the current
 *       thread, falling back to the default {@code Object.toString} otherwise.</li>
 *   <li>{@code getImplementation} exposes the underlying {@code SchemaClassImpl} directly (the
 *       single proxy method that does NOT route through {@code session.assertIfNotActive}).</li>
 *   <li>The {@code SchemaPropertyProxy.equals} / {@code hashCode} / {@code getOwnerClass}
 *       contract is symmetrical: equals requires both the session match and the owner-class
 *       name to match, hashCode is the {@code (propName, ownerName)} tuple's combined hash.</li>
 * </ul>
 */
public class SchemaClassProxyBoundaryTest extends DbTestBase {

  @Test
  public void getPropertyReturnsProxyNotImpl() {
    // SchemaClassProxy.getProperty wraps the result in a SchemaPropertyProxy. The returned object
    // must be a proxy bound to the same session — naive impl could return the raw impl.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PropProxy");
    cls.createProperty("p", PropertyType.STRING);

    var prop = cls.getProperty("p");
    assertTrue("getProperty must return a SchemaPropertyProxy",
        prop instanceof SchemaPropertyProxy);
    assertEquals("p", prop.getName());

    // Property is bound to the same session as the schema lookup.
    assertSame(session, ((SchemaPropertyProxy) prop).getBoundToSession());
  }

  @Test
  public void getPropertyMissingReturnsNull() {
    // The "result != null ? new proxy : null" arm of SchemaClassProxy.getProperty.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PropMissing");
    assertNull(cls.getProperty("doesNotExist"));
  }

  @Test
  public void declaredAndPolymorphicPropertyCollectionsContainOnlyProxies() {
    // Pin the wrapping in getDeclaredProperties / getProperties / getPropertiesMap. Every entry
    // must be a SchemaPropertyProxy (never the raw impl).
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("PropCollParent");
    parent.createProperty("p1", PropertyType.STRING);
    var child = schema.createClass("PropCollChild", parent);
    child.createProperty("p2", PropertyType.INTEGER);

    var declared = child.getDeclaredProperties();
    assertEquals(1, declared.size());
    assertTrue(declared.iterator().next() instanceof SchemaPropertyProxy);

    var allProps = child.getProperties();
    assertEquals(2, allProps.size());
    for (var p : allProps) {
      assertTrue("getProperties entries must be SchemaPropertyProxy: " + p.getClass(),
          p instanceof SchemaPropertyProxy);
    }

    var pMap = child.getPropertiesMap();
    assertEquals(2, pMap.size());
    for (var entry : pMap.entrySet()) {
      assertTrue("getPropertiesMap entries must be SchemaPropertyProxy: "
          + entry.getValue().getClass(),
          entry.getValue() instanceof SchemaPropertyProxy);
    }
  }

  @Test
  public void subclassesAndSuperclassesAreProxiesNotImpls() {
    // Pin SchemaClassProxy.getSubclasses / getAllSubclasses / getAllSuperClasses /
    // getSuperClasses — each result must be a SchemaClassProxy bound to the same session.
    Schema schema = session.getMetadata().getSchema();
    var grand = schema.createClass("GrandProxy");
    var parent = schema.createClass("ParentProxy", grand);
    schema.createClass("ChildProxy", parent);

    var subs = grand.getSubclasses();
    assertFalse(subs.isEmpty());
    for (var sc : subs) {
      assertTrue("getSubclasses entries must be SchemaClassProxy: " + sc.getClass(),
          sc instanceof SchemaClassProxy);
      assertSame(session, ((SchemaClassProxy) sc).getBoundToSession());
    }

    var allSubs = grand.getAllSubclasses();
    assertEquals(2, allSubs.size());
    for (var sc : allSubs) {
      assertTrue(sc instanceof SchemaClassProxy);
    }

    var refetchedChild = schema.getClass("ChildProxy");
    var allSupers = refetchedChild.getAllSuperClasses();
    assertEquals(2, allSupers.size());
    for (var sc : allSupers) {
      assertTrue(sc instanceof SchemaClassProxy);
    }

    var supers = refetchedChild.getSuperClasses();
    assertEquals(1, supers.size());
    assertTrue(supers.getFirst() instanceof SchemaClassProxy);
  }

  @Test
  public void createPropertyOverloadsAllReturnProxies() {
    // SchemaClassProxy.createProperty(...) — three overloads on the public interface, each must
    // wrap the resulting impl in a SchemaPropertyProxy. The setLinkedClass and setLinkedType
    // overloads also exercise the linked-class extraction (delegate's getImplementation()).
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("CreatePropProxy");
    var linked = schema.createClass("CreatePropLinked");

    var p1 = cls.createProperty("a", PropertyType.STRING);
    var p2 = cls.createProperty("b", PropertyType.LINK, linked);
    var p3 = cls.createProperty("c", PropertyType.EMBEDDEDLIST, PropertyType.STRING);

    assertTrue(p1 instanceof SchemaPropertyProxy);
    assertTrue(p2 instanceof SchemaPropertyProxy);
    assertTrue(p3 instanceof SchemaPropertyProxy);

    assertEquals(linked.getName(), p2.getLinkedClass().getName());
    assertEquals(PropertyType.STRING, p3.getLinkedType());
    // Linked class is also a proxy bound to the same session — re-walking the chain.
    assertTrue(p2.getLinkedClass() instanceof SchemaClassProxy);
  }

  @Test
  public void equalsRequiresSessionAndNameMatch() {
    // Pin SchemaClassProxy.equals(...) — accepts SchemaClassInternal peers whose
    // getBoundToSession matches the proxy's session AND whose name matches. The snapshot's
    // SchemaImmutableClass (BoundToSession == null) is NOT equal to a session-bound proxy.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("EqProxy");

    var proxy1 = (SchemaClassProxy) session.getMetadata().getSchema().getClass("EqProxy");
    var proxy2 = (SchemaClassProxy) session.getMetadata().getSchema().getClass("EqProxy");

    // Two proxies fetched from the same session for the same class — equal.
    assertEquals(proxy1, proxy2);
    assertEquals(proxy1.hashCode(), proxy2.hashCode());

    // Different-name proxy — not equal.
    schema.createClass("EqProxyOther");
    var otherProxy = session.getMetadata().getSchema().getClass("EqProxyOther");
    assertNotEquals(proxy1, otherProxy);

    // Snapshot-of-same-class — NOT equal because BoundToSession differs (snapshot returns null).
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot().getClass("EqProxy");
    assertFalse("proxy.equals(snapshot) must be false (different BoundToSession)",
        proxy1.equals(snapshot));

    // Reflexive identity short-circuit.
    assertEquals(proxy1, proxy1);

    // Non-SchemaClassInternal peer — false.
    assertNotEquals(proxy1, "EqProxy");
    assertNotEquals(proxy1, null);
  }

  @Test
  public void hashCodeIsCachedUntilSetNameInvalidatesIt() {
    // SchemaClassProxy.hashCode lazily computes from the name and caches in a private int field;
    // setName(...) resets that cache. Pin both branches.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("HashProxy");
    var proxy = (SchemaClassProxy) session.getMetadata().getSchema().getClass("HashProxy");

    int firstHash = proxy.hashCode();
    int secondHash = proxy.hashCode();
    assertEquals("hashCode must be stable on the same proxy across calls", firstHash, secondHash);

    // Rename — hashCode should be reset and recomputed; new hash equals the new name's hash.
    proxy.setName("HashProxyRenamed");
    int afterRename = proxy.hashCode();
    assertEquals("hashCode must reflect the new name after setName",
        "HashProxyRenamed".hashCode(), afterRename);
    assertNotEquals("hashCode must change after rename", firstHash, afterRename);
  }

  @Test
  public void toStringReturnsNameWhenSessionActive() {
    // SchemaClassProxy.toString returns delegate.getName() when session.isActiveOnCurrentThread();
    // otherwise it falls through to Object.toString. Pin the active-session arm.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("StrProxy");
    var proxy = session.getMetadata().getSchema().getClass("StrProxy");
    assertEquals("StrProxy", proxy.toString());
  }

  @Test
  public void getImplementationExposesUnderlyingImpl() {
    // SchemaClassProxy.getImplementation returns the delegate without an active-session assertion
    // (it's the only method that does NOT call session.assertIfNotActive — used by callers like
    // setSuperClasses to extract the underlying impl).
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("ImplExpose");
    var proxy = (SchemaClassProxy) session.getMetadata().getSchema().getClass("ImplExpose");
    var impl = proxy.getImplementation();
    assertNotNull(impl);
    assertEquals("ImplExpose", impl.getName());
    // The runtime class is one of the SchemaClassImpl concrete subclasses (e.g.,
    // SchemaClassEmbedded), never a proxy or snapshot — getImplementation strips the wrapper.
    assertTrue("getImplementation must return a SchemaClassImpl: " + impl.getClass(),
        impl instanceof SchemaClassImpl);
  }

  @Test
  public void getBoundToSessionMatchesTheProxyOwner() {
    // Each proxy reports its bound session; this is what the SchemaImmutableClass equals contract
    // hinges on (snapshot returns null, proxy returns the session reference).
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("BoundProxy");
    var proxy = (SchemaClassProxy) session.getMetadata().getSchema().getClass("BoundProxy");
    assertSame(session, proxy.getBoundToSession());
  }

  @Test
  public void renameThroughProxySurfacesViaSchemaLookup() {
    // SchemaClassProxy.setName routes through the impl's setName which goes through the
    // SchemaShared rename path. After the rename, the new name is reachable via the schema and
    // the old name is gone. This exercises the proxy's rename-then-cache-invalidation path.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("RenameProxy");
    var proxy = session.getMetadata().getSchema().getClass("RenameProxy");
    assertEquals("RenameProxy", proxy.getName());

    proxy.setName("RenameProxyNew");
    assertEquals("RenameProxyNew", proxy.getName());
    assertNotNull(session.getMetadata().getSchema().getClass("RenameProxyNew"));
    assertNull(session.getMetadata().getSchema().getClass("RenameProxy"));
  }

  @Test
  public void schemaPropertyProxyEqualsRequiresOwnerAndSessionMatch() {
    // SchemaPropertyProxy.equals is true only for SchemaPropertyInternal peers whose
    // getBoundToSession matches AND whose name + ownerClass.getName both match. Pin:
    //   - same prop / same owner / same session → equal
    //   - same prop name on a different class → not equal (different owner)
    //   - immutable snapshot of same prop → not equal (different BoundToSession)
    Schema schema = session.getMetadata().getSchema();
    var clsA = schema.createClass("OwnerEqA");
    clsA.createProperty("p", PropertyType.STRING);
    var clsB = schema.createClass("OwnerEqB");
    clsB.createProperty("p", PropertyType.STRING);

    var pA1 = clsA.getProperty("p");
    var pA2 = session.getMetadata().getSchema().getClass("OwnerEqA").getProperty("p");
    var pB = clsB.getProperty("p");

    // Same name, same owner, same session — equal.
    assertEquals(pA1, pA2);
    assertEquals(pA1.hashCode(), pA2.hashCode());

    // Same prop name, different owner class — not equal.
    assertNotEquals("same property name on a different class must NOT be equal",
        pA1, pB);

    // Snapshot equivalent — different BoundToSession.
    var snapPropA = session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("OwnerEqA").getProperty("p");
    assertFalse("proxy-prop must NOT equal snapshot-prop (different BoundToSession)",
        pA1.equals(snapPropA));

    // Reflexive.
    assertEquals(pA1, pA1);
    assertNotEquals(pA1, null);
    assertNotEquals(pA1, "p");
  }

  @Test
  public void schemaPropertyProxyToStringIncludesType() {
    // SchemaPropertyProxy.toString — the active-session arm format is "name (type=TYPE)".
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PropToString");
    cls.createProperty("p", PropertyType.STRING);

    var prop = cls.getProperty("p");
    assertEquals("p (type=STRING)", prop.toString());
  }

  @Test
  public void schemaPropertyProxyOwnerClassIsAlsoAProxy() {
    // SchemaPropertyProxy.getOwnerClass returns a SchemaClassProxy (re-wrapping the underlying
    // impl). This is symmetrical to SchemaClassProxy.getProperty wrapping in SchemaPropertyProxy.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("OwnerProxy");
    cls.createProperty("p", PropertyType.STRING);

    var prop = cls.getProperty("p");
    var owner = prop.getOwnerClass();
    assertNotNull(owner);
    assertTrue("getOwnerClass must return a SchemaClassProxy: " + owner.getClass(),
        owner instanceof SchemaClassProxy);
    assertEquals("OwnerProxy", owner.getName());
  }

  @Test
  public void hasCollectionIdAndIsAbstractRouteThroughProxy() {
    // Pin the proxy's straight-through delegations for the simple read predicates: hasCollectionId,
    // hasPolymorphicCollectionId, isAbstract, isStrictMode, hasSuperClasses, isVertexType,
    // isEdgeType. Each reads through to the underlying impl after asserting the session is active.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ProxyReads");
    cls.setStrictMode(true);

    int collId = cls.getCollectionIds()[0];
    assertTrue(cls.hasCollectionId(collId));
    assertFalse(cls.hasCollectionId(-99));
    assertTrue(cls.hasPolymorphicCollectionId(collId));
    assertFalse(cls.isAbstract());
    assertTrue(cls.isStrictMode());
    assertFalse(cls.hasSuperClasses());
    assertFalse(cls.isVertexType());
    assertFalse(cls.isEdgeType());
  }

  @Test
  public void countAndApproximateCountRouteThroughProxy() {
    // SchemaClassProxy.count / approximateCount delegate to impl.count(session) / impl
    // .approximateCount(session). Drive both polymorphic-default and explicit branches.
    // Both methods require an active transaction.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("CountProxy");
    session.executeInTx(tx -> {
      session.newEntity("CountProxy");
      session.newEntity("CountProxy");
    });

    var clsInternal = (SchemaClassInternal) cls;
    session.executeInTx(tx -> {
      assertEquals(2L, clsInternal.count(session));
      assertEquals(2L, clsInternal.count(session, true));
      long approx = clsInternal.approximateCount(session);
      assertTrue(approx >= 0);
      long approxNonPoly = clsInternal.approximateCount(session, false);
      assertTrue(approxNonPoly >= 0);
    });
  }

  @Test
  public void schemaPropertyProxyHashCodeMatchesNameAndOwnerCombination() {
    // SchemaPropertyProxy.hashCode is computed once and cached; the hash combines the property
    // name's hash with 31 * ownerClass.name.hash. Pin the value structure.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("HashProp");
    cls.createProperty("p", PropertyType.STRING);

    var p = cls.getProperty("p");
    int expected = "p".hashCode() + 31 * "HashProp".hashCode();
    assertEquals(expected, p.hashCode());
    // Repeated call returns the cached value.
    assertEquals(expected, p.hashCode());
  }

  @Test
  public void getCustomKeysAndPolymorphicCollectionIdsRouteAsExpected() {
    // Two more proxy methods that have not been pinned elsewhere: getCustomKeys (returns the
    // delegate's underlying set) and getPolymorphicCollectionIds (defensive copy through the impl).
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ProxyCustom");
    cls.setCustom("a", "1");
    cls.setCustom("b", "2");
    var keys = cls.getCustomKeys();
    assertEquals(2, keys.size());
    assertTrue(keys.contains("a"));
    assertTrue(keys.contains("b"));

    int[] poly = cls.getPolymorphicCollectionIds();
    assertNotNull(poly);
    assertTrue(poly.length >= 1);
  }

  @Test
  public void setSuperClassesAndAddRemoveRouteThroughProxy() {
    // Pin SchemaClassProxy.setSuperClasses / addSuperClass / removeSuperClass — each unwraps the
    // SchemaClass argument(s) via getImplementation() before calling the delegate.
    Schema schema = session.getMetadata().getSchema();
    var p1 = schema.createClass("ProxySupP1");
    var p2 = schema.createClass("ProxySupP2");
    var child = schema.createClass("ProxySupChild");

    child.addSuperClass(p1);
    assertTrue(child.isSubClassOf(p1));

    child.removeSuperClass(p1);
    assertFalse(child.isSubClassOf(p1));

    // setSuperClasses replaces the entire set in one call.
    child.setSuperClasses(java.util.List.of(p1, p2));
    var refetched = session.getMetadata().getSchema().getClass("ProxySupChild");
    assertEquals(2, refetched.getSuperClasses().size());
    assertTrue(refetched.isSubClassOf(p1));
    assertTrue(refetched.isSubClassOf(p2));
  }

  @Test
  public void truncateThroughProxyWipesData() {
    // SchemaClassProxy.truncate() routes to delegate.truncate(session). Compose with
    // insert+verify; countClass requires an active transaction.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ProxyTrunc");
    session.executeInTx(tx -> session.newEntity("ProxyTrunc"));
    session.executeInTx(tx -> assertEquals(1L, session.countClass("ProxyTrunc")));

    var clsInternal = (SchemaClassInternal) cls;
    clsInternal.truncate();
    session.executeInTx(tx -> assertEquals(0L, session.countClass("ProxyTrunc")));
  }

  @Test
  public void getImplementationFromSnapshotAlsoExposesLiveImpl() {
    // SchemaImmutableClass.getImplementation surfaces the live SchemaClassImpl that backed the
    // snapshot at capture time. Together with SchemaClassProxy.getImplementation, both the proxy
    // and the snapshot expose the SAME impl instance for the same class — confirming there is one
    // canonical impl per live class.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("ImplShared");

    var proxy = (SchemaClassProxy) session.getMetadata().getSchema().getClass("ImplShared");
    var snapshot = (SchemaImmutableClass) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("ImplShared");

    assertSame("proxy and snapshot must back to the same SchemaClassImpl instance",
        proxy.getImplementation(), snapshot.getImplementation());
  }

  @Test
  public void existsPropertyRoutesPolymorphicAcrossSuperclassChain() {
    // SchemaClassProxy.existsProperty delegates to impl.existsProperty which walks the superclass
    // chain. Pin via a parent → child relationship where the inherited property must be visible
    // through the child proxy.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("ExistsParent");
    parent.createProperty("p1", PropertyType.STRING);
    var child = schema.createClass("ExistsChild", parent);

    SchemaClass refetchedChild = session.getMetadata().getSchema().getClass("ExistsChild");
    assertTrue("child proxy must report inherited property as exists()",
        refetchedChild.existsProperty("p1"));
    assertFalse(refetchedChild.existsProperty("missing"));

    // dropProperty on the child must NOT touch the parent's property.
    refetchedChild.createProperty("p2", PropertyType.STRING);
    assertTrue(refetchedChild.existsProperty("p2"));
    refetchedChild.dropProperty("p2");
    assertFalse(refetchedChild.existsProperty("p2"));
    assertTrue("parent's property must remain after child dropProperty",
        refetchedChild.existsProperty("p1"));
  }

  @Test
  public void getCollectionForNewInstanceHonoursSelectionStrategy() {
    // SchemaClassProxy.getCollectionForNewInstance(entity) delegates to
    // delegate.getCollectionSelection().getCollection(this.session, this, entity). The selector
    // returns one of the polymorphic collection ids; for a class with no subclasses, that's
    // exactly the class's own collection id (or one of them — the round-robin strategy cycles
    // through the array). Pin "the result must be a member of the polymorphic id set".
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ProxyCollSel");
    int[] polymorphic = cls.getPolymorphicCollectionIds();
    assertTrue("class must have at least one polymorphic collection id",
        polymorphic.length >= 1);

    var clsInternal = (SchemaClassInternal) cls;
    // Build an entity so the round-robin selector can pick a collection.
    int[] picked = new int[] {-1};
    session.executeInTx(tx -> {
      var e = session.newInstance("ProxyCollSel");
      picked[0] = clsInternal.getCollectionForNewInstance(
          (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) e);
    });
    boolean validPick = false;
    for (int id : polymorphic) {
      if (id == picked[0]) {
        validPick = true;
        break;
      }
    }
    assertTrue("selector must return one of the polymorphic collection ids; got "
        + picked[0] + ", available=" + java.util.Arrays.toString(polymorphic),
        validPick);
  }

  @Test
  public void getCollectionSelectionExposesRoundRobinStrategy() {
    // SchemaClassProxy.getCollectionSelection delegates to delegate.getCollectionSelection() —
    // the live SchemaClassImpl hard-codes a RoundRobin strategy in its field initializer
    // (the Balanced / Default strategies are dead-code-pinned). Pin the strategy class.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ProxyStrategy");
    var strategy = ((SchemaClassInternal) cls).getCollectionSelection();
    assertNotNull(strategy);
    assertEquals("only the round-robin strategy is wired in production",
        "round-robin", strategy.getName());
  }

  @Test
  public void getInvolvedAndClassIndexesReturnEmptyOnFreshClass() {
    // The per-method index lookups on the proxy delegate cleanly to the impl. With no indexes
    // defined, every query returns an empty set / no error. Pin the smoke for getClassIndexes,
    // getInvolvedIndexes, getClassInvolvedIndexes, and the boolean areIndexed.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ProxyIdx");
    cls.createProperty("f", PropertyType.STRING);

    var clsInternal = (SchemaClassInternal) cls;
    assertTrue(clsInternal.getClassIndexes().isEmpty());
    assertTrue(clsInternal.getIndexes().isEmpty());
    assertTrue(clsInternal.getInvolvedIndexes(session, "f").isEmpty());
    assertTrue(clsInternal.getClassInvolvedIndexes(session, "f").isEmpty());
    assertFalse(clsInternal.areIndexed(session, "f"));

    // Add an index — assert visibility through the immutable schema snapshot's indexExists path
    // (the portable check across the disk / memory storage modes per the R8 working note).
    cls.createIndex("ProxyIdx.fIdx",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "f");
    var snapshot = session.getMetadata().getImmutableSchemaSnapshot();
    assertTrue("created index must be visible in the immutable snapshot",
        snapshot.indexExists("ProxyIdx.fIdx"));

    // SchemaProperty's setName interacts with the proxy's setName cache — re-fetch and confirm
    // the renamed property is still findable in a fresh snapshot.
    var refetched =
        (SchemaClassInternal) session.getMetadata().getSchema().getClass("ProxyIdx");
    var prop = refetched.getProperty("f");
    prop.setName("renamed_f");
    var freshSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
    SchemaProperty renamed = freshSnapshot.getClass("ProxyIdx").getProperty("renamed_f");
    assertNotNull("renamed property must be visible in a fresh snapshot", renamed);
  }
}
