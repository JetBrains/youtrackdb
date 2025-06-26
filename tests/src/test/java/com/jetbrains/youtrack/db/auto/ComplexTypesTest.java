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
package com.jetbrains.youtrack.db.auto;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang.StringUtils;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@SuppressWarnings("unchecked")
@Test
public class ComplexTypesTest extends BaseDBTest {
  @Test
  public void testBigDecimal() {
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

    assertThat(loadedDoc.<BigDecimal>getProperty("integer_no_schema")).isEqualTo(new BigDecimal(largeNumberInt));
    assertThat(loadedDoc.<BigDecimal>getProperty("decimal_schema")).isEqualTo(new BigDecimal(largeNumberIntNeg));

    session.commit();
  }

  @Test
  public void testLinkList2() {
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
      Assert.assertEquals(d.getProperty("name"), "Luca");
      d = session.load(((RID) id2));
      Assert.assertEquals(d.getSchemaClassName(), "Account");
      Assert.assertEquals(d.getProperty("name"), "Marcus");
    });
  }

  @Test
  public void testLinkList() {
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
    Assert.assertEquals(d.getProperty("name"), "Luca");
    var transaction = session.getActiveTransaction();
    d = transaction.load(((List<Identifiable>) loadedDoc.getProperty("linkedList")).get(1));
    Assert.assertEquals(d.getSchemaClassName(), "Account");
    Assert.assertEquals(d.getProperty("name"), "Marcus");
    session.commit();
  }

  @Test
  public void testLinkSet2() {
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
        Assert.assertEquals(d.getSchemaClassName(), "Account");
      }

      ++tot;
    }

    Assert.assertEquals(tot, 2);
    session.commit();
  }

  @Test
  public void testLinkSet() {
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
        Assert.assertEquals(d.getSchemaClassName(), "Account");
      }

      ++tot;
    }

    Assert.assertEquals(tot, 2);
    session.commit();
  }

  @Test
  public void testLinkMap2() {
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
    Assert.assertEquals(d.getProperty("name"), "Luca");

    Identifiable identifiable1 = loadedDoc.getLinkMap("linkMap").get("Marcus");
    var transaction1 = session.getActiveTransaction();
    d = transaction1.loadEntity(identifiable1);
    Assert.assertEquals(d.getProperty("name"), "Marcus");

    Identifiable identifiable = loadedDoc.getLinkMap("linkMap").get("Cesare");
    var transaction = session.getActiveTransaction();
    d = transaction.loadEntity(identifiable);
    Assert.assertEquals(d.getProperty("name"), "Cesare");
    Assert.assertEquals(d.getSchemaClassName(), "Account");
    session.commit();
  }

  @Test
  public void testEmptyEmbeddedMap() {
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
    Assert.assertEquals(loadedMap.size(), 0);
    session.commit();
  }

  @Test
  public void testLinkMap() {
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
    Assert.assertEquals(d.getProperty("name"), "Luca");

    Identifiable identifiable1 = ((Map<String, Identifiable>) loadedDoc.getProperty(
        "linkedMap")).get("Marcus");
    var transaction1 = session.getActiveTransaction();
    d = transaction1.load(identifiable1);
    Assert.assertEquals(d.getProperty("name"), "Marcus");

    Identifiable identifiable = ((Map<String, Identifiable>) loadedDoc.getProperty(
        "linkedMap")).get("Cesare");
    var transaction = session.getActiveTransaction();
    d = transaction.load(identifiable);
    Assert.assertEquals(d.getProperty("name"), "Cesare");
    Assert.assertEquals(d.getSchemaClassName(), "Account");
    session.commit();
  }
}
