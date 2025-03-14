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

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
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
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;


@Test
public class CRUDDocumentPhysicalTest extends BaseDBTest {

  @Parameters(value = "remote")
  public CRUDDocumentPhysicalTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

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
    session.begin();
    session.command("delete from Account").close();
    session.commit();

    Assert.assertEquals(session.countClass("Account"), 0);

    fillInAccountData();

    session.begin();
    session.command("delete from Profile").close();
    session.commit();

    Assert.assertEquals(session.countClass("Profile"), 0);

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

    var it = session.browseClass("Account", false, false);
    while (it.hasNext()) {
      var rec = it.next();

      if (rec != null) {
        var id = ((Number) rec.field("id")).intValue();
        Assert.assertTrue(ids.remove(id));
        Assert.assertEquals(rec.field("name"), "Gipsy");
        Assert.assertEquals(rec.field("location"), "Italy");
        Assert.assertEquals(((Number) rec.field("testLong")).longValue(), 10000000000L);
        Assert.assertEquals(((Number) rec.field("salary")).intValue(), id + 300);
        Assert.assertNotNull(rec.field("extra"));
        Assert.assertEquals(rec.getByte("value"), (byte) 10);

        binary = rec.getBinary("binary");

        for (var b = 0; b < binary.length; ++b) {
          Assert.assertEquals(binary[b], (byte) b);
        }
      }
    }

    Assert.assertTrue(ids.isEmpty());
  }

  @Test(dependsOnMethods = "readAndBrowseDescendingAndCheckHoleUtilization")
  public void update() {
    var i = new int[1];

    var iterator = (Iterator<EntityImpl>) session.<EntityImpl>browseCluster("Account");
    session.forEachInTx(iterator, (session, rec) -> {
      if (i[0] % 2 == 0) {
        rec.field("location", "Spain");
      }

      rec.field("price", i[0] + 100);

      i[0]++;
    });
  }

  @Test(dependsOnMethods = "update")
  public void testUpdate() {
    var entityIterator = (Iterator<EntityImpl>) session.<EntityImpl>browseCluster("Account");
    while (entityIterator.hasNext()) {
      var rec = entityIterator.next();
      var price = ((Number) rec.field("price")).intValue();
      Assert.assertTrue(price - 100 >= 0);

      if ((price - 100) % 2 == 0) {
        Assert.assertEquals(rec.field("location"), "Spain");
      } else {
        Assert.assertEquals(rec.field("location"), "Italy");
      }
    }
  }

  @Test(dependsOnMethods = "testUpdate")
  public void testDoubleChanges() {
    checkEmbeddedDB();

    final Set<Integer> profileClusterIds =
        Arrays.stream(session.getMetadata().getSchema().getClass("Profile").getClusterIds())
            .asLongStream()
            .mapToObj(i -> (int) i)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);

    session.begin();
    EntityImpl vDoc = session.newInstance("Profile");
    vDoc.field("nick", "JayM1").field("name", "Jay").field("surname", "Miner");

    Assert.assertTrue(profileClusterIds.contains(vDoc.getIdentity().getClusterId()));

    vDoc = session.load(vDoc.getIdentity());
    vDoc.field("nick", "JayM2");
    vDoc.field("nick", "JayM3");

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
    vDoc.field("nick", "Jacky").field("name", "Jack").field("surname", "Tramiel");

    // add a new record with the same name "nameA".
    vDoc = session.newInstance("Profile");
    vDoc.field("nick", "Jack").field("name", "Jack").field("surname", "Bauer");

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
    session.delete(session.bindToSession(vDoc));
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
    vDoc.field("nick", "MostFamousJack")
        .field("name", "Kiefer")
        .field("surname", "Sutherland")
        .field("tag_list", new String[]{"actor", "myth"});

    session.commit();

    @SuppressWarnings("deprecation")
    List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from Profile where name = 'Kiefer' and tag_list.size() > 0 "))
            .execute(session);

    Assert.assertEquals(result.size(), 1);
  }

  public void testLazyLoadingByLink() {
    session.begin();
    var coreDoc = ((EntityImpl) session.newEntity());
    var linkDoc = ((EntityImpl) session.newEntity());

    coreDoc.field("link", linkDoc);

    session.commit();

    EntityImpl coreDocCopy = session.load(coreDoc.getIdentity());
    Assert.assertNotSame(coreDocCopy, coreDoc);

    coreDocCopy.setLazyLoad(false);
    Assert.assertTrue(coreDocCopy.field("link") instanceof RecordId);
    coreDocCopy.setLazyLoad(true);
    Assert.assertTrue(coreDocCopy.field("link") instanceof EntityImpl);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testDbCacheUpdated() {
    session.createClassIfNotExist("Profile");
    session.begin();

    EntityImpl vDoc = session.newInstance("Profile");
    Set<String> tags = new HashSet<>();
    tags.add("test");
    tags.add("yeah");

    vDoc.field("nick", "Dexter")
        .field("name", "Michael")
        .field("surname", "Hall")
        .field("tag_list", tags);

    session.commit();

    @SuppressWarnings("deprecation")
    List<EntityImpl> result =
        session
            .command(new SQLSynchQuery<EntityImpl>("select from Profile where name = 'Michael'"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);
    var dexter = result.getFirst();

    session.begin();
    dexter = session.bindToSession(dexter);
    ((Collection<String>) dexter.field("tag_list")).add("actor");

    dexter.setDirty();

    session.commit();

    //noinspection deprecation
    result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from Profile where tag_list contains 'actor' and tag_list contains"
                        + " 'test'"))
            .execute(session);
    Assert.assertEquals(result.size(), 1);
  }

  @Test(dependsOnMethods = "testUnderscoreField")
  public void testUpdateLazyDirtyPropagation() {
    var iterator = (Iterator<EntityImpl>) session.<EntityImpl>browseCluster("Profile");
    session.forEachInTx(iterator, (session, rec) -> {
      Assert.assertFalse(rec.isDirty());
      Collection<?> followers = rec.field("followers");
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

    final Map<String, HashMap<?, ?>> map1 = new HashMap<>();
    newDoc.field("map1", map1, PropertyType.EMBEDDEDMAP);

    final Map<String, HashMap<?, ?>> map2 = new HashMap<>();
    map1.put("map2", (HashMap<?, ?>) map2);

    final Map<String, HashMap<?, ?>> map3 = new HashMap<>();
    map2.put("map3", (HashMap<?, ?>) map3);

    final var rid = newDoc.getIdentity();
    session.commit();

    newDoc = session.bindToSession(newDoc);
    final EntityImpl loadedDoc = session.load(rid);
    Assert.assertTrue(newDoc.hasSameContentOf(loadedDoc));

    Assert.assertTrue(loadedDoc.containsField("map1"));
    Assert.assertTrue(loadedDoc.field("map1") instanceof Map<?, ?>);
    final Map<String, EntityImpl> loadedMap1 = loadedDoc.field("map1");
    Assert.assertEquals(loadedMap1.size(), 1);

    Assert.assertTrue(loadedMap1.containsKey("map2"));
    Assert.assertTrue(loadedMap1.get("map2") instanceof Map<?, ?>);
    final var loadedMap2 = (Map<String, EntityImpl>) loadedMap1.get("map2");
    Assert.assertEquals(loadedMap2.size(), 1);

    Assert.assertTrue(loadedMap2.containsKey("map3"));
    Assert.assertTrue(loadedMap2.get("map3") instanceof Map<?, ?>);
    final var loadedMap3 = (Map<String, EntityImpl>) loadedMap2.get("map3");
    Assert.assertEquals(loadedMap3.size(), 0);
  }

  @Test
  public void commandWithPositionalParameters() {
    final var query =
        new SQLSynchQuery<EntityImpl>("select from Profile where name = ? and surname = ?");
    @SuppressWarnings("deprecation")
    List<EntityImpl> result = session.command(query).execute(session, "Barack", "Obama");

    Assert.assertFalse(result.isEmpty());
  }

  @Test(dependsOnMethods = "testCreate")
  public void queryWithPositionalParameters() {
    addBarackObamaAndFollowers();

    final var query =
        new SQLSynchQuery<EntityImpl>("select from Profile where name = ? and surname = ?");
    @SuppressWarnings("deprecation")
    List<EntityImpl> result = session.query(query, "Barack", "Obama");

    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void commandWithNamedParameters() {
    final var query =
        new SQLSynchQuery<EntityImpl>(
            "select from Profile where name = :name and surname = :surname");

    var params = new HashMap<String, String>();
    params.put("name", "Barack");
    params.put("surname", "Obama");

    addBarackObamaAndFollowers();

    @SuppressWarnings("deprecation")
    List<EntityImpl> result = session.command(query).execute(session, params);
    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void commandWrongParameterNames() {
    EntityImpl doc = session.newInstance();
    session.executeInTx(
        () -> {
          try {
            doc.field("a:b", 10);
            Assert.fail();
          } catch (IllegalArgumentException e) {
            Assert.assertTrue(true);
          }
        });

    session.executeInTx(
        () -> {
          try {
            doc.field("a,b", 10);
            Assert.fail();
          } catch (IllegalArgumentException e) {
            Assert.assertTrue(true);
          }
        });
  }

  @Test
  public void queryWithNamedParameters() {
    addBarackObamaAndFollowers();

    final var query =
        new SQLSynchQuery<EntityImpl>(
            "select from Profile where name = :name and surname = :surname");

    var params = new HashMap<String, String>();
    params.put("name", "Barack");
    params.put("surname", "Obama");

    @SuppressWarnings("deprecation")
    List<EntityImpl> result = session.query(query, params);

    Assert.assertFalse(result.isEmpty());
  }

  public void testJSONLinkd() {
    session.createClassIfNotExist("PersonTest");
    session.begin();
    var jaimeDoc = ((EntityImpl) session.newEntity("PersonTest"));
    jaimeDoc.field("name", "jaime");

    var cerseiDoc = ((EntityImpl) session.newEntity("PersonTest"));
    cerseiDoc.updateFromJSON(
        "{\"@type\":\"d\",\"name\":\"cersei\",\"valonqar\":" + jaimeDoc.toJSON("") + "}");

    session.commit();

    session.begin();
    jaimeDoc = session.bindToSession(jaimeDoc);
    // The link between jamie and tyrion is not saved properly
    var tyrionDoc = ((EntityImpl) session.newEntity("PersonTest"));
    tyrionDoc.updateFromJSON(
        "{\"@type\":\"d\",\"name\":\"tyrion\",\"emergency_contact\":{\"relationship\":\"brother\",\"contact\":"
            + jaimeDoc.toJSON()
            + "}}");

    session.commit();

    var entityIterator = session.browseClass("PersonTest");
    while (entityIterator.hasNext()) {
      var o = entityIterator.next();
      for (Identifiable id : session.query("traverse * from " + o.getIdentity())
          .stream().map(
              Result::getIdentity).toList()) {
        session.load(id.getIdentity()).toJSON();
      }
    }
  }

  @Test
  public void testDirtyChild() {
    var parent = ((EntityImpl) session.newEntity());

    var child1 = ((EntityImpl) session.newEntity());
    child1.setOwner(parent);
    parent.field("child1", child1);

    Assert.assertTrue(child1.hasOwners());

    var child2 = ((EntityImpl) session.newEntity());
    child2.setOwner(child1);
    child1.field("child2", child2);

    Assert.assertTrue(child2.hasOwners());

    // BEFORE FIRST TOSTREAM
    Assert.assertTrue(parent.isDirty());
    parent.toStream();
    // AFTER TOSTREAM
    Assert.assertTrue(parent.isDirty());
    // CHANGE FIELDS VALUE (Automaticaly set dirty this child)
    child1.field("child2", session.newEntity());
    Assert.assertTrue(parent.isDirty());
  }

  public void testEncoding() {
    var s = " \r\n\t:;,.|+*/\\=!?[]()'\"";

    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.field("test", s);

    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertEquals(doc.field("test"), s);
  }

  @Test(dependsOnMethods = "create")
  public void polymorphicQuery() {
    session.begin();
    final RecordAbstract newAccount =
        ((EntityImpl) session.newEntity("Account")).field("name", "testInheritanceName");

    session.commit();

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

    var browsed = new HashSet<EntityImpl>();
    var entityIterator = session.browseClass("Account");
    while (entityIterator.hasNext()) {
      var d = entityIterator.next();
      Assert.assertFalse(browsed.contains(d));
      browsed.add(d);
    }

    session.begin();
    session.bindToSession(newAccount).delete();
    session.commit();
  }

  @Test(dependsOnMethods = "testCreate")
  public void testBrowseClassHasNextTwice() {
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
  }

  @Test(dependsOnMethods = "testCreate")
  public void nonPolymorphicQuery() {
    session.begin();
    final RecordAbstract newAccount =
        ((EntityImpl) session.newEntity("Account")).field("name", "testInheritanceName");

    session.commit();

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

    var browsed = new HashSet<EntityImpl>();
    var entityIterator = session.browseClass("Account");
    while (entityIterator.hasNext()) {
      var d = entityIterator.next();
      Assert.assertFalse(browsed.contains(d));
      browsed.add(d);
    }

    session.begin();
    session.bindToSession(newAccount).delete();
    session.commit();
  }

  @Test(dependsOnMethods = "testCreate")
  public void testEmbeddeDocumentInTx() {
    EntityImpl bank = session.newInstance("Account");
    session.begin();

    bank.field("Name", "MyBank");

    EntityImpl bank2 = session.newInstance("Account");
    bank.field("embedded", bank2, PropertyType.EMBEDDED);

    session.commit();

    session.close();

    session = acquireSession();

    session.begin();
    bank = session.bindToSession(bank);
    Assert.assertTrue(((EntityImpl) bank.field("embedded")).isEmbedded());
    Assert.assertFalse(((EntityImpl) bank.field("embedded")).getIdentity().isPersistent());
    session.rollback();

    session.begin();
    session.bindToSession(bank).delete();
    session.commit();
  }

  @Test(dependsOnMethods = "testCreate")
  public void testUpdateInChain() {
    session.begin();
    EntityImpl bank = session.newInstance("Account");
    bank.field("name", "MyBankChained");

    // EMBEDDED
    var embedded = session.newInstance("Account")
        .field("name", "embedded1");
    bank.field("embedded", embedded, PropertyType.EMBEDDED);

    var embeddeds =
        new EntityImpl[]{
            session.newInstance("Account").field("name", "embedded2"),
            session.newInstance("Account").field("name", "embedded3")
        };
    bank.field("embeddeds", embeddeds, PropertyType.EMBEDDEDLIST);

    // LINKED
    var linked = session.newInstance("Account").field("name", "linked1");
    bank.field("linked", linked);

    var linkeds =
        new EntityImpl[]{
            session.newInstance("Account").field("name", "linked2"),
            session.newInstance("Account").field("name", "linked3")
        };
    bank.field("linkeds", linkeds, PropertyType.LINKLIST);

    session.commit();

    session.close();
    session = acquireSession();

    session.begin();
    bank = session.bindToSession(bank);
    var changedDoc1 = bank.field("embedded.total", 100);
    // MUST CHANGE THE PARENT DOC BECAUSE IT'S EMBEDDED
    Assert.assertEquals(changedDoc1.field("name"), "MyBankChained");
    Assert.assertEquals(changedDoc1.<Object>field("embedded.total"), 100);

    var changedDoc2 = bank.field("embeddeds.total", 200);
    // MUST CHANGE THE PARENT DOC BECAUSE IT'S EMBEDDED
    Assert.assertEquals(changedDoc2.field("name"), "MyBankChained");

    Collection<Integer> intEmbeddeds = changedDoc2.field("embeddeds.total");
    for (var e : intEmbeddeds) {
      Assert.assertEquals(e.intValue(), 200);
    }

    var changedDoc3 = bank.field("linked.total", 300);
    // MUST CHANGE THE LINKED DOCUMENT
    Assert.assertEquals(changedDoc3.field("name"), "linked1");
    Assert.assertEquals(changedDoc3.<Object>field("total"), 300);
    session.commit();

    session.begin();
    bank = session.bindToSession(bank);
    ((EntityImpl) bank.field("linked")).delete();
    //noinspection unchecked
    for (var l : (Collection<Identifiable>) bank.field("linkeds")) {
      l.getRecord(session).delete();
    }
    bank.delete();
    session.commit();
  }

  @Test(dependsOnMethods = "readAndBrowseDescendingAndCheckHoleUtilization")
  public void testUpdateNoVersionCheck() {
    var resultSet = executeQuery("select from Account");

    session.begin();
    var doc = (EntityImpl) resultSet.getFirst().asEntityOrNull();
    doc.field("name", "modified");
    var oldVersion = doc.getVersion();

    RecordInternal.setVersion(doc, -2);

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    Assert.assertEquals(doc.getVersion(), oldVersion);
    Assert.assertEquals(doc.field("name"), "modified");
    session.commit();
  }

  public void testCreateEmbddedClassDocument() {
    final Schema schema = session.getMetadata().getSchema();

    var testClass1 = schema.createClass("testCreateEmbddedClass1");
    var testClass2 = schema.createClass("testCreateEmbddedClass2");
    testClass2.createProperty("testClass1Property", PropertyType.EMBEDDED, testClass1);

    testClass1 = schema.getClass("testCreateEmbddedClass1");
    testClass2 = schema.getClass("testCreateEmbddedClass2");

    session.begin();
    var testClass2Document = ((EntityImpl) session.newEntity(testClass2));
    testClass2Document.field("testClass1Property", session.newEntity(testClass1));

    session.commit();

    testClass2Document = session.load(testClass2Document.getIdentity());
    Assert.assertNotNull(testClass2Document);

    Assert.assertEquals(testClass2Document.getSchemaClass(), testClass2);

    EntityImpl embeddedDoc = testClass2Document.field("testClass1Property");
    Assert.assertEquals(embeddedDoc.getSchemaClass(), testClass1);
  }

  public void testRemoveAllLinkList() {
    var doc = ((EntityImpl) session.newEntity());

    final List<EntityImpl> allDocs = new ArrayList<>();

    session.begin();
    for (var i = 0; i < 10; i++) {
      final var linkDoc = ((EntityImpl) session.newEntity());

      allDocs.add(linkDoc);
    }
    doc.field("linkList", allDocs);

    session.commit();

    session.begin();
    final List<EntityImpl> docsToRemove = new ArrayList<>(allDocs.size() / 2);
    for (var i = 0; i < 5; i++) {
      docsToRemove.add(allDocs.get(i));
    }

    doc = session.bindToSession(doc);
    List<Identifiable> linkList = doc.field("linkList");
    linkList.removeAll(docsToRemove);

    Assert.assertEquals(linkList.size(), 5);

    for (var i = 5; i < 10; i++) {
      Assert.assertEquals(linkList.get(i - 5), allDocs.get(i));
    }

    session.commit();

    session.begin();
    session.bindToSession(doc);

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    linkList = doc.field("linkList");
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
    doc1 = session.bindToSession(doc1);
    session.delete(doc1);
    session.commit();

    session.begin();
    try {
      session.load(doc1.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException rnf) {
      // ignore
    }

    session.commit();
  }

  @Test
  public void testAny() {
    session.command("create class TestExport").close();
    session.command("create property TestExport.anything ANY").close();

    session.begin();
    session.command("insert into TestExport set anything = 3").close();
    session.command("insert into TestExport set anything = 'Jay'").close();
    session.command("insert into TestExport set anything = 2.3").close();
    session.commit();

    var result = session.command("select count(*) from TestExport where anything = 3");
    Assert.assertNotNull(result);
    Assert.assertEquals(result.stream().count(), 1);

    result = session.command("select count(*) from TestExport where anything = 'Jay'");
    Assert.assertNotNull(result);
    Assert.assertEquals(result.stream().count(), 1);

    result = session.command("select count(*) from TestExport where anything = 2.3");
    Assert.assertNotNull(result);
    Assert.assertEquals(result.stream().count(), 1);
  }
}
