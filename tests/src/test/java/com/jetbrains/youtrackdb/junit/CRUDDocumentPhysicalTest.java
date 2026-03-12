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
package com.jetbrains.youtrackdb.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class CRUDDocumentPhysicalTest extends BaseDBJUnit5Test {
  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();
    createProfileClass();
    createCompanyClass();
    addBarackObamaAndFollowers();
  }

  @Test
  @Order(1)
  void create() {
    session.executeInTx(transaction -> session.execute("delete from Account").close());

    session.executeInTx(transaction -> assertEquals(0, session.countClass("Account")));

    fillInAccountData();

    session.executeInTx(transaction -> session.execute("delete from Profile").close());

    session.executeInTx(transaction -> assertEquals(0, session.countClass("Profile")));

    generateCompanyData();
  }

  @Test
  @Order(2)
  void testCreate() {
    session.begin();
    assertEquals(TOT_RECORDS_ACCOUNT, session.countClass("Account", false));
    session.rollback();
  }

  @Test
  @Order(3)
  void readAndBrowseDescendingAndCheckHoleUtilization() {
    // BROWSE IN THE OPPOSITE ORDER
    byte[] binary;

    Set<Integer> ids = new HashSet<>();
    for (var i = 0; i < TOT_RECORDS_ACCOUNT; i++) {
      ids.add(i);
    }

    session.begin();
    final var idsForward = new ArrayList<RecordIdInternal>();
    final var idsBackward = new ArrayList<RecordIdInternal>();
    session
        .browseClass("Account", false, true)
        .forEachRemaining(rec -> {
          idsForward.add(rec.getIdentity());
        });

    var it = session.browseClass("Account", false, false);
    while (it.hasNext()) {
      var rec = it.next();
      idsBackward.add(rec.getIdentity());

      var id = ((Number) rec.getProperty("id")).intValue();
      assertTrue(ids.remove(id));
      assertEquals("Gipsy", rec.getProperty("name"));
      assertEquals("Italy", rec.getProperty("location"));
      assertEquals(10000000000L, ((Number) rec.getProperty("testLong")).longValue());
      assertEquals(id + 300, ((Number) rec.getProperty("salary")).intValue());
      assertNotNull(rec.getProperty("extra"));
      assertEquals((byte) 10, rec.getByte("value"));

      binary = rec.getBinary("binary");

      for (var b = 0; b < binary.length; ++b) {
        assertEquals((byte) b, binary[b]);
      }
    }
    session.commit();

    assertThat(ids).isEmpty();
    assertThat(idsBackward).isEqualTo(idsForward.reversed());
  }

  @Test
  @Order(4)
  void update() {
    var i = new int[1];

    session.executeInTx(tx -> {
      var iterator = (Iterator<EntityImpl>) session.<EntityImpl>browseCollection("Account");
      session.forEachInTx(iterator, (session, rec) -> {
        rec = session.load(rec);
        if (i[0] % 2 == 0) {
          rec.setProperty("location", "Spain");
        }

        rec.setProperty("price", i[0] + 100);

        i[0]++;
      });
    });
  }

  @Test
  @Order(5)
  void testUpdate() {
    session.begin();
    var entityIterator = (Iterator<EntityImpl>) session.<EntityImpl>browseCollection("Account");
    while (entityIterator.hasNext()) {
      var rec = entityIterator.next();
      var price = ((Number) rec.getProperty("price")).intValue();
      assertTrue(price - 100 >= 0);

      if ((price - 100) % 2 == 0) {
        assertEquals("Spain", rec.getProperty("location"));
      } else {
        assertEquals("Italy", rec.getProperty("location"));
      }
    }
    session.commit();
  }

  @Test
  @Order(6)
  void testDoubleChanges() {

    final Set<Integer> profileCollectionIds =
        Arrays.stream(session.getMetadata().getSchema().getClass("Profile").getCollectionIds())
            .asLongStream()
            .mapToObj(i -> (int) i)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);

    session.begin();
    EntityImpl vDoc = session.newInstance("Profile");
    vDoc.setPropertyInChain("nick", "JayM1").setPropertyInChain("name", "Jay")
        .setProperty("surname", "Miner");

    assertTrue(profileCollectionIds.contains(vDoc.getIdentity().getCollectionId()));

    vDoc = session.load(vDoc.getIdentity());
    vDoc.setProperty("nick", "JayM2");
    vDoc.setProperty("nick", "JayM3");

    session.commit();

    var indexes =
        session.getMetadata().getSchemaInternal().getClassInternal("Profile")
            .getPropertyInternal("nick")
            .getAllIndexesInternal();

    assertEquals(1, indexes.size());

    var indexDefinition = indexes.iterator().next();
    session.begin();
    try (final var stream = indexDefinition.getRids(session, "JayM1")) {
      assertFalse(stream.findAny().isPresent());
    }

    try (final var stream = indexDefinition.getRids(session, "JayM2")) {
      assertFalse(stream.findAny().isPresent());
    }

    try (var stream = indexDefinition.getRids(session, "JayM3")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  @Order(7)
  void testMultiValues() {
    session.begin();
    EntityImpl vDoc = session.newInstance("Profile");
    vDoc.setPropertyInChain("nick", "Jacky").setPropertyInChain("name", "Jack")
        .setProperty("surname", "Tramiel");

    // add a new record with the same name "nameA".
    vDoc = session.newInstance("Profile");
    vDoc.setPropertyInChain("nick", "Jack").setPropertyInChain("name", "Jack")
        .setProperty("surname", "Bauer");

    session.commit();

    var indexes =
        session.getMetadata().getSchemaInternal().getClassInternal("Profile")
            .getPropertyInternal("name")
            .getAllIndexesInternal();
    assertEquals(1, indexes.size());

    var indexName = indexes.iterator().next();
    // We must get 2 records for "nameA".
    session.begin();
    try (var stream = indexName.getRids(session, "Jack")) {
      assertEquals(2, stream.count());
    }
    session.rollback();

    session.begin();
    // Remove this last record.
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<EntityImpl>load(vDoc));
    session.commit();

    // We must get 1 record for "nameA".
    session.begin();
    try (var stream = indexName.getRids(session, "Jack")) {
      assertEquals(1, stream.count());
    }
    session.rollback();
  }

  @Test
  @Order(8)
  void testUnderscoreField() {
    session.begin();
    EntityImpl vDoc = session.newInstance("Profile");
    vDoc.setPropertyInChain("nick", "MostFamousJack")
        .setPropertyInChain("name", "Kiefer")
        .setPropertyInChain("surname", "Sutherland")
        .getOrCreateEmbeddedList("tag_list").addAll(List.of(
            "actor", "myth"));

    session.commit();

    final var result =
        session
            .query("select from Profile where name = 'Kiefer' and tag_list.size() > 0 ")
            .toList();

    assertEquals(1, result.size());
  }

  @Test
  @Order(9)
  void testUpdateLazyDirtyPropagation() {
    var iterator = (Iterator<EntityImpl>) session.<EntityImpl>browseCollection("Profile");
    session.forEachInTx(iterator, (transaction, rec) -> {
      assertFalse(rec.isDirty());
      Collection<?> followers = rec.getProperty("followers");
      if (followers != null && !followers.isEmpty()) {
        followers.remove(followers.iterator().next());
        assertTrue(rec.isDirty());
        return false;
      }
      return true;
    });
  }

  @Test
  @Order(10)
  void polymorphicQuery() {
    session.begin();
    final RecordAbstract newAccount = ((EntityImpl) session
        .newEntity("Account"))
        .setPropertyInChain("name", "testInheritanceName");

    session.commit();

    session.begin();
    var superClassResult = executeQuery("select from Account");

    var subClassResult = executeQuery("select from Company");

    assertFalse(superClassResult.isEmpty());
    assertFalse(subClassResult.isEmpty());
    assertTrue(superClassResult.size() >= subClassResult.size());

    // VERIFY ALL THE SUBCLASS RESULT ARE ALSO CONTAINED IN SUPERCLASS
    // RESULT
    for (var result : subClassResult) {
      assertTrue(superClassResult.contains(result));
    }
    session.commit();

    session.begin();
    var browsed = new HashSet<EntityImpl>();
    var entityIterator = session.browseClass("Account");
    while (entityIterator.hasNext()) {
      var d = entityIterator.next();
      assertFalse(browsed.contains(d));
      browsed.add(d);
    }
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<RecordAbstract>load(newAccount).delete();
    session.commit();
  }

  @Test
  @Order(11)
  void queryWithPositionalParameters() {
    addBarackObamaAndFollowers();

    final var result =
        session
            .query("select from Profile where name = ? and surname = ?", "Barack", "Obama")
            .toList();

    assertFalse(result.isEmpty());
  }

  @Test
  @Order(12)
  void testBrowseClassHasNextTwice() {
    session.begin();
    EntityImpl doc1 = null;
    //noinspection LoopStatementThatDoesntLoop
    for (Iterator<EntityImpl> itDoc = session.browseClass("Account"); itDoc.hasNext();) {
      doc1 = itDoc.next();
      break;
    }

    EntityImpl doc2 = null;
    //noinspection LoopStatementThatDoesntLoop
    for (Iterator<EntityImpl> itDoc = session.browseClass("Account"); itDoc.hasNext();) {
      // Intentionally call hasNext() again to verify idempotency
      @SuppressWarnings("unused")
      var unused = itDoc.hasNext();
      doc2 = itDoc.next();
      break;
    }

    assertEquals(doc1, doc2);
    session.commit();
  }

  @Test
  @Order(13)
  void nonPolymorphicQuery() {
    session.begin();
    final RecordAbstract newAccount =
        ((EntityImpl) session.newEntity("Account")).setPropertyInChain("name",
            "testInheritanceName");

    session.commit();

    session.begin();
    var allResult = executeQuery("select from Account");
    var superClassResult = executeQuery(
        "select from Account where @class = 'Account'");
    var subClassResult = executeQuery(
        "select from Company where @class = 'Company'");

    assertFalse(allResult.isEmpty());
    assertFalse(superClassResult.isEmpty());
    assertFalse(subClassResult.isEmpty());

    // VERIFY ALL THE SUBCLASS RESULT ARE NOT CONTAINED IN SUPERCLASS RESULT
    for (var r : subClassResult) {
      assertFalse(superClassResult.contains(r));
    }
    session.commit();

    session.begin();
    var browsed = new HashSet<EntityImpl>();
    var entityIterator = session.browseClass("Account");
    while (entityIterator.hasNext()) {
      var d = entityIterator.next();
      assertFalse(browsed.contains(d));
      browsed.add(d);
    }
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<RecordAbstract>load(newAccount).delete();
    session.commit();
  }

  @Test
  @Order(14)
  void testLazyLoadingByLink() {
    session.begin();
    var coreDoc = ((EntityImpl) session.newEntity());
    var linkDoc = ((EntityImpl) session.newEntity());

    coreDoc.setProperty("link", linkDoc);

    session.commit();

    session.begin();
    EntityImpl coreDocCopy = session.load(coreDoc.getIdentity());
    assertNotSame(coreDoc, coreDocCopy);

    coreDocCopy.setLazyLoad(false);
    assertTrue(coreDocCopy.getProperty("link") instanceof RecordIdInternal);
    coreDocCopy.setLazyLoad(true);
    assertTrue(coreDocCopy.getProperty("link") instanceof EntityImpl);
    session.commit();
  }

  @Test
  @Order(15)
  void testDbCacheUpdated() {
    session.createClassIfNotExist("Profile");
    session.begin();

    final var vDoc = session.newInstance("Profile");
    final var tags = session.newEmbeddedSet();
    tags.add("test");
    tags.add("yeah");

    vDoc.setPropertyInChain("nick", "Dexter")
        .setPropertyInChain("name", "Michael")
        .setPropertyInChain("surname", "Hall")
        .setProperty("tag_list", tags);

    session.executeInTx(transaction -> {

      final var result =
          session.query("select from Profile where name = 'Michael'").entityStream().toList();

      assertEquals(1, result.size());
      var dexter = result.getFirst();

      session.begin();
      var activeTx = session.getActiveTransaction();
      dexter = activeTx.load(dexter);
      ((Collection<String>) dexter.getProperty("tag_list")).add("actor");
    });

    session.executeInTx(transaction -> {
      final var result = session
          .query("select from Profile where tag_list contains 'actor' and tag_list contains 'test'")
          .toList();
      assertEquals(1, result.size());
    });
  }

  @SuppressWarnings("unchecked")
  @Test
  @Order(16)
  void testNestedEmbeddedMap() {
    session.begin();
    var newDoc = ((EntityImpl) session.newEntity());

    final Map<String, Map<String, ?>> map1 = session.newEmbeddedMap();
    newDoc.setProperty("map1", map1, PropertyType.EMBEDDEDMAP);

    final Map<String, Map<String, ?>> map2 = session.newEmbeddedMap();
    map1.put("map2", map2);

    final Map<String, ?> map3 = session.newEmbeddedMap();
    map2.put("map3", map3);

    final var rid = newDoc.getIdentity();
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    newDoc = activeTx.load(newDoc);
    final EntityImpl loadedDoc = session.load(rid);
    assertTrue(newDoc.hasSameContentOf(loadedDoc));

    assertTrue(loadedDoc.hasProperty("map1"));
    assertTrue(loadedDoc.getProperty("map1") instanceof Map<?, ?>);
    final Map<String, EntityImpl> loadedMap1 = loadedDoc.getProperty("map1");
    assertEquals(1, loadedMap1.size());

    assertTrue(loadedMap1.containsKey("map2"));
    assertTrue(loadedMap1.get("map2") instanceof Map<?, ?>);
    final var loadedMap2 = (Map<String, EntityImpl>) loadedMap1.get("map2");
    assertEquals(1, loadedMap2.size());

    assertTrue(loadedMap2.containsKey("map3"));
    assertTrue(loadedMap2.get("map3") instanceof Map<?, ?>);
    final var loadedMap3 = (Map<String, EntityImpl>) loadedMap2.get("map3");
    assertEquals(0, loadedMap3.size());
    session.commit();
  }

  @Test
  @Order(17)
  void commandWithPositionalParameters() {
    final var result = session
        .query("select from Profile where name = ? and surname = ?", "Barack", "Obama")
        .toList();

    assertFalse(result.isEmpty());
  }

  @Test
  @Order(18)
  void commandWithNamedParameters() {
    final var query = "select from Profile where name = :name and surname = :surname";

    var params = new HashMap<String, String>();
    params.put("name", "Barack");
    params.put("surname", "Obama");

    addBarackObamaAndFollowers();

    final var result = session.query(query, params).toList();
    assertFalse(result.isEmpty());
  }

  @Test
  @Order(19)
  void commandWrongParameterNames() {
    session.executeInTx(
        transaction -> {
          try {
            EntityImpl doc = session.newInstance();
            doc.setProperty("a:b", 10);
            fail();
          } catch (IllegalArgumentException e) {
            assertTrue(true);
          }
        });

    session.executeInTx(
        transaction -> {
          try {
            EntityImpl doc = session.newInstance();
            doc.setProperty("a,b", 10);
            fail();
          } catch (IllegalArgumentException e) {
            assertTrue(true);
          }
        });
  }

  @Test
  @Order(20)
  void queryWithNamedParameters() {
    addBarackObamaAndFollowers();

    final var query = "select from Profile where name = :name and surname = :surname";

    var params = new HashMap<String, String>();
    params.put("name", "Barack");
    params.put("surname", "Obama");

    final var result = session.query(query, params).toList();

    assertFalse(result.isEmpty());
  }

  @Test
  @Order(21)
  void testJSONMap() {
    session.createClassIfNotExist("JsonMapTest");
    session.executeInTx(tx -> {

      var emptyMapDoc = tx.newEntity("JsonMapTest");
      emptyMapDoc.updateFromJSON(
          """
              {
                 "emptyMap": {},
                 "nonEmptyMap": {"a": "b"}
              }
              """);
    });

    session.executeInTx(tx -> {
      final var object = tx.query("SELECT FROM JsonMapTest").toList().getFirst();

      assertThat(object.getEmbeddedMap("emptyMap")).isEqualTo(Map.of());
      assertThat(object.getEmbeddedMap("nonEmptyMap")).isEqualTo(Map.of("a", "b"));
    });
  }

  @Test
  @Order(22)
  void testJSONLinkd() {
    session.createClassIfNotExist("PersonTest");
    session.begin();
    var jaimeDoc = ((EntityImpl) session.newEntity("PersonTest"));
    jaimeDoc.setProperty("name", "jaime");

    var cerseiDoc = ((EntityImpl) session.newEntity("PersonTest"));
    cerseiDoc.updateFromJSON(
        """
            {
              "@type":"d",
              "name":"cersei",
              "valonqar":""" + jaimeDoc.toJSON("") + """
            }""");

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    jaimeDoc = activeTx.load(jaimeDoc);
    // The link between jamie and tyrion is not saved properly
    var tyrionDoc = ((EntityImpl) session.newEntity("PersonTest"));

    tyrionDoc.updateFromJSON(
        """
            {
                "@type":"d",
                "name":"tyrion",
                "emergency_contact":{
                  "contact":""" + jaimeDoc.toJSON() + """
                }
            }""");

    session.commit();

    session.executeInTx(tx -> {
      var entityIterator = session.browseClass("PersonTest");
      while (entityIterator.hasNext()) {
        var o = entityIterator.next();
        for (Identifiable id : tx.query(
            "match {class:PersonTest, where:(@rid =?)}.out(){as:record, maxDepth: 10000000} return record",
            o.getIdentity())
            .stream().map(result -> result.getLink("record")).toList()) {
          tx.load(id.getIdentity()).toJSON();
        }
      }
    });
  }

  @Test
  @Order(23)
  void testDirtyChild() {
    session.executeInTx(transaction -> {
      var parent = ((EntityImpl) session.newEntity());

      var child1 = ((EntityImpl) session.newEmbeddedEntity());
      parent.setProperty("child1", child1);

      assertTrue(child1.hasOwners());

      var child2 = ((EntityImpl) session.newEmbeddedEntity());
      child1.setProperty("child2", child2);

      assertTrue(child2.hasOwners());

      // BEFORE FIRST TOSTREAM
      assertTrue(parent.isDirty());
      parent.toStream();
      // AFTER TOSTREAM
      assertTrue(parent.isDirty());
      // CHANGE FIELDS VALUE (Automaticaly set dirty this child)
      child1.setPropertyInChain("child2", session.newEmbeddedEntity());
      assertTrue(parent.isDirty());
    });
  }

  @Test
  @Order(24)
  void testEncoding() {
    var s = " \r\n\t:;,.|+*/\\=!?[]()'\"";

    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("test", s);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    assertEquals(s, doc.getProperty("test"));
    session.commit();
  }

  @Test
  @Order(25)
  void testCreateEmbddedClassDocument() {
    final Schema schema = session.getMetadata().getSchema();

    var testClass1 = schema.createAbstractClass("testCreateEmbddedClass1");
    var testClass2 = schema.createClass("testCreateEmbddedClass2");
    testClass2.createProperty("testClass1Property", PropertyType.EMBEDDED, testClass1);

    testClass1 = schema.getClass("testCreateEmbddedClass1");
    testClass2 = schema.getClass("testCreateEmbddedClass2");

    session.begin();
    var testClass2Document = ((EntityImpl) session.newEntity(testClass2));
    testClass2Document.setPropertyInChain("testClass1Property",
        session.newEmbeddedEntity(testClass1));

    session.commit();

    session.begin();
    testClass2Document = session.load(testClass2Document.getIdentity());
    assertNotNull(testClass2Document);

    assertEquals(testClass2, testClass2Document.getSchemaClass());

    EntityImpl embeddedDoc = testClass2Document.getProperty("testClass1Property");
    assertEquals(testClass1, embeddedDoc.getSchemaClass());
    session.commit();
  }

  @Test
  @Order(26)
  void testRemoveAllLinkList() {
    var doc = session.computeInTx(transaction -> session.newEntity());

    session.begin();
    final var allDocs = session.newLinkList();
    for (var i = 0; i < 10; i++) {
      final var linkDoc = ((EntityImpl) session.newEntity());

      allDocs.add(linkDoc);
    }
    var activeTx3 = session.getActiveTransaction();
    doc = activeTx3.load(doc);
    doc.setProperty("linkList", allDocs);
    session.commit();

    session.begin();
    final List<Identifiable> docsToRemove = new ArrayList<>(allDocs.size() / 2);
    for (var i = 0; i < 5; i++) {
      docsToRemove.add(allDocs.get(i));
    }

    var activeTx2 = session.getActiveTransaction();
    doc = activeTx2.load(doc);
    List<Identifiable> linkList = doc.getProperty("linkList");
    linkList.removeAll(docsToRemove);

    assertEquals(5, linkList.size());

    for (var i = 5; i < 10; i++) {
      assertEquals(allDocs.get(i), linkList.get(i - 5));
    }

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    activeTx1.load(doc);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    linkList = doc.getProperty("linkList");
    assertEquals(5, linkList.size());

    for (var i = 5; i < 10; i++) {
      assertEquals(allDocs.get(i), linkList.get(i - 5));
    }
    session.commit();
  }

  @Test
  @Order(27)
  void testRemoveAndReload() {
    EntityImpl doc1;

    session.begin();
    {
      doc1 = ((EntityImpl) session.newEntity());

    }
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc1 = activeTx.load(doc1);
    session.delete(doc1);
    session.commit();

    session.begin();
    try {
      session.load(doc1.getIdentity());
      fail();
    } catch (RecordNotFoundException rnf) {
      // ignore
    }

    session.commit();
  }
}
