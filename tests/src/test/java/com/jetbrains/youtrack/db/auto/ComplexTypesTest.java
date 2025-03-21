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
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@SuppressWarnings("unchecked")
@Test
public class ComplexTypesTest extends BaseDBTest {

  @Parameters(value = "remote")
  public ComplexTypesTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void testBigDecimal() {
    session.begin();
    var newDoc = session.newEntity();
    newDoc.setProperty("integer", new BigInteger("10"));
    newDoc.setProperty("decimal_integer", new BigDecimal(10));
    newDoc.setProperty("decimal_float", new BigDecimal("10.34"));
    session.commit();

    final RID rid = newDoc.getIdentity();

    session.close();
    session = acquireSession();

    EntityImpl loadedDoc = session.load(rid);
    Assert.assertEquals(((Number) loadedDoc.getProperty("integer")).intValue(), 10);
    Assert.assertEquals(loadedDoc.getProperty("decimal_integer"), new BigDecimal(10));
    Assert.assertEquals(loadedDoc.getProperty("decimal_float"), new BigDecimal("10.34"));
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

    EntityImpl d = ((List<Identifiable>) loadedDoc.getProperty("linkedList")).getFirst().getRecord(
        session);
    Assert.assertTrue(d.getIdentity().isValidPosition());
    Assert.assertEquals(d.getProperty("name"), "Luca");
    d = ((List<Identifiable>) loadedDoc.getProperty("linkedList")).get(1).getRecord(session);
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
    Assert.assertNotNull(loadedDoc.getEmbeddedSet("linkSet"));

    final var it = (loadedDoc.getLinkSet("linkSet")).iterator();

    var tot = 0;
    while (it.hasNext()) {
      var d = it.next().getEntity(session);

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
      var d = it.next().getEntity(session);

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

    var d = loadedDoc.getLinkMap("linkMap").get("Luca").getEntity(session);
    Assert.assertEquals(d.getProperty("name"), "Luca");

    d = loadedDoc.getLinkMap("linkMap").get("Marcus").getEntity(session);
    Assert.assertEquals(d.getProperty("name"), "Marcus");

    d = loadedDoc.getLinkMap("linkMap").get("Cesare").getEntity(session);
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

    EntityImpl d =
        ((Map<String, Identifiable>) loadedDoc.getProperty("linkedMap")).get("Luca")
            .getRecord(session);
    Assert.assertEquals(d.getProperty("name"), "Luca");

    d = ((Map<String, Identifiable>) loadedDoc.getProperty("linkedMap")).get("Marcus")
        .getRecord(session);
    Assert.assertEquals(d.getProperty("name"), "Marcus");

    d = ((Map<String, Identifiable>) loadedDoc.getProperty("linkedMap")).get("Cesare")
        .getRecord(session);
    Assert.assertEquals(d.getProperty("name"), "Cesare");
    Assert.assertEquals(d.getSchemaClassName(), "Account");
    session.commit();
  }
}
