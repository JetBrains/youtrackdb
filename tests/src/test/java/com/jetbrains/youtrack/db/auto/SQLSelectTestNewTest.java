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

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.core.command.CommandResultListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCluster;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLAsynchQuery;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
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
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * If some of the tests start to fail then check cluster number in queries, e.g #7:1. It can be
 * because the order of clusters could be affected due to adding or removing cluster from storage.
 */
@Test
public class SQLSelectTestNewTest extends AbstractSelectTest {

  private EntityImpl record = ((EntityImpl) session.newEntity());

  @Parameters(value = "remote")
  public SQLSelectTestNewTest(boolean remote) throws Exception {
    super(remote);
  }

  @BeforeClass
  public void init() {
    if (!session.getMetadata().getSchema().existsClass("Profile")) {
      session.getMetadata().getSchema().createClass("Profile", 1);

      for (var i = 0; i < 1000; ++i) {
        session.begin();
        session.newInstance("Profile").field("test", i).field("name", "N" + i);

        session.commit();
      }
    }

    if (!session.getMetadata().getSchema().existsClass("company")) {
      session.getMetadata().getSchema().createClass("company", 1);
      for (var i = 0; i < 20; ++i) {
        session.begin();
        ((EntityImpl) session.newEntity("company")).field("id", i);

        session.commit();
      }
    }

    session.getMetadata().getSchema().getOrCreateClass("Account");
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
    var result1 =
        executeQuery("select * from cluster:profile where name like 'Gi%'", session);

    for (var value : result1) {
      record = (EntityImpl) value.asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertTrue(record.field("name").toString().startsWith("Gi"));
    }

    var result2 =
        executeQuery("select * from cluster:profile where name like '%epp%'", session);

    Assert.assertEquals(result1, result2);

    var result3 =
        executeQuery("select * from cluster:profile where name like 'Gius%pe'", session);

    Assert.assertEquals(result1, result3);

    result1 = executeQuery("select * from cluster:profile where name like '%Gi%'", session);

    for (var result : result1) {
      record = (EntityImpl) result.asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertTrue(record.field("name").toString().contains("Gi"));
    }

    result1 = executeQuery("select * from cluster:profile where name like ?", session, "%Gi%");

    for (var result : result1) {
      record = (EntityImpl) result.asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertTrue(record.field("name").toString().contains("Gi"));
    }
  }

  @Test
  public void queryContainsInEmbeddedSet() {
    Set<String> tags = new HashSet<String>();
    tags.add("smart");
    tags.add("nice");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.field("tags", tags, PropertyType.EMBEDDEDSET);

    session.commit();

    var resultset =
        executeQuery("select from Profile where tags CONTAINS 'smart'", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    session.begin();
    doc.delete();
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedList() {
    List<String> tags = new ArrayList<String>();
    tags.add("smart");
    tags.add("nice");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.field("tags", tags);

    session.commit();

    var resultset =
        executeQuery("select from Profile where tags[0] = 'smart'", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    resultset =
        executeQuery("select from Profile where tags[0,1] CONTAINSALL ['smart','nice']", session);

    Assert.assertEquals(resultset.size(), 1);
    Assert.assertEquals(resultset.getFirst().getIdentity(), doc.getIdentity());

    session.begin();
    doc.delete();
    session.commit();
  }

  @Test
  public void queryContainsInDocumentSet() {
    var coll = new HashSet<EntityImpl>();
    var entity = session.newEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    coll.add((EntityImpl) entity);

    entity = session.newEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    coll.add((EntityImpl) entity);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.field("coll", coll, PropertyType.EMBEDDEDSET);

    session.commit();

    var resultset =
        executeQuery(
            "select coll[name='Jay'] as value from Profile where coll is not null", session);
    Assert.assertEquals(resultset.size(), 1);
    Assert.assertTrue(resultset.getFirst().getProperty("value") instanceof Collection);

    Collection<?> xcoll = resultset.getFirst().getProperty("value");
    Assert.assertEquals(((Entity) xcoll.iterator().next()).getProperty("name"), "Jay");

    session.begin();
    doc.delete();
    session.commit();
  }

  @Test
  public void queryContainsInDocumentList() {
    var coll = new ArrayList<>();
    var entity = session.newEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    coll.add(entity);

    entity = session.newEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    coll.add(entity);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.field("coll", coll, PropertyType.EMBEDDEDLIST);

    session.commit();

    var resultset =
        executeQuery(
            "select coll[name='Jay'] as value from Profile where coll is not null", session);
    Assert.assertEquals(resultset.size(), 1);
    //    Assert.assertEquals(resultset.get(0).field("value").getClass(), EntityImpl.class);
    var result = resultset.getFirst().getProperty("value");
    Assert.assertTrue(result instanceof Collection);
    Assert.assertEquals(((Collection<?>) result).size(), 1);
    Assert.assertEquals(
        ((Entity) ((Collection<?>) result).iterator().next()).getProperty("name"), "Jay");

    session.begin();
    doc.delete();
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedMapClassic() {
    Map<String, EntityImpl> customReferences = new HashMap<>();
    var entity = session.newEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    customReferences.put("first", (EntityImpl) entity);

    entity = session.newEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    customReferences.put("second", (EntityImpl) entity);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.field("customReferences", customReferences, PropertyType.EMBEDDEDMAP);

    session.commit();

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

    if (resultset.getFirst().getProperty("customReferences").getClass().isArray()) {
      Object[] customReferencesBack = resultset.getFirst().getProperty("customReferences");
      Assert.assertEquals(customReferencesBack.length, 2);
      Assert.assertTrue(customReferencesBack[0] instanceof EntityImpl);
      Assert.assertTrue(customReferencesBack[1] instanceof EntityImpl);
    } else if (resultset.getFirst().getProperty("customReferences") instanceof List) {
      List<EntityImpl> customReferencesBack = resultset.getFirst().getProperty("customReferences");
      Assert.assertEquals(customReferencesBack.size(), 2);
      Assert.assertTrue(customReferencesBack.get(0) instanceof EntityImpl);
      Assert.assertTrue(customReferencesBack.get(1) instanceof EntityImpl);
    } else {
      Assert.fail("Wrong type received: " + resultset.getFirst().getProperty("customReferences"));
    }

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

    session.begin();
    doc.delete();
    session.commit();
  }

  @Test
  public void queryContainsInEmbeddedMapNew() {
    session.begin();
    Map<String, EntityImpl> customReferences = new HashMap<>();
    var entity = session.newEntity();
    entity.setProperty("name", "Luca");
    entity.setProperty("surname", "Garulli");
    customReferences.put("first", (EntityImpl) entity);

    entity = session.newEntity();
    entity.setProperty("name", "Jay");
    entity.setProperty("surname", "Miner");
    customReferences.put("second", (EntityImpl) entity);

    var doc = ((EntityImpl) session.newEntity("Profile"));
    doc.field("customReferences", customReferences, PropertyType.EMBEDDEDMAP);

    session.commit();

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

    session.begin();
    doc.delete();
    session.commit();
  }

  @Test
  public void queryCollectionContainsLowerCaseSubStringIgnoreCase() {
    var result =
        executeQuery(
            "select * from cluster:profile where races contains"
                + " (name.toLowerCase(Locale.ENGLISH).subString(0,1) = 'e')",
            session);

    for (var value : result) {
      record = (EntityImpl) value.asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("profile"));
      Assert.assertNotNull(record.field("races"));

      Collection<EntityImpl> races = record.field("races");
      var found = false;
      for (var race : races) {
        if (((String) race.field("name")).toLowerCase(Locale.ENGLISH).charAt(0) == 'e') {
          found = true;
          break;
        }
      }
      Assert.assertTrue(found);
    }
  }

  @Test
  public void queryCollectionContainsInRecords() {
    record = ((EntityImpl) session.newEntity("Animal"));
    record.field("name", "Cat");

    session.begin();
    Collection<EntityImpl> races = new HashSet<EntityImpl>();
    races.add(session.newInstance("AnimalRace").field("name", "European"));
    races.add(session.newInstance("AnimalRace").field("name", "Siamese"));
    record.field("age", 10);
    record.field("races", races);

    session.commit();

    var result =
        executeQuery(
            "select * from cluster:animal where races contains (name in ['European','Asiatic'])",
            session);

    var found = false;
    for (var i = 0; i < result.size() && !found; ++i) {
      record = (EntityImpl) result.get(i).asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("animal"));
      Assert.assertNotNull(record.field("races"));

      races = record.field("races");
      for (var race : races) {
        if (race.field("name").equals("European") || race.field("name").equals("Asiatic")) {
          found = true;
          break;
        }
      }
    }
    Assert.assertTrue(found);

    result =
        executeQuery(
            "select * from cluster:animal where races contains (name in ['Asiatic','European'])",
            session);

    found = false;
    for (var i = 0; i < result.size() && !found; ++i) {
      record = (EntityImpl) result.get(i).asEntity();

      Assert.assertTrue(record.getSchemaClassName().equalsIgnoreCase("animal"));
      Assert.assertNotNull(record.field("races"));

      races = record.field("races");
      for (var race : races) {
        if (race.field("name").equals("European") || race.field("name").equals("Asiatic")) {
          found = true;
          break;
        }
      }
    }
    Assert.assertTrue(found);

    result =
        executeQuery(
            "select * from cluster:animal where races contains (name in ['aaa','bbb'])", session);
    Assert.assertEquals(result.size(), 0);

    result =
        executeQuery(
            "select * from cluster:animal where races containsall (name in ['European','Asiatic'])",
            session);
    Assert.assertEquals(result.size(), 0);

    result =
        executeQuery(
            "select * from cluster:animal where races containsall (name in ['European','Siamese'])",
            session);
    Assert.assertEquals(result.size(), 1);

    result =
        executeQuery(
            "select * from cluster:animal where races containsall (age < 100) LIMIT 1000 SKIP 0",
            session);
    Assert.assertEquals(result.size(), 0);

    result =
        executeQuery(
            "select * from cluster:animal where not ( races contains (age < 100) ) LIMIT 20 SKIP 0",
            session);
    Assert.assertEquals(result.size(), 1);

    session.begin();
    record.delete();
    session.commit();
  }

  @Test
  public void queryCollectionInNumbers() {
    session.begin();
    record = ((EntityImpl) session.newEntity("Animal"));
    record.field("name", "Cat");

    Collection<Integer> rates = new HashSet<Integer>();
    rates.add(100);
    rates.add(200);
    record.field("rates", rates);
    session.commit();

    var result =
        executeQuery("select * from cluster:animal where rates contains 500", session);
    Assert.assertEquals(result.size(), 0);

    result = executeQuery("select * from cluster:animal where rates contains 100", session);
    Assert.assertEquals(result.size(), 1);

    session.begin();
    record.delete();
    session.commit();
  }

  @Test
  public void queryWhereRidDirectMatching() {
    var clusterId = session.getMetadata().getSchema().getClass("ORole").getClusterIds(session)[0];
    var positions = getValidPositions(clusterId);

    var result =
        executeQuery(
            "select * from OUser where roles contains #" + clusterId + ":" + positions.getFirst(),
            session);

    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void queryWhereInpreparred() {
    var result =
        executeQuery("select * from OUser where name in [ :name ]", session, "admin");

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(((EntityImpl) result.getFirst().asEntity()).field("name"), "admin");
  }

  @Test
  public void queryAllOperator() {
    var result = executeQuery("select from Account where all() is null", session);

    Assert.assertEquals(result.size(), 0);
  }

  @Test
  public void queryOrderBy() {
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
    var result = executeQuery("select from Profile", session);

    var page = executeQuery("select from Profile skip 10 limit 10", session);
    Assert.assertEquals(page.size(), 10);

    for (var i = 0; i < page.size(); ++i) {
      Assert.assertEquals(page.get(i), result.get(10 + i));
    }
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
    var result = executeQuery("select from Profile", session);

    var page = executeQuery("select from Profile offset 10 limit 10", session);
    Assert.assertEquals(page.size(), 10);

    for (var i = 0; i < page.size(); ++i) {
      Assert.assertEquals(page.get(i), result.get(10 + i));
    }
  }

  @Test
  public void queryPaginationWithOrderBySkipAndLimit() {
    var result = executeQuery("select from Profile order by name", session);

    var page =
        executeQuery("select from Profile order by name limit 10 skip 10", session);
    Assert.assertEquals(page.size(), 10);

    for (var i = 0; i < page.size(); ++i) {
      Assert.assertEquals(page.get(i), result.get(10 + i));
    }
  }

  @Test
  public void queryPaginationWithOrderByDescSkipAndLimit() {
    var result = executeQuery("select from Profile order by name desc", session);

    var page =
        executeQuery("select from Profile order by name desc limit 10 skip 10", session);
    Assert.assertEquals(page.size(), 10);

    for (var i = 0; i < page.size(); ++i) {
      Assert.assertEquals(page.get(i), result.get(10 + i));
    }
  }

  @Test
  public void queryOrderByAndLimit() {
    var result = executeQuery("select from Profile order by name limit 2", session);

    Assert.assertTrue(result.size() <= 2);

    String lastName = null;
    for (var d : result) {
      if (lastName != null && d.getProperty("name") != null) {
        Assert.assertTrue(((String) d.getProperty("name")).compareTo(lastName) >= 0);
      }
      lastName = d.getProperty("name");
    }
  }

  @Test
  public void queryConditionAndOrderBy() {
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
  }

  @Test
  public void queryConditionsAndOrderBy() {
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
  }

  @Test
  public void queryRecordTargetRid() {
    var profileClusterId =
        session.getMetadata().getSchema().getClass("Profile").getClusterIds(session)[0];
    var positions = getValidPositions(profileClusterId);

    var result =
        executeQuery("select from " + profileClusterId + ":" + positions.getFirst(), session);

    Assert.assertEquals(result.size(), 1);

    for (var d : result) {
      Assert.assertEquals(
          d.getIdentity().toString(), "#" + profileClusterId + ":" + positions.getFirst());
    }
  }

  @Test
  public void queryRecordTargetRids() {
    var profileClusterId =
        session.getMetadata().getSchema().getClass("Profile").getClusterIds(session)[0];
    var positions = getValidPositions(profileClusterId);

    var result =
        executeQuery(
            " select from ["
                + profileClusterId
                + ":"
                + positions.get(0)
                + ", "
                + profileClusterId
                + ":"
                + positions.get(1)
                + "]",
            session);

    Assert.assertEquals(result.size(), 2);

    Assert.assertEquals(
        result.get(0).getIdentity().toString(), "#" + profileClusterId + ":" + positions.get(0));
    Assert.assertEquals(
        result.get(1).getIdentity().toString(), "#" + profileClusterId + ":" + positions.get(1));
  }

  @Test
  public void queryRecordAttribRid() {

    var profileClusterId =
        session.getMetadata().getSchema().getClass("Profile").getClusterIds(session)[0];
    var postions = getValidPositions(profileClusterId);

    var result =
        executeQuery(
            "select from Profile where @rid = #" + profileClusterId + ":" + postions.getFirst(),
            session);

    Assert.assertEquals(result.size(), 1);

    for (var d : result) {
      Assert.assertEquals(
          d.getIdentity().toString(), "#" + profileClusterId + ":" + postions.getFirst());
    }
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
    var result = executeQuery("select from Profile where @type = 'document'",
        session);

    Assert.assertFalse(result.isEmpty());
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
    for (var record : session.browseClass("V")) {
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
            d.getIdentity().getClusterId() < 0
                || (d.getIdentity().getClusterId() >= last.getClusterId())
                && d.getIdentity().getClusterPosition() > last.getClusterPosition());
      }

      last = resultset.getLast().getIdentity();

      iterationCount++;
      resultset = executeQuery("select from Profile where @rid > ? LIMIT 3", session, last);
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAutomaticPagination() {
    final var query =
        new SQLSynchQuery<EntityImpl>("select from Profile LIMIT 3");
    RID last = new ChangeableRecordId();

    List<EntityImpl> resultset = session.query(query);

    var iterationCount = 0;
    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getClusterId() >= last.getClusterId()
                && d.getIdentity().getClusterPosition() > last.getClusterPosition());
      }

      last = resultset.getLast().getIdentity();

      iterationCount++;
      resultset = session.query(query);
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAutomaticPaginationAndRidInWhere() {
    var clusterId = session.getClusterIdByName("profile");

    var range = session.getStorage().getClusterDataRange(session, clusterId);

    final var query =
        new SQLSynchQuery<EntityImpl>(
            "select from Profile where @rid between #"
                + clusterId
                + ":"
                + range[0]
                + " and #"
                + clusterId
                + ":"
                + range[1]
                + " LIMIT 3");

    RID last = new ChangeableRecordId();

    List<EntityImpl> resultset = session.query(query);

    Assert.assertEquals(resultset.getFirst().getIdentity(), new RecordId(clusterId, range[0]));

    var iterationCount = 0;
    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getClusterId() >= last.getClusterId()
                && d.getIdentity().getClusterPosition() > last.getClusterPosition());
      }

      last = resultset.getLast().getIdentity();

      iterationCount++;
      resultset = session.query(query);
    }

    Assert.assertEquals(last, new RecordId(clusterId, range[1]));
    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAutomaticPaginationWithWhere() {
    final var query =
        new SQLSynchQuery<EntityImpl>(
            "select from Profile where followers.length() > 0 LIMIT 3");
    RID last = new ChangeableRecordId();

    List<EntityImpl> resultset = session.query(query);

    var iterationCount = 0;

    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getClusterId() >= last.getClusterId()
                && d.getIdentity().getClusterPosition() > last.getClusterPosition());
      }

      last = resultset.getLast().getIdentity();

      // System.out.printf("\nIterating page %d, last record is %s", iterationCount, last);

      iterationCount++;
      resultset = session.query(query);
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAutomaticPaginationWithWhereAndBindingVar() {
    final var query =
        new SQLSynchQuery<EntityImpl>(
            "select from Profile where followers.length() > ? LIMIT 3");
    RID last = new ChangeableRecordId();

    List<EntityImpl> resultset = session.query(query, 0);

    var iterationCount = 0;

    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getClusterId() >= last.getClusterId()
                && d.getIdentity().getClusterPosition() > last.getClusterPosition());
      }

      last = resultset.getLast().getIdentity();

      iterationCount++;
      resultset = session.query(query, 0);
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAutomaticPaginationWithWhereAndBindingVarAtTheFirstQueryCall() {
    final var query =
        new SQLSynchQuery<EntityImpl>(
            "select from Profile where followers.length() > ? LIMIT 3");
    RID last = new ChangeableRecordId();

    List<EntityImpl> resultset = session.query(query, 0);

    var iterationCount = 0;

    while (!resultset.isEmpty()) {
      Assert.assertTrue(resultset.size() <= 3);

      for (var d : resultset) {
        Assert.assertTrue(
            d.getIdentity().getClusterId() >= last.getClusterId()
                && d.getIdentity().getClusterPosition() > last.getClusterPosition());
      }

      last = resultset.getLast().getIdentity();

      iterationCount++;
      resultset = session.query(query, 0);
    }

    Assert.assertTrue(iterationCount > 1);
  }

  @Test
  public void queryWithAbsenceOfAutomaticPaginationBecauseOfBindingVarReset() {
    final var query =
        new SQLSynchQuery<EntityImpl>(
            "select from Profile where followers.length() > ? LIMIT 3");

    List<EntityImpl> resultset = session.query(query, -1);

    final RID firstRidFirstQuery = resultset.getFirst().getIdentity();

    resultset = session.query(query, -2);

    final RID firstRidSecondQueryQuery = resultset.getFirst().getIdentity();

    Assert.assertEquals(firstRidFirstQuery, firstRidSecondQueryQuery);
  }

  @Test
  public void includeFields() {
    final var query =
        new SQLSynchQuery<EntityImpl>("select expand( roles.include('name') ) from OUser");

    List<EntityImpl> resultset = session.query(query);

    for (var d : resultset) {
      Assert.assertTrue(d.fields() <= 1);
      if (d.fields() == 1) {
        Assert.assertTrue(d.containsField("name"));
      }
    }
  }

  @Test
  public void excludeFields() {
    final var query =
        new SQLSynchQuery<EntityImpl>("select expand( roles.exclude('rules') ) from OUser");

    List<EntityImpl> resultset = session.query(query);

    for (var d : resultset) {
      Assert.assertFalse(d.containsField("rules"));
    }
  }

  @Test
  public void excludeAttributes() {
    final var query =
        new SQLSynchQuery<EntityImpl>(
            "select expand( roles.exclude('@rid', '@class') ) from OUser");

    List<EntityImpl> resultset = session.query(query);

    for (var d : resultset) {
      Assert.assertFalse(d.getIdentity().isPersistent());
      Assert.assertNull(d.getSchemaClass());
    }
  }

  @Test
  public void queryResetPagination() {
    final var query =
        new SQLSynchQuery<EntityImpl>("select from Profile LIMIT 3");

    List<EntityImpl> resultset = session.query(query);
    final RID firstRidFirstQuery = resultset.getFirst().getIdentity();
    query.resetPagination();

    resultset = session.query(query);
    final RID firstRidSecondQueryQuery = resultset.getFirst().getIdentity();

    Assert.assertEquals(firstRidFirstQuery, firstRidSecondQueryQuery);
  }

  @Test
  public void queryBetween() {
    var result =
        executeQuery("select * from account where nr between 10 and 20", session);

    for (var value : result) {
      record = (EntityImpl) value.asEntity();

      Assert.assertTrue(
          ((Integer) record.field("nr")) >= 10 && ((Integer) record.field("nr")) <= 20);
    }
  }

  @Test
  public void queryParenthesisInStrings() {

    session.command("INSERT INTO account (name) VALUES ('test (demo)')").close();

    var result =
        executeQuery("select * from account where name = 'test (demo)'", session);

    Assert.assertEquals(result.size(), 1);

    for (var value : result) {
      record = (EntityImpl) value.asEntity();
      Assert.assertEquals(record.field("name"), "test (demo)");
    }
  }

  @Test
  public void queryMathOperators() {
    var result = executeQuery("select * from account where id < 3 + 4", session);
    Assert.assertFalse(result.isEmpty());
    for (var result3 : result) {
      Assert.assertTrue(((Number) result3.getProperty("id")).intValue() < 3 + 4);
    }

    result = executeQuery("select * from account where id < 10 - 3", session);
    Assert.assertFalse(result.isEmpty());
    for (var result1 : result) {
      Assert.assertTrue(((Number) result1.getProperty("id")).intValue() < 10 - 3);
    }

    result = executeQuery("select * from account where id < 3 * 2", session);
    Assert.assertFalse(result.isEmpty());
    for (var element : result) {
      Assert.assertTrue(((Number) element.getProperty("id")).intValue() < 3 << 1);
    }

    result = executeQuery("select * from account where id < 120 / 20", session);
    Assert.assertFalse(result.isEmpty());
    for (var item : result) {
      Assert.assertTrue(((Number) item.getProperty("id")).intValue() < 120 / 20);
    }

    result = executeQuery("select * from account where id < 27 % 10", session);
    Assert.assertFalse(result.isEmpty());
    for (var value : result) {
      Assert.assertTrue(((Number) value.getProperty("id")).intValue() < 27 % 10);
    }

    result = executeQuery("select * from account where id = id * 1", session);
    Assert.assertFalse(result.isEmpty());

    var result2 =
        executeQuery("select count(*) as tot from account where id >= 0", session);
    Assert.assertEquals(result.size(), ((Number) result2.getFirst().getProperty("tot")).intValue());
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
      Assert.assertEquals(
          ((EntityImpl)
              (d.<Collection<Identifiable>>getProperty("addresses")).iterator().next()
                  .getRecord(session))
              .getSchemaClass()
              .getName(session),
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
      Assert.assertTrue(
          d.getProperty("addresses") == null
              || (d.<Collection<Identifiable>>getProperty("addresses")).isEmpty()
              || !((EntityImpl)
              (d.<Collection<Identifiable>>getProperty("addresses"))
                  .iterator()
                  .next()
                  .getRecord(session))
              .getSchemaClass()
              .getName(session)
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
      Assert.assertEquals(
          ((EntityImpl)
              (d.<Collection<Identifiable>>getProperty("addresses")).iterator().next()
                  .getRecord(session))
              .getSchemaClass()
              .getName(session),
          "Address");
    }
  }

  public void testParams() {
    var test = session.getMetadata().getSchema().getClass("test");
    if (test == null) {
      test = session.getMetadata().getSchema().createClass("test");
      test.createProperty(session, "f1", PropertyType.STRING);
      test.createProperty(session, "f2", PropertyType.STRING);
    }
    var document = ((EntityImpl) session.newEntity(test));
    document.field("f1", "a").field("f2", "a");

    session.begin();
    session.commit();

    Map<String, Object> parameters = new HashMap<String, Object>();
    parameters.put("p1", "a");
    session.query(new SQLSynchQuery<EntityImpl>("select from test where (f1 = :p1)"),
        parameters);
    session.query(
        new SQLSynchQuery<EntityImpl>("select from test where f1 = :p1 and f2 = :p1"),
        parameters);
  }

  @Test
  public void queryInstanceOfOperator() {
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
    if (!facClass.existsProperty(session, "date")) {
      facClass.createProperty(session, "date", PropertyType.DATE);
    }

    final var currentYear = Calendar.getInstance();
    final var oneYearAgo = Calendar.getInstance();
    oneYearAgo.add(Calendar.YEAR, -1);

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity(facClass));
    doc1.field("context", "test");
    doc1.field("date", currentYear.getTime());

    var doc2 = ((EntityImpl) session.newEntity(facClass));
    doc2.field("context", "test");
    doc2.field("date", oneYearAgo.getTime());

    session.commit();

    var result =
        session.query(
            "select * from " + facClass.getName(session) + " where context = 'test' order by date",
            1).toList();

    var smaller = Calendar.getInstance();
    smaller.setTime(result.getFirst().getProperty("date"));
    Assert.assertEquals(smaller.get(Calendar.YEAR), oneYearAgo.get(Calendar.YEAR));

    result =
        session.query(
            "select * from "
                + facClass.getName(session)
                + " where context = 'test' order by date DESC",
            1).toList();

    var bigger = Calendar.getInstance();
    bigger.setTime(result.getFirst().getProperty("date"));
    Assert.assertEquals(bigger.get(Calendar.YEAR), currentYear.get(Calendar.YEAR));
  }

  @Test
  public void queryWithTwoRidInWhere() {
    var clusterId = session.getClusterIdByName("profile");

    var positions = getValidPositions(clusterId);

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
                + clusterId
                + ":"
                + positions.get(5)
                + "] or @rid in [#"
                + clusterId
                + ":"
                + positions.get(25)
                + "]) AND @rid > ? LIMIT 10000",
            session,
            new RecordId(clusterId, minPos));

    Assert.assertEquals(resultset.size(), 1);

    Assert.assertEquals(resultset.getFirst().getProperty("oid"),
        new RecordId(clusterId, maxPos).toString());
  }

  @Test
  public void testSelectFromListParameter() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    placeClass.createProperty(session, "id", PropertyType.STRING);
    placeClass.createProperty(session, "descr", PropertyType.STRING);
    placeClass.createIndex(session, "place_id_index", INDEX_TYPE.UNIQUE, "id");

    var odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.field("id", "adda");
    odoc.field("descr", "Adda");

    session.begin();
    session.commit();

    odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.field("id", "lago_di_como");
    odoc.field("descr", "Lago di Como");

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
    placeClass.createProperty(session, "id", PropertyType.STRING);
    placeClass.createProperty(session, "descr", PropertyType.STRING);
    placeClass.createIndex(session, "place_id_index", INDEX_TYPE.UNIQUE, "id");

    List<RID> inputValues = new ArrayList<RID>();

    var odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.field("id", "adda");
    odoc.field("descr", "Adda");

    session.begin();
    session.commit();

    inputValues.add(odoc.getIdentity());

    odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.field("id", "lago_di_como");
    odoc.field("descr", "Lago di Como");

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
    Assert.assertTrue(secondPlaceId.getClusterId() < famousPlaceId.getClusterId());
    Assert.assertTrue(secondPlaceId.getClusterPosition() > famousPlaceId.getClusterPosition());

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
  public void queryAsynch() {
    final var sqlOne = "select * from company where id between 4 and 7";
    final var sqlTwo =
        "select $names let $names = (select EXPAND( addresses.city ) as city from Account where"
            + " addresses.size() > 0 )";

    final List<EntityImpl> synchResultOne =
        session.command(new SQLSynchQuery<EntityImpl>(sqlOne)).execute(session);
    final List<EntityImpl> synchResultTwo =
        session.command(new SQLSynchQuery<EntityImpl>(sqlTwo)).execute(session);

    Assert.assertFalse(synchResultOne.isEmpty());
    Assert.assertFalse(synchResultTwo.isEmpty());

    final List<EntityImpl> asynchResultOne = new ArrayList<EntityImpl>();
    final List<EntityImpl> asynchResultTwo = new ArrayList<EntityImpl>();
    final var endOneCalled = new AtomicBoolean();
    final var endTwoCalled = new AtomicBoolean();

    session
        .command(
            new SQLAsynchQuery<EntityImpl>(
                sqlOne,
                new CommandResultListener() {
                  @Override
                  public boolean result(@Nonnull DatabaseSessionInternal session, Object iRecord) {
                    asynchResultOne.add((EntityImpl) iRecord);
                    return true;
                  }

                  @Override
                  public void end(@Nonnull DatabaseSessionInternal db) {
                    endOneCalled.set(true);

                    SQLSelectTestNewTest.this.session
                        .command(
                            new SQLAsynchQuery<EntityImpl>(
                                sqlTwo,
                                new CommandResultListener() {
                                  @Override
                                  public boolean result(@Nonnull DatabaseSessionInternal session,
                                      Object iRecord) {
                                    asynchResultTwo.add((EntityImpl) iRecord);
                                    return true;
                                  }

                                  @Override
                                  public void end(@Nonnull DatabaseSessionInternal session) {
                                    endTwoCalled.set(true);
                                  }

                                  @Override
                                  public Object getResult() {
                                    return null;
                                  }
                                }))
                        .execute(SQLSelectTestNewTest.this.session);
                  }

                  @Override
                  public Object getResult() {
                    return null;
                  }
                }))
        .execute(session);

    Assert.assertTrue(endOneCalled.get());
    Assert.assertTrue(endTwoCalled.get());

    Assert.assertTrue(
        EntityHelper.compareCollections(
            session, synchResultTwo, session, asynchResultTwo, null),
        "synchResultTwo=" + synchResultTwo.size() + " asynchResultTwo=" + asynchResultTwo.size());
    Assert.assertTrue(
        EntityHelper.compareCollections(
            session, synchResultOne, session, asynchResultOne, null),
        "synchResultOne=" + synchResultOne.size() + " asynchResultOne=" + asynchResultOne.size());
  }

  @Test
  public void queryAsynchHalfForheFirstQuery() {
    final var sqlOne = "select * from company where id between 4 and 7";
    final var sqlTwo =
        "select $names let $names = (select EXPAND( addresses.city ) as city from Account where"
            + " addresses.size() > 0 )";

    final List<EntityImpl> synchResultOne =
        session.command(new SQLSynchQuery<EntityImpl>(sqlOne)).execute(session);
    final List<EntityImpl> synchResultTwo =
        session.command(new SQLSynchQuery<EntityImpl>(sqlTwo)).execute(session);

    Assert.assertFalse(synchResultOne.isEmpty());
    Assert.assertFalse(synchResultTwo.isEmpty());

    final List<EntityImpl> asynchResultOne = new ArrayList<EntityImpl>();
    final List<EntityImpl> asynchResultTwo = new ArrayList<EntityImpl>();
    final var endOneCalled = new AtomicBoolean();
    final var endTwoCalled = new AtomicBoolean();

    session
        .command(
            new SQLAsynchQuery<EntityImpl>(
                sqlOne,
                new CommandResultListener() {
                  @Override
                  public boolean result(@Nonnull DatabaseSessionInternal session, Object iRecord) {
                    asynchResultOne.add((EntityImpl) iRecord);
                    return asynchResultOne.size() < synchResultOne.size() / 2;
                  }

                  @Override
                  public void end(@Nonnull DatabaseSessionInternal db) {
                    endOneCalled.set(true);

                    SQLSelectTestNewTest.this.session
                        .command(
                            new SQLAsynchQuery<EntityImpl>(
                                sqlTwo,
                                new CommandResultListener() {
                                  @Override
                                  public boolean result(@Nonnull DatabaseSessionInternal session,
                                      Object iRecord) {
                                    asynchResultTwo.add((EntityImpl) iRecord);
                                    return true;
                                  }

                                  @Override
                                  public void end(@Nonnull DatabaseSessionInternal session) {
                                    endTwoCalled.set(true);
                                  }

                                  @Override
                                  public Object getResult() {
                                    return null;
                                  }
                                }))
                        .execute(SQLSelectTestNewTest.this.session);
                  }

                  @Override
                  public Object getResult() {
                    return null;
                  }
                }))
        .execute(session);

    Assert.assertTrue(endOneCalled.get());
    Assert.assertTrue(endTwoCalled.get());

    Assert.assertTrue(
        EntityHelper.compareCollections(
            session,
            synchResultOne.subList(0, synchResultOne.size() / 2),
            session,
            asynchResultOne,
            null));
    Assert.assertTrue(
        EntityHelper.compareCollections(
            session, synchResultTwo, session, asynchResultTwo, null));
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

    EntityImpl res =
        session.command(new CommandSQL("explain select from OUser order by @rid desc"))
            .execute(session);
    Assert.assertNull(res.field("orderByElapsed"));
  }

  public void testQueryParameterNotPersistent() {
    var doc = ((EntityImpl) session.newEntity());
    doc.field("test", "test");
    session.query("select from OUser where @rid = ?", doc).close();
    Assert.assertTrue(doc.isDirty());
  }

  public void testQueryLetExecutedOnce() {
    final List<Identifiable> result =
        session.query(
            new SQLSynchQuery<Identifiable>(
                "select name, $counter as counter from OUser let $counter = eval(\"$counter +"
                    + " 1\")"));

    Assert.assertFalse(result.isEmpty());
    var i = 1;
    for (var r : result) {
      Assert.assertEquals(((EntityImpl) r.getRecord(session)).<Object>field("counter"), i++);
    }
  }

  @Test
  public void testMultipleClustersWithPagination() throws Exception {
    final var cls = session.getMetadata().getSchema()
        .createClass("PersonMultipleClusters");
    try {
      Set<String> names =
          new HashSet<String>(Arrays.asList("Luca", "Jill", "Sara", "Tania", "Gianluca", "Marco"));
      for (var n : names) {
        session.begin();
        ((EntityImpl) session.newEntity("PersonMultipleClusters")).field("First", n);

        session.commit();
      }

      var query =
          new SQLSynchQuery<EntityImpl>(
              "select from PersonMultipleClusters where @rid > ? limit 2");
      List<EntityImpl> resultset = session.query(query, new ChangeableRecordId());

      while (!resultset.isEmpty()) {
        final RID last = resultset.getLast().getIdentity();
        for (var personDoc : resultset) {
          Assert.assertTrue(names.contains(personDoc.<String>getProperty("First")));
          Assert.assertTrue(names.remove(personDoc.<String>getProperty("First")));
        }

        resultset = session.query(query, last);
      }

      Assert.assertTrue(names.isEmpty());

    } finally {
      session.getMetadata().getSchema().dropClass("PersonMultipleClusters");
    }
  }

  @Test
  public void testOutFilterInclude() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("TestOutFilterInclude", schema.getClass("V"));
    session.command("create class linkedToOutFilterInclude extends E").close();
    session.command("insert into TestOutFilterInclude content { \"name\": \"one\" }").close();
    session.command("insert into TestOutFilterInclude content { \"name\": \"two\" }").close();
    session
        .command(
            "create edge linkedToOutFilterInclude from (select from TestOutFilterInclude where name"
                + " = 'one') to (select from TestOutFilterInclude where name = 'two')")
        .close();

    final List<Identifiable> result =
        session.query(
            new SQLSynchQuery<Identifiable>(
                "select"
                    + " expand(out('linkedToOutFilterInclude')[@class='TestOutFilterInclude'].include('@rid'))"
                    + " from TestOutFilterInclude where name = 'one'"));

    Assert.assertEquals(result.size(), 1);

    for (var r : result) {
      Assert.assertNull(((EntityImpl) r.getRecord(session)).field("name"));
    }
  }

  private List<Long> getValidPositions(int clusterId) {
    final List<Long> positions = new ArrayList<Long>();

    final RecordIteratorCluster<EntityImpl> iteratorCluster =
        session.browseCluster(session.getClusterNameById(clusterId));

    for (var i = 0; i < 100; i++) {
      if (!iteratorCluster.hasNext()) {
        break;
      }

      var doc = iteratorCluster.next();
      positions.add(doc.getIdentity().getClusterPosition());
    }
    return positions;
  }

  @Test
  public void testBinaryClusterSelect() {
    session.command("create blob cluster binarycluster").close();
    session.begin();
    session.newBlob(new byte[]{1, 2, 3});
    session.commit();

    List<Identifiable> result =
        session.query(new SQLSynchQuery<Identifiable>("select from cluster:binarycluster"));

    Assert.assertEquals(result.size(), 1);

    session.command("delete from cluster:binarycluster").close();

    result = session.query(
        new SQLSynchQuery<Identifiable>("select from cluster:binarycluster"));

    Assert.assertEquals(result.size(), 0);
  }

  @Test
  public void testExpandSkip() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    final var cls = schema.createClass("TestExpandSkip", v);
    cls.createProperty(session, "name", PropertyType.STRING);
    cls.createIndex(session, "TestExpandSkip.name", INDEX_TYPE.UNIQUE, "name");
    session.command("CREATE VERTEX TestExpandSkip set name = '1'").close();
    session.command("CREATE VERTEX TestExpandSkip set name = '2'").close();
    session.command("CREATE VERTEX TestExpandSkip set name = '3'").close();
    session.command("CREATE VERTEX TestExpandSkip set name = '4'").close();

    session
        .command(
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

    session.command("CREATE VERTEX TestPolymorphicEdges_V set name = '1'").close();
    session.command("CREATE VERTEX TestPolymorphicEdges_V set name = '2'").close();
    session.command("CREATE VERTEX TestPolymorphicEdges_V set name = '3'").close();

    session
        .command(
            "CREATE EDGE TestPolymorphicEdges_E1 FROM (SELECT FROM TestPolymorphicEdges_V WHERE"
                + " name = '1') to (SELECT FROM TestPolymorphicEdges_V WHERE name = '2')")
        .close();
    session
        .command(
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
    session.command("CREATE VERTEX TestSizeOfLink set name = '1'").close();
    session.command("CREATE VERTEX TestSizeOfLink set name = '2'").close();
    session.command("CREATE VERTEX TestSizeOfLink set name = '3'").close();
    session
        .command(
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
    session.command("CREATE VERTEX EmbeddedMapAndDotNotation set name = 'foo'").close();
    session
        .command(
            "CREATE VERTEX EmbeddedMapAndDotNotation set data = {\"bar\": \"baz\", \"quux\":"
                + " 1}, name = 'bar'")
        .close();
    session
        .command(
            "CREATE EDGE E FROM (SELECT FROM EmbeddedMapAndDotNotation WHERE name = 'foo') to"
                + " (SELECT FROM EmbeddedMapAndDotNotation WHERE name = 'bar')")
        .close();

    List<Identifiable> result =
        session.query(
            new SQLSynchQuery<Identifiable>(
                " select out().data as result from (select from EmbeddedMapAndDotNotation where"
                    + " name = 'foo')"));
    Assert.assertEquals(result.size(), 1);
    EntityImpl doc = result.getFirst().getRecord(session);
    Assert.assertNotNull(doc);
    List list = doc.field("result");
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
    session.command("CREATE VERTEX LetWithQuotedValue set name = \"\\\"foo\\\"\"").close();

    List<Identifiable> result =
        session.query(
            new SQLSynchQuery<Identifiable>(
                " select expand($a) let $a = (select from LetWithQuotedValue where name ="
                    + " \"\\\"foo\\\"\")"));
    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testNestedProjection1() {
    var className = this.getClass().getSimpleName() + "_testNestedProjection1";
    session.command("create class " + className).close();

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
