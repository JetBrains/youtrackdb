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
import java.util.Objects;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
@SuppressWarnings("unchecked")
public class SQLSelectTest extends AbstractSelectTest {

  @BeforeClass
  public void init() {
    generateCompanyData();

    addBarackObamaAndFollowers();
    generateProfiles();
  }

  @Test
  public void queryNoDirtyResultset() {
    var result = executeQuery("select from Profile ", session);
    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void queryNoWhere() {
    var result = executeQuery("select from Profile ", session);

    Assert.assertFalse(result.isEmpty());
  }

  @Test
  public void queryParentesisAsRight() {
    var result =
        executeQuery(
            "select from Profile where (name = 'Giuseppe' and ( name <> 'Napoleone' and nick is"
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
    var result = executeQuery("select count(*) from V");
    Assert.assertEquals(result.getFirst().<Object>getProperty("count(*)"), vertexesCount);
  }

  @Test
  public void querySchemaAndLike() {
    session.begin();
    var result1 =
        executeQuery("select * from Profile where name like 'Gi%'", session);

    for (var record : result1) {
      Assert.assertTrue(record.asEntityOrNull().getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertTrue(record.getProperty("name").toString().startsWith("Gi"));
    }

    var result2 =
        executeQuery("select * from Profile where name like '%epp%'", session);

    Assert.assertEquals(result1, result2);

    var result3 =
        executeQuery("select * from Profile where name like 'Gius%pe'", session);

    Assert.assertEquals(result1, result3);

    result1 = executeQuery("select * from Profile where name like '%Gi%'", session);

    for (var record : result1) {
      Assert.assertTrue(record.asEntityOrNull().getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertTrue(record.getProperty("name").toString().contains("Gi"));
    }

    result1 = executeQuery("select * from Profile where name like ?", session, "%Gi%");

    for (var record : result1) {
      Assert.assertTrue(record.asEntityOrNull().getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertTrue(record.getProperty("name").toString().contains("Gi"));
    }
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedSet() {
    session.begin();
    var tags = session.newEmbeddedSet();
    tags.add("smart");
    tags.add("nice");

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("tags", tags, PropertyType.EMBEDDEDSET);

    session.commit();

    session.begin();
    var resultset =
        executeQuery("select from Profile where tags CONTAINS 'smart'", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedList() {
    session.begin();
    var tags = session.newEmbeddedList();
    tags.add("smart");
    tags.add("nice");

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("tags", tags);

    session.commit();

    session.begin();
    var resultset =
        executeQuery("select from Profile where tags[0] = 'smart'", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    resultset =
        executeQuery("select from Profile where tags[0,1] CONTAINSALL ['smart','nice']", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInDocumentSet() {
    session.begin();
    var coll = session.newEmbeddedSet();
    var entity = session.newEmbeddedEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    coll.add(entity);

    entity = session.newEmbeddedEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    coll.add(entity);

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("coll", coll, PropertyType.EMBEDDEDSET);

    session.commit();

    session.begin();
    var resultset =
        executeQuery(
            "select coll[name='Jay'] as value from Profile where @rid = " + doc.getIdentity(),
            session);
    Assert.assertEquals(resultset.size(), 1);
    Assert.assertTrue(resultset.getFirst().getProperty("value") instanceof List<?>);
    Assert.assertEquals(
        resultset.getFirst().<Result>getEmbeddedList("value").getFirst().getProperty("name"),
        "Jay");
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInDocumentList() {
    session.begin();
    var coll = session.newEmbeddedList();

    var entity = session.newEmbeddedEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    coll.add(entity);

    entity = session.newEmbeddedEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    coll.add(entity);

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.setProperty("coll", coll, PropertyType.EMBEDDEDLIST);
    session.commit();

    session.begin();
    var resultset =
        executeQuery(
            "select coll[name='Jay'] as value from Profile where @rid = " + doc.getIdentity(),
            session);
    Assert.assertEquals(resultset.size(), 1);
    Assert.assertTrue(resultset.getFirst().getProperty("value") instanceof List<?>);
    Assert.assertEquals(
        ((Result) ((List<?>) resultset.getFirst().getProperty("value")).getFirst()).getProperty(
            "name"),
        "Jay");

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedMapClassic() {
    var customReferences = session.newEmbeddedMap();

    session.begin();
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

    session.begin();
    var resultset =
        executeQuery("select from Profile where customReferences CONTAINSKEY 'first'", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    resultset =
        executeQuery(
            "select from Profile where customReferences CONTAINSVALUE (name like 'Ja%')", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedMapNew() {
    session.begin();
    var customReferences = session.newEmbeddedMap();
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

    session.begin();
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
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  public void queryCollectionContainsLowerCaseSubStringIgnoreCase() {
    session.begin();
    var result =
        executeQuery(
            "select * from Profile where races contains"
                + " (name.toLowerCase(Locale.ENGLISH).subString(0,1) = 'e')",
            session);

    for (var record : result) {
      Assert.assertTrue(record.asEntityOrNull().getSchemaClassName().equalsIgnoreCase("profile"));
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
    session.commit();
  }

  @Test
  public void queryCollectionContainsInRecords() {
    session.begin();
    var record = ((EntityImpl) session.newEntity("Animal"));
    record.setProperty("name", "Cat");

    var races = session.newLinkSet();
    races.add(session.newInstance("AnimalRace").setPropertyInChain("name", "European"));
    races.add(session.newInstance("AnimalRace").setPropertyInChain("name", "Siamese"));
    record.setProperty("age", 10);
    record.setProperty("races", races);

    session.commit();

    session.begin();
    var result =
        executeQuery(
            "select * from Animal where races contains (name in ['European','Asiatic'])",
            session);

    var found = false;
    for (var i = 0; i < result.size() && !found; ++i) {
      record = (EntityImpl) result.get(i).asEntityOrNull();

      Assert.assertTrue(
          Objects.requireNonNull(record.getSchemaClassName()).equalsIgnoreCase("animal"));
      Assert.assertNotNull(record.getProperty("races"));

      races = record.getProperty("races");
      for (var race : races) {
        var transaction = session.getActiveTransaction();
        var transaction1 = session.getActiveTransaction();
        if (Objects.equals(transaction1.loadEntity(race).getProperty("name"), "European")
            || Objects.equals(transaction.loadEntity(race).getProperty("name"), "Asiatic")) {
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
      record = (EntityImpl) result.get(i).asEntityOrNull();

      Assert.assertTrue(
          Objects.requireNonNull(record.getSchemaClassName()).equalsIgnoreCase("animal"));
      Assert.assertNotNull(record.getProperty("races"));

      races = record.getProperty("races");
      for (var race : races) {
        var transaction = session.getActiveTransaction();
        var transaction1 = session.getActiveTransaction();
        if (Objects.equals(transaction1.loadEntity(race).getProperty("name"), "European")
            || Objects.equals(transaction.loadEntity(race).getProperty("name"), "Asiatic")) {
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
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(record).delete();
    session.commit();
  }

  @Test
  public void queryCollectionInNumbers() {
    session.begin();
    Entity record = session.newEntity("Animal");
    record.setProperty("name", "Cat");

    var rates = session.<Integer>newEmbeddedSet();
    rates.add(100);
    rates.add(200);
    record.setProperty("rates", rates);
    session.commit();

    session.begin();
    var result = executeQuery(
        "select * from Animal where rates in [100,200]");

    var found = false;
    for (var i = 0; i < result.size() && !found; ++i) {
      record = result.get(i).asEntityOrNull();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("animal"));
      Assert.assertNotNull(record.getProperty("rates"));

      rates = record.getProperty("rates");
      for (var rate : rates) {
        if (rate == 100 || rate == 105) {
          found = true;
          break;
        }
      }
    }
    Assert.assertTrue(found);
    session.commit();

    session.begin();
    result = executeQuery("select from Animal where rates in [200,10333]");

    found = false;
    for (var i = 0; i < result.size() && !found; ++i) {
      record = result.get(i).asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("animal"));
      Assert.assertNotNull(record.getProperty("rates"));

      rates = record.getProperty("rates");
      for (var rate : rates) {
        if (rate == 100 || rate == 105) {
          found = true;
          break;
        }
      }
    }
    Assert.assertTrue(found);
    session.commit();

    session.begin();
    result = executeQuery("select * from Animal where rates contains 500", session);
    Assert.assertEquals(result.size(), 0);
    session.commit();

    session.begin();
    result = executeQuery("select * from Animal where rates contains 100", session);
    Assert.assertEquals(result.size(), 1);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(record).delete();
    session.commit();
  }

  @Test
  public void queryWhereRidDirectMatching() {
    session.begin();
    var collectionId = session.getMetadata().getSchema().getClass("ORole").getCollectionIds()[0];
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
    Assert.assertEquals(result.getFirst().asEntityOrNull().getProperty("name"), "admin");
    session.commit();
  }

  @Test
  public void queryInAsParameter() {
    var roles = executeQuery("select from orole limit 1", session);

    var result = executeQuery("select * from OUser where roles in ?", session,
        roles);

    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void queryAnyOperator() {
    session.begin();
    var result = executeQuery("select from Profile where any() like 'N%'", session);

    Assert.assertFalse(result.isEmpty());

    for (var record : result) {
      Assert.assertTrue(record.asEntityOrNull().getSchemaClassName().equalsIgnoreCase("Profile"));

      var found = false;
      for (var fieldValue :
          record.getPropertyNames().stream().map(record::getProperty).toArray()) {
        if (fieldValue != null && !fieldValue.toString().isEmpty()
            && fieldValue.toString().toLowerCase(Locale.ROOT).charAt(0) == 'n') {
          found = true;
          break;
        }
      }
      Assert.assertTrue(found);
    }
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
    var result = executeQuery("select from Profile order by name desc");

    var page = executeQuery(
        "select from Profile order by name desc limit 10 skip 10");
    Assert.assertEquals(page.size(), 10);

    for (var i = 0; i < page.size(); ++i) {
      Assert.assertEquals(page.get(i), result.get(10 + i));
    }
    session.commit();
  }

  @Test
  public void queryOrderByAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile order by name limit 2");

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
    session.begin();
    var result =
        executeQuery("select from Profile where name is not null order by name");

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

  @Test(enabled = false)
  public void queryConditionsAndOrderBy() {
    var result =
        executeQuery("select from Profile where name is not null order by name desc, id asc");

    Assert.assertFalse(result.isEmpty());

    String lastName = null;
    for (var d : result) {
      if (lastName != null && d.getProperty("name") != null) {
        Assert.assertTrue(((String) d.getProperty("name")).compareTo(lastName) <= 0);
      }
      lastName = d.getProperty("name");
    }
  }

  @Test
  public void queryRecordTargetRid() {
    session.begin();
    var profileCollectionId =
        session.getMetadata().getSchema().getClass("Profile").getCollectionIds()[0];
    var positions = getValidPositions(profileCollectionId);

    var result =
        executeQuery("select from " + profileCollectionId + ":" + positions.getFirst());

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
    var result = executeQuery("select from Profile where @class = 'Profile'");

    Assert.assertFalse(result.isEmpty());

    for (var d : result) {
      Assert.assertEquals(d.asEntityOrNull().getSchemaClassName(), "Profile");
    }
  }

  @Test
  public void queryRecordAttribVersion() {
    var result = executeQuery("select from Profile where @version > 0", session);

    Assert.assertFalse(result.isEmpty());

    for (var d : result) {
      Assert.assertTrue(d.asEntityOrNull().getVersion() > 0);
    }
  }

  @Test
  public void queryRecordAttribType() {
    session.begin();
    var result = executeQuery("select from Profile where @type = 'entity'", session);

    Assert.assertFalse(result.isEmpty());
    session.commit();
  }

  @Test
  public void queryWrongOperator() {
    try {
      executeQuery(
          "select from Profile where name like.toLowerCase4(Locale.ENGLISH) '%Jay%'", session);
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
    session.begin();
    var tot = session.countClass("V");

    var count = 0;
    var entityIterator = session.browseClass("V");
    while (entityIterator.hasNext()) {
      entityIterator.next();
      count++;
    }

    Assert.assertEquals(count, tot);

    Assert.assertTrue(executeQuery("select from V", session).size() >= tot);
    session.commit();
  }

  @Test
  public void includeFields() {
    var query = "select expand( roles.include('name') ) from OUser";

    var resultset = executeQuery(query);

    for (var d : resultset) {
      Assert.assertTrue(d.getPropertyNames().size() <= 1);
      if (d.getPropertyNames().size() == 1) {
        Assert.assertTrue(d.hasProperty("name"));
      }
    }
  }

  @Test
  public void excludeFields() {
    var query = "select expand( roles.exclude('rules') ) from OUser";

    var resultset = executeQuery(query);

    for (var d : resultset) {
      Assert.assertFalse(d.hasProperty("rules"));
      Assert.assertTrue(d.hasProperty("name"));
    }
  }

  @Test
  public void queryBetween() {
    session.begin();
    var result = executeQuery("select * from account where nr between 10 and 20");

    for (var record : result) {
      Assert.assertTrue(
          ((Integer) record.getProperty("nr")) >= 10 && ((Integer) record.getProperty("nr")) <= 20);
    }
    session.commit();
  }

  @Test
  public void queryParenthesisInStrings() {

    session.begin();
    session.execute("INSERT INTO account (name) VALUES ('test (demo)')");
    session.commit();

    session.begin();
    var result = executeQuery("select * from account where name = 'test (demo)'");

    Assert.assertEquals(result.size(), 1);

    for (var record : result) {
      Assert.assertEquals(record.getProperty("name"), "test (demo)");
    }
    session.commit();
  }

  @Test
  public void queryMathOperators() {
    session.begin();
    var result = executeQuery("select * from account where id < 3 + 4");
    Assert.assertFalse(result.isEmpty());
    for (var document : result) {
      Assert.assertTrue(((Number) document.getProperty("id")).intValue() < 3 + 4);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 10 - 3");
    Assert.assertFalse(result.isEmpty());
    for (var document : result) {
      Assert.assertTrue(((Number) document.getProperty("id")).intValue() < 10 - 3);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 3 * 2");
    Assert.assertFalse(result.isEmpty());
    for (var document : result) {
      Assert.assertTrue(((Number) document.getProperty("id")).intValue() < 3 << 1);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 120 / 20");
    Assert.assertFalse(result.isEmpty());
    for (var document : result) {
      Assert.assertTrue(((Number) document.getProperty("id")).intValue() < 120 / 20);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 27 % 10");
    Assert.assertFalse(result.isEmpty());
    for (var document : result) {
      Assert.assertTrue(((Number) document.getProperty("id")).intValue() < 27 % 10);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id = id * 1");
    Assert.assertFalse(result.isEmpty());
    var result2 = executeQuery("select count(*) as tot from account where id >= 0");
    Assert.assertEquals(result.size(), ((Number) result2.getFirst().getProperty("tot")).intValue());
    session.commit();
  }

  @Test
  public void testBetweenWithParameters() {
    session.begin();

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

    final List<Integer> resultsList = new ArrayList<>(Arrays.asList(4, 5, 6, 7));
    for (var record : result) {
      Assert.assertTrue(resultsList.remove(record.<Integer>getProperty("id")));
    }
    session.commit();
  }

  @Test
  public void testInWithParameters() {
    session.begin();

    final var result =
        executeQuery(
            "select * from Company where id in [?, ?, ?, ?] and salary is not null",
            session,
            4,
            5,
            6,
            7);

    Assert.assertEquals(result.size(), 4);

    final List<Integer> resultsList = new ArrayList<>(Arrays.asList(4, 5, 6, 7));
    for (var record : result) {
      Assert.assertTrue(resultsList.remove(record.<Integer>getProperty("id")));
    }
    session.commit();
  }

  @Test
  public void testEqualsNamedParameter() {

    Map<String, Object> params = new HashMap<>();
    params.put("id", 4);
    final var result =
        executeQuery("select * from Company where id = :id and salary is not null", params);

    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testQueryAsClass() {
    session.begin();

    var result =
        executeQuery("select from Account where addresses.@class in [ 'Address' ]");
    Assert.assertFalse(result.isEmpty());
    for (var d : result) {
      Assert.assertNotNull(d.getProperty("addresses"));
      Identifiable identifiable = ((Collection<Identifiable>) d.getProperty("addresses"))
          .iterator()
          .next();
      var transaction = session.getActiveTransaction();
      Assert.assertEquals(
          Objects.requireNonNull(
                  ((EntityImpl)
                      transaction.load(identifiable))
                      .getSchemaClass())
              .getName(),
          "Address");
    }
    session.commit();
  }

  @Test
  public void testQueryNotOperator() {
    final var tx = session.begin();

    var result =
        executeQuery("select from Account where not ( addresses.@class in [ 'Address' ] )");
    Assert.assertFalse(result.isEmpty());
    for (var d : result) {
      final var addresses = d.getLinkList("addresses");
      if (addresses != null && !addresses.isEmpty()) {

        for (var a : addresses) {
          Assert.assertNotEquals(tx.loadEntity(a).getSchemaClassName(), "Address");
        }
      }
    }
    session.commit();
  }

  public void testParams() {
    var test = session.getMetadata().getSchema().getClass("test");
    if (test == null) {
      test = session.getMetadata().getSchema().createClass("test");
      test.createProperty("f1", PropertyType.STRING);
      test.createProperty("f2", PropertyType.STRING);
    }
    session.begin();
    var document = ((EntityImpl) session.newEntity(test));
    document.setProperty("f1", "a");

    session.commit();

    session.begin();
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("p1", "a");
    executeQuery("select from test where (f1 = :p1)", parameters);
    executeQuery("select from test where f1 = :p1 and f2 = :p1", parameters);
    session.commit();
  }

  @Test
  public void queryInstanceOfOperator() {
    var result = executeQuery("select from Account");

    Assert.assertFalse(result.isEmpty());

    var result2 = executeQuery(
        "select from Account where @this instanceof 'Account'");

    Assert.assertEquals(result2.size(), result.size());

    var result3 = executeQuery(
        "select from Account where @class instanceof 'Account'");

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
            "select $names let $names = (select EXPAND( addresses.city ) from Account where"
                + " addresses.size() > 0 )");

    Assert.assertFalse(result2.isEmpty());
    Assert.assertTrue(result2.getFirst().getProperty("$names") instanceof Collection<?>);
    Assert.assertFalse(((Collection<?>) result2.getFirst().getProperty("$names")).isEmpty());
  }

  @Test
  public void subQueryLetAndIndexedWhere() {
    var result =
        executeQuery("select $now from OUser let $now = eval('42') where name = 'admin'");

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
        executeQuery(
            "select * from " + facClass.getName() + " where context = 'test' order by date",
            1);

    var smaller = Calendar.getInstance();
    smaller.setTime(result.getFirst().asEntityOrNull().getProperty("date"));
    Assert.assertEquals(smaller.get(Calendar.YEAR), oneYearAgo.get(Calendar.YEAR));
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from " + facClass.getName()
                + " where context = 'test' order by date DESC",
            1);

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

    session.begin();
    var odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "adda");
    odoc.setProperty("descr", "Adda");

    session.commit();

    session.begin();
    odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "lago_di_como");
    odoc.setProperty("descr", "Lago di Como");

    session.commit();

    session.begin();
    Map<String, Object> params = new HashMap<>();
    List<String> inputValues = new ArrayList<>();
    inputValues.add("lago_di_como");
    inputValues.add("lecco");
    params.put("place", inputValues);

    var result = executeQuery("select from place where id in :place", session,
        params);
    Assert.assertEquals(result.size(), 1);
    session.commit();

    session.getMetadata().getSchema().dropClass("Place");
  }

  @Test
  public void testSelectRidFromListParameter() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    placeClass.createProperty("id", PropertyType.STRING);
    placeClass.createProperty("descr", PropertyType.STRING);
    placeClass.createIndex("place_id_index", INDEX_TYPE.UNIQUE, "id");

    List<RID> inputValues = new ArrayList<>();

    session.begin();
    var odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "adda");
    odoc.setProperty("descr", "Adda");

    inputValues.add(odoc.getIdentity());

    odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "lago_di_como");
    odoc.setProperty("descr", "Lago di Como");

    session.commit();

    session.begin();
    inputValues.add(odoc.getIdentity());

    Map<String, Object> params = new HashMap<>();
    params.put("place", inputValues);

    var result =
        executeQuery("select from place where @rid in :place", session, params);
    Assert.assertEquals(result.size(), 2);
    session.commit();

    session.getMetadata().getSchema().dropClass("Place");
  }

  @Test
  public void testSelectRidInList() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    session.getMetadata().getSchema().createClass("FamousPlace", 1, placeClass);

    session.begin();
    var firstPlace = ((EntityImpl) session.newEntity("Place"));
    session.commit();

    session.begin();
    var secondPlace = ((EntityImpl) session.newEntity("Place"));
    session.commit();

    session.begin();
    var famousPlace = ((EntityImpl) session.newEntity("FamousPlace"));
    session.commit();

    RID secondPlaceId = secondPlace.getIdentity();
    RID famousPlaceId = famousPlace.getIdentity();
    // if one of these two asserts fails, the test will be meaningless.
    Assert.assertTrue(secondPlaceId.getCollectionId() < famousPlaceId.getCollectionId());
    Assert.assertTrue(
        secondPlaceId.getCollectionPosition() > famousPlaceId.getCollectionPosition());

    session.begin();
    var result =
        executeQuery(
            "select from Place where @rid in [" + secondPlaceId + "," + famousPlaceId + "]",
            session);
    Assert.assertEquals(result.size(), 2);
    session.commit();

    session.getMetadata().getSchema().dropClass("FamousPlace");
    session.getMetadata().getSchema().dropClass("Place");
  }

  @Test
  public void testMapKeys() {
    Map<String, Object> params = new HashMap<>();
    params.put("id", 4);

    final var result =
        executeQuery(
            "select * from company where id = :id and salary is not null", session, params);

    Assert.assertEquals(result.size(), 1);
  }

  public void queryOrderByRidDesc() {
    var result = executeQuery("select from OUser order by @rid desc", session);

    Assert.assertFalse(result.isEmpty());

    RID lastRid = null;
    for (var d : result) {
      var rid = d.getIdentity();
      if (lastRid != null) {
        Assert.assertTrue(rid.compareTo(lastRid) < 0);
      }
      lastRid = rid;
    }

    var res = executeQuery("explain select from OUser order by @rid desc").getFirst();
    Assert.assertNull(res.getProperty("orderByElapsed"));
  }

  public void testQueryParameterNotPersistent() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("test", "test");
    executeQuery("select from OUser where @rid = ?", doc);
    Assert.assertTrue(doc.isDirty());
    session.commit();
  }

  public void testQueryLetExecutedOnce() {
    session.begin();
    final var result =
        executeQuery(
            "select name, $counter as counter from OUser let $counter = eval(\"$counter +"
                + " 1\")");

    Assert.assertFalse(result.isEmpty());
    for (var r : result) {
      Assert.assertEquals(r.<Object>getProperty("counter"), 1);
    }
    session.commit();
  }

  @Test
  public void testMultipleCollectionsWithPagination() {
    session.getMetadata().getSchema().createClass("PersonMultipleCollections");
    try {
      Set<String> names =
          new HashSet<>(Arrays.asList("Luca", "Jill", "Sara", "Tania", "Gianluca", "Marco"));
      for (var n : names) {
        session.begin();
        EntityImpl entity = ((EntityImpl) session.newEntity("PersonMultipleCollections"));
        entity.setProperty("First", n);

        session.commit();
      }

      session.begin();
      var query = "select from PersonMultipleCollections where @rid > ? limit 2";
      var resultset = executeQuery(query,
          new RecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID));

      while (!resultset.isEmpty()) {
        final var last = resultset.getLast().getIdentity();

        for (var personDoc : resultset) {
          Assert.assertTrue(names.contains(personDoc.<String>getProperty("First")));
          Assert.assertTrue(names.remove(personDoc.<String>getProperty("First")));
        }

        resultset = executeQuery(query, last);
      }

      Assert.assertTrue(names.isEmpty());
      session.commit();

    } finally {
      session.getMetadata().getSchema().dropClass("PersonMultipleCollections");
    }
  }

  @Test
  public void testOutFilterInclude() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("TestOutFilterInclude", schema.getClass("V"));
    session.execute("create class linkedToOutFilterInclude extends E").close();

    session.begin();
    session.execute("insert into TestOutFilterInclude content { \"name\": \"one\" }").close();
    session.execute("insert into TestOutFilterInclude content { \"name\": \"two\" }").close();
    session
        .execute(
            "create edge linkedToOutFilterInclude from (select from TestOutFilterInclude where name"
                + " = 'one') to (select from TestOutFilterInclude where name = 'two')")
        .close();
    session.commit();

    final var result =
        executeQuery(
            "select"
                + " expand(out('linkedToOutFilterInclude')[@class='TestOutFilterInclude'].include('@rid'))"
                + " from TestOutFilterInclude where name = 'one'");

    Assert.assertEquals(result.size(), 1);

    for (var r : result) {
      Assert.assertNull(r.getProperty("name"));
    }
  }

  private List<Long> getValidPositions(int collectionId) {
    final List<Long> positions = new ArrayList<>();

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
  public void testExpandSkip() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    final var cls = schema.createClass("TestExpandSkip", v);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("TestExpandSkip.name", INDEX_TYPE.UNIQUE, "name");

    session.begin();
    session.execute("CREATE VERTEX TestExpandSkip set name = '1'").close();
    session.execute("CREATE VERTEX TestExpandSkip set name = '2'").close();
    session.execute("CREATE VERTEX TestExpandSkip set name = '3'").close();
    session.execute("CREATE VERTEX TestExpandSkip set name = '4'").close();

    session
        .execute(
            "CREATE EDGE E FROM (SELECT FROM TestExpandSkip WHERE name = '1') to (SELECT FROM"
                + " TestExpandSkip WHERE name <> '1')")
        .close();
    session.commit();

    var result = session.query(
        "select expand(out()) from TestExpandSkip where name = '1'");

    Assert.assertEquals(result.stream().count(), 3);

    Map<Object, Object> params = new HashMap<>();
    params.put("values", Arrays.asList("2", "3", "antani"));
    result =
        session.query(
            "select expand(out()[name in :values]) from TestExpandSkip where name = '1'", params);
    Assert.assertEquals(result.stream().count(), 2);

    result = session.query("select expand(out()) from TestExpandSkip where name = '1' skip 1");

    Assert.assertEquals(result.stream().count(), 2);

    result = session.query("select expand(out()) from TestExpandSkip where name = '1' skip 2");
    Assert.assertEquals(result.stream().count(), 1);

    result = session.query("select expand(out()) from TestExpandSkip where name = '1' skip 3");
    Assert.assertEquals(result.stream().count(), 0);

    result =
        session.query("select expand(out()) from TestExpandSkip where name = '1' skip 1 limit 1");
    Assert.assertEquals(result.stream().count(), 1);
  }

  @Test
  public void testPolymorphicEdges() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var e = schema.getClass("E");
    schema.createClass("TestPolymorphicEdges_V", v);
    final var e1 = schema.createClass("TestPolymorphicEdges_E1", e);
    schema.createClass("TestPolymorphicEdges_E2", e1);

    session.begin();
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
    session.commit();

    var result =
        session.query(
            "select expand(out('TestPolymorphicEdges_E1')) from TestPolymorphicEdges_V where name ="
                + " '1'");
    Assert.assertEquals(result.stream().count(), 2);

    result =
        session.query(
            "select expand(out('TestPolymorphicEdges_E2')) from TestPolymorphicEdges_V where name ="
                + " '1' ");
    Assert.assertEquals(result.stream().count(), 1);
  }

  @Test
  public void testSizeOfLink() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    schema.createClass("TestSizeOfLink", v);

    session.begin();
    session.execute("CREATE VERTEX TestSizeOfLink set name = '1'").close();
    session.execute("CREATE VERTEX TestSizeOfLink set name = '2'").close();
    session.execute("CREATE VERTEX TestSizeOfLink set name = '3'").close();
    session
        .execute(
            "CREATE EDGE E FROM (SELECT FROM TestSizeOfLink WHERE name = '1') to (SELECT FROM"
                + " TestSizeOfLink WHERE name <> '1')")
        .close();
    session.commit();

    var result =
        session.query(
            " select from (select from TestSizeOfLink where name = '1') where out()[name=2].size()"
                + " > 0");
    Assert.assertEquals(result.stream().count(), 1);
  }

  @Test
  public void testEmbeddedMapAndDotNotation() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    schema.createClass("EmbeddedMapAndDotNotation", v);

    session.begin();
    session.execute("CREATE VERTEX EmbeddedMapAndDotNotation set name = 'foo'").close();
    session
        .execute(
            "CREATE VERTEX EmbeddedMapAndDotNotation set data = {\"bar\": \"baz\", \"quux\": 1},"
                + " name = 'bar'")
        .close();
    session
        .execute(
            "CREATE EDGE E FROM (SELECT FROM EmbeddedMapAndDotNotation WHERE name = 'foo') to"
                + " (SELECT FROM EmbeddedMapAndDotNotation WHERE name = 'bar')")
        .close();
    session.commit();

    var result =
        executeQuery(
            " select out().data as result from (select from EmbeddedMapAndDotNotation where"
                + " name = 'foo')");
    Assert.assertEquals(result.size(), 1);
    var doc = result.getFirst();
    Assert.assertNotNull(doc);
    @SuppressWarnings("rawtypes")
    List list = doc.getProperty("result");
    Assert.assertEquals(list.size(), 1);
    var first = list.getFirst();
    Assert.assertTrue(first instanceof Map);
    //noinspection rawtypes
    Assert.assertEquals(((Map) first).get("bar"), "baz");
  }

  @Test
  public void testLetWithQuotedValue() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    schema.createClass("LetWithQuotedValue", v);
    session.begin();
    session.execute("CREATE VERTEX LetWithQuotedValue set name = \"\\\"foo\\\"\"").close();
    session.commit();

    var result =
        session.query(
            " select expand($a) let $a = (select from LetWithQuotedValue where name ="
                + " \"\\\"foo\\\"\")");
    Assert.assertEquals(result.stream().count(), 1);
  }

  @Test
  public void testNamedParams() {
    // issue #7236

    session.execute("create class testNamedParams extends V").close();
    session.execute("create class testNamedParams_permission extends V").close();
    session.execute("create class testNamedParams_HasPermission extends E").close();

    session.begin();
    session.execute("insert into testNamedParams_permission set type = ['USER']").close();
    session.execute("insert into testNamedParams set login = 20").close();
    session
        .execute(
            "CREATE EDGE testNamedParams_HasPermission from (select from testNamedParams) to"
                + " (select from testNamedParams_permission)")
        .close();
    session.commit();

    Map<String, Object> params = new HashMap<>();
    params.put("key", 10);
    params.put("permissions", new String[]{"USER"});
    params.put("limit", 1);
    var results =
        executeQuery(
            "SELECT *, out('testNamedParams_HasPermission').type as permissions FROM"
                + " testNamedParams WHERE login >= :key AND"
                + " out('testNamedParams_HasPermission').type IN :permissions ORDER BY login"
                + " ASC LIMIT :limit",
            params);
    Assert.assertEquals(results.size(), 1);
  }

  @Test
  public void selectLikeFromSet() {
    var vertexClass = "SetContainer";
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var clazz = schema.createClass(vertexClass, v);
    session.begin();
    var container1 = session.newVertex(clazz);
    container1.setProperty("data", session.newEmbeddedSet(Set.of("hello", "world", "baobab")));
    var container2 = session.newVertex(vertexClass);
    container2.setProperty("data", session.newEmbeddedSet(Set.of("1hello", "2world", "baobab")));
    session.commit();

    var results = executeQuery("SELECT FROM SetContainer WHERE data LIKE 'wor%'");
    Assert.assertEquals(results.size(), 1);

    results = executeQuery("SELECT FROM SetContainer WHERE data LIKE 'bobo%'");
    Assert.assertEquals(results.size(), 0);

    results = executeQuery("SELECT FROM SetContainer WHERE data LIKE '%hell%'");
    Assert.assertEquals(results.size(), 2);
  }

  @Test
  public void selectLikeFromList() {
    var vertexClass = "ListContainer";
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var clazz = schema.createClass(vertexClass, v);
    session.begin();
    var container1 = session.newVertex(clazz);
    container1.setProperty("data", session.newEmbeddedList(List.of("hello", "world", "baobab")));
    var container2 = session.newVertex(vertexClass);
    container2.setProperty("data", session.newEmbeddedList(List.of("1hello", "2world", "baobab")));
    session.commit();
    var results = executeQuery("SELECT FROM ListContainer WHERE data LIKE 'wor%'");
    Assert.assertEquals(results.size(), 1);

    results = executeQuery("SELECT FROM ListContainer WHERE data LIKE 'bobo%'");
    Assert.assertEquals(results.size(), 0);

    results = executeQuery("SELECT FROM ListContainer WHERE data LIKE '%hell%'");
    Assert.assertEquals(results.size(), 2);
  }
}
