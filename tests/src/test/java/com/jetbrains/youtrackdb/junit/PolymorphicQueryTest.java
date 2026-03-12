package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Tests for polymorphic queries across class hierarchies with indexes.
 */
public class PolymorphicQueryTest extends BaseDBJUnit5Test {

  @Override
  @BeforeAll
  void beforeAll() throws Exception {
    super.beforeAll();

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

  @Override
  @BeforeEach
  void beforeEach() throws Exception {
    super.beforeEach();

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
  @Order(2)
  void testSubclassesIndexes() {
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
                + " 'name9999' order by name ASC")
            .toList();
    assertEquals(6, result.size());
    var entity1 = result.getFirst();
    String lastName = entity1.getProperty("name");

    for (var i = 1; i < result.size(); i++) {
      var current = result.get(i);
      String currentName = current.getProperty("name");
      assertTrue(lastName.compareTo(currentName) <= 0);
      lastName = currentName;
    }

    result =
        session.query(
            "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                + " 'name9999' order by name DESC")
            .toList();
    assertEquals(6, result.size());
    var entity = result.getFirst();
    lastName = entity.getProperty("name");
    for (var i = 1; i < result.size(); i++) {
      var current = result.get(i);
      String currentName = current.getProperty("name");
      assertTrue(lastName.compareTo(currentName) >= 0);
      lastName = currentName;
    }
    session.commit();
  }

  @Test
  @Order(1)
  void testBaseWithoutIndexAndSubclassesIndexes() {
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
                + " 'name9999' order by name ASC")
            .toList();
    assertEquals(9, result.size());
    var entity1 = result.getFirst();
    String lastName = entity1.getProperty("name");
    for (var i = 1; i < result.size(); i++) {
      var current = result.get(i);
      String currentName = current.getProperty("name");
      assertTrue(lastName.compareTo(currentName) <= 0);
      lastName = currentName;
    }

    result =
        session.query(
            "select from IndexInSubclassesTestBase where name > 'name9995' and name <"
                + " 'name9999' order by name DESC")
            .toList();
    assertEquals(9, result.size());
    var entity = result.getFirst();
    lastName = entity.getProperty("name");
    for (var i = 1; i < result.size(); i++) {
      var current = result.get(i);
      String currentName = current.getProperty("name");
      assertTrue(lastName.compareTo(currentName) >= 0);
      lastName = currentName;
    }
    session.commit();
  }

  @Test
  @Order(3)
  void testSubclassesIndexesFailed() {
    session.begin();

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
    assertEquals(6, result.stream().count());
  }

  @Test
  @Order(4)
  void testIteratorOnSubclassWithoutValues() {
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
      assertEquals("foo", doc.getProperty("name"));
      count++;
    }
    assertEquals(2, count);
    session.commit();
  }
}
