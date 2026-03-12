package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class SQLDeleteEdgeTest extends BaseDBJUnit5Test {

  @Test
  @Order(1)
  void testDeleteFromTo() {
    session.execute("CREATE CLASS testFromToOneE extends E").close();
    session.execute("CREATE CLASS testFromToTwoE extends E").close();
    session.execute("CREATE CLASS testFromToV extends V").close();

    session.begin();
    session.execute("create vertex testFromToV set name = 'Luca'").close();
    session.execute("create vertex testFromToV set name = 'Luca'").close();
    session.commit();

    session.begin();
    var rs = session.query("select from testFromToV");
    var result = rs.toList();
    rs.close();
    session.commit();

    session.begin();
    session
        .execute(
            "CREATE EDGE testFromToOneE from "
                + result.get(1).getIdentity()
                + " to "
                + result.get(0).getIdentity())
        .close();
    session
        .execute(
            "CREATE EDGE testFromToTwoE from "
                + result.get(1).getIdentity()
                + " to "
                + result.get(0).getIdentity())
        .close();
    session.commit();

    session.begin();
    try (var resultTwo =
        session.query("select expand(outE()) from " + result.get(1).getIdentity())) {
      assertEquals(2, resultTwo.stream().count());
    }
    session.commit();

    session.begin();
    session
        .execute(
            "DELETE EDGE testFromToTwoE from "
                + result.get(1).getIdentity()
                + " to"
                + result.get(0).getIdentity())
        .close();

    try (var resultTwo = session.query(
        "select expand(outE()) from " + result.get(1).getIdentity())) {
      assertEquals(1, resultTwo.stream().count());
    }

    session.execute("DELETE FROM testFromToOneE unsafe").close();
    session.execute("DELETE FROM testFromToTwoE unsafe").close();
    session.execute("DELETE VERTEX testFromToV").close();
    session.commit();
  }

  @Test
  @Order(2)
  void testDeleteFrom() {
    session.execute("CREATE CLASS testFromOneE extends E").close();
    session.execute("CREATE CLASS testFromTwoE extends E").close();
    session.execute("CREATE CLASS testFromV extends V").close();

    session.begin();
    session.execute("create vertex testFromV set name = 'Luca'").close();
    session.execute("create vertex testFromV set name = 'Luca'").close();
    session.commit();

    session.begin();
    List<Result> resultList;
    try (var rs = session.query("select from testFromV")) {
      resultList = rs.toList();
    }
    session.commit();

    session.begin();
    session
        .execute(
            "CREATE EDGE testFromOneE from "
                + resultList.get(1).getIdentity()
                + " to "
                + resultList.get(0).getIdentity())
        .close();
    session
        .execute(
            "CREATE EDGE testFromTwoE from "
                + resultList.get(1).getIdentity()
                + " to "
                + resultList.get(0).getIdentity())
        .close();
    session.commit();

    session.begin();
    List<Result> resultListTwo;
    try (var rs = session.query("select expand(outE()) from " + resultList.get(1).getIdentity())) {
      resultListTwo = rs.toList();
    }
    assertEquals(2, resultListTwo.size());
    session.commit();

    try {
      session.begin();
      session.execute("DELETE EDGE testFromTwoE from " + resultList.get(1).getIdentity()).close();
      session.commit();
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }

    session.begin();
    try (var rs = session.query("select expand(outE()) from " + resultList.get(1).getIdentity())) {
      resultListTwo = rs.toList();
      assertEquals(1, resultListTwo.size());
    }
    session.commit();

    session.begin();
    session.execute("DELETE FROM testFromOneE unsafe").close();
    session.execute("DELETE FROM testFromTwoE unsafe").close();
    session.execute("DELETE VERTEX testFromV").close();
    session.commit();
  }

  @Test
  @Order(3)
  void testDeleteTo() {
    session.execute("CREATE CLASS testToOneE extends E").close();
    session.execute("CREATE CLASS testToTwoE extends E").close();
    session.execute("CREATE CLASS testToV extends V").close();

    session.begin();
    session.execute("create vertex testToV set name = 'Luca'").close();
    session.execute("create vertex testToV set name = 'Luca'").close();
    session.commit();

    session.begin();
    var rs = session.query("select from testToV");
    var entityList = rs.toEntityList();
    rs.close();
    session.commit();

    session.begin();
    session
        .execute(
            "CREATE EDGE testToOneE from "
                + entityList.get(1).getIdentity()
                + " to "
                + entityList.get(0).getIdentity())
        .close();
    session
        .execute(
            "CREATE EDGE testToTwoE from "
                + entityList.get(1).getIdentity()
                + " to "
                + entityList.get(0).getIdentity())
        .close();
    session.commit();

    session.begin();
    try (var resultSetTwo =
        session.query("select expand(outE()) from " + entityList.get(1).getIdentity())) {
      assertEquals(2, resultSetTwo.stream().count());
    }
    session.commit();

    session.begin();
    session.execute("DELETE EDGE testToTwoE to " + entityList.get(0).getIdentity()).close();
    session.commit();

    session.begin();
    try (var resultSetTwo = session.query(
        "select expand(outE()) from " + entityList.get(1).getIdentity())) {
      assertEquals(1, resultSetTwo.stream().count());
    }
    session.commit();

    session.begin();
    session.execute("DELETE FROM testToOneE unsafe").close();
    session.execute("DELETE FROM testToTwoE unsafe").close();
    session.execute("DELETE VERTEX testToV").close();
    session.commit();
  }

  @Test
  @Order(4)
  void testDropClassVandEwithUnsafe() {
    session.execute("CREATE CLASS SuperE extends E").close();
    session.execute("CREATE CLASS SuperV extends V").close();

    session.begin();
    Identifiable v1 =
        session.execute("create vertex SuperV set name = 'Luca'").next().getIdentity();
    Identifiable v2 =
        session.execute("create vertex SuperV set name = 'Mark'").next().getIdentity();
    session
        .execute("CREATE EDGE SuperE from " + v1.getIdentity() + " to " + v2.getIdentity())
        .close();
    session.commit();

    assertThrows(CommandExecutionException.class,
        () -> session.execute("DROP CLASS SuperV").close());

    assertThrows(CommandExecutionException.class,
        () -> session.execute("DROP CLASS SuperE").close());

    session.execute("DROP CLASS SuperV unsafe").close();

    session.execute("DROP CLASS SuperE UNSAFE").close();
  }

  @Test
  @Order(5)
  void testDropClassVandEwithDeleteElements() {
    session.execute("CREATE CLASS SuperE extends E").close();
    session.execute("CREATE CLASS SuperV extends V").close();

    session.begin();
    Identifiable v1 =
        session.execute("create vertex SuperV set name = 'Luca'").next().getIdentity();
    Identifiable v2 =
        session.execute("create vertex SuperV set name = 'Mark'").next().getIdentity();
    session
        .execute("CREATE EDGE SuperE from " + v1.getIdentity() + " to " + v2.getIdentity())
        .close();
    session.commit();

    assertThrows(CommandExecutionException.class,
        () -> session.execute("DROP CLASS SuperV").close());

    assertThrows(CommandExecutionException.class,
        () -> session.execute("DROP CLASS SuperE").close());

    session.begin();
    session.execute("DELETE VERTEX SuperV").close();
    session.commit();

    session.execute("DROP CLASS SuperV").close();

    session.execute("DROP CLASS SuperE").close();
  }

  @Test
  @Order(6)
  void testFromInString() {
    session.execute("CREATE CLASS FromInStringE extends E").close();
    session.execute("CREATE CLASS FromInStringV extends V").close();

    session.begin();
    Identifiable v1 =
        session
            .execute("create vertex FromInStringV set name = ' from '")
            .next()
            .getIdentity();
    Identifiable v2 =
        session
            .execute("create vertex FromInStringV set name = ' FROM '")
            .next()
            .getIdentity();
    Identifiable v3 =
        session
            .execute("create vertex FromInStringV set name = ' TO '")
            .next()
            .getIdentity();

    session
        .execute("create edge FromInStringE from " + v1.getIdentity() + " to " + v2.getIdentity())
        .close();
    session
        .execute("create edge FromInStringE from " + v1.getIdentity() + " to " + v3.getIdentity())
        .close();
    session.commit();

    var result = session.query("SELECT expand(out()[name = ' FROM ']) FROM FromInStringV");
    assertEquals(1, result.stream().count());

    result = session.query("SELECT expand(in()[name = ' from ']) FROM FromInStringV");
    assertEquals(2, result.stream().count());

    result = session.query("SELECT expand(out()[name = ' TO ']) FROM FromInStringV");
    assertEquals(1, result.stream().count());
  }

  @Test
  @Order(7)
  void testDeleteVertexWithReturn() {
    session.begin();
    Identifiable v1 =
        session.execute("create vertex V set returning = true").next().getIdentity();

    List<Identifiable> v2s =
        session.execute("delete vertex V return before where returning = true").stream()
            .map((r) -> r.getIdentity())
            .collect(Collectors.toList());
    session.commit();

    assertEquals(1, v2s.size());
    assertTrue(v2s.contains(v1));
  }
}
