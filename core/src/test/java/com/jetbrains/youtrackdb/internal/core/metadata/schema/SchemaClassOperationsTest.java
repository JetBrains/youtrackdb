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
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.ATTRIBUTES;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * End-to-end behavioural coverage for the live class-mutation surface implemented by
 * {@link SchemaClassImpl} (read-side accessors, lock framing, the bulk {@code get/set(ATTRIBUTES)}
 * switches, polymorphic queries) and {@link SchemaClassEmbedded} (mutators routed through
 * {@code session.checkSecurity} → write-lock → {@code …Internal} → release pattern). All assertions
 * drive the public {@link SchemaClass} interface obtained from
 * {@code session.getMetadata().getSchema()} so dispatch traverses the {@code SchemaClassProxy} →
 * impl chain — the proxy super-method-dispatch pattern: PSI find-usages on individual proxy
 * methods returns 0 production callers because every call routes through the public
 * {@link SchemaClass} interface.
 *
 * <p>This class deliberately complements the existing {@link SchemaClassImplTest},
 * {@link AlterClassTest}, {@link AlterSuperclassTest}, {@link TestMultiSuperClasses} and
 * {@link CaseSensitiveClassNameTest} by covering the mutation paths those classes leave uncovered
 * in {@code SchemaClassImpl}, {@code SchemaClassEmbedded}, and the bulk attribute switch:
 * <ul>
 *   <li>The strict-mode + description + abstract setter / clearer paths and their re-fetch
 *       semantics across an {@code executeInTx} boundary.</li>
 *   <li>The custom-attribute lifecycle on {@code SchemaClassEmbedded} including the
 *       {@code setCustom("k", "null")} string-literal-removal arm, {@code removeCustom},
 *       {@code clearCustom}, and {@code getCustomKeys}.</li>
 *   <li>The 6-arm {@code get(ATTRIBUTES)} bulk-get and the 6-arm {@code set(ATTRIBUTES, Object)}
 *       bulk-set switch including the {@code CUSTOM "name=value"} / quoted / empty-value-removes /
 *       clear / bad-syntax sub-cases.</li>
 *   <li>The {@code count}, {@code approximateCount}, {@code truncate} polymorphic execution paths
 *       across a parent / child class.</li>
 *   <li>{@code addSuperClass}-then-{@code removeSuperClass} round-trip including the
 *       "already has superclass" rejection arm.</li>
 *   <li>{@code hasCollectionId} / {@code hasPolymorphicCollectionId} predicates and
 *       {@code getCollectionIds} / {@code getPolymorphicCollectionIds} read-side defensive copies
 *       on the polymorphic side.</li>
 *   <li>{@code declaredProperties} / {@code properties} / {@code propertiesMap}
 *       polymorphic-vs-declared distinction across a parent / child class.</li>
 *   <li>{@code isSubClassOf} / {@code isSuperClassOf} positive and negative arms by name and by
 *       reference, including the {@code null} early-return.</li>
 *   <li>The {@code equals} / {@code hashCode} / {@code toString} contract on the live impl class.
 *   </li>
 * </ul>
 *
 * <p>Per the R7 working note the schema reference is re-fetched via
 * {@code session.getMetadata().getSchema()} after each mutation rather than cached across a
 * transaction boundary; per the R8 working note any test that asserts on a fresh index reads
 * back through the immutable schema snapshot's {@code indexExists} predicate, which is portable
 * across the disk / memory storage modes.
 */
public class SchemaClassOperationsTest extends DbTestBase {

  @Test
  public void strictModeRoundTripsAcrossSchemaRefetch() {
    // Pin SchemaClassEmbedded.setStrictMode → setStrictModeInternal → checkEmbedded path; the
    // setting must survive a re-fetch of the SchemaClass via getMetadata().getSchema() (not just
    // be visible on the original reference, which could be a stale proxy).
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("StrictModeRT");
    assertFalse("default strictMode must be false", cls.isStrictMode());

    cls.setStrictMode(true);

    var refetched = session.getMetadata().getSchema().getClass("StrictModeRT");
    assertTrue(refetched.isStrictMode());

    refetched.setStrictMode(false);
    assertFalse(session.getMetadata().getSchema().getClass("StrictModeRT").isStrictMode());
  }

  @Test
  public void descriptionRoundTripIncludingNullAndWhitespaceTrim() {
    // Pin SchemaClassEmbedded.setDescription: trims whitespace, treats whitespace-only as null,
    // and survives a re-fetch.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DescRT");
    assertNull("default description must be null", cls.getDescription());

    cls.setDescription("hello");
    assertEquals("hello",
        session.getMetadata().getSchema().getClass("DescRT").getDescription());

    cls.setDescription("   ");
    assertNull("whitespace-only description must coerce to null",
        session.getMetadata().getSchema().getClass("DescRT").getDescription());

    cls.setDescription("  trimmed  ");
    assertEquals("trimmed",
        session.getMetadata().getSchema().getClass("DescRT").getDescription());

    cls.setDescription(null);
    assertNull(session.getMetadata().getSchema().getClass("DescRT").getDescription());
  }

  @Test
  public void abstractToggleRetainsCollectionIdInvariants() {
    // Pin SchemaClassEmbedded.setAbstract round-trip — toggling abstract to false on an originally-
    // abstract class allocates a fresh non-default collection id; toggling back to true removes
    // the collection ids and sets defaultCollectionId to NOT_EXISTENT_COLLECTION_ID (-1).
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createAbstractClass("AbsRT");
    assertEquals(-1, cls.getCollectionIds()[0]);
    assertTrue(cls.isAbstract());

    cls.setAbstract(false);
    assertFalse(cls.isAbstract());
    assertNotEquals(-1, cls.getCollectionIds()[0]);

    var concreteId = cls.getCollectionIds()[0];
    assertTrue("abstract→concrete should expose the id via hasCollectionId",
        cls.hasCollectionId(concreteId));

    // Re-toggle to abstract — the class is still empty, so the abstract-switch transition succeeds.
    cls.setAbstract(true);
    assertTrue(cls.isAbstract());
    assertEquals(-1, cls.getCollectionIds()[0]);
  }

  @Test
  public void abstractTrueOnNonEmptyClassThrowsIllegalState() {
    // The setAbstract(true) path enforces that the class is empty before dropping the collection.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("AbsNotEmpty");

    session.executeInTx(tx -> session.newEntity("AbsNotEmpty"));

    var thrown = assertThrows(IllegalStateException.class, () -> cls.setAbstract(true));
    assertTrue("error message must mention 'abstract'",
        thrown.getMessage().toLowerCase().contains("abstract"));
    assertFalse("class must remain concrete after the rejected toggle", cls.isAbstract());
  }

  @Test
  public void abstractToggleNoOpWhenAlreadyConcrete() {
    // Calling setAbstract(false) on an already-concrete class is a no-op — the early-return in
    // setAbstractInternal's else branch ("if (!abstractClass) { return; }") is the path under
    // test. The collection id must not change.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("AbsNoop");
    int before = cls.getCollectionIds()[0];

    cls.setAbstract(false);
    assertEquals("collection id must be preserved on a no-op toggle",
        before, cls.getCollectionIds()[0]);
    assertFalse(cls.isAbstract());
  }

  @Test
  public void customAttributeLifecycleAcrossSetRemoveClear() {
    // Drive the SchemaClassEmbedded.setCustom / removeCustom / clearCustom path including the
    // string-literal "null" arm (which removes the entry) and getCustomKeys() / clearCustom().
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("CustomLifecycle");

    assertNull("missing custom must return null", cls.getCustom("missing"));
    assertTrue("getCustomKeys must be empty initially", cls.getCustomKeys().isEmpty());

    cls.setCustom("k1", "v1");
    cls.setCustom("k2", "v2");
    assertEquals("v1", cls.getCustom("k1"));
    assertEquals("v2", cls.getCustom("k2"));
    assertEquals(2, cls.getCustomKeys().size());

    // Setting the literal string "null" must remove the entry — distinct from setting actual null
    // (which is the same path) and from setting an empty string (which is preserved).
    cls.setCustom("k1", "null");
    assertNull("literal 'null' value must remove the entry", cls.getCustom("k1"));
    assertEquals(1, cls.getCustomKeys().size());

    cls.removeCustom("k2");
    assertNull("removeCustom must drop the entry", cls.getCustom("k2"));

    // Re-populate then clearCustom — after clear, all custom entries are gone.
    cls.setCustom("a", "1");
    cls.setCustom("b", "2");
    assertEquals(2, cls.getCustomKeys().size());

    cls.clearCustom();
    assertTrue("clearCustom must empty the keyset", cls.getCustomKeys().isEmpty());
    assertNull(cls.getCustom("a"));
    assertNull(cls.getCustom("b"));
  }

  @Test
  public void getAttributesBulkSwitchCoversEveryArm() {
    // Pin SchemaClassImpl.get(ATTRIBUTES) — six arms: NAME, SUPERCLASSES, STRICT_MODE, ABSTRACT,
    // CUSTOM, DESCRIPTION. The CUSTOM arm returns the underlying customFields map (or null).
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("AttrParent");
    var cls = schema.createClass("AttrCls", parent);
    cls.setStrictMode(true);
    cls.setDescription("desc");
    cls.setCustom("k", "v");

    var clsImpl = (SchemaClassImpl) ((SchemaClassInternal) cls).getImplementation();
    var dbSession = session;

    assertEquals("AttrCls", clsImpl.get(dbSession, ATTRIBUTES.NAME));
    @SuppressWarnings("unchecked")
    List<SchemaClassImpl> superclasses =
        (List<SchemaClassImpl>) clsImpl.get(dbSession, ATTRIBUTES.SUPERCLASSES);
    assertEquals("SUPERCLASSES arm must return the live superclass list",
        1, superclasses.size());
    assertEquals("AttrParent", superclasses.getFirst().getName());
    assertEquals(Boolean.TRUE, clsImpl.get(dbSession, ATTRIBUTES.STRICT_MODE));
    assertEquals(Boolean.FALSE, clsImpl.get(dbSession, ATTRIBUTES.ABSTRACT));
    @SuppressWarnings("unchecked")
    Map<String, String> customMap =
        (Map<String, String>) clsImpl.get(dbSession, ATTRIBUTES.CUSTOM);
    assertNotNull("CUSTOM arm must return a non-null map after setCustom", customMap);
    assertEquals("v", customMap.get("k"));
    assertEquals("desc", clsImpl.get(dbSession, ATTRIBUTES.DESCRIPTION));
  }

  @Test
  public void getAttributesNullArgumentThrows() {
    // The null-argument guard at the top of get(ATTRIBUTES) is its own arm.
    Schema schema = session.getMetadata().getSchema();
    var clsImpl = (SchemaClassImpl) ((SchemaClassInternal) schema.createClass("AttrNull"))
        .getImplementation();
    assertThrows(IllegalArgumentException.class, () -> clsImpl.get(session, null));
  }

  @Test
  public void setAttributesBulkSwitchRoundTripsEveryArm() {
    // Pin SchemaClassImpl.set(ATTRIBUTES, Object): NAME, SUPERCLASSES (comma-separated string),
    // STRICT_MODE, ABSTRACT, DESCRIPTION arms drive the corresponding setter via the public
    // interface; the CUSTOM arm has its own sub-case test below.
    Schema schema = session.getMetadata().getSchema();
    var parent1 = schema.createClass("BulkP1");
    var parent2 = schema.createClass("BulkP2");
    var cls = schema.createClass("BulkSet");

    var clsImpl = (SchemaClassImpl) ((SchemaClassInternal) cls).getImplementation();
    var dbSession = session;

    clsImpl.set(dbSession, ATTRIBUTES.STRICT_MODE, "true");
    clsImpl.set(dbSession, ATTRIBUTES.DESCRIPTION, "via-bulk");
    clsImpl.set(dbSession, ATTRIBUTES.SUPERCLASSES, "BulkP1, BulkP2");

    // Re-fetch the class via the public Schema interface to confirm the impl-level mutation is
    // visible through the proxy chain (T3 trap rule).
    var refetched = session.getMetadata().getSchema().getClass("BulkSet");
    assertTrue(refetched.isStrictMode());
    assertEquals("via-bulk", refetched.getDescription());
    assertEquals(2, refetched.getSuperClassesNames().size());
    assertTrue(refetched.isSubClassOf(parent1));
    assertTrue(refetched.isSubClassOf(parent2));

    clsImpl.set(dbSession, ATTRIBUTES.NAME, "BulkRenamed");
    assertNotNull(session.getMetadata().getSchema().getClass("BulkRenamed"));
    assertNull(session.getMetadata().getSchema().getClass("BulkSet"));

    // ABSTRACT toggle requires a class with no records — the rename above did not insert any.
    var renamed = session.getMetadata().getSchema().getClass("BulkRenamed");
    var renamedImpl = (SchemaClassImpl) ((SchemaClassInternal) renamed).getImplementation();
    renamedImpl.set(dbSession, ATTRIBUTES.ABSTRACT, "true");
    assertTrue(session.getMetadata().getSchema().getClass("BulkRenamed").isAbstract());
  }

  @Test
  public void setAttributesNullArgumentThrows() {
    Schema schema = session.getMetadata().getSchema();
    var clsImpl = (SchemaClassImpl) ((SchemaClassInternal) schema.createClass("BulkNull"))
        .getImplementation();
    assertThrows(IllegalArgumentException.class, () -> clsImpl.set(session, null, "x"));
  }

  @Test
  public void setAttributesCustomKeyValueAndClearAndQuoted() {
    // The CUSTOM arm of set(ATTRIBUTES, Object) parses "k=v" — sub-cases include quoted values
    // (single, double, backtick), empty value (which removes the entry), the literal "clear"
    // string (which clears all customs), and the bad-syntax rejection (no '=' sign).
    Schema schema = session.getMetadata().getSchema();
    var clsImpl = (SchemaClassImpl) ((SchemaClassInternal) schema.createClass("BulkCustom"))
        .getImplementation();
    var dbSession = session;

    clsImpl.set(dbSession, ATTRIBUTES.CUSTOM, "k1=v1");
    clsImpl.set(dbSession, ATTRIBUTES.CUSTOM, "k2=\"v2\"");
    clsImpl.set(dbSession, ATTRIBUTES.CUSTOM, "k3='v3'");
    clsImpl.set(dbSession, ATTRIBUTES.CUSTOM, "k4=`v4`");

    assertEquals("v1", clsImpl.getCustom("k1"));
    assertEquals("v2", clsImpl.getCustom("k2"));
    assertEquals("v3", clsImpl.getCustom("k3"));
    assertEquals("v4", clsImpl.getCustom("k4"));

    // Empty value after '=' triggers removeCustom.
    clsImpl.set(dbSession, ATTRIBUTES.CUSTOM, "k1=");
    assertNull(clsImpl.getCustom("k1"));

    // The literal "clear" string clears the entire custom map (the early-return path).
    clsImpl.set(dbSession, ATTRIBUTES.CUSTOM, "clear");
    assertTrue(clsImpl.getCustomKeys().isEmpty());

    // Re-populate and assert that null also clears.
    clsImpl.set(dbSession, ATTRIBUTES.CUSTOM, "kx=vx");
    assertEquals("vx", clsImpl.getCustom("kx"));
    clsImpl.set(dbSession, ATTRIBUTES.CUSTOM, null);
    assertTrue("setting CUSTOM to null also clears the map",
        clsImpl.getCustomKeys().isEmpty());

    // The "no equals sign" path raises IllegalArgumentException (after the null/clear early-return
    // arm so the message-bearing branch is reachable).
    assertThrows(IllegalArgumentException.class,
        () -> clsImpl.set(dbSession, ATTRIBUTES.CUSTOM, "no-equals-here"));
  }

  @Test
  public void countAndApproximateCountReflectInsertedRows() {
    // Pin SchemaClassImpl.count + approximateCount paths via the public SchemaClass interface;
    // both polymorphic-default and explicit-non-polymorphic branches. countClass requires an
    // active transaction.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("CountParent");
    schema.createClass("CountChild", parent);

    session.executeInTx(tx -> {
      session.newEntity("CountParent");
      session.newEntity("CountParent");
      session.newEntity("CountChild");
    });

    // The session-level count public API drives the SchemaClassProxy-routed count(...) method.
    session.executeInTx(tx -> {
      assertEquals(3L, session.countClass("CountParent"));
      assertEquals(2L, session.countClass("CountParent", false));
      assertEquals(1L, session.countClass("CountChild"));

      // approximateCount has the same polymorphic / non-polymorphic split. The exact value is
      // storage-dependent (memory mode returns the exact count, disk mode an estimate) but the
      // monotonic invariant holds: polymorphic >= non-polymorphic on the parent.
      var parentImpl =
          (SchemaClassImpl) ((SchemaClassInternal) parent).getImplementation();
      long polymorphic = parentImpl.approximateCount(session, true);
      long onlyParent = parentImpl.approximateCount(session, false);
      assertTrue("polymorphic approximateCount must be >= non-polymorphic ("
          + polymorphic + " >= " + onlyParent + ")", polymorphic >= onlyParent);
      assertTrue(polymorphic >= 0);
    });
  }

  @Test
  public void truncateClassEmptiesTheStorage() {
    // Pin SchemaClassImpl.truncate (delegates to session.truncateClass(name, false)).
    // countClass requires an active transaction; truncate itself runs outside a tx.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TruncateMe");
    session.executeInTx(tx -> {
      session.newEntity("TruncateMe");
      session.newEntity("TruncateMe");
    });
    session.executeInTx(tx -> assertEquals(2L, session.countClass("TruncateMe")));

    // Drive truncate via the proxy — SchemaClassProxy.truncate() forwards to delegate.truncate().
    // truncate is on the SchemaClassInternal interface (not SchemaClass) — cast to drive it.
    ((SchemaClassInternal) cls).truncate();
    session.executeInTx(tx -> assertEquals(0L, session.countClass("TruncateMe")));
  }

  @Test
  public void addAndRemoveSuperClassRoundTrip() {
    // The addSuperClass / removeSuperClass + already-superclass rejection path on
    // SchemaClassEmbedded — drives the abstract/concrete addSuperClassInternal /
    // removeSuperClassInternal pair.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("AddRmParent");
    var child = schema.createClass("AddRmChild");

    assertTrue("freshly-created class must have no superclasses",
        child.getSuperClasses().isEmpty());

    child.addSuperClass(parent);
    assertEquals(1, child.getSuperClasses().size());
    assertTrue(child.isSubClassOf(parent));
    assertTrue(parent.isSuperClassOf(child));

    // Re-adding the same superclass must throw — the "already has the class … as superclass"
    // arm in addSuperClassInternal.
    assertThrows(SchemaException.class, () -> child.addSuperClass(parent));

    child.removeSuperClass(parent);
    assertTrue("after removeSuperClass the relation must be gone",
        child.getSuperClasses().isEmpty());
    assertFalse(child.isSubClassOf(parent));
    assertFalse(parent.isSuperClassOf(child));
  }

  @Test
  public void hasCollectionIdAndHasPolymorphicCollectionIdReflectMembership() {
    // Pin the binarySearch-based hasCollectionId / hasPolymorphicCollectionId predicates via the
    // proxy chain. Polymorphic ids include the parent's own ids plus subclass ids, so a child
    // class's id is reachable on the parent only via hasPolymorphicCollectionId, not via
    // hasCollectionId.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("CollIdParent");
    var child = schema.createClass("CollIdChild", parent);

    int parentColl = parent.getCollectionIds()[0];
    int childColl = child.getCollectionIds()[0];

    assertTrue(parent.hasCollectionId(parentColl));
    assertTrue(child.hasCollectionId(childColl));

    // The child collection id is in the parent's polymorphicCollectionIds but NOT the parent's
    // collectionIds.
    assertFalse(parent.hasCollectionId(childColl));
    assertTrue(parent.hasPolymorphicCollectionId(childColl));
    assertTrue(parent.hasPolymorphicCollectionId(parentColl));

    // Random non-existent ids return false.
    assertFalse(parent.hasCollectionId(99_999));
    assertFalse(parent.hasPolymorphicCollectionId(99_999));
  }

  @Test
  public void getPolymorphicCollectionIdsReturnsDefensiveCopy() {
    // SchemaClassImpl.getPolymorphicCollectionIds returns Arrays.copyOf — mutation of the returned
    // array must not leak into the class's internal state.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PolyCopy");
    var copy = cls.getPolymorphicCollectionIds();
    assertTrue(copy.length >= 1);

    int original = copy[0];
    copy[0] = -42;
    var freshCopy = cls.getPolymorphicCollectionIds();
    assertEquals("getPolymorphicCollectionIds must defensively copy",
        original, freshCopy[0]);
  }

  @Test
  public void declaredAndPolymorphicPropertiesReflectInheritance() {
    // Pin declaredProperties + properties + propertiesMap inheritance walks. A child class must
    // expose its parent's properties via properties() / propertiesMap(), but only its own via
    // declaredProperties().
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("PropParent");
    parent.createProperty("p1", PropertyType.STRING);
    var child = schema.createClass("PropChild", parent);
    child.createProperty("p2", PropertyType.INTEGER);

    // declaredProperties: the child's own only.
    var declared = child.getDeclaredProperties();
    assertEquals(1, declared.size());
    assertEquals("p2", declared.iterator().next().getName());

    // getProperties (the polymorphic walk via the public Schema interface): both p1 and p2.
    var all = child.getProperties();
    assertEquals(2, all.size());
    var names = all.stream().map(p -> p.getName()).sorted().toList();
    assertEquals(List.of("p1", "p2"), names);

    // getPropertiesMap is also polymorphic and indexed by name.
    var map = child.getPropertiesMap();
    assertNotNull(map.get("p1"));
    assertNotNull(map.get("p2"));
    assertEquals(2, map.size());

    // existsProperty: must walk the superclass chain.
    assertTrue("inherited property must be exists()", child.existsProperty("p1"));
    assertTrue(child.existsProperty("p2"));
    assertFalse(child.existsProperty("nonexistent"));

    // The parent itself only sees its declared property.
    assertEquals(1, parent.getDeclaredProperties().size());
    assertEquals(1, parent.getProperties().size());
  }

  @Test
  public void isSubClassOfHandlesNullClassNameAndReferenceArms() {
    // Pin SchemaClassImpl.isSubClassOf(String) and isSubClassOf(SchemaClassImpl) early-return
    // arms for null + self + walk + miss. The proxy-level isSubClassOf(SchemaClass) does NOT
    // null-guard the argument (it unwraps via getImplementation()), so the null-reference arm is
    // tested at the impl level — only the by-name null arm is reachable through the proxy.
    Schema schema = session.getMetadata().getSchema();
    var grandparent = schema.createClass("ScpGrand");
    var parent = schema.createClass("ScpParent", grandparent);
    var child = schema.createClass("ScpChild", parent);

    // Null-name arm: the impl returns false on a null class-name argument.
    assertFalse(child.isSubClassOf((String) null));

    // Self arm.
    assertTrue(child.isSubClassOf("ScpChild"));
    assertTrue(child.isSubClassOf(child));

    // Direct + transitive parent.
    assertTrue(child.isSubClassOf("ScpParent"));
    assertTrue(child.isSubClassOf("ScpGrand"));

    // Negative miss arm.
    assertFalse(child.isSubClassOf("Missing"));

    // isSuperClassOf(SchemaClass) on the public interface — pin both positive and negative arms
    // by reference (the by-name overload exists only on SchemaClassImpl, not on the interface,
    // so is exercised separately via the impl-level isSuperClassOf(String) call below).
    assertTrue(grandparent.isSuperClassOf(child));
    assertTrue(grandparent.isSuperClassOf(parent));
    assertFalse(child.isSuperClassOf(parent));

    // Impl-level null-reference arm: the impl IS null-guarded for the SchemaClassImpl arg even
    // though the proxy is not.
    var childImpl =
        (SchemaClassImpl) ((SchemaClassInternal) child).getImplementation();
    assertFalse(childImpl.isSuperClassOf((SchemaClassImpl) null));
    assertFalse(childImpl.isSubClassOf((SchemaClassImpl) null));

    // Drive the impl-only isSuperClassOf(String) overload — looks up the named class via the
    // schema and forwards to clazz.isSuperClassOf(this), so the predicate effectively asks
    // "is the receiver `this` a subclass of the named class?". The grand class is NOT a subclass
    // of ScpChild (grand IS a super), so the result is false. The Missing-name early-return is
    // also false. This impl-level overload is reachable only on SchemaClassImpl (not on the
    // public interface or proxy) — pin both arms here.
    var grandImpl =
        (SchemaClassImpl) ((SchemaClassInternal) grandparent).getImplementation();
    var childImpl2 = (SchemaClassImpl) ((SchemaClassInternal) child).getImplementation();
    assertFalse("grand is not a subclass of ScpChild — isSuperClassOf returns false here",
        grandImpl.isSuperClassOf("ScpChild"));
    assertTrue("child IS a subclass of ScpGrand — isSuperClassOf returns true",
        childImpl2.isSuperClassOf("ScpGrand"));
    assertFalse("Missing name early-returns false",
        grandImpl.isSuperClassOf("Missing"));
  }

  @Test
  public void equalsHashCodeAndToStringContractOnLiveImpl() {
    // SchemaClassImpl.equals / hashCode use the class name as identity; toString returns the name.
    // The proxy-level equals/hashCode are pinned in SchemaClassProxyBoundaryTest.
    Schema schema = session.getMetadata().getSchema();
    var c1 = schema.createClass("EqClsA");
    schema.createClass("EqClsB");

    var c1Impl = ((SchemaClassInternal) c1).getImplementation();
    var c1ImplAlias =
        ((SchemaClassInternal) session.getMetadata().getSchema().getClass("EqClsA"))
            .getImplementation();
    var c2Impl =
        ((SchemaClassInternal) session.getMetadata().getSchema().getClass("EqClsB"))
            .getImplementation();

    // Reflexive.
    assertEquals(c1Impl, c1Impl);
    // Same-name impls are equal.
    assertEquals(c1Impl, c1ImplAlias);
    // Different-name impls are not equal.
    assertNotEquals(c1Impl, c2Impl);
    // Non-SchemaClassImpl object — false (and must not throw).
    assertNotEquals(c1Impl, "EqClsA");
    // Hash code stable across calls.
    assertEquals(c1Impl.hashCode(), c1ImplAlias.hashCode());
    // toString returns the class name.
    assertEquals("EqClsA", c1Impl.toString());
  }

  @Test
  public void getSuperClassesReturnsUnmodifiableList() {
    // SchemaClassImpl.getSuperClasses returns Collections.unmodifiableList — pin the immutability
    // contract.
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("SupListParent");
    var child = schema.createClass("SupListChild", parent);

    var clsImpl = (SchemaClassImpl) ((SchemaClassInternal) child).getImplementation();
    var supers = clsImpl.getSuperClasses();
    assertEquals(1, supers.size());

    assertThrows("getSuperClasses must be unmodifiable",
        UnsupportedOperationException.class, () -> supers.add(clsImpl));
    assertThrows(UnsupportedOperationException.class, () -> supers.remove(0));
  }

  @Test
  public void allBaseClassesAndAllSuperClassesWalkTransitively() {
    // Pin getAllSuperClasses + getAllSubclasses transitive walks. Build a 3-level chain
    // (grand → parent → child) and assert the closure on both sides.
    Schema schema = session.getMetadata().getSchema();
    var grand = schema.createClass("AllGrand");
    var parent = schema.createClass("AllParent", grand);
    var child = schema.createClass("AllChild", parent);

    var grandImpl = (SchemaClassImpl) ((SchemaClassInternal) grand).getImplementation();
    var parentImpl = (SchemaClassImpl) ((SchemaClassInternal) parent).getImplementation();
    var childImpl = (SchemaClassImpl) ((SchemaClassInternal) child).getImplementation();

    // childImpl.getAllSuperClasses must contain both parent and grand.
    var allSupers = childImpl.getAllSuperClasses();
    assertEquals(2, allSupers.size());
    assertTrue(allSupers.contains(parentImpl));
    assertTrue(allSupers.contains(grandImpl));

    // grandImpl.getAllSubclasses must contain both parent and child.
    var allSubs = grandImpl.getAllSubclasses();
    assertEquals(2, allSubs.size());
    assertTrue(allSubs.contains(parentImpl));
    assertTrue(allSubs.contains(childImpl));

    // The deprecated getBaseClasses / getAllBaseClasses aliases delegate to the same impl —
    // pin the equality with the modern alias.
    assertEquals(grandImpl.getSubclasses().size(), grandImpl.getBaseClasses().size());
    assertEquals(grandImpl.getAllSubclasses().size(), grandImpl.getAllBaseClasses().size());
  }

  @Test
  public void streamableNameMatchesNameOnLiveClass() {
    // SchemaClassImpl.getStreamableName returns the same as getName for live classes — pin the
    // contract so any future divergence (e.g., adding an alias layer) is caught.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("StreamName");
    var impl = (SchemaClassImpl) ((SchemaClassInternal) cls).getImplementation();
    assertEquals("StreamName", impl.getName());
    assertEquals("StreamName", impl.getStreamableName());
  }

  @Test
  public void renamePropertyMovesEntryWithoutReinsertion() {
    // SchemaClassImpl.renameProperty (the underlying map-level rename used by
    // SchemaPropertyEmbedded.setName) — pin the in-place key swap. The original entry must be gone
    // and the new key must point at the same SchemaPropertyImpl instance.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenProp");
    cls.createProperty("oldName", PropertyType.STRING);

    var clsImpl = (SchemaClassImpl) ((SchemaClassInternal) cls).getImplementation();
    var oldImpl = clsImpl.getDeclaredPropertyInternal("oldName");
    assertNotNull(oldImpl);

    clsImpl.renameProperty("oldName", "newName");
    assertNull("old key must be removed", clsImpl.getDeclaredPropertyInternal("oldName"));
    var newImpl = clsImpl.getDeclaredPropertyInternal("newName");
    assertNotNull(newImpl);
    assertSame("renameProperty must move the same instance, not create a new one",
        oldImpl, newImpl);
  }

  @Test
  public void renamePropertyMissingKeyIsNoOp() {
    // The early-return when the old key is absent — call renameProperty on a name that doesn't
    // exist; the call must not throw and must not create the new key.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenMiss");
    var clsImpl = (SchemaClassImpl) ((SchemaClassInternal) cls).getImplementation();

    clsImpl.renameProperty("notThere", "newName");
    assertNull(clsImpl.getDeclaredPropertyInternal("notThere"));
    assertNull(clsImpl.getDeclaredPropertyInternal("newName"));
  }

  @Test
  public void setSuperClassesReplacesEntireSet() {
    // SchemaClassEmbedded.setSuperClasses(List<SchemaClassImpl>) replaces the parent set in one
    // step (different from add+remove). Verify the diff-based add/remove logic in
    // setSuperClassesInternal: the old parent is dropped and the new ones are wired in.
    Schema schema = session.getMetadata().getSchema();
    var p1 = schema.createClass("RepP1");
    var p2 = schema.createClass("RepP2");
    var p3 = schema.createClass("RepP3");
    var child = schema.createClass("RepChild", p1);

    assertEquals(List.of(p1.getName()), child.getSuperClassesNames());

    child.setSuperClasses(List.of(p2, p3));
    var refetched = session.getMetadata().getSchema().getClass("RepChild");
    var supers = refetched.getSuperClassesNames();
    assertEquals(2, supers.size());
    assertTrue(supers.contains("RepP2"));
    assertTrue(supers.contains("RepP3"));
    assertFalse("p1 must be dropped from the superclass set", supers.contains("RepP1"));

    // Setting null drops everything — the empty-list early-return path in
    // setSuperClassesByNames + the diff-based remove-all path in setSuperClassesInternal.
    refetched.setSuperClasses(Collections.emptyList());
    assertTrue(session.getMetadata().getSchema().getClass("RepChild")
        .getSuperClasses().isEmpty());
  }

  @Test
  public void createPropertyInsideTransactionIsRejected() {
    // SchemaClassEmbedded.addProperty rejects creation inside an active transaction — pin the
    // SchemaException arm.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PropInTx");

    session.begin();
    try {
      cls.createProperty("p", PropertyType.STRING);
      fail("createProperty must throw inside a transaction");
    } catch (SchemaException expected) {
      assertTrue("error must mention the inside-transaction case",
          expected.getMessage().toLowerCase().contains("transaction"));
    } finally {
      session.rollback();
    }

    // Creation succeeds outside the transaction.
    cls.createProperty("p", PropertyType.STRING);
    assertNotNull(cls.getProperty("p"));
  }

  @Test
  public void dropPropertyInsideTransactionIsRejected() {
    // SchemaClassEmbedded.dropProperty rejects deletion inside an active transaction — pin the
    // IllegalStateException arm.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DropInTx");
    cls.createProperty("p", PropertyType.STRING);

    session.begin();
    try {
      cls.dropProperty("p");
      fail("dropProperty must throw inside a transaction");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().toLowerCase().contains("transaction"));
    } finally {
      session.rollback();
    }

    cls.dropProperty("p");
    assertFalse(cls.existsProperty("p"));
  }

  @Test
  public void dropMissingPropertyThrowsSchemaException() {
    // Drop of a non-existent property goes through the "Property not found" rejection arm.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DropMiss");
    var thrown = assertThrows(SchemaException.class, () -> cls.dropProperty("nonexistent"));
    assertTrue(thrown.getMessage().toLowerCase().contains("nonexistent"));
  }

  @Test
  public void duplicatePropertyCreationThrowsSchemaException() {
    // Pin the "already has property" rejection arm in addPropertyInternal.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DupProp");
    cls.createProperty("p", PropertyType.STRING);

    var thrown = assertThrows(SchemaException.class,
        () -> cls.createProperty("p", PropertyType.STRING));
    assertTrue("error must mention the property name",
        thrown.getMessage().contains("p"));
  }

  @Test
  public void edgeAndVertexClassFlagsReflectInheritance() {
    // SchemaClassImpl.isVertexType / isEdgeType — pin both arms via the V / E built-in roots.
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var e = schema.getClass("E");
    assertTrue(v.isVertexType());
    assertFalse(v.isEdgeType());
    assertTrue(e.isEdgeType());
    assertFalse(e.isVertexType());

    // A subclass of V is a vertex type.
    var customVertex = schema.createClass("CustomV", v);
    assertTrue(customVertex.isVertexType());
    assertFalse(customVertex.isEdgeType());

    // A subclass of neither V nor E is neither.
    var plain = schema.createClass("PlainCls");
    assertFalse(plain.isVertexType());
    assertFalse(plain.isEdgeType());
  }
}
