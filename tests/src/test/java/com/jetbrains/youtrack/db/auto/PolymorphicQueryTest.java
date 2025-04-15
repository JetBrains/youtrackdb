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

import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 *
 */
public class PolymorphicQueryTest extends BaseDBTest {

  @Parameters(value = "remote")
  public PolymorphicQueryTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    session.execute("create class IndexInSubclassesTestBase").close();
    session.execute("create property IndexInSubclassesTestBase.name string").close();

    session
        .execute("create class IndexInSubclassesTestChild1 extends IndexInSubclassesTestBase")
        .close();
    session
        .execute(
            "create index IndexInSubclassesTestChild1.name on IndexInSubclassesTestChild1 (name)"
                + " notunique")
        .close();

    session
        .execute("create class IndexInSubclassesTestChild2 extends IndexInSubclassesTestBase")
        .close();
    session
        .execute(
            "create index IndexInSubclassesTestChild2.name on IndexInSubclassesTestChild2 (name)"
                + " notunique")
        .close();

    session.execute("create class IndexInSubclassesTestBaseFail").close();
    session.execute("create property IndexInSubclassesTestBaseFail.name string").close();

    session
        .execute(
            "create class IndexInSubclassesTestChild1Fail extends IndexInSubclassesTestBaseFail")
        .close();
    // database.command(
    // new CommandSQL("create index IndexInSubclassesTestChild1Fail.name on
    // IndexInSubclassesTestChild1Fail (name) notunique"))
    // .execute();

    session
        .execute(
            "create class IndexInSubclassesTestChild2Fail extends IndexInSubclassesTestBaseFail")
        .close();
    session
        .execute(
            "create index IndexInSubclassesTestChild2Fail.name on IndexInSubclassesTestChild2Fail"
                + " (name) notunique")
        .close();

    session.execute("create class GenericCrash").close();
    session.execute("create class SpecificCrash extends GenericCrash").close();
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    session.begin();
    session.command("delete from IndexInSubclassesTestBase");
    session.command("delete from IndexInSubclassesTestChild1");
    session.command("delete from IndexInSubclassesTestChild2");

    session.command("delete from IndexInSubclassesTestBaseFail");
    session.command("delete from IndexInSubclassesTestChild1Fail");
    session.command("delete from IndexInSubclassesTestChild2Fail");
    session.commit();
  }

  @Test
  public void testSubclassesIndexes() throws Exception {
    session.begin();

    for (var i = 0; i < 10000; i++) {

      final var doc1 = ((EntityImpl) session.newEntity("IndexInSubclassesTestChild1"));
      doc1.setProperty("name", "name" + i);

      final var doc2 = ((EntityImpl) session.newEntity("IndexInSubclassesTestChild2"));
      doc2.setProperty("name", "name" + i);

      if (i % 100 == 0) {
        session.commit();
        session.begin();
      }
    }
    session.commit();

    session.begin();
    var result =
        session.query(
            "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                + " 'name9999' order by name ASC").toList();
    Assert.assertEquals(result.size(), 6);
    var entity1 = result.getFirst();
    String lastName = entity1.getProperty("name");

    for (var i = 1; i < result.size(); i++) {
      var current = result.get(i);
      String currentName = current.getProperty("name");
      Assert.assertTrue(lastName.compareTo(currentName) <= 0);
      lastName = currentName;
    }

    result =
        session.query(
            "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                + " 'name9999' order by name DESC").toList();
    Assert.assertEquals(result.size(), 6);
    var entity = result.getFirst();
    lastName = entity.getProperty("name");
    for (var i = 1; i < result.size(); i++) {
      var current = result.get(i);
      String currentName = current.getProperty("name");
      Assert.assertTrue(lastName.compareTo(currentName) >= 0);
      lastName = currentName;
    }
    session.commit();
  }

  @Test
  public void testBaseWithoutIndexAndSubclassesIndexes() throws Exception {
    session.begin();

    for (var i = 0; i < 10000; i++) {
      final var doc0 = session.newInstance("IndexInSubclassesTestBase");
      doc0.setProperty("name", "name" + i);

      final var doc1 = ((EntityImpl) session.newEntity("IndexInSubclassesTestChild1"));
      doc1.setProperty("name", "name" + i);

      final var doc2 = ((EntityImpl) session.newEntity("IndexInSubclassesTestChild2"));
      doc2.setProperty("name", "name" + i);

      if (i % 100 == 0) {
        session.commit();
        session.begin();
      }
    }
    session.commit();

    session.begin();
    var result =
        session.query(

            "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                + " 'name9999' order by name ASC").toList();
    Assert.assertEquals(result.size(), 9);
    var entity1 = result.getFirst();
    String lastName = entity1.getProperty("name");
    for (var i = 1; i < result.size(); i++) {
      var current = result.get(i);
      String currentName = current.getProperty("name");
      Assert.assertTrue(lastName.compareTo(currentName) <= 0);
      lastName = currentName;
    }

    result =
        session.query(
            "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                + " 'name9999' order by name DESC").toList();
    Assert.assertEquals(result.size(), 9);
    var entity = result.getFirst();
    lastName = entity.getProperty("name");
    for (var i = 1; i < result.size(); i++) {
      var current = result.get(i);
      String currentName = current.getProperty("name");
      Assert.assertTrue(lastName.compareTo(currentName) >= 0);
      lastName = currentName;
    }
    session.commit();
  }

  @Test
  public void testSubclassesIndexesFailed() throws Exception {
    session.begin();

    var profiler = ProfilerStub.INSTANCE;

    for (var i = 0; i < 10000; i++) {

      final var doc1 = ((EntityImpl) session.newEntity("IndexInSubclassesTestChild1Fail"));
      doc1.setProperty("name", "name" + i);

      final var doc2 = ((EntityImpl) session.newEntity("IndexInSubclassesTestChild2Fail"));
      doc2.setProperty("name", "name" + i);

      if (i % 100 == 0) {
        session.commit();
        session.begin();
      }
    }
    session.commit();

    var result =
        session.query(
            "select from IndexInSubclassesTestBaseFail where name > 'name9995' and name <"
                + " 'name9999' order by name ASC");
    Assert.assertEquals(result.stream().count(), 6);

  }

  @Test
  public void testIteratorOnSubclassWithoutValues() {
    session.begin();
    for (var i = 0; i < 2; i++) {
      final var doc1 = ((EntityImpl) session.newEntity("GenericCrash"));
      doc1.setProperty("name", "foo");
    }
    session.commit();

    session.begin();
    // crashed with YTIOException, issue #3632
    var result =
        session.query("SELECT FROM GenericCrash WHERE @class='GenericCrash' ORDER BY @rid DESC");

    var count = 0;
    while (result.hasNext()) {
      var doc = result.next();
      Assert.assertEquals(doc.getProperty("name"), "foo");
      count++;
    }
    Assert.assertEquals(count, 2);
    session.commit();
  }
}
