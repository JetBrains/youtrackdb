package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.List;
import java.util.stream.Collectors;
import org.testng.Assert;

public class SQLDeleteEdgeTest extends BaseDBTest {

  public void testDeleteFromTo() {
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
      Assert.assertEquals(resultTwo.stream().count(), 2);
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
      Assert.assertEquals(resultTwo.stream().count(), 1);
    }

    session.execute("DELETE FROM testFromToOneE unsafe").close();
    session.execute("DELETE FROM testFromToTwoE unsafe").close();
    session.execute("DELETE VERTEX testFromToV").close();
    session.commit();
  }

  public void testDeleteFrom() {
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
    Assert.assertEquals(resultListTwo.size(), 2);
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
      Assert.assertEquals(resultListTwo.size(), 1);
    }
    session.commit();

    session.begin();
    session.execute("DELETE FROM testFromOneE unsafe").close();
    session.execute("DELETE FROM testFromTwoE unsafe").close();
    session.execute("DELETE VERTEX testFromV").close();
    session.commit();
  }

  public void testDeleteTo() {
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
      Assert.assertEquals(resultSetTwo.stream().count(), 2);
    }
    session.commit();

    session.begin();
    session.execute("DELETE EDGE testToTwoE to " + entityList.get(0).getIdentity()).close();
    session.commit();

    session.begin();
    try (var resultSetTwo = session.query(
        "select expand(outE()) from " + entityList.get(1).getIdentity())) {
      Assert.assertEquals(resultSetTwo.stream().count(), 1);
    }
    session.commit();

    session.begin();
    session.execute("DELETE FROM testToOneE unsafe").close();
    session.execute("DELETE FROM testToTwoE unsafe").close();
    session.execute("DELETE VERTEX testToV").close();
    session.commit();
  }

  public void testDropClassVandEwithUnsafe() {
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

    try {
      session.execute("DROP CLASS SuperV").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }

    try {
      session.execute("DROP CLASS SuperE").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }

    try {
      session.execute("DROP CLASS SuperV unsafe").close();
      Assert.assertTrue(true);
    } catch (CommandExecutionException e) {
      Assert.fail();
    }

    try {
      session.execute("DROP CLASS SuperE UNSAFE").close();
      Assert.assertTrue(true);
    } catch (CommandExecutionException e) {
      Assert.fail();
    }
  }

  public void testDropClassVandEwithDeleteElements() {
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

    try {
      session.execute("DROP CLASS SuperV").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }

    try {
      session.execute("DROP CLASS SuperE").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }

    session.begin();
    session.execute("DELETE VERTEX SuperV").close();
    session.commit();

    try {
      session.execute("DROP CLASS SuperV").close();
      Assert.assertTrue(true);
    } catch (CommandExecutionException e) {
      Assert.fail();
    }

    try {
      session.execute("DROP CLASS SuperE").close();
      Assert.assertTrue(true);
    } catch (CommandExecutionException e) {
      Assert.fail();
    }
  }

  public void testFromInString() {
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
    Assert.assertEquals(result.stream().count(), 1);

    result = session.query("SELECT expand(in()[name = ' from ']) FROM FromInStringV");
    Assert.assertEquals(result.stream().count(), 2);

    result = session.query("SELECT expand(out()[name = ' TO ']) FROM FromInStringV");
    Assert.assertEquals(result.stream().count(), 1);
  }

  public void testDeleteVertexWithReturn() {
    session.begin();
    Identifiable v1 =
        session.execute("create vertex V set returning = true").next().getIdentity();

    List<Identifiable> v2s =
        session.execute("delete vertex V return before where returning = true").stream()
            .map((r) -> r.getIdentity())
            .collect(Collectors.toList());
    session.commit();

    Assert.assertEquals(v2s.size(), 1);
    Assert.assertTrue(v2s.contains(v1));
  }
}
