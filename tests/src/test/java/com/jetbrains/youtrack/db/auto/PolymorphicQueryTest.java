/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 *
 */
public class PolymorphicQueryTest extends BaseDBTest {

  @Parameters(value = "remote")
  public PolymorphicQueryTest(boolean remote) {
    super(remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    database.command("create class IndexInSubclassesTestBase").close();
    database.command("create property IndexInSubclassesTestBase.name string").close();

    database
        .command("create class IndexInSubclassesTestChild1 extends IndexInSubclassesTestBase")
        .close();
    database
        .command(
            "create index IndexInSubclassesTestChild1.name on IndexInSubclassesTestChild1 (name)"
                + " notunique")
        .close();

    database
        .command("create class IndexInSubclassesTestChild2 extends IndexInSubclassesTestBase")
        .close();
    database
        .command(
            "create index IndexInSubclassesTestChild2.name on IndexInSubclassesTestChild2 (name)"
                + " notunique")
        .close();

    database.command("create class IndexInSubclassesTestBaseFail").close();
    database.command("create property IndexInSubclassesTestBaseFail.name string").close();

    database
        .command(
            "create class IndexInSubclassesTestChild1Fail extends IndexInSubclassesTestBaseFail")
        .close();
    // database.command(
    // new CommandSQL("create index IndexInSubclassesTestChild1Fail.name on
    // IndexInSubclassesTestChild1Fail (name) notunique"))
    // .execute();

    database
        .command(
            "create class IndexInSubclassesTestChild2Fail extends IndexInSubclassesTestBaseFail")
        .close();
    database
        .command(
            "create index IndexInSubclassesTestChild2Fail.name on IndexInSubclassesTestChild2Fail"
                + " (name) notunique")
        .close();

    database.command("create class GenericCrash").close();
    database.command("create class SpecificCrash extends GenericCrash").close();
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    database.command("delete from IndexInSubclassesTestBase").close();
    database.command("delete from IndexInSubclassesTestChild1").close();
    database.command("delete from IndexInSubclassesTestChild2").close();

    database.command("delete from IndexInSubclassesTestBaseFail").close();
    database.command("delete from IndexInSubclassesTestChild1Fail").close();
    database.command("delete from IndexInSubclassesTestChild2Fail").close();
  }

  @Test
  public void testSubclassesIndexes() throws Exception {
    database.begin();

    ProfilerStub profiler = ProfilerStub.INSTANCE;

    long indexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long indexUsageReverted = profiler.getCounter("db.demo.query.indexUseAttemptedAndReverted");

    if (indexUsage < 0) {
      indexUsage = 0;
    }

    if (indexUsageReverted < 0) {
      indexUsageReverted = 0;
    }

    for (int i = 0; i < 10000; i++) {

      final EntityImpl doc1 = new EntityImpl("IndexInSubclassesTestChild1");
      doc1.field("name", "name" + i);
      doc1.save();

      final EntityImpl doc2 = new EntityImpl("IndexInSubclassesTestChild2");
      doc2.field("name", "name" + i);
      doc2.save();
      if (i % 100 == 0) {
        database.commit();
      }
    }
    database.commit();

    List<EntityImpl> result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                    + " 'name9999' order by name ASC"));
    Assert.assertEquals(result.size(), 6);
    String lastName = result.get(0).field("name");

    for (int i = 1; i < result.size(); i++) {
      EntityImpl current = result.get(i);
      String currentName = current.field("name");
      Assert.assertTrue(lastName.compareTo(currentName) <= 0);
      lastName = currentName;
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), indexUsage + 2);
    long reverted = profiler.getCounter("db.demo.query.indexUseAttemptedAndReverted");
    Assert.assertEquals(reverted < 0 ? 0 : reverted, indexUsageReverted);

    result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                    + " 'name9999' order by name DESC"));
    Assert.assertEquals(result.size(), 6);
    lastName = result.get(0).field("name");
    for (int i = 1; i < result.size(); i++) {
      EntityImpl current = result.get(i);
      String currentName = current.field("name");
      Assert.assertTrue(lastName.compareTo(currentName) >= 0);
      lastName = currentName;
    }
  }

  @Test
  public void testBaseWithoutIndexAndSubclassesIndexes() throws Exception {
    database.begin();

    ProfilerStub profiler = ProfilerStub.INSTANCE;

    long indexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long indexUsageReverted = profiler.getCounter("db.demo.query.indexUseAttemptedAndReverted");

    if (indexUsage < 0) {
      indexUsage = 0;
    }

    if (indexUsageReverted < 0) {
      indexUsageReverted = 0;
    }

    for (int i = 0; i < 10000; i++) {
      final EntityImpl doc0 = new EntityImpl("IndexInSubclassesTestBase");
      doc0.field("name", "name" + i);
      doc0.save();

      final EntityImpl doc1 = new EntityImpl("IndexInSubclassesTestChild1");
      doc1.field("name", "name" + i);
      doc1.save();

      final EntityImpl doc2 = new EntityImpl("IndexInSubclassesTestChild2");
      doc2.field("name", "name" + i);
      doc2.save();
      if (i % 100 == 0) {
        database.commit();
      }
    }
    database.commit();

    List<EntityImpl> result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                    + " 'name9999' order by name ASC"));
    Assert.assertEquals(result.size(), 9);
    String lastName = result.get(0).field("name");
    for (int i = 1; i < result.size(); i++) {
      EntityImpl current = result.get(i);
      String currentName = current.field("name");
      Assert.assertTrue(lastName.compareTo(currentName) <= 0);
      lastName = currentName;
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), indexUsage + 2);

    long reverted = profiler.getCounter("db.demo.query.indexUseAttemptedAndReverted");
    Assert.assertEquals(reverted < 0 ? 0 : reverted, indexUsageReverted);

    result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                    + " 'name9999' order by name DESC"));
    Assert.assertEquals(result.size(), 9);
    lastName = result.get(0).field("name");
    for (int i = 1; i < result.size(); i++) {
      EntityImpl current = result.get(i);
      String currentName = current.field("name");
      Assert.assertTrue(lastName.compareTo(currentName) >= 0);
      lastName = currentName;
    }
  }

  @Test
  public void testSubclassesIndexesFailed() throws Exception {
    database.begin();

    ProfilerStub profiler = ProfilerStub.INSTANCE;

    for (int i = 0; i < 10000; i++) {

      final EntityImpl doc1 = new EntityImpl("IndexInSubclassesTestChild1Fail");
      doc1.field("name", "name" + i);
      doc1.save();

      final EntityImpl doc2 = new EntityImpl("IndexInSubclassesTestChild2Fail");
      doc2.field("name", "name" + i);
      doc2.save();
      if (i % 100 == 0) {
        database.commit();
      }
    }
    database.commit();

    long indexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long indexUsageReverted = profiler.getCounter("db.demo.query.indexUseAttemptedAndReverted");

    if (indexUsage < 0) {
      indexUsage = 0;
    }

    if (indexUsageReverted < 0) {
      indexUsageReverted = 0;
    }

    ResultSet result =
        database.query(
            "select from IndexInSubclassesTestBaseFail where name > 'name9995' and name <"
                + " 'name9999' order by name ASC");
    Assert.assertEquals(result.stream().count(), 6);

    long lastIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long lastIndexUsageReverted = profiler.getCounter("db.demo.query.indexUseAttemptedAndReverted");
    if (lastIndexUsage < 0) {
      lastIndexUsage = 0;
    }

    if (lastIndexUsageReverted < 0) {
      lastIndexUsageReverted = 0;
    }

    Assert.assertEquals(lastIndexUsage - indexUsage, lastIndexUsageReverted - indexUsageReverted);
  }

  @Test
  public void testIteratorOnSubclassWithoutValues() {
    for (int i = 0; i < 2; i++) {
      final EntityImpl doc1 = new EntityImpl("GenericCrash");
      database.begin();
      doc1.field("name", "foo");
      doc1.save();
    }

    // crashed with YTIOException, issue #3632
    ResultSet result =
        database.query("SELECT FROM GenericCrash WHERE @class='GenericCrash' ORDER BY @rid DESC");

    int count = 0;
    while (result.hasNext()) {
      Result doc = result.next();
      Assert.assertEquals(doc.getProperty("name"), "foo");
      count++;
    }
    Assert.assertEquals(count, 2);
  }
}
