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
package com.jetbrains.youtrackdb.auto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
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
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


@Test
public class CRUDDocumentPhysicalTest extends BaseDBTest {
  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    createProfileClass();
    createCompanyClass();
    addBarackObamaAndFollowers();
  }

  @Test
  public void create() {
    session.executeInTx(transaction -> session.execute("delete from Account").close());

    session.executeInTx(transaction ->
        Assert.assertEquals(session.countClass("Account"), 0)
    );

    fillInAccountData();

    session.executeInTx(transaction -> session.execute("delete from Profile").close());

    session.executeInTx(transaction ->
        Assert.assertEquals(session.countClass("Profile"), 0)
    );

    generateCompanyData();
  }

  @Test(dependsOnMethods = "create")
  public void testCreate() {
    Assert.assertEquals(session.countClass("Account", false), TOT_RECORDS_ACCOUNT);
  }

  @Test(dependsOnMethods = "testCreate")
  public void readAndBrowseDescendingAndCheckHoleUtilization() {
    // BROWSE IN THE OPPOSITE ORDER
    byte[] binary;

    Set<Integer> ids = new HashSet<>();
    for (var i = 0; i < TOT_RECORDS_ACCOUNT; i++) {
      ids.add(i);
    }

    session.begin();
    final var idsForward = new ArrayList<RecordId>();
    final var idsBackward = new ArrayList<RecordId>();
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
      Assert.assertTrue(ids.remove(id));
      Assert.assertEquals(rec.getProperty("name"), "Gipsy");
      Assert.assertEquals(rec.getProperty("location"), "Italy");
      Assert.assertEquals(((Number) rec.getProperty("testLong")).longValue(), 10000000000L);
      Assert.assertEquals(((Number) rec.getProperty("salary")).intValue(), id + 300);
      Assert.assertNotNull(rec.getProperty("extra"));
      Assert.assertEquals(rec.getByte("value"), (byte) 10);

      binary = rec.getBinary("binary");

      for (var b = 0; b < binary.length; ++b) {
        Assert.assertEquals(binary[b], (byte) b);
      }
    }
    session.commit();

    assertThat(ids).isEmpty();
    assertThat(idsBackward).isEqualTo(idsForward.reversed());
  }

  @Test(dependsOnMethods = "readAndBrowseDescendingAndCheckHoleUtilization")
  public void update() {
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

  @Test(dependsOnMethods = "update")
  public void testUpdate() {
    session.begin();
    var entityIterator = (Iterator<EntityImpl>) session.<EntityImpl>browseCollection("Account");
    while (entityIterator.hasNext()) {
      var rec = entityIterator.next();
      var price = ((Number) rec.getProperty("price")).intValue();
      Assert.assertTrue(price - 100 >= 0);

      if ((price - 100) % 2 == 0) {
        Assert.assertEquals(rec.getProperty("location"), "Spain");
      } else {
        Assert.assertEquals(rec.getProperty("location"), "Italy");
      }
    }
    session.commit();
  }

  @Test(dependsOnMethods = "testUpdate")
  public void testDoubleChanges() {

    final Set<Integer> profileCollectionIds =
        Arrays.stream(session.getMetadata().getSchema().getClass("Profile").getCollectionIds())
            .asLongStream()
            .mapToObj(i -> (int) i)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);

    session.begin();
    EntityImpl vDoc = session.newInstance("Profile");
    vDoc.setPropertyInChain("nick", "JayM1").setPropertyInChain("name", "Jay")
        .setProperty("surname", "Miner");

    Assert.assertTrue(profileCollectionIds.contains(vDoc.getIdentity().getCollectionId()));

    vDoc = session.load(vDoc.getIdentity());
    vDoc.setProperty("nick", "JayM2");
    vDoc.setProperty("nick", "JayM3");

    session.commit();

    var indexes =
        session.getMetadata().getSchemaInternal().getClassInternal("Profile")
            .getPropertyInternal("nick")
            .getAllIndexesInternal();

    Assert.assertEquals(indexes.size(), 1);

    var indexDefinition = indexes.iterator().next();
    try (final var stream = indexDefinition.getRids(session, "JayM1")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    try (final var stream = indexDefinition.getRids(session, "JayM2")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    try (var stream = indexDefinition.getRids(session, "JayM3")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  @Test(dependsOnMethods = "testDoubleChanges")
  public void testMultiValues() {
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
    Assert.assertEquals(indexes.size(), 1);

    var indexName = indexes.iterator().next();
    // We must get 2 records for "nameA".
    try (var stream = indexName.getRids(session, "Jack")) {
      Assert.assertEquals(stream.count(), 2);
    }

    session.begin();
    // Remove this last record.
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<EntityImpl>load(vDoc));
    session.commit();

    // We must get 1 record for "nameA".
    try (var stream = indexName.getRids(session, "Jack")) {
      Assert.assertEquals(stream.count(), 1);
    }
  }

  @Test(dependsOnMethods = "testMultiValues")
  public void testUnderscoreField() {
    session.begin();
    EntityImpl vDoc = session.newInstance("Profile");
    vDoc.setPropertyInChain("nick", "MostFamousJack")
        .setPropertyInChain("name", "Kiefer")
        .setPropertyInChain("surname", "Sutherland")
        .getOrCreateEmbeddedList("tag_list").addAll(List.of(
            "actor", "myth"
        ));

    session.commit();

    final var result =
        session
            .query("select from Profile where name = 'Kiefer' and tag_list.size() > 0 ")
            .toList();

    Assert.assertEquals(result.size(), 1);
  }

  public void testLazyLoadingByLink() {
    session.begin();
    var coreDoc = ((EntityImpl) session.newEntity());
    var linkDoc = ((EntityImpl) session.newEntity());

    coreDoc.setProperty("link", linkDoc);

    session.commit();

    session.begin();
    EntityImpl coreDocCopy = session.load(coreDoc.getIdentity());
    Assert.assertNotSame(coreDocCopy, coreDoc);

    coreDocCopy.setLazyLoad(false);
    Assert.assertTrue(coreDocCopy.getProperty("link") instanceof RecordId);
    coreDocCopy.setLazyLoad(true);
    Assert.assertTrue(coreDocCopy.getProperty("link") instanceof EntityImpl);
    session.commit();
  }

  @Test
  public void testDbCacheUpdated() {
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

      Assert.assertEquals(result.size(), 1);
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
      Assert.assertEquals(result.size(), 1);
    });
  }

  @Test(dependsOnMethods = "testUnderscoreField")
  public void testUpdateLazyDirtyPropagation() {
    var iterator = (Iterator<EntityImpl>) session.<EntityImpl>browseCollection("Profile");
    session.forEachInTx(iterator, (transaction, rec) -> {
      Assert.assertFalse(rec.isDirty());
      Collection<?> followers = rec.getProperty("followers");
      if (followers != null && !followers.isEmpty()) {
        followers.remove(followers.iterator().next());
        Assert.assertTrue(rec.isDirty());
        return false;
      }
      return true;
    });
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testNestedEmbeddedMap() {
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
    Assert.assertTrue(newDoc.hasSameContentOf(loadedDoc));

    Assert.assertTrue(loadedDoc.hasProperty("map1"));
    Assert.assertTrue(loadedDoc.getProperty("map1") instanceof Map<?, ?>);
    final Map<String, EntityImpl> loadedMap1 = loadedDoc.getProperty("map1");
    Assert.assertEquals(loadedMap1.size(), 1);

    Assert.assertTrue(loadedMap1.containsKey("map2"));
    Assert.assertTrue(loadedMap1.get("map2") instanceof Map<?, ?>);
    final var loadedMap2 = (Map<String, EntityImpl>) loadedMap1.get("map2");
    Assert.assertEquals(loadedMap2.size(), 1);

    Assert.assertTrue(loadedMap2.containsKey("map3"));
    Assert.assertTrue(loadedMap2.get("map3") instanceof Map<?, ?>);
    final var loadedMap3 = (Map<String, EntityImpl>) loadedMap2.get("map3");
    Assert.assertEquals(loadedMap3.size(), 0);
    session.commit();
  }

  @Test
  public void commandWithPositionalParameters() {
    final var result = session
        .query("select from Profile where name = ? and surname = ?", "Barack", "Obama")
        .toList();

    Assert.assertFalse(result.isEmpty());
  }

  @Test(dependsOnMethods = "testCreate")
  public void queryWithPositionalParameters() {
    addBarackObamaAndFollowers();

    final var result =
        session
            .query("select from Profile where name = ? and surname = ?", "Barack", "Obama")
            .toList();

    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void commandWithNamedParameters() {
    final var query = "select from Profile where name = :name and surname = :surname";

    var params = new HashMap<String, String>();
    params.put("name", "Barack");
    params.put("surname", "Obama");

    addBarackObamaAndFollowers();

    final var result = session.query(query, params).toList();
    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void commandWrongParameterNames() {
    session.executeInTx(
        transaction -> {
          try {
            EntityImpl doc = session.newInstance();
            doc.setProperty("a:b", 10);
            fail();
          } catch (IllegalArgumentException e) {
            Assert.assertTrue(true);
          }
        });

    session.executeInTx(
        transaction -> {
          try {
            EntityImpl doc = session.newInstance();
            doc.setProperty("a,b", 10);
            fail();
          } catch (IllegalArgumentException e) {
            Assert.assertTrue(true);
          }
        });
  }

  @Test
  public void queryWithNamedParameters() {
    addBarackObamaAndFollowers();

    final var query = "select from Profile where name = :name and surname = :surname";

    var params = new HashMap<String, String>();
    params.put("name", "Barack");
    params.put("surname", "Obama");

    final var result = session.query(query, params).toList();

    Assert.assertFalse(result.isEmpty());
  }

  public void testJSONMap() {
    session.createClassIfNotExist("JsonMapTest");
    session.executeInTx(tx -> {

      var emptyMapDoc = tx.newEntity("JsonMapTest");
      emptyMapDoc.updateFromJSON(
          """
              {
                 "emptyMap": {},
                 "nonEmptyMap": {"a": "b"}
              }
              """
      );
    });

    session.executeInTx(tx -> {
      final var object = tx.query("SELECT FROM JsonMapTest").toList().getFirst();

      assertThat(object.getEmbeddedMap("emptyMap")).isEqualTo(Map.of());
      assertThat(object.getEmbeddedMap("nonEmptyMap")).isEqualTo(Map.of("a", "b"));
    });
  }

  public void testJSONLinkd() {
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
            }"""
    );

    session.commit();

    session.executeInTx(tx -> {
      var entityIterator = session.browseClass("PersonTest");
      while (entityIterator.hasNext()) {
        var o = entityIterator.next();
        for (Identifiable id :
            tx.query(
                    "match {class:PersonTest, where:(@rid =?)}.out(){as:record, maxDepth: 10000000} return record",
                    o.getIdentity())
                .stream().map(result -> result.getLink("record")).toList()) {
          tx.load(id.getIdentity()).toJSON();
        }
      }
    });
  }

  @Test
  public void testDirtyChild() {
    session.executeInTx(transaction -> {
      var parent = ((EntityImpl) session.newEntity());

      var child1 = ((EntityImpl) session.newEmbeddedEntity());
      parent.setProperty("child1", child1);

      Assert.assertTrue(child1.hasOwners());

      var child2 = ((EntityImpl) session.newEmbeddedEntity());
      child1.setProperty("child2", child2);

      Assert.assertTrue(child2.hasOwners());

      // BEFORE FIRST TOSTREAM
      Assert.assertTrue(parent.isDirty());
      parent.toStream();
      // AFTER TOSTREAM
      Assert.assertTrue(parent.isDirty());
      // CHANGE FIELDS VALUE (Automaticaly set dirty this child)
      child1.setPropertyInChain("child2", session.newEmbeddedEntity());
      Assert.assertTrue(parent.isDirty());
    });
  }

  public void testEncoding() {
    var s = " \r\n\t:;,.|+*/\\=!?[]()'\"";

    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("test", s);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    Assert.assertEquals(doc.getProperty("test"), s);
    session.commit();
  }

  @Test(dependsOnMethods = "create")
  public void polymorphicQuery() {
    // seems like tests interconnection
    // I don't know where I've broken it, but at this point Company is no longet a subclass of an account,
    // before my changes it was like this

    session.getClass("Company").setSuperClasses(List.of(session.getClass("Account")));

    session.begin();
    final RecordAbstract newAccount = ((EntityImpl) session
        .newEntity("Account"))
        .setPropertyInChain("name", "testInheritanceName");

    session.commit();

    session.begin();
    var superClassResult = executeQuery("select from Account");

    var subClassResult = executeQuery("select from Company");

    Assert.assertFalse(superClassResult.isEmpty());
    Assert.assertFalse(subClassResult.isEmpty());
    Assert.assertTrue(superClassResult.size() >= subClassResult.size());

    // VERIFY ALL THE SUBCLASS RESULT ARE ALSO CONTAINED IN SUPERCLASS
    // RESULT
    for (var result : subClassResult) {
      Assert.assertTrue(superClassResult.contains(result));
    }
    session.commit();

    session.begin();
    var browsed = new HashSet<EntityImpl>();
    var entityIterator = session.browseClass("Account");
    while (entityIterator.hasNext()) {
      var d = entityIterator.next();
      Assert.assertFalse(browsed.contains(d));
      browsed.add(d);
    }
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<RecordAbstract>load(newAccount).delete();
    session.commit();
  }

  @Test(dependsOnMethods = "testCreate")
  public void testBrowseClassHasNextTwice() {
    session.begin();
    EntityImpl doc1 = null;
    //noinspection LoopStatementThatDoesntLoop
    for (Iterator<EntityImpl> itDoc = session.browseClass("Account"); itDoc.hasNext(); ) {
      doc1 = itDoc.next();
      break;
    }

    EntityImpl doc2 = null;
    //noinspection LoopStatementThatDoesntLoop
    for (Iterator<EntityImpl> itDoc = session.browseClass("Account"); itDoc.hasNext(); ) {
      //noinspection ResultOfMethodCallIgnored
      itDoc.hasNext();
      doc2 = itDoc.next();
      break;
    }

    Assert.assertEquals(doc1, doc2);
    session.commit();
  }

  @Test(dependsOnMethods = "testCreate")
  public void nonPolymorphicQuery() {
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

    Assert.assertFalse(allResult.isEmpty());
    Assert.assertFalse(superClassResult.isEmpty());
    Assert.assertFalse(subClassResult.isEmpty());

    // VERIFY ALL THE SUBCLASS RESULT ARE NOT CONTAINED IN SUPERCLASS RESULT
    for (var r : subClassResult) {
      Assert.assertFalse(superClassResult.contains(r));
    }
    session.commit();

    session.begin();
    var browsed = new HashSet<EntityImpl>();
    var entityIterator = session.browseClass("Account");
    while (entityIterator.hasNext()) {
      var d = entityIterator.next();
      Assert.assertFalse(browsed.contains(d));
      browsed.add(d);
    }
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<RecordAbstract>load(newAccount).delete();
    session.commit();
  }

  @Test
  public void testCreateEmbddedClassDocument() {
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
    Assert.assertNotNull(testClass2Document);

    Assert.assertEquals(testClass2Document.getSchemaClass(), testClass2);

    EntityImpl embeddedDoc = testClass2Document.getProperty("testClass1Property");
    Assert.assertEquals(embeddedDoc.getSchemaClass(), testClass1);
    session.commit();
  }

  public void testRemoveAllLinkList() {
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

    Assert.assertEquals(linkList.size(), 5);

    for (var i = 5; i < 10; i++) {
      Assert.assertEquals(linkList.get(i - 5), allDocs.get(i));
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
    Assert.assertEquals(linkList.size(), 5);

    for (var i = 5; i < 10; i++) {
      Assert.assertEquals(linkList.get(i - 5), allDocs.get(i));
    }
    session.commit();
  }

  public void testRemoveAndReload() {
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
