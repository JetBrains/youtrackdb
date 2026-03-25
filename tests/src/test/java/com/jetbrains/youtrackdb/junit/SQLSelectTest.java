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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.query.Result;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class SQLSelectTest extends AbstractSelectJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();
    generateCompanyData();
    addBarackObamaAndFollowers();
    generateProfiles();
  }

  /**
   * Helper that appends a LIMIT clause and executes the query.
   * Called from queryOrderByWithLimit as executeQuery(sql, 1).
   */
  private List<Result> executeQuery(String sql, int limit) {
    return executeQuery(sql + " LIMIT " + limit);
  }

  @Test
  @Order(1)
  void queryNoDirtyResultset() {
    var result = executeQuery("select from Profile ", session);
    assertFalse(result.isEmpty());
  }

  @Test
  @Order(2)
  void queryNoWhere() {
    var result = executeQuery("select from Profile ", session);

    assertFalse(result.isEmpty());
  }

  @Test
  @Order(3)
  void queryParentesisAsRight() {
    var result =
        executeQuery(
            "select from Profile where (name = 'Giuseppe' and ( name <> 'Napoleone' and nick is"
                + " not null ))  ",
            session);

    assertFalse(result.isEmpty());
  }

  @Test
  @Order(4)
  void querySingleAndDoubleQuotes() {
    var result = executeQuery("select from Profile where name = 'Giuseppe'",
        session);

    final var count = result.size();
    assertFalse(result.isEmpty());

    result = executeQuery("select from Profile where name = \"Giuseppe\"", session);
    assertFalse(result.isEmpty());
    assertEquals(count, result.size());
  }

  @Test
  @Order(5)
  void queryTwoParentesisConditions() {
    var result =
        executeQuery(
            "select from Profile  where ( name = 'Giuseppe' and nick is not null ) or ( name ="
                + " 'Napoleone' and nick is not null ) ",
            session);

    assertFalse(result.isEmpty());
  }

  @Test
  @Order(6)
  void testQueryCount() {
    session.getMetadata().reload();
    session.begin();
    final var vertexesCount = session.countClass("V");
    var result = executeQuery("select count(*) from V");
    assertEquals(vertexesCount, result.getFirst().<Object>getProperty("count(*)"));
    session.rollback();
  }

  @Test
  @Order(7)
  void querySchemaAndLike() {
    session.begin();
    var result1 =
        executeQuery("select * from Profile where name like 'Gi%'", session);

    for (var record : result1) {
      assertTrue(record.asEntityOrNull().getSchemaClassName().equals("Profile"));
      assertTrue(record.getProperty("name").toString().startsWith("Gi"));
    }

    var result2 =
        executeQuery("select * from Profile where name like '%epp%'", session);

    assertEquals(result2, result1);

    var result3 =
        executeQuery("select * from Profile where name like 'Gius%pe'", session);

    assertEquals(result3, result1);

    result1 = executeQuery("select * from Profile where name like '%Gi%'", session);

    for (var record : result1) {
      assertTrue(record.asEntityOrNull().getSchemaClassName().equals("Profile"));
      assertTrue(record.getProperty("name").toString().contains("Gi"));
    }

    result1 = executeQuery("select * from Profile where name like ?", session, "%Gi%");

    for (var record : result1) {
      assertTrue(record.asEntityOrNull().getSchemaClassName().equals("Profile"));
      assertTrue(record.getProperty("name").toString().contains("Gi"));
    }
    session.commit();
  }

  @Test
  @Order(8)
  void queryContainsInEmbeddedSet() {
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

    assertEquals(1, resultset.size());
    assertEquals(doc.getIdentity(), resultset.getFirst().getIdentity());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  @Order(9)
  void queryContainsInEmbeddedList() {
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

    assertEquals(1, resultset.size());
    assertEquals(doc.getIdentity(), resultset.getFirst().getIdentity());

    resultset =
        executeQuery("select from Profile where tags[0,1] CONTAINSALL ['smart','nice']", session);

    assertEquals(1, resultset.size());
    assertEquals(doc.getIdentity(), resultset.getFirst().getIdentity());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  @Order(10)
  void queryContainsInDocumentSet() {
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
    assertEquals(1, resultset.size());
    assertTrue(resultset.getFirst().getProperty("value") instanceof List<?>);
    assertEquals(
        "Jay",
        resultset.getFirst().<Result>getEmbeddedList("value").getFirst().getProperty("name"));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  @Order(11)
  void queryContainsInDocumentList() {
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
    assertEquals(1, resultset.size());
    assertTrue(resultset.getFirst().getProperty("value") instanceof List<?>);
    assertEquals(
        "Jay",
        ((Result) ((List<?>) resultset.getFirst().getProperty("value")).getFirst()).getProperty(
            "name"));

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  @Order(12)
  void queryContainsInEmbeddedMapClassic() {
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

    assertEquals(1, resultset.size());
    assertEquals(doc.getIdentity(), resultset.getFirst().getIdentity());

    resultset =
        executeQuery(
            "select from Profile where customReferences CONTAINSVALUE (name like 'Ja%')", session);

    assertEquals(1, resultset.size());
    assertEquals(doc.getIdentity(), resultset.getFirst().getIdentity());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  @Order(13)
  void queryContainsInEmbeddedMapNew() {
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

    assertEquals(1, resultset.size());
    assertEquals(doc.getIdentity(), resultset.getFirst().getIdentity());

    resultset =
        executeQuery(
            "select from Profile where customReferences.values() CONTAINS ( name like 'Ja%')",
            session);

    assertEquals(1, resultset.size());
    assertEquals(doc.getIdentity(), resultset.getFirst().getIdentity());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  @Order(14)
  void queryCollectionContainsLowerCaseSubStringIgnoreCase() {
    session.begin();
    var result =
        executeQuery(
            "select * from Profile where races contains"
                + " (name.toLowerCase(Locale.ENGLISH).subString(0,1) = 'e')",
            session);

    for (var record : result) {
      assertTrue(record.asEntityOrNull().getSchemaClassName().equals("Profile"));
      assertNotNull(record.getProperty("races"));

      Collection<EntityImpl> races = record.getProperty("races");
      var found = false;
      for (var race : races) {
        if (((String) race.getProperty("name")).toLowerCase(Locale.ENGLISH).charAt(0) == 'e') {
          found = true;
          break;
        }
      }
      assertTrue(found);
    }
    session.commit();
  }

  @Test
  @Order(15)
  void queryCollectionContainsInRecords() {
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

      assertTrue(
          Objects.requireNonNull(record.getSchemaClassName()).equals("Animal"));
      assertNotNull(record.getProperty("races"));

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
    assertTrue(found);
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races contains (name in ['Asiatic','European'])",
            session);

    found = false;
    for (var i = 0; i < result.size() && !found; ++i) {
      record = (EntityImpl) result.get(i).asEntityOrNull();

      assertTrue(
          Objects.requireNonNull(record.getSchemaClassName()).equals("Animal"));
      assertNotNull(record.getProperty("races"));

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
    assertTrue(found);
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races contains (name in ['aaa','bbb'])", session);
    assertEquals(0, result.size());
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races containsall (name in ['European','Asiatic'])",
            session);
    assertEquals(0, result.size());
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races containsall (name in ['European','Siamese'])",
            session);
    assertEquals(1, result.size());
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where races containsall (age < 100) LIMIT 1000 SKIP 0",
            session);
    assertEquals(0, result.size());
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from Animal where not ( races contains (age < 100) ) LIMIT 20 SKIP 0",
            session);
    assertEquals(1, result.size());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(record).delete();
    session.commit();
  }

  @Test
  @Order(16)
  void queryCollectionInNumbers() {
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

      assertTrue(record.getSchemaClassName().equals("Animal"));
      assertNotNull(record.getProperty("rates"));

      rates = record.getProperty("rates");
      for (var rate : rates) {
        if (rate == 100 || rate == 105) {
          found = true;
          break;
        }
      }
    }
    assertTrue(found);
    session.commit();

    session.begin();
    result = executeQuery("select from Animal where rates in [200,10333]");

    found = false;
    for (var i = 0; i < result.size() && !found; ++i) {
      record = result.get(i).asEntity();

      assertTrue(record.getSchemaClassName().equals("Animal"));
      assertNotNull(record.getProperty("rates"));

      rates = record.getProperty("rates");
      for (var rate : rates) {
        if (rate == 100 || rate == 105) {
          found = true;
          break;
        }
      }
    }
    assertTrue(found);
    session.commit();

    session.begin();
    result = executeQuery("select * from Animal where rates contains 500", session);
    assertEquals(0, result.size());
    session.commit();

    session.begin();
    result = executeQuery("select * from Animal where rates contains 100", session);
    assertEquals(1, result.size());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(record).delete();
    session.commit();
  }

  @Test
  @Order(17)
  void queryWhereRidDirectMatching() {
    session.begin();
    var collectionId = session.getMetadata().getSchema().getClass("ORole").getCollectionIds()[0];
    var positions = getValidPositions(collectionId);

    var result =
        executeQuery(
            "select * from OUser where roles contains #" + collectionId + ":"
                + positions.getFirst(),
            session);

    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  @Order(18)
  void queryWhereInpreparred() {
    session.begin();
    var result =
        executeQuery("select * from OUser where name in [ :name ]", session, "admin");

    assertEquals(1, result.size());
    assertEquals("admin", result.getFirst().asEntityOrNull().getProperty("name"));
    session.commit();
  }

  @Test
  @Order(19)
  void queryInAsParameter() {
    var roles = executeQuery("select from orole limit 1", session);

    var result = executeQuery("select * from OUser where roles in ?", session,
        roles);

    assertEquals(1, result.size());
  }

  @Test
  @Order(20)
  void queryAnyOperator() {
    session.begin();
    var result = executeQuery("select from Profile where any() like 'N%'", session);

    assertFalse(result.isEmpty());

    for (var record : result) {
      assertTrue(record.asEntityOrNull().getSchemaClassName().equals("Profile"));

      var found = false;
      for (var fieldValue : record.getPropertyNames().stream().map(record::getProperty).toArray()) {
        if (fieldValue != null && !fieldValue.toString().isEmpty()
            && fieldValue.toString().toLowerCase(Locale.ROOT).charAt(0) == 'n') {
          found = true;
          break;
        }
      }
      assertTrue(found);
    }
    session.commit();
  }

  @Test
  @Order(21)
  void queryAllOperator() {
    var result = executeQuery("select from Account where all() is null", session);

    assertEquals(0, result.size());
  }

  @Test
  @Order(22)
  void queryOrderBy() {
    session.begin();
    var result = executeQuery("select from Profile order by name", session);

    assertFalse(result.isEmpty());

    String lastName = null;
    var isNullSegment = true; // NULL VALUES AT THE BEGINNING!
    for (var d : result) {
      final String fieldValue = d.getProperty("name");
      if (fieldValue != null) {
        isNullSegment = false;
      } else {
        assertTrue(isNullSegment);
      }

      if (lastName != null) {
        assertTrue(fieldValue.compareTo(lastName) >= 0);
      }
      lastName = fieldValue;
    }
    session.commit();
  }

  @Test
  @Order(23)
  void queryOrderByWrongSyntax() {
    try {
      executeQuery("select from Profile order by name aaaa", session);
      fail();
    } catch (CommandSQLParsingException ignored) {
    }
  }

  @Test
  @Order(24)
  void queryLimitOnly() {
    var result = executeQuery("select from Profile limit 1", session);

    assertEquals(1, result.size());
  }

  @Test
  @Order(25)
  void querySkipOnly() {
    var result = executeQuery("select from Profile", session);
    var total = result.size();

    result = executeQuery("select from Profile skip 1", session);
    assertEquals(total - 1, result.size());
  }

  @Test
  @Order(26)
  void queryPaginationWithSkipAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile", session);

    var page = executeQuery("select from Profile skip 10 limit 10", session);
    assertEquals(10, page.size());

    for (var i = 0; i < page.size(); ++i) {
      assertEquals(result.get(10 + i), page.get(i));
    }
    session.commit();
  }

  @Test
  @Order(27)
  void queryOffsetOnly() {
    var result = executeQuery("select from Profile", session);
    var total = result.size();

    result = executeQuery("select from Profile offset 1", session);
    assertEquals(total - 1, result.size());
  }

  @Test
  @Order(28)
  void queryPaginationWithOffsetAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile", session);

    var page = executeQuery("select from Profile offset 10 limit 10", session);
    assertEquals(10, page.size());

    for (var i = 0; i < page.size(); ++i) {
      assertEquals(result.get(10 + i), page.get(i));
    }
    session.commit();
  }

  @Test
  @Order(29)
  void queryPaginationWithOrderBySkipAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile order by name", session);

    var page =
        executeQuery("select from Profile order by name limit 10 skip 10", session);
    assertEquals(10, page.size());

    for (var i = 0; i < page.size(); ++i) {
      assertEquals(result.get(10 + i), page.get(i));
    }
    session.commit();
  }

  @Test
  @Order(30)
  void queryPaginationWithOrderByDescSkipAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile order by name desc");

    var page = executeQuery(
        "select from Profile order by name desc limit 10 skip 10");
    assertEquals(10, page.size());

    for (var i = 0; i < page.size(); ++i) {
      assertEquals(result.get(10 + i), page.get(i));
    }
    session.commit();
  }

  @Test
  @Order(31)
  void queryOrderByAndLimit() {
    session.begin();
    var result = executeQuery("select from Profile order by name limit 2");

    assertTrue(result.size() <= 2);

    String lastName = null;
    for (var d : result) {
      if (lastName != null && d.getProperty("name") != null) {
        assertTrue(((String) d.getProperty("name")).compareTo(lastName) >= 0);
      }
      lastName = d.getProperty("name");
    }
    session.commit();
  }

  @Test
  @Order(32)
  void queryConditionAndOrderBy() {
    session.begin();
    var result =
        executeQuery("select from Profile where name is not null order by name");

    assertFalse(result.isEmpty());

    String lastName = null;
    for (var d : result) {
      if (lastName != null && d.getProperty("name") != null) {
        assertTrue(((String) d.getProperty("name")).compareTo(lastName) >= 0);
      }
      lastName = d.getProperty("name");
    }
    session.commit();
  }

  @Test
  @Order(33)
  @Disabled("Query with conditions and ORDER BY produces incorrect results")
  void queryConditionsAndOrderBy() {
    var result =
        executeQuery("select from Profile where name is not null order by name desc, id asc");

    assertFalse(result.isEmpty());

    String lastName = null;
    for (var d : result) {
      if (lastName != null && d.getProperty("name") != null) {
        assertTrue(((String) d.getProperty("name")).compareTo(lastName) <= 0);
      }
      lastName = d.getProperty("name");
    }
  }

  @Test
  @Order(34)
  void queryRecordTargetRid() {
    session.begin();
    var profileCollectionId =
        session.getMetadata().getSchema().getClass("Profile").getCollectionIds()[0];
    var positions = getValidPositions(profileCollectionId);

    var result =
        executeQuery("select from " + profileCollectionId + ":" + positions.getFirst());

    assertEquals(1, result.size());

    for (var d : result) {
      assertEquals(
          "#" + profileCollectionId + ":" + positions.getFirst(), d.getIdentity().toString());
    }
    session.commit();
  }

  @Test
  @Order(35)
  void queryRecordTargetRids() {
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

    assertEquals(2, result.size());

    assertEquals(
        "#" + profileCollectionId + ":" + positions.get(0),
        result.get(0).getIdentity().toString());
    assertEquals(
        "#" + profileCollectionId + ":" + positions.get(1),
        result.get(1).getIdentity().toString());
    session.commit();
  }

  @Test
  @Order(36)
  void queryRecordAttribRid() {
    session.begin();

    var profileCollectionId =
        session.getMetadata().getSchema().getClass("Profile").getCollectionIds()[0];
    var postions = getValidPositions(profileCollectionId);

    var result =
        executeQuery(
            "select from Profile where @rid = #" + profileCollectionId + ":" + postions.getFirst(),
            session);

    assertEquals(1, result.size());

    for (var d : result) {
      assertEquals(
          "#" + profileCollectionId + ":" + postions.getFirst(), d.getIdentity().toString());
    }
    session.commit();
  }

  @Test
  @Order(37)
  void queryRecordAttribClass() {
    var result = executeQuery("select from Profile where @class = 'Profile'");

    assertFalse(result.isEmpty());

    for (var d : result) {
      assertEquals("Profile", d.asEntityOrNull().getSchemaClassName());
    }
  }

  @Test
  @Order(38)
  void queryRecordAttribVersion() {
    var result = executeQuery("select from Profile where @version > 0", session);

    assertFalse(result.isEmpty());

    for (var d : result) {
      assertTrue(d.asEntityOrNull().getVersion() > 0);
    }
  }

  @Test
  @Order(39)
  void queryRecordAttribType() {
    session.begin();
    var result = executeQuery("select from Profile where @type = 'entity'", session);

    assertFalse(result.isEmpty());
    session.commit();
  }

  @Test
  @Order(40)
  void queryWrongOperator() {
    try {
      executeQuery(
          "select from Profile where name like.toLowerCase4(Locale.ENGLISH) '%Jay%'", session);
      fail();
    } catch (Exception e) {
      assertTrue(true);
    }
  }

  @Test
  @Order(41)
  void queryEscaping() {
    executeQuery("select from Profile where name like '%\\'Jay%'", session);
  }

  @Test
  @Order(42)
  void queryWithLimit() {
    assertEquals(3, executeQuery("select from Profile limit 3", session).size());
  }

  @SuppressWarnings("unused")
  @Test
  @Order(43)
  void testRecordNumbers() {
    session.begin();
    var tot = session.countClass("V");

    var count = 0;
    var entityIterator = session.browseClass("V");
    while (entityIterator.hasNext()) {
      entityIterator.next();
      count++;
    }

    assertEquals(tot, count);

    assertTrue(executeQuery("select from V", session).size() >= tot);
    session.commit();
  }

  @Test
  @Order(44)
  void includeFields() {
    var query = "select expand( roles.include('name') ) from OUser";

    var resultset = executeQuery(query);

    for (var d : resultset) {
      assertTrue(d.getPropertyNames().size() <= 1);
      if (d.getPropertyNames().size() == 1) {
        assertTrue(d.hasProperty("name"));
      }
    }
  }

  @Test
  @Order(45)
  void excludeFields() {
    var query = "select expand( roles.exclude('rules') ) from OUser";

    var resultset = executeQuery(query);

    for (var d : resultset) {
      assertFalse(d.hasProperty("rules"));
      assertTrue(d.hasProperty("name"));
    }
  }

  @Test
  @Order(46)
  void queryBetween() {
    session.begin();
    var result = executeQuery("select * from account where nr between 10 and 20");

    for (var record : result) {
      assertTrue(
          ((Integer) record.getProperty("nr")) >= 10
              && ((Integer) record.getProperty("nr")) <= 20);
    }
    session.commit();
  }

  @Test
  @Order(47)
  void queryParenthesisInStrings() {

    session.begin();
    session.execute("INSERT INTO account (name) VALUES ('test (demo)')");
    session.commit();

    session.begin();
    var result = executeQuery("select * from account where name = 'test (demo)'");

    assertEquals(1, result.size());

    for (var record : result) {
      assertEquals("test (demo)", record.getProperty("name"));
    }
    session.commit();
  }

  @Test
  @Order(48)
  void queryMathOperators() {
    session.begin();
    var result = executeQuery("select * from account where id < 3 + 4");
    assertFalse(result.isEmpty());
    for (var document : result) {
      assertTrue(((Number) document.getProperty("id")).intValue() < 3 + 4);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 10 - 3");
    assertFalse(result.isEmpty());
    for (var document : result) {
      assertTrue(((Number) document.getProperty("id")).intValue() < 10 - 3);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 3 * 2");
    assertFalse(result.isEmpty());
    for (var document : result) {
      assertTrue(((Number) document.getProperty("id")).intValue() < 3 << 1);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 120 / 20");
    assertFalse(result.isEmpty());
    for (var document : result) {
      assertTrue(((Number) document.getProperty("id")).intValue() < 120 / 20);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id < 27 % 10");
    assertFalse(result.isEmpty());
    for (var document : result) {
      assertTrue(((Number) document.getProperty("id")).intValue() < 27 % 10);
    }
    session.commit();

    session.begin();
    result = executeQuery("select * from account where id = id * 1");
    assertFalse(result.isEmpty());
    var result2 = executeQuery("select count(*) as tot from account where id >= 0");
    assertEquals(
        ((Number) result2.getFirst().getProperty("tot")).intValue(), result.size());
    session.commit();
  }

  @Test
  @Order(49)
  void testBetweenWithParameters() {
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

    assertEquals(4, result.size(), "Found: " + result);

    final List<Integer> resultsList = new ArrayList<>(Arrays.asList(4, 5, 6, 7));
    for (var record : result) {
      assertTrue(resultsList.remove(record.<Integer>getProperty("id")));
    }
    session.commit();
  }

  @Test
  @Order(50)
  void testInWithParameters() {
    session.begin();

    final var result =
        executeQuery(
            "select * from Company where id in [?, ?, ?, ?] and salary is not null",
            session,
            4,
            5,
            6,
            7);

    assertEquals(4, result.size());

    final List<Integer> resultsList = new ArrayList<>(Arrays.asList(4, 5, 6, 7));
    for (var record : result) {
      assertTrue(resultsList.remove(record.<Integer>getProperty("id")));
    }
    session.commit();
  }

  @Test
  @Order(51)
  void testEqualsNamedParameter() {

    Map<String, Object> params = new HashMap<>();
    params.put("id", 4);
    final var result =
        executeQuery("select * from Company where id = :id and salary is not null", params);

    assertEquals(1, result.size());
  }

  @Test
  @Order(52)
  void testQueryAsClass() {
    session.begin();

    var result =
        executeQuery("select from Account where addresses.@class in [ 'Address' ]");
    assertFalse(result.isEmpty());
    for (var d : result) {
      assertNotNull(d.getProperty("addresses"));
      Identifiable identifiable = ((Collection<Identifiable>) d.getProperty("addresses"))
          .iterator()
          .next();
      var transaction = session.getActiveTransaction();
      assertEquals(
          "Address",
          Objects.requireNonNull(
              ((EntityImpl) transaction.load(identifiable))
                  .getSchemaClass())
              .getName());
    }
    session.commit();
  }

  @Test
  @Order(53)
  void testQueryNotOperator() {
    final var tx = session.begin();

    var result =
        executeQuery("select from Account where not ( addresses.@class in [ 'Address' ] )");
    assertFalse(result.isEmpty());
    for (var d : result) {
      final var addresses = d.getLinkList("addresses");
      if (addresses != null && !addresses.isEmpty()) {

        for (var a : addresses) {
          assertNotEquals("Address", tx.loadEntity(a).getSchemaClassName());
        }
      }
    }
    session.commit();
  }

  // Original had no explicit @Test; relied on class-level @Test annotation
  @Test
  @Order(54)
  void testParams() {
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
  @Order(55)
  void queryInstanceOfOperator() {
    var result = executeQuery("select from Account");

    assertFalse(result.isEmpty());

    var result2 = executeQuery(
        "select from Account where @this instanceof 'Account'");

    assertEquals(result.size(), result2.size());

    var result3 = executeQuery(
        "select from Account where @class instanceof 'Account'");

    assertEquals(result.size(), result3.size());
  }

  @Test
  @Order(56)
  void subQuery() {
    var result =
        executeQuery(
            "select from Account where name in ( select name from Account where name is not null"
                + " limit 1 )",
            session);

    assertFalse(result.isEmpty());
  }

  @Test
  @Order(57)
  void subQueryNoFrom() {
    var result2 =
        executeQuery(
            "select $names let $names = (select EXPAND( addresses.city ) from Account where"
                + " addresses.size() > 0 )");

    assertFalse(result2.isEmpty());
    assertTrue(result2.getFirst().getProperty("$names") instanceof Collection<?>);
    assertFalse(((Collection<?>) result2.getFirst().getProperty("$names")).isEmpty());
  }

  @Test
  @Order(58)
  void subQueryLetAndIndexedWhere() {
    var result =
        executeQuery("select $now from OUser let $now = eval('42') where name = 'admin'");

    assertEquals(1, result.size());
    assertNotNull(result.getFirst().getProperty("$now"), result.getFirst().toString());
  }

  @Test
  @Order(59)
  void queryOrderByWithLimit() {

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
    assertEquals(oneYearAgo.get(Calendar.YEAR), smaller.get(Calendar.YEAR));
    session.commit();

    session.begin();
    result =
        executeQuery(
            "select * from " + facClass.getName()
                + " where context = 'test' order by date DESC",
            1);

    var bigger = Calendar.getInstance();
    bigger.setTime(result.getFirst().getProperty("date"));
    assertEquals(currentYear.get(Calendar.YEAR), bigger.get(Calendar.YEAR));
    session.commit();
  }

  @Test
  @Order(60)
  void queryWithTwoRidInWhere() {
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

    assertEquals(1, resultset.size());

    assertEquals(
        new RecordId(collectionId, maxPos).toString(),
        resultset.getFirst().getProperty("oid"));
    session.commit();
  }

  @Test
  @Order(61)
  void testSelectFromListParameter() {
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
    assertEquals(1, result.size());
    session.commit();

    session.getMetadata().getSchema().dropClass("Place");
  }

  @Test
  @Order(62)
  void testSelectRidFromListParameter() {
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
    assertEquals(2, result.size());
    session.commit();

    session.getMetadata().getSchema().dropClass("Place");
  }

  @Test
  @Order(63)
  void testSelectRidInList() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    session.getMetadata().getSchema().createClass("FamousPlace", 1, placeClass);

    session.begin();
    session.newEntity("Place");
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
    assertTrue(secondPlaceId.getCollectionId() < famousPlaceId.getCollectionId());
    assertTrue(
        secondPlaceId.getCollectionPosition() > famousPlaceId.getCollectionPosition());

    session.begin();
    var result =
        executeQuery(
            "select from Place where @rid in [" + secondPlaceId + "," + famousPlaceId + "]",
            session);
    assertEquals(2, result.size());
    session.commit();

    session.getMetadata().getSchema().dropClass("FamousPlace");
    session.getMetadata().getSchema().dropClass("Place");
  }

  @Test
  @Order(64)
  void testMapKeys() {
    Map<String, Object> params = new HashMap<>();
    params.put("id", 4);

    final var result =
        executeQuery(
            "select * from company where id = :id and salary is not null", session, params);

    assertEquals(1, result.size());
  }

  // Original had no explicit @Test; relied on class-level @Test annotation
  @Test
  @Order(65)
  void queryOrderByRidDesc() {
    var result = executeQuery("select from OUser order by @rid desc", session);

    assertFalse(result.isEmpty());

    RID lastRid = null;
    for (var d : result) {
      var rid = d.getIdentity();
      if (lastRid != null) {
        assertTrue(rid.compareTo(lastRid) < 0);
      }
      lastRid = rid;
    }

    var res = executeQuery("explain select from OUser order by @rid desc").getFirst();
    assertNull(res.getProperty("orderByElapsed"));
  }

  // Original had no explicit @Test; relied on class-level @Test annotation
  @Test
  @Order(66)
  void testQueryParameterNotPersistent() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("test", "test");
    executeQuery("select from OUser where @rid = ?", doc);
    assertTrue(doc.isDirty());
    session.commit();
  }

  // Original had no explicit @Test; relied on class-level @Test annotation
  @Test
  @Order(67)
  void testQueryLetExecutedOnce() {
    session.begin();
    final var result =
        executeQuery(
            "select name, $counter as counter from OUser let $counter = eval(\"$counter +"
                + " 1\")");

    assertFalse(result.isEmpty());
    for (var r : result) {
      assertEquals(1, r.<Object>getProperty("counter"));
    }
    session.commit();
  }

  @Test
  @Order(68)
  void testMultipleCollectionsWithPagination() {
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
          assertTrue(names.contains(personDoc.<String>getProperty("First")));
          assertTrue(names.remove(personDoc.<String>getProperty("First")));
        }

        resultset = executeQuery(query, last);
      }

      assertTrue(names.isEmpty());
      session.commit();

    } finally {
      session.getMetadata().getSchema().dropClass("PersonMultipleCollections");
    }
  }

  @Test
  @Order(69)
  void testOutFilterInclude() {
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

    assertEquals(1, result.size());

    for (var r : result) {
      assertNull(r.getProperty("name"));
    }
  }

  @Test
  @Order(70)
  void testExpandSkip() {
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

    assertEquals(3, result.stream().count());

    Map<Object, Object> params = new HashMap<>();
    params.put("values", Arrays.asList("2", "3", "antani"));
    result =
        session.query(
            "select expand(out()[name in :values]) from TestExpandSkip where name = '1'", params);
    assertEquals(2, result.stream().count());

    result = session.query("select expand(out()) from TestExpandSkip where name = '1' skip 1");

    assertEquals(2, result.stream().count());

    result = session.query("select expand(out()) from TestExpandSkip where name = '1' skip 2");
    assertEquals(1, result.stream().count());

    result = session.query("select expand(out()) from TestExpandSkip where name = '1' skip 3");
    assertEquals(0, result.stream().count());

    result =
        session.query("select expand(out()) from TestExpandSkip where name = '1' skip 1 limit 1");
    assertEquals(1, result.stream().count());
  }

  @Test
  @Order(71)
  void testPolymorphicEdges() {
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
    assertEquals(2, result.stream().count());

    result =
        session.query(
            "select expand(out('TestPolymorphicEdges_E2')) from TestPolymorphicEdges_V where name ="
                + " '1' ");
    assertEquals(1, result.stream().count());
  }

  @Test
  @Order(72)
  void testSizeOfLink() {
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
    assertEquals(1, result.stream().count());
  }

  @Test
  @Order(73)
  void testEmbeddedMapAndDotNotation() {
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
    assertEquals(1, result.size());
    var doc = result.getFirst();
    assertNotNull(doc);
    @SuppressWarnings("rawtypes")
    List list = doc.getProperty("result");
    assertEquals(1, list.size());
    var first = list.getFirst();
    assertTrue(first instanceof Map);
    //noinspection rawtypes
    assertEquals("baz", ((Map) first).get("bar"));
  }

  @Test
  @Order(74)
  void testLetWithQuotedValue() {
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
    assertEquals(1, result.stream().count());
  }

  @Test
  @Order(75)
  void testNamedParams() {
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
    params.put("permissions", new String[] {"USER"});
    params.put("limit", 1);
    var results =
        executeQuery(
            "SELECT *, out('testNamedParams_HasPermission').type as permissions FROM"
                + " testNamedParams WHERE login >= :key AND"
                + " out('testNamedParams_HasPermission').type IN :permissions ORDER BY login"
                + " ASC LIMIT :limit",
            params);
    assertEquals(1, results.size());
  }

  @Test
  @Order(76)
  void selectLikeFromSet() {
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
    assertEquals(1, results.size());

    results = executeQuery("SELECT FROM SetContainer WHERE data LIKE 'bobo%'");
    assertEquals(0, results.size());

    results = executeQuery("SELECT FROM SetContainer WHERE data LIKE '%hell%'");
    assertEquals(2, results.size());
  }

  @Test
  @Order(77)
  void selectLikeFromList() {
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
    assertEquals(1, results.size());

    results = executeQuery("SELECT FROM ListContainer WHERE data LIKE 'bobo%'");
    assertEquals(0, results.size());

    results = executeQuery("SELECT FROM ListContainer WHERE data LIKE '%hell%'");
    assertEquals(2, results.size());
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
}
