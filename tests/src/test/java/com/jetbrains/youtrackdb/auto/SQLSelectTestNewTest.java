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

import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * If some of the tests start to fail then check collection number in queries, e.g #7:1. It can be
 * because the order of collections could be affected due to adding or removing collection from
 * storage.
 */
@Test
public class SQLSelectTestNewTest extends AbstractSelectTest {

  private EntityImpl record;

  @BeforeClass
  public void init() {
    if (!session.getMetadata().getSchema().existsClass("Profile")) {
      session.getMetadata().getSchema().createClass("Profile", 1);

      for (var i = 0; i < 1000; ++i) {
        session.begin();
        session.newInstance("Profile").setPropertyInChain("test", i).setProperty("name", "N" + i);
        session.commit();
      }
    }

    if (!session.getMetadata().getSchema().existsClass("company")) {
      session.getMetadata().getSchema().createClass("company", 1);
      for (var i = 0; i < 20; ++i) {
        session.begin();
        session.newEntity("company").setProperty("id", i);

        session.commit();
      }
    }

    session.getMetadata().getSchema().getOrCreateClass("Account");
    session.begin();
    record = ((EntityImpl) session.newEntity());
    session.commit();
  }

  @Test
  public void queryNoDirtyResultset() {
    var result = executeQuery(" select from Profile ", session);

    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void queryNoWhere() {
    var result = executeQuery(" select from Profile ", session);
    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void queryParentesisAsRight() {
    var result =
        executeQuery(
            "  select from Profile where ( name = 'Giuseppe' and ( name <> 'Napoleone' and nick is"
                + " not null ))  ",
            session);

    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void querySingleAndDoubleQuotes() {
    var result = executeQuery("select from Profile where name = 'Giuseppe'",
        session);

    final var count = result.size();
    Assert.assertFalse(result.isEmpty());

    result = executeQuery("select from Profile where name = \"Giuseppe\"", session);
    Assert.assertFalse(result.isEmpty());
    Assert.assertEquals(result.size(), count);
  }

  @Test
  public void queryTwoParentesisConditions() {
    var result =
        executeQuery(
            "select from Profile  where ( name = 'Giuseppe' and nick is not null ) or ( name ="
                + " 'Napoleone' and nick is not null ) ",
            session);

    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void testQueryCount() {
    session.getMetadata().reload();
    final var vertexesCount = session.countClass("V");
    var result =
        session.query("select count(*) as count from V").toList();
    Assert.assertEquals(result.getFirst().<Object>getProperty("count"), vertexesCount);
  }

  @Test
  public void querySchemaAndLike() {
    session.begin();
    var result1 =
        executeQuery("select * from Profile where name like 'Gi%'", session);

    for (var value : result1) {
      record = (EntityImpl) value.asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertTrue(record.getProperty("name").toString().startsWith("Gi"));
    }

    var result2 =
        executeQuery("select * from Profile where name like '%epp%'", session);

    Assert.assertEquals(result1, result2);

    var result3 =
        executeQuery("select * from Profile where name like 'Gius%pe'", session);

    Assert.assertEquals(result1, result3);

    result1 = executeQuery("select * from Profile where name like '%Gi%'", session);

    for (var result : result1) {
      record = (EntityImpl) result.asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertTrue(record.getProperty("name").toString().contains("Gi"));
    }

    result1 = executeQuery("select * from Profile where name like ?", session, "%Gi%");

    for (var result : result1) {
      record = (EntityImpl) result.asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertTrue(record.getProperty("name").toString().contains("Gi"));
    }
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedSet() {
    session.begin();
    Set<String> tags = session.newEmbeddedSet();
    tags.add("smart");
    tags.add("nice");

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("tags", tags, PropertyType.EMBEDDEDSET);

    session.commit();

    var tx = session.begin();
    var resultset =
        executeQuery("select from Profile where tags CONTAINS 'smart'", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    tx.load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedList() {
    session.begin();

    List<String> tags = session.newEmbeddedList();
    tags.add("smart");
    tags.add("nice");

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("tags", tags);

    session.commit();

    var resultset =
        executeQuery("select from Profile where tags[0] = 'smart'", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    resultset =
        executeQuery("select from Profile where tags[0,1] CONTAINSALL ['smart','nice']", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    var tx = session.begin();
    tx.load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInDocumentSet() {

    session.begin();
    var coll = session.newLinkSet();
    var entity = session.newEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    coll.add(entity);

    entity = session.newEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    coll.add(entity);

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("coll", coll, PropertyType.LINKSET);

    session.commit();

    final var tx = session.begin();
    var resultset =
        executeQuery(
            "select coll[name='Jay'] as value from Profile where @rid = " + doc.getIdentity(),
            session);
    Assert.assertEquals(resultset.size(), 1);
    final var value = resultset.getFirst().getLinkList("value");
    Assert.assertEquals(value.size(), 1);

    Assert.assertEquals(tx.loadEntity(value.getFirst()).getProperty("name"), "Jay");

    tx.load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInDocumentList() {
    session.begin();
    var coll = session.newLinkList();
    var entity = session.newEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    coll.add(entity);

    entity = session.newEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    coll.add(entity);

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("coll", coll, PropertyType.LINKLIST);

    session.commit();

    final var tx = session.begin();
    var resultset =
        executeQuery(
            "select coll[name='Jay'] as value from Profile where @rid = " + doc.getIdentity(),
            session);
    Assert.assertEquals(resultset.size(), 1);
    //    Assert.assertEquals(resultset.get(0).field("value").getClass(), EntityImpl.class);
    var result = resultset.getFirst().getLinkList("value");
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(
        tx.loadEntity(result.getFirst()).getProperty("name"), "Jay");

    tx.load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedMapClassic() {
    session.begin();

    Map<String, Entity> customReferences = session.newEmbeddedMap();

    var entity = session.newEmbeddedEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    customReferences.put("first", entity);

    entity = session.newEmbeddedEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    customReferences.put("second", entity);

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("customReferences", customReferences, PropertyType.EMBEDDEDMAP);

    session.commit();

    final var tx = session.begin();
    var resultset =
        executeQuery("select from Profile where customReferences CONTAINSKEY 'first'", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    resultset =
        executeQuery(
            "select from Profile where customReferences CONTAINSVALUE ( name like 'Ja%')",
            session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    resultset =
        executeQuery(
            "select from Profile where customReferences['second']['name'] like 'Ja%'", session);
    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());
    resultset =
        executeQuery(
            "select customReferences['second', 'first'] as customReferences from Profile where"
                + " customReferences.size() = 2",
            session);
    Assert.assertEquals(resultset.size(), 1);

    Assert.assertTrue(resultset.getFirst().getProperty("customReferences") instanceof List);
    List<EntityImpl> customReferencesBack = resultset.getFirst().getProperty("customReferences");
    Assert.assertEquals(customReferencesBack.size(), 2);

    resultset =
        executeQuery(
            "select customReferences['second']['name'] from Profile where"
                + " customReferences['second']['name'] is not null",
            session);
    Assert.assertEquals(resultset.size(), 1);

    resultset =
        executeQuery(
            "select customReferences['second']['name'] as value from Profile where"
                + " customReferences['second']['name'] is not null",
            session);
    Assert.assertEquals(resultset.size(), 1);

    tx.load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedMapNew() {
    session.begin();
    Map<String, Entity> customReferences = session.newEmbeddedMap();

    var entity = session.newEmbeddedEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    customReferences.put("first", entity);

    entity = session.newEmbeddedEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    customReferences.put("second", entity);

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("customReferences", customReferences, PropertyType.EMBEDDEDMAP);

    session.commit();

    var tx = session.begin();
    var resultset =
        executeQuery(
            "select from Profile where customReferences.keys() CONTAINS 'first'", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    resultset =
        executeQuery(
            "select from Profile where customReferences.values() CONTAINS ( name like 'Ja%')",
            session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    tx.load(doc).delete();
    session.commit();
  }

  @Test
  public void queryCollectionContainsLowerCaseSubStringIgnoreCase() {
    var result =
        executeQuery(
            "select * from Profile where races contains"
                + " (name.toLowerCase(Locale.ENGLISH).subString(0,1) = 'e')",
            session);

    for (var value : result) {
      record = (EntityImpl) value.asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertNotNull(record.getProperty("races"));

      Collection<EntityImpl> races = record.getProperty("races");
      var found = false;
      for (var race : races) {
        if (((String) race.getProperty("name")).toLowerCase(Locale.ENGLISH).charAt(0) == 'e') {
          found = true;
          break;
        }
      }
      Assert.assertTrue(found);
    }
  }

  @Test
  public void queryCollectionContainsInRecords() {
    session.begin();
    record = ((EntityImpl) session.newEntity("Animal"));
    record.setProperty("name", "Cat");

    Collection<Identifiable> races = new HashSet<>();
    races.add(session.newInstance("AnimalRace").setPropertyInChain("name", "European"));
    races.add(session.newInstance("AnimalRace").setPropertyInChain("name", "Siamese"));
    record.setProperty("age", 10);
    record.setProperty("races", session.newLinkSet(races));

    session.commit();

    session.begin();
    var result =
        executeQuery(
            "select * from Animal where races contains (name in ['European','Asiatic'])",
            session);

    var found = false;
    for (var i = 0; i < result.size() && !found; ++i) {
      record = (EntityImpl) result.get(i).asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("animal"));
      Assert.assertNotNull(record.getProperty("races"));

      races = record.getProperty("races");
      for (var r : races) {
        var race = session.loadEntity(r.getIdentity());
        if (race.getProperty("name").equals("European") || race.getProperty("name")
            .equals("Asiatic")) {
          found = true;
          break;
        }
      }
    }
    Assert.assertTrue(found);
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races contains (name in ['Asiatic','European'])",
            session);

    found = false;
    for (var i = 0; i < result.size() && !found; ++i) {
      record = (EntityImpl) result.get(i).asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("animal"));
      Assert.assertNotNull(record.getProperty("races"));

      races = record.getProperty("races");
      for (var r : races) {
        var race = session.loadEntity(r.getIdentity());
        if (race.getProperty("name").equals("European") || race.getProperty("name")
            .equals("Asiatic")) {
          found = true;
          break;
        }
      }
    }
    Assert.assertTrue(found);
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races contains (name in ['aaa','bbb'])", session);
    Assert.assertEquals(result.size(), 0);
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races containsall (name in ['European','Asiatic'])",
            session);
    Assert.assertEquals(result.size(), 0);
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races containsall (name in ['European','Siamese'])",
            session);
    Assert.assertEquals(result.size(), 1);
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races containsall (age < 100) LIMIT 1000 SKIP 0",
            session);
    Assert.assertEquals(result.size(), 0);
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where not ( races contains (age < 100) ) LIMIT 20 SKIP 0",
            session);
    Assert.assertEquals(result.size(), 1);
    session.commit();

    session.begin();
    session.load(record.getIdentity()).delete();
    session.commit();
  }

  @Test
  public void queryCollectionInNumbers() {
    session.begin();
    record = ((EntityImpl) session.newEntity("Animal"));
    record.setProperty("name", "Cat");

    var rates = session.newEmbeddedSet();
    rates.add(100);
    rates.add(200);
    record.setProperty("rates", rates);
    session.commit();

    var result =
        executeQuery("select * from Animal where rates contains 500", session);
    Assert.assertEquals(result.size(), 0);

    result = executeQuery("select * from Animal where rates contains 100", session);
    Assert.assertEquals(result.size(), 1);

    session.begin();
    session.load(record.getIdentity()).delete();
    session.commit();
  }

  @Test
  public void queryWhereRidDirectMatching() {
    var collectionId = session.getMetadata().getSchema().getClass("ORole").getCollectionIds()[0];
    session.begin();
    var positions = getValidPositions(collectionId);

    var result =
        executeQuery(
            "select * from OUser where roles contains #" + collectionId + ":"
                + positions.getFirst(),
            session);

    Assert.assertEquals(result.size(), 1);
    session.commit();
  }

  @Test
  public void queryWhereInpreparred() {
    session.begin();
    var result =
        executeQuery("select * from OUser where name in [ :name ]", session, "admin");

    Assert.assertEquals(result.size(), 1);
    var entity = ((EntityImpl) result.getFirst().asEntity());
    Assert.assertEquals(entity.getProperty("name"), "admin");
    session.commit();
  }

  @Test
  public void queryAllOperator() {
    var result = executeQuery("select from Account where all() is null", session);

    Assert.assertEquals(result.size(), 0);
  }

  @Test
  public void queryOrderBy() {
    session.begin();
    var result = executeQuery("select from Profile order by name", session);

    Assert.assertFalse(result.isEmpty());

    String lastName = null;
    var isNullSegment = true; // NULL VALUES AT THE BEGINNING!
    for (var d : result) {
      final String fieldValue = d.getProperty("name");
      if (fieldValue != null) {
        isNullSegment = false;
      } else {
        Assert.assertTrue(isNullSegment);
      }

      if (lastName != null) {
        Assert.assertTrue(fieldValue.compareTo(lastName) >= 0);
      }
      lastName = fieldValue;
    }
    session.commit();
  }

  @Test
  public void queryOrderByWrongSyntax() {
    try {
      executeQuery("select from Profile order by name aaaa", session);
      Assert.fail();
    } catch (CommandSQLParsingException ignored) {
    }
  }

  @Test
  public void queryLimitOnly() {
    var result = executeQuery("select from Profile limit 1", session);

    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void querySkipOnly() {
    var result = executeQuery("select from Profile", session);
    var total = result.size();

    result = executeQuery("select from Profile skip 1", session);
    Assert.assertEquals(result.size(), total - 1);
  }

  @Test
  public void queryPaginationWithSkipAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile", session);

    var page = executeQuery("select from Profile skip 10 limit 10", session);
    Assert.assertEquals(page.size(), 10);

    for (var i = 0; i < page.size(); ++i) {
      Assert.assertEquals(page.get(i), result.get(10 + i));
    }
    session.commit();
  }

  @Test
  public void queryOffsetOnly() {
    var result = executeQuery("select from Profile", session);
    var total = result.size();

    result = executeQuery("select from Profile offset 1", session);
    Assert.assertEquals(result.size(), total - 1);
  }

  @Test
  public void queryPaginationWithOffsetAndLimit() {

    session.begin();

    for (int i = 0; i < 30; i++) {
      session.newEntity("Profile").setProperty("name", "Test" + i);
    }

    session.commit();

    session.begin();
    var result = executeQuery("select from Profile", session);

    var page = executeQuery("select from Profile offset 10 limit 10", session);
    Assert.assertEquals(page.size(), 10);

    for (var i = 0; i < page.size(); ++i) {
      Assert.assertEquals(page.get(i), result.get(10 + i));
    }
    session.commit();
  }

  @Test
  public void queryPaginationWithOrderBySkipAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile order by name", session);

    var page =
        executeQuery("select from Profile order by name limit 10 skip 10", session);
    Assert.assertEquals(page.size(), 10);

    for (var i = 0; i < page.size(); ++i) {
      Assert.assertEquals(page.get(i), result.get(10 + i));
    }
    session.commit();
  }

  @Test
  public void queryPaginationWithOrderByDescSkipAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile order by name desc", session);

    var page =
        executeQuery("select from Profile order by name desc limit 10 skip 10", session);
    Assert.assertEquals(page.size(), 10);

    for (var i = 0; i < page.size(); ++i) {
      Assert.assertEquals(page.get(i), result.get(10 + i));
    }
    session.commit();
  }

  @Test
  public void queryOrderByAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile order by name limit 2", session);

    Assert.assertTrue(result.size() <= 2);

    String lastName = null;
    for (var d : result) {
      if (lastName != null && d.getProperty("name") != null) {
        Assert.assertTrue(((String) d.getProperty("name")).compareTo(lastName) >= 0);
      }
      lastName = d.getProperty("name");
    }
    session.commit();
  }

  @Test
  public void queryConditionAndOrderBy() {

    addBarackObamaAndFollowers();
    addGaribaldiAndBonaparte();

    session.begin();
    var result =
        executeQuery("select from Profile where name is not null order by name", session);

    Assert.assertFalse(result.isEmpty());

    String lastName = null;
    for (var d : result) {
      if (lastName != null && d.getProperty("name") != null) {
        Assert.assertTrue(((String) d.getProperty("name")).compareTo(lastName) >= 0);
      }
      lastName = d.getProperty("name");
    }
    session.commit();
  }

  @Test
  public void queryConditionsAndOrderBy() {
    addGaribaldiAndBonaparte();
    addBarackObamaAndFollowers();

    session.begin();
    var result =
        executeQuery(
            "select from Profile where name is not null order by name desc, id asc", session);

    Assert.assertFalse(result.isEmpty());

    String lastName = null;
    for (var d : result) {
      if (lastName != null && d.getProperty("name") != null) {
        Assert.assertTrue(((String) d.getProperty("name")).compareTo(lastName) <= 0);
      }
      lastName = d.getProperty("name");
    }
    session.commit();
  }

  @Test
  public void queryRecordTargetRid() {
    session.begin();
    var profileCollectionId =
        session.getMetadata().getSchema().getClass("Profile").getCollectionIds()[0];
    var positions = getValidPositions(profileCollectionId);

    var result =
        executeQuery("select from " + profileCollectionId + ":" + positions.getFirst(), session);

    Assert.assertEquals(result.size(), 1);

    for (var d : result) {
      Assert.assertEquals(
          d.getIdentity().toString(), "#" + profileCollectionId + ":" + positions.getFirst());
    }
    session.commit();
  }

  @Test
  public void queryRecordTargetRids() {
    session.begin();
    var profileCollectionId =
        session.getMetadata().getSchema().getClass("Profile").getCollectionIds()[0];
    var positions = getValidPositions(profileCollectionId);

    var result =
        executeQuery(
            " select from ["
                + profileCollectionId
                + ":"
                + positions.get(0)
                + ", "
                + profileCollectionId
                + ":"
                + positions.get(1)
                + "]",
            session);

    Assert.assertEquals(result.size(), 2);

    Assert.assertEquals(
        result.get(0).getIdentity().toString(), "#" + profileCollectionId + ":" + positions.get(0));
    Assert.assertEquals(
        result.get(1).getIdentity().toString(), "#" + profileCollectionId + ":" + positions.get(1));
    session.commit();
  }

  @Test
  public void queryRecordAttribRid() {
    session.begin();

    var profileCollectionId =
        session.getMetadata().getSchema().getClass("Profile").getCollectionIds()[0];
    var postions = getValidPositions(profileCollectionId);

    var result =
        executeQuery(
            "select from Profile where @rid = #" + profileCollectionId + ":" + postions.getFirst(),
            session);

    Assert.assertEquals(result.size(), 1);

    for (var d : result) {
      Assert.assertEquals(
          d.getIdentity().toString(), "#" + profileCollectionId + ":" + postions.getFirst());
    }
    session.commit();
  }

  @Test
  public void queryRecordAttribClass() {
    var result = executeQuery("select from Profile where @class = 'Profile'",
        session);

    Assert.assertFalse(result.isEmpty());

    for (var d : result) {
      Assert.assertEquals(d.asEntity().getSchemaClassName(), "Profile");
    }
  }

  @Test
  public void queryRecordAttribVersion() {
    var result = executeQuery("select from Profile where @version > 0", session);

    Assert.assertFalse(result.isEmpty());

    for (var d : result) {
      Assert.assertTrue(d.asEntity().getVersion() > 0);
    }
  }

  @Test
  public void queryRecordAttribType() {
    session.begin();
    var result = executeQuery("select from Profile where @type = 'entity'",
        session);

    Assert.assertFalse(result.isEmpty());
    session.commit();
  }

  @Test
  public void queryWrongOperator() {
    try {
      executeQuery("select from Profile where name like.toLowerCase() '%Jay%'", session);
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(true);
    }
  }

  @Test
  public void queryEscaping() {
    executeQuery("select from Profile where name like '%\\'Jay%'", session);
  }

  @Test
  public void queryWithLimit() {
    Assert.assertEquals(executeQuery("select from Profile limit 3", session).size(), 3);
  }

  @SuppressWarnings("unused")
  @Test
  public void testRecordNumbers() {
    var tot = session.countClass("V");

    var count = 0;
    var recordIterator = session.browseClass("V");

    while (recordIterator.hasNext()) {
      recordIterator.next();
      count++;
    }

    Assert.assertEquals(count, tot);

    Assert.assertTrue(executeQuery("select from V", session).size() >= tot);
  }

  @Test
  public void queryWithManualPagination() {

    RID last = new ChangeableRecordId();
    var resultset =
        executeQuery("select from Profile where @rid > ? LIMIT 3", session, last);

    var iterationCount = 0;
    Assert.assertFalse(resultset.isEmpty());
    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getCollectionId() < 0
                || (d.getIdentity().getCollectionId() >= last.getCollectionId())
                && d.getIdentity().getCollectionPosition() > last.getCollectionPosition());
      }

      last = resultset.getLast().getIdentity();

      iterationCount++;
      resultset = executeQuery("select from Profile where @rid > ? LIMIT 3", session, last);
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAutomaticPagination() {
    final var query = "select from Profile LIMIT 3";
    RID last = new ChangeableRecordId();

    var resultset = session.query(query).toList();

    var iterationCount = 0;
    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getCollectionId() >= last.getCollectionId()
                && d.getIdentity().getCollectionPosition() > last.getCollectionPosition());
      }

      last = resultset.getLast().getIdentity();

      iterationCount++;
      resultset = session.query(query).toList();
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAutomaticPaginationWithWhere() {
    final var query = "select from Profile where followers.length() > 0 LIMIT 3";
    RID last = new ChangeableRecordId();

    var resultset = session.query(query).toList();

    var iterationCount = 0;

    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getCollectionId() >= last.getCollectionId()
                && d.getIdentity().getCollectionPosition() > last.getCollectionPosition());
      }

      last = resultset.getLast().getIdentity();

      // System.out.printf("\nIterating page %d, last record is %s", iterationCount, last);

      iterationCount++;
      resultset = session.query(query).toList();
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAutomaticPaginationWithWhereAndBindingVar() {
    final var query = "select from Profile where followers.length() > ? LIMIT 3";
    RID last = new ChangeableRecordId();

    var resultset = session.query(query, 0).toList();

    var iterationCount = 0;

    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getCollectionId() >= last.getCollectionId()
                && d.getIdentity().getCollectionPosition() > last.getCollectionPosition());
      }

      last = resultset.getLast().getIdentity();

      iterationCount++;
      resultset = session.query(query, 0).toList();
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAutomaticPaginationWithWhereAndBindingVarAtTheFirstQueryCall() {
    final var query = "select from Profile where followers.length() > ? LIMIT 3";
    RID last = new ChangeableRecordId();

    var resultset = session.query(query, 0).toList();

    var iterationCount = 0;

    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getCollectionId() >= last.getCollectionId()
                && d.getIdentity().getCollectionPosition() > last.getCollectionPosition());
      }

      last = resultset.getLast().getIdentity();

      iterationCount++;
      resultset = session.query(query, 0).toList();
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAbsenceOfAutomaticPaginationBecauseOfBindingVarReset() {
    final var query = "select from Profile where followers.length() > ? LIMIT 3";

    var resultset = session.query(query, -1).toList();

    final var firstRidFirstQuery = resultset.getFirst().getIdentity();

    resultset = session.query(query, -2).toList();

    final var firstRidSecondQueryQuery = resultset.getFirst().getIdentity();

    Assert.assertEquals(firstRidFirstQuery, firstRidSecondQueryQuery);
  }

  @Test
  public void includeFields() {
    final var query = "select expand( roles.include('name') ) from OUser";

    var resultset = session.query(query).toList();

    for (var d : resultset) {
      Assert.assertTrue(d.getPropertyNames().size() <= 1);
      if (d.getPropertyNames().size() == 1) {
        Assert.assertTrue(d.hasProperty("name"));
      }
    }
  }

  @Test
  public void excludeFields() {
    final var query = "select expand( roles.exclude('rules') ) from OUser";

    var resultset = session.query(query).toList();

    for (var d : resultset) {
      Assert.assertFalse(d.hasProperty("rules"));
    }
  }

  @Test
  public void excludeAttributes() {
    session.begin();
    final var query = "select expand( roles.exclude('@rid', '@class') ) from OUser";

    var resultset = session.query(query).toList();

    for (var d : resultset) {
      Assert.assertNull(d.getIdentity());
      Assert.assertNull(d.getProperty("@rid"));
      Assert.assertNull(d.getProperty("@class"));
    }
    session.commit();
  }

  @Test
  public void queryBetween() {
    var result =
        executeQuery("select * from account where nr between 10 and 20", session);

    for (var value : result) {
      record = (EntityImpl) value.asEntity();

      Assert.assertTrue(
          ((Integer) record.getProperty("nr")) >= 10 && ((Integer) record.getProperty("nr")) <= 20);
    }
  }

  @Test
  public void queryParenthesisInStrings() {
    session.begin();

    session.execute("INSERT INTO account (name) VALUES ('test (demo)')").close();

    var result =
        executeQuery("select * from account where name = 'test (demo)'", session);

    Assert.assertEquals(result.size(), 1);

    for (var value : result) {
      record = (EntityImpl) value.asEntity();
      Assert.assertEquals(record.getProperty("name"), "test (demo)");
    }
    session.commit();
  }

  @Test
  public void queryMathOperators() {
    session.begin();
    var result = executeQuery("select * from account where id < 3 + 4", session);
    Assert.assertFalse(result.isEmpty());
    for (var result3 : result) {
      Assert.assertTrue(((Number) result3.getProperty("id")).intValue() < 3 + 4);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 10 - 3", session);
    Assert.assertFalse(result.isEmpty());
    for (var result1 : result) {
      Assert.assertTrue(((Number) result1.getProperty("id")).intValue() < 10 - 3);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 3 * 2", session);
    Assert.assertFalse(result.isEmpty());
    for (var element : result) {
      Assert.assertTrue(((Number) element.getProperty("id")).intValue() < 3 << 1);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 120 / 20", session);
    Assert.assertFalse(result.isEmpty());
    for (var item : result) {
      Assert.assertTrue(((Number) item.getProperty("id")).intValue() < 120 / 20);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 27 % 10", session);
    Assert.assertFalse(result.isEmpty());
    for (var value : result) {
      Assert.assertTrue(((Number) value.getProperty("id")).intValue() < 27 % 10);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id = id * 1", session);
    Assert.assertFalse(result.isEmpty());
    session.commit();

    session.begin();
    var result2 =
        executeQuery("select count(*) as tot from account where id >= 0", session);
    Assert.assertEquals(result.size(), ((Number) result2.getFirst().getProperty("tot")).intValue());
    session.commit();
  }

  @Test
  public void testBetweenWithParameters() {

    final var result =
        executeQuery(
            "select * from company where id between ? and ? and salary is not null",
            session,
            4,
            7);

    System.out.println("testBetweenWithParameters:");
    for (var d : result) {
      System.out.println(d);
    }

    Assert.assertEquals(result.size(), 4, "Found: " + result);

    final List<Integer> resultsList = new ArrayList<Integer>(Arrays.asList(4, 5, 6, 7));
    for (var record : result) {
      Assert.assertTrue(resultsList.remove(record.<Integer>getProperty("id")));
    }
  }

  @Test
  public void testInWithParameters() {

    final var result =
        executeQuery(
            "select * from company where id in [?, ?, ?, ?] and salary is not null",
            session,
            4,
            5,
            6,
            7);

    Assert.assertEquals(result.size(), 4);

    final List<Integer> resultsList = new ArrayList<Integer>(Arrays.asList(4, 5, 6, 7));
    for (var record : result) {
      Assert.assertTrue(resultsList.remove(record.<Integer>getProperty("id")));
    }
  }

  @Test
  public void testEqualsNamedParameter() {

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("id", 4);
    final var result =
        executeQuery(
            "select * from company where id = :id and salary is not null", session, params);

    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testQueryAsClass() {

    var result =
        executeQuery("select from Account where addresses.@class in [ 'Address' ]", session);
    Assert.assertFalse(result.isEmpty());
    for (var d : result) {
      Assert.assertNotNull(d.getProperty("addresses"));
      var identifiable = (d.<Collection<Identifiable>>getProperty("addresses")).iterator()
          .next();
      var transaction = session.getActiveTransaction();
      Assert.assertEquals(
          ((EntityImpl)
              transaction.load(identifiable))
              .getSchemaClass()
              .getName(),
          "Address");
    }
  }

  @Test
  public void testQueryNotOperator() {

    var result =
        executeQuery(
            "select from Account where not ( addresses.@class in [ 'Address' ] )", session);
    Assert.assertFalse(result.isEmpty());
    for (var d : result) {
      var identifiable = (d.<Collection<Identifiable>>getProperty("addresses"))
          .iterator()
          .next();
      var transaction = session.getActiveTransaction();
      Assert.assertTrue(
          d.getProperty("addresses") == null
              || (d.<Collection<Identifiable>>getProperty("addresses")).isEmpty()
              || !((EntityImpl)
              transaction.load(identifiable))
              .getSchemaClass()
              .getName()
              .equals("Address"));
    }
  }

  @Test
  public void testSquareBracketsOnCondition() {
    var result =
        executeQuery(
            "select from Account where addresses[@class='Address'][city.country.name] ="
                + " 'Washington'",
            session);
    Assert.assertFalse(result.isEmpty());
    for (var d : result) {
      Assert.assertNotNull(d.getProperty("addresses"));
      var identifiable = (d.<Collection<Identifiable>>getProperty("addresses")).iterator()
          .next();
      var transaction = session.getActiveTransaction();
      Assert.assertEquals(
          ((EntityImpl)
              transaction.load(identifiable))
              .getSchemaClass()
              .getName(),
          "Address");
    }
  }

  public void testParams() {
    var test = session.getMetadata().getSchema().getClass("test");
    if (test == null) {
      test = session.getMetadata().getSchema().createClass("test");
      test.createProperty("f1", PropertyType.STRING);
      test.createProperty("f2", PropertyType.STRING);
    }
    var document = ((EntityImpl) session.newEntity(test));
    document.setProperty("f1", "a");

    session.begin();
    session.commit();

    Map<String, Object> parameters = new HashMap<String, Object>();
    parameters.put("p1", "a");
    session.query("select from test where (f1 = :p1)",
        parameters).close();
    session.query("select from test where f1 = :p1 and f2 = :p1",
        parameters).close();
  }

  @Test
  public void queryInstanceOfOperator() {
    fillInAccountData();
    var result = executeQuery("select from Account", session);

    Assert.assertFalse(result.isEmpty());

    var result2 =
        executeQuery("select from Account where @this instanceof 'Account'", session);

    Assert.assertEquals(result2.size(), result.size());

    var result3 =
        executeQuery("select from Account where @class instanceof 'Account'", session);

    Assert.assertEquals(result3.size(), result.size());
  }

  @Test
  public void subQuery() {
    var result =
        executeQuery(
            "select from Account where name in ( select name from Account where name is not null"
                + " limit 1 )",
            session);

    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void subQueryNoFrom() {
    var result2 =
        executeQuery(
            "select $names let $names = (select EXPAND( addresses.city ) as city from Account where"
                + " addresses.size() > 0 )",
            session);

    Assert.assertFalse(result2.isEmpty());
    Assert.assertTrue(result2.getFirst().getProperty("$names") instanceof Collection<?>);
    Assert.assertFalse(((Collection<?>) result2.getFirst().getProperty("$names")).isEmpty());
  }

  @Test
  public void subQueryLetAndIndexedWhere() {
    var result =
        executeQuery("select $now from OUser let $now = eval('42') where name = 'admin'", session);

    Assert.assertEquals(result.size(), 1);
    Assert.assertNotNull(result.getFirst().getProperty("$now"), result.getFirst().toString());
  }

  @Test
  public void queryOrderByWithLimit() {

    Schema schema = session.getMetadata().getSchema();
    var facClass = schema.getClass("FicheAppelCDI");
    if (facClass == null) {
      facClass = schema.createClass("FicheAppelCDI");
    }
    if (!facClass.existsProperty("date")) {
      facClass.createProperty("date", PropertyType.DATE);
    }

    final var currentYear = Calendar.getInstance();
    final var oneYearAgo = Calendar.getInstance();
    oneYearAgo.add(Calendar.YEAR, -1);

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity(facClass));
    doc1.setProperty("context", "test");
    doc1.setProperty("date", currentYear.getTime());

    var doc2 = ((EntityImpl) session.newEntity(facClass));
    doc2.setProperty("context", "test");
    doc2.setProperty("date", oneYearAgo.getTime());

    session.commit();

    session.begin();
    var result =
        session.query(
            "select * from " + facClass.getName() + " where context = 'test' order by date",
            1).toList();

    var smaller = Calendar.getInstance();
    smaller.setTime(result.getFirst().getProperty("date"));
    Assert.assertEquals(smaller.get(Calendar.YEAR), oneYearAgo.get(Calendar.YEAR));

    result =
        session.query(
            "select * from "
                + facClass.getName()
                + " where context = 'test' order by date DESC",
            1).toList();

    var bigger = Calendar.getInstance();
    bigger.setTime(result.getFirst().getProperty("date"));
    Assert.assertEquals(bigger.get(Calendar.YEAR), currentYear.get(Calendar.YEAR));
    session.commit();
  }

  @Test
  public void queryWithTwoRidInWhere() {
    session.begin();
    var collectionId = session.getCollectionIdByName("profile");

    var positions = getValidPositions(collectionId);

    final long minPos;
    final long maxPos;
    if (positions.get(5).compareTo(positions.get(25)) > 0) {
      minPos = positions.get(25);
      maxPos = positions.get(5);
    } else {
      minPos = positions.get(5);
      maxPos = positions.get(25);
    }

    var resultset =
        executeQuery(
            "select @rid.trim() as oid, name from Profile where (@rid in [#"
                + collectionId
                + ":"
                + positions.get(5)
                + "] or @rid in [#"
                + collectionId
                + ":"
                + positions.get(25)
                + "]) AND @rid > ? LIMIT 10000",
            session,
            new RecordId(collectionId, minPos));

    Assert.assertEquals(resultset.size(), 1);

    Assert.assertEquals(resultset.getFirst().getProperty("oid"),
        new RecordId(collectionId, maxPos).toString());
    session.commit();
  }

  @Test
  public void testSelectFromListParameter() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    placeClass.createProperty("id", PropertyType.STRING);
    placeClass.createProperty("descr", PropertyType.STRING);
    placeClass.createIndex("place_id_index", INDEX_TYPE.UNIQUE, "id");

    var odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "adda");
    odoc.setProperty("descr", "Adda");

    session.begin();
    session.commit();

    odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "lago_di_como");
    odoc.setProperty("descr", "Lago di Como");

    session.begin();
    session.commit();

    Map<String, Object> params = new HashMap<String, Object>();
    List<String> inputValues = new ArrayList<String>();
    inputValues.add("lago_di_como");
    inputValues.add("lecco");
    params.put("place", inputValues);

    var result = executeQuery("select from place where id in :place", session,
        params);
    Assert.assertEquals(result.size(), 1);

    session.getMetadata().getSchema().dropClass("Place");
  }

  @Test
  public void testSelectRidFromListParameter() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    placeClass.createProperty("id", PropertyType.STRING);
    placeClass.createProperty("descr", PropertyType.STRING);
    placeClass.createIndex("place_id_index", INDEX_TYPE.UNIQUE, "id");

    List<RID> inputValues = new ArrayList<RID>();

    var odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "adda");
    odoc.setProperty("descr", "Adda");

    session.begin();
    session.commit();

    inputValues.add(odoc.getIdentity());

    odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "lago_di_como");
    odoc.setProperty("descr", "Lago di Como");

    session.begin();
    session.commit();
    inputValues.add(odoc.getIdentity());

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("place", inputValues);

    var result =
        executeQuery("select from place where @rid in :place", session, params);
    Assert.assertEquals(result.size(), 2);

    session.getMetadata().getSchema().dropClass("Place");
  }

  @Test
  public void testSelectRidInList() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    session.getMetadata().getSchema().createClass("FamousPlace", 1, placeClass);

    var firstPlace = ((EntityImpl) session.newEntity("Place"));

    session.begin();
    var secondPlace = ((EntityImpl) session.newEntity("Place"));
    var famousPlace = ((EntityImpl) session.newEntity("FamousPlace"));
    session.commit();

    RID secondPlaceId = secondPlace.getIdentity();
    RID famousPlaceId = famousPlace.getIdentity();
    // if one of these two asserts fails, the test will be meaningless.
    Assert.assertTrue(secondPlaceId.getCollectionId() < famousPlaceId.getCollectionId());
    Assert.assertTrue(
        secondPlaceId.getCollectionPosition() > famousPlaceId.getCollectionPosition());

    var result =
        executeQuery(
            "select from Place where @rid in [" + secondPlaceId + "," + famousPlaceId + "]",
            session);
    Assert.assertEquals(result.size(), 2);

    session.getMetadata().getSchema().dropClass("FamousPlace");
    session.getMetadata().getSchema().dropClass("Place");
  }

  @Test
  public void testMapKeys() {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("id", 4);
    final var result =
        executeQuery(
            "select * from company where id = :id and salary is not null", session, params);

    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void queryOrderByRidDesc() {
    var resultSet = executeQuery("select from OUser order by @rid desc", session);

    Assert.assertFalse(resultSet.isEmpty());

    RID lastRid = null;
    for (var d : resultSet) {
      var rid = d.getIdentity();

      if (lastRid != null) {
        Assert.assertTrue(rid.compareTo(lastRid) < 0);
      }
      lastRid = rid;
    }

    var res =
        session.query("explain select from OUser order by @rid desc").findFirst(Result::detach);
    Assert.assertNull(res.getProperty("orderByElapsed"));
  }

  public void testQueryParameterNotPersistent() {
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("test", "test");
    session.query("select from OUser where @rid = ?", doc).close();
    Assert.assertTrue(doc.isDirty());
  }

  public void testQueryLetExecutedOnce() {
    final var result =
        session.query(

            "select name, $counter as counter from OUser let $counter = eval(\"$counter +"
                + " 1\")").toList();

    Assert.assertFalse(result.isEmpty());
    var i = 1;
    for (var r : result) {
      var entity = r.asEntity();
      Assert.assertEquals(entity.<Object>getProperty("counter"), i++);
    }
  }

  @Test
  public void testMultipleCollectionsWithPagination() throws Exception {
    final var cls = session.getMetadata().getSchema()
        .createClass("PersonMultipleCollections");
    try {
      Set<String> names =
          new HashSet<String>(Arrays.asList("Luca", "Jill", "Sara", "Tania", "Gianluca", "Marco"));
      for (var n : names) {
        session.begin();
        var entity = ((EntityImpl) session.newEntity("PersonMultipleCollections"));
        entity.setProperty("First", n);

        session.commit();
      }

      var query =
          "select from PersonMultipleCollections where @rid > ? limit 2";
      var resultset = session.query(query, new ChangeableRecordId()).toList();

      while (!resultset.isEmpty()) {
        final var last = resultset.getLast().getIdentity();
        for (var personDoc : resultset) {
          Assert.assertTrue(names.contains(personDoc.<String>getProperty("First")));
          Assert.assertTrue(names.remove(personDoc.<String>getProperty("First")));
        }

        resultset = session.query(query, last).toList();
      }

      Assert.assertTrue(names.isEmpty());

    } finally {
      session.getMetadata().getSchema().dropClass("PersonMultipleCollections");
    }
  }

  @Test
  public void testOutFilterInclude() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("TestOutFilterInclude", schema.getClass("V"));
    session.execute("create class linkedToOutFilterInclude extends E").close();
    session.execute("insert into TestOutFilterInclude content { \"name\": \"one\" }").close();
    session.execute("insert into TestOutFilterInclude content { \"name\": \"two\" }").close();
    session
        .execute(
            "create edge linkedToOutFilterInclude from (select from TestOutFilterInclude where name"
                + " = 'one') to (select from TestOutFilterInclude where name = 'two')")
        .close();

    final var result =
        session.query(

            "select"
                + " expand(out('linkedToOutFilterInclude')[@class='TestOutFilterInclude'].include('@rid'))"
                + " from TestOutFilterInclude where name = 'one'").toList();

    Assert.assertEquals(result.size(), 1);

    for (var r : result) {
      var entity = r.asEntity();
      Assert.assertNull(entity.getProperty("name"));
    }
  }

  private List<Long> getValidPositions(int collectionId) {
    final List<Long> positions = new ArrayList<Long>();

    final RecordIteratorCollection<EntityImpl> iteratorCollection =
        session.browseCollection(session.getCollectionNameById(collectionId));

    for (var i = 0; i < 100; i++) {
      if (!iteratorCollection.hasNext()) {
        break;
      }

      var doc = iteratorCollection.next();
      positions.add(doc.getIdentity().getCollectionPosition());
    }
    return positions;
  }

  @Test
  public void testBinaryCollectionSelect() {
    session.execute("create blob collection binarycollection").close();
    session.begin();
    session.newBlob(new byte[]{1, 2, 3});
    session.commit();

    var result =
        session.query("select from collection:binarycollection").toList();

    Assert.assertEquals(result.size(), 1);

    session.execute("delete from collection:binarycollection").close();

    result = session.query("select from collection:binarycollection").toList();

    Assert.assertEquals(result.size(), 0);
  }

  @Test
  public void testExpandSkip() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    final var cls = schema.createClass("TestExpandSkip", v);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("TestExpandSkip.name", INDEX_TYPE.UNIQUE, "name");
    session.execute("CREATE VERTEX TestExpandSkip set name = '1'").close();
    session.execute("CREATE VERTEX TestExpandSkip set name = '2'").close();
    session.execute("CREATE VERTEX TestExpandSkip set name = '3'").close();
    session.execute("CREATE VERTEX TestExpandSkip set name = '4'").close();

    session
        .execute(
            "CREATE EDGE E FROM (SELECT FROM TestExpandSkip WHERE name = '1') to (SELECT FROM"
                + " TestExpandSkip WHERE name <> '1')")
        .close();

    var result =
        session.query("select expand(out()) from TestExpandSkip where name = '1'").toList();
    Assert.assertEquals(result.size(), 3);

    Map<Object, Object> params = new HashMap<Object, Object>();
    params.put("values", Arrays.asList("2", "3", "antani"));
    result =
        session
            .query(
                "select expand(out()[name in :values]) from TestExpandSkip where name = '1'",
                params).toList();
    Assert.assertEquals(result.size(), 2);

    result =
        session.query("select expand(out()) from TestExpandSkip where name = '1' skip 1").toList();
    Assert.assertEquals(result.size(), 2);

    result =
        session.query("select expand(out()) from TestExpandSkip where name = '1' skip 2").toList();
    Assert.assertEquals(result.size(), 1);

    result =
        session.query("select expand(out()) from TestExpandSkip where name = '1' skip 3").toList();
    Assert.assertEquals(result.size(), 0);

    result =
        session
            .query("select expand(out()) from TestExpandSkip where name = '1' skip 1 limit 1")
            .toList();
    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testPolymorphicEdges() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var e = schema.getClass("E");
    final var v1 = schema.createClass("TestPolymorphicEdges_V", v);
    final var e1 = schema.createClass("TestPolymorphicEdges_E1", e);
    final var e2 = schema.createClass("TestPolymorphicEdges_E2", e1);

    session.execute("CREATE VERTEX TestPolymorphicEdges_V set name = '1'").close();
    session.execute("CREATE VERTEX TestPolymorphicEdges_V set name = '2'").close();
    session.execute("CREATE VERTEX TestPolymorphicEdges_V set name = '3'").close();

    session
        .execute(
            "CREATE EDGE TestPolymorphicEdges_E1 FROM (SELECT FROM TestPolymorphicEdges_V WHERE"
                + " name = '1') to (SELECT FROM TestPolymorphicEdges_V WHERE name = '2')")
        .close();
    session
        .execute(
            "CREATE EDGE TestPolymorphicEdges_E2 FROM (SELECT FROM TestPolymorphicEdges_V WHERE"
                + " name = '1') to (SELECT FROM TestPolymorphicEdges_V WHERE name = '3')")
        .close();

    var result =
        session
            .query(
                "select expand(out('TestPolymorphicEdges_E1')) from TestPolymorphicEdges_V where"
                    + " name = '1'")
            .toList();
    Assert.assertEquals(result.size(), 2);

    result =
        session
            .query(
                "select expand(out('TestPolymorphicEdges_E2')) from TestPolymorphicEdges_V where"
                    + " name = '1' ")
            .toList();
    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testSizeOfLink() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    final var cls = schema.createClass("TestSizeOfLink", v);
    session.execute("CREATE VERTEX TestSizeOfLink set name = '1'").close();
    session.execute("CREATE VERTEX TestSizeOfLink set name = '2'").close();
    session.execute("CREATE VERTEX TestSizeOfLink set name = '3'").close();
    session
        .execute(
            "CREATE EDGE E FROM (SELECT FROM TestSizeOfLink WHERE name = '1') to (SELECT FROM"
                + " TestSizeOfLink WHERE name <> '1')")
        .close();

    var result =
        session
            .query(
                " select from (select from TestSizeOfLink where name = '1') where"
                    + " out()[name=2].size() > 0")
            .toList();
    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testEmbeddedMapAndDotNotation() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    final var cls = schema.createClass("EmbeddedMapAndDotNotation", v);
    session.execute("CREATE VERTEX EmbeddedMapAndDotNotation set name = 'foo'").close();
    session
        .execute(
            "CREATE VERTEX EmbeddedMapAndDotNotation set data = {\"bar\": \"baz\", \"quux\":"
                + " 1}, name = 'bar'")
        .close();
    session
        .execute(
            "CREATE EDGE E FROM (SELECT FROM EmbeddedMapAndDotNotation WHERE name = 'foo') to"
                + " (SELECT FROM EmbeddedMapAndDotNotation WHERE name = 'bar')")
        .close();

    var result =
        session.query(

            " select out().data as result from (select from EmbeddedMapAndDotNotation where"
                + " name = 'foo')").toList();
    Assert.assertEquals(result.size(), 1);
    var identifiable = result.getFirst();
    var doc = identifiable.asEntity();
    Assert.assertNotNull(doc);
    List list = doc.getProperty("result");
    Assert.assertEquals(list.size(), 1);
    var first = list.getFirst();
    Assert.assertTrue(first instanceof Map);
    Assert.assertEquals(((Map) first).get("bar"), "baz");
  }

  @Test
  public void testLetWithQuotedValue() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    final var cls = schema.createClass("LetWithQuotedValue", v);
    session.execute("CREATE VERTEX LetWithQuotedValue set name = \"\\\"foo\\\"\"").close();

    var result =
        session.query(
            " select expand($a) let $a = (select from LetWithQuotedValue where name ="
                + " \"\\\"foo\\\"\")").toList();
    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testNestedProjection1() {
    var className = this.getClass().getSimpleName() + "_testNestedProjection1";
    session.execute("create class " + className).close();

    session.begin();
    var elem1 = session.newEntity(className);
    elem1.setProperty("name", "a");

    var elem2 = session.newEntity(className);
    elem2.setProperty("name", "b");
    elem2.setProperty("surname", "lkj");

    var elem3 = session.newEntity(className);
    elem3.setProperty("name", "c");

    var elem4 = session.newEntity(className);
    elem4.setProperty("name", "d");
    elem4.setProperty("elem1", elem1);
    elem4.setProperty("elem2", elem2);
    elem4.setProperty("elem3", elem3);
    session.commit();

    var result =
        session.query(
            "select name, elem1:{*}, elem2:{!surname} from " + className + " where name = 'd'");
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertTrue(item.getProperty("elem1") instanceof Result);
    Assert.assertEquals(((Result) item.getProperty("elem1")).getProperty("name"), "a");
    Assert.assertEquals(((Result) item.getProperty("elem2")).getProperty("name"), "b");
    Assert.assertNull(((Result) item.getProperty("elem2")).getProperty("surname"));
    result.close();
  }

  @Override
  protected List<Result> executeQuery(String sql, DatabaseSessionInternal db,
      Object... args) {
    var rs = db.query(sql, args);
    return rs.toList();
  }
}
