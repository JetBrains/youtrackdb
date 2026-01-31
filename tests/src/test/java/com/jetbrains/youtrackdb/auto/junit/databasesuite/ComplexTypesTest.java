/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Tests for complex data types including BigDecimal, LinkList, LinkSet, LinkMap, and EmbeddedMap.
 *
 * <p><b>Suite Dependency:</b> This test is part of {@link DatabaseTestSuite} and depends on
 * the Account class created by earlier tests in the Schema group. Can be run individually as the
 * {@code @BeforeClass} method initializes the required schema.</p>
 *
 * <p>Original test class: {@code com.jetbrains.youtrackdb.auto.ComplexTypesTest}</p>
 */
@SuppressWarnings("unchecked")
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ComplexTypesTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    ComplexTypesTest instance = new ComplexTypesTest();
    instance.beforeClass();
  }

  /**
   * Original test method: testBigDecimal Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ComplexTypesTest.java:39
   */
  @Test
  public void test01_BigDecimal() {
    final var clazz = session.createClass("BigDecimalTest");
    clazz.createProperty("integer_schema", PropertyType.INTEGER);
    clazz.createProperty("decimal_schema", PropertyType.DECIMAL);

    final var largeNumber = new BigDecimal(Long.MAX_VALUE).multiply(new BigDecimal(Long.MAX_VALUE));
    final var largeNumberFract = largeNumber.add(
        new BigDecimal("0." + StringUtils.repeat("12345", 10)));
    final var largeNumberInt = largeNumber.toBigInteger();
    final var largeNumberIntNeg = largeNumberInt.negate();

    // big integers must be converted to DECIMAL type
    session.begin();
    var newDoc = session.newEntity(clazz);
    newDoc.setProperty("decimal_integer", largeNumber);
    newDoc.setProperty("decimal_float", largeNumberFract);

    newDoc.setProperty("integer_no_schema", largeNumberInt);
    newDoc.setProperty("decimal_schema", largeNumberIntNeg);
    try {
      newDoc.setProperty("integer_schema", new BigInteger("10"));
      // TODO uncomment this when YTDB-255 is resolved
      // fail("BigInteger values should not be allowed for INTEGER schema property");
    } catch (SchemaException ex) {
      // ok
    }
    session.commit();

    final RID rid = newDoc.getIdentity();

    session.close();
    session = acquireSession();

    session.begin();
    EntityImpl loadedDoc = session.load(rid);

    assertThat(loadedDoc.<BigDecimal>getProperty("decimal_integer")).isEqualTo(largeNumber);
    assertThat(loadedDoc.<BigDecimal>getProperty("decimal_float")).isEqualTo(largeNumberFract);

    assertThat(loadedDoc.<BigDecimal>getProperty("integer_no_schema")).isEqualTo(
        new BigDecimal(largeNumberInt));
    assertThat(loadedDoc.<BigDecimal>getProperty("decimal_schema")).isEqualTo(
        new BigDecimal(largeNumberIntNeg));

    session.commit();
  }

  /**
   * Original test method: testLinkList2 Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ComplexTypesTest.java:85
   */
  @Test
  public void test02_LinkList2() {
    session.begin();
    var newDoc = session.newEntity();
    final var list = session.newLinkList();
    newDoc.setProperty("linkList", list, PropertyType.LINKLIST);
    list.add(((EntityImpl) session.newEntity()).setPropertyInChain("name", "Luca"));
    list.add(((EntityImpl) session.newEntity("Account")).setPropertyInChain("name", "Marcus"));

    session.commit();

    final RID rid = newDoc.getIdentity();

    session.close();
    session = acquireSession();

    session.executeInTx(tx -> {
      EntityImpl loadedDoc = session.load(rid);
      Assert.assertTrue(loadedDoc.hasProperty("linkList"));
      Assert.assertTrue(loadedDoc.getProperty("linkList") instanceof List<?>);
      final var id1 = (loadedDoc.getLinkList("linkList")).getFirst();
      final var id2 = (loadedDoc.getLinkList("linkList")).get(1);
      Assert.assertTrue(id1 instanceof RID);
      Assert.assertTrue(id2 instanceof RID);

      var d = session.<Entity>load(((RID) id1));
      Assert.assertEquals("Luca", d.getProperty("name"));
      d = session.load(((RID) id2));
      Assert.assertEquals("Account", d.getSchemaClassName());
      Assert.assertEquals("Marcus", d.getProperty("name"));
    });
  }

  /**
   * Original test method: testLinkList Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ComplexTypesTest.java:118
   */
  @Test
  public void test03_LinkList() {
    session.begin();
    var newDoc = ((EntityImpl) session.newEntity());
    final var list = session.newLinkList();
    newDoc.setProperty("linkedList", list, PropertyType.LINKLIST);

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("name", "Luca");

    list.add(doc);

    list.add(((EntityImpl) session.newEntity("Account")).setPropertyInChain("name", "Marcus"));

    session.commit();

    final RID rid = newDoc.getIdentity();

    session.close();
    session = acquireSession();

    session.begin();
    EntityImpl loadedDoc = session.load(rid);
    Assert.assertTrue(loadedDoc.hasProperty("linkedList"));
    Assert.assertTrue(loadedDoc.getProperty("linkedList") instanceof List<?>);
    Assert.assertTrue(
        ((List<Identifiable>) loadedDoc.getProperty(
            "linkedList")).getFirst() instanceof Identifiable);

    Identifiable identifiable = ((List<Identifiable>) loadedDoc.getProperty(
        "linkedList")).getFirst();
    var transaction1 = session.getActiveTransaction();
    EntityImpl d = transaction1.load(identifiable);
    Assert.assertTrue(d.getIdentity().isValidPosition());
    Assert.assertEquals("Luca", d.getProperty("name"));
    var transaction = session.getActiveTransaction();
    d = transaction.load(((List<Identifiable>) loadedDoc.getProperty("linkedList")).get(1));
    Assert.assertEquals("Account", d.getSchemaClassName());
    Assert.assertEquals("Marcus", d.getProperty("name"));
    session.commit();
  }

  /**
   * Original test method: testLinkSet2 Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ComplexTypesTest.java:160
   */
  @Test
  public void test04_LinkSet2() {
    session.begin();
    var newDoc = session.newEntity();

    final var set = session.newLinkSet();
    newDoc.setProperty("linkSet", set, PropertyType.LINKSET);
    set.add(((EntityImpl) session.newEntity()).setPropertyInChain("name", "Luca"));
    set.add(((EntityImpl) session.newEntity("Account")).setPropertyInChain("name", "Marcus"));

    session.commit();

    final RID rid = newDoc.getIdentity();

    session.close();
    session = acquireSession();

    session.begin();
    EntityImpl loadedDoc = session.load(rid);
    Assert.assertTrue(loadedDoc.hasProperty("linkSet"));
    Assert.assertNotNull(loadedDoc.getLinkSet("linkSet"));

    final var it = (loadedDoc.getLinkSet("linkSet")).iterator();

    var tot = 0;
    while (it.hasNext()) {
      Identifiable identifiable = it.next();
      var transaction = session.getActiveTransaction();
      var d = transaction.loadEntity(identifiable);

      if (d.getProperty("name").equals("Marcus")) {
        Assert.assertEquals("Account", d.getSchemaClassName());
      }

      ++tot;
    }

    Assert.assertEquals(2, tot);
    session.commit();
  }

  /**
   * Original test method: testLinkSet Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ComplexTypesTest.java:201
   */
  @Test
  public void test05_LinkSet() {
    session.begin();
    var newDoc = ((EntityImpl) session.newEntity());

    final var set = session.newLinkSet();
    newDoc.setProperty("linkedSet", set, PropertyType.LINKSET);
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("name", "Luca");

    set.add(doc);

    set.add(((EntityImpl) session.newEntity("Account")).setPropertyInChain("name", "Marcus"));

    session.commit();

    final RID rid = newDoc.getIdentity();

    session.close();
    session = acquireSession();
    session.begin();

    EntityImpl loadedDoc = session.load(rid);
    Assert.assertTrue(loadedDoc.hasProperty("linkedSet"));
    Assert.assertNotNull(loadedDoc.getLinkSet("linkedSet"));

    final var it =
        ((Collection<Identifiable>) loadedDoc.getProperty("linkedSet")).iterator();

    var tot = 0;
    while (it.hasNext()) {
      Identifiable identifiable = it.next();
      var transaction = session.getActiveTransaction();
      var d = transaction.loadEntity(identifiable);

      if (Objects.equals(d.getProperty("name"), "Marcus")) {
        Assert.assertEquals("Account", d.getSchemaClassName());
      }

      ++tot;
    }

    Assert.assertEquals(2, tot);
    session.commit();
  }

  /**
   * Original test method: testLinkMap2 Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ComplexTypesTest.java:247
   */
  @Test
  public void test06_LinkMap2() {
    session.begin();
    var newDoc = ((EntityImpl) session.newEntity());

    final var map = session.newLinkMap();
    newDoc.setProperty("linkMap", map, PropertyType.LINKMAP);
    map.put("Luca", ((EntityImpl) session.newEntity()).setPropertyInChain("name", "Luca"));
    map.put("Marcus", ((EntityImpl) session.newEntity()).setPropertyInChain("name", "Marcus"));
    map.put("Cesare",
        ((EntityImpl) session.newEntity("Account")).setPropertyInChain("name", "Cesare"));

    session.commit();

    final RID rid = newDoc.getIdentity();

    session.close();
    session = acquireSession();

    session.begin();
    EntityImpl loadedDoc = session.load(rid);
    Assert.assertTrue(loadedDoc.hasProperty("linkMap"));
    Assert.assertTrue(loadedDoc.getProperty("linkMap") instanceof Map<?, ?>);

    Identifiable identifiable2 = loadedDoc.getLinkMap("linkMap").get("Luca");
    var transaction2 = session.getActiveTransaction();
    var d = transaction2.loadEntity(identifiable2);
    Assert.assertEquals("Luca", d.getProperty("name"));

    Identifiable identifiable1 = loadedDoc.getLinkMap("linkMap").get("Marcus");
    var transaction1 = session.getActiveTransaction();
    d = transaction1.loadEntity(identifiable1);
    Assert.assertEquals("Marcus", d.getProperty("name"));

    Identifiable identifiable = loadedDoc.getLinkMap("linkMap").get("Cesare");
    var transaction = session.getActiveTransaction();
    d = transaction.loadEntity(identifiable);
    Assert.assertEquals("Cesare", d.getProperty("name"));
    Assert.assertEquals("Account", d.getSchemaClassName());
    session.commit();
  }

  /**
   * Original test method: testEmptyEmbeddedMap Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ComplexTypesTest.java:289
   */
  @Test
  public void test07_EmptyEmbeddedMap() {
    session.begin();
    var newDoc = ((EntityImpl) session.newEntity());
    newDoc.setProperty("embeddedMap", session.newEmbeddedMap(), PropertyType.EMBEDDEDMAP);
    session.commit();

    final RID rid = newDoc.getIdentity();

    session.close();
    session = acquireSession();

    session.begin();
    EntityImpl loadedDoc = session.load(rid);

    Assert.assertTrue(loadedDoc.hasProperty("embeddedMap"));
    Assert.assertTrue(loadedDoc.getProperty("embeddedMap") instanceof Map<?, ?>);

    final Map<String, EntityImpl> loadedMap = loadedDoc.getProperty("embeddedMap");
    Assert.assertEquals(0, loadedMap.size());
    session.commit();
  }

  /**
   * Original test method: testLinkMap Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ComplexTypesTest.java:312
   */
  @Test
  public void test08_LinkMap() {
    session.begin();
    var newDoc = ((EntityImpl) session.newEntity());

    final var map = session.newLinkMap();
    newDoc.setProperty("linkedMap", map, PropertyType.LINKMAP);
    var doc1 = ((EntityImpl) session.newEntity());
    doc1.setProperty("name", "Luca");

    map.put("Luca", doc1);
    var doc2 = ((EntityImpl) session.newEntity());
    doc2.setProperty("name", "Marcus");

    map.put("Marcus", doc2);

    var doc3 = ((EntityImpl) session.newEntity("Account"));
    doc3.setProperty("name", "Cesare");

    map.put("Cesare", doc3);

    session.commit();

    final RID rid = newDoc.getIdentity();

    session.close();
    session = acquireSession();
    session.begin();

    EntityImpl loadedDoc = session.load(rid);
    Assert.assertNotNull(loadedDoc.getLinkMap("linkedMap"));
    Assert.assertTrue(loadedDoc.getProperty("linkedMap") instanceof Map<?, ?>);
    Assert.assertTrue(
        ((Map<String, Identifiable>) loadedDoc.getProperty("linkedMap")).values().iterator().next()
            instanceof Identifiable);

    Identifiable identifiable2 = ((Map<String, Identifiable>) loadedDoc.getProperty(
        "linkedMap")).get("Luca");
    var transaction2 = session.getActiveTransaction();
    EntityImpl d =
        transaction2.load(identifiable2);
    Assert.assertEquals("Luca", d.getProperty("name"));

    Identifiable identifiable1 = ((Map<String, Identifiable>) loadedDoc.getProperty(
        "linkedMap")).get("Marcus");
    var transaction1 = session.getActiveTransaction();
    d = transaction1.load(identifiable1);
    Assert.assertEquals("Marcus", d.getProperty("name"));

    Identifiable identifiable = ((Map<String, Identifiable>) loadedDoc.getProperty(
        "linkedMap")).get("Cesare");
    var transaction = session.getActiveTransaction();
    d = transaction.load(identifiable);
    Assert.assertEquals("Cesare", d.getProperty("name"));
    Assert.assertEquals("Account", d.getSchemaClassName());
    session.commit();
  }
}
