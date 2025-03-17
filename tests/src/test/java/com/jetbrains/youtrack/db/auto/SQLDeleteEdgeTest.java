package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.List;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

/**
 * href="mailto:lomakin.andrey@gmail.com">Andrey Lomakin</a>
 *
 * @since 04/12/14
 */
public class SQLDeleteEdgeTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLDeleteEdgeTest(@Optional Boolean remote) {
    //super(remote != null && remote);
    super(true);
  }

  public void testDeleteFromTo() {
    session.command("CREATE CLASS testFromToOneE extends E").close();
    session.command("CREATE CLASS testFromToTwoE extends E").close();
    session.command("CREATE CLASS testFromToV extends V").close();

    session.begin();
    session.command("create vertex testFromToV set name = 'Luca'").close();
    session.command("create vertex testFromToV set name = 'Luca'").close();
    session.commit();

    List<Identifiable> result =
        session.query(new SQLSynchQuery<EntityImpl>("select from testFromToV"));

    session.begin();
    session
        .command(
            "CREATE EDGE testFromToOneE from "
                + result.get(1).getIdentity()
                + " to "
                + result.get(0).getIdentity())
        .close();
    session
        .command(
            "CREATE EDGE testFromToTwoE from "
                + result.get(1).getIdentity()
                + " to "
                + result.get(0).getIdentity())
        .close();
    session.commit();

    var resultTwo =
        session.query("select expand(outE()) from " + result.get(1).getIdentity());

    Assert.assertEquals(resultTwo.stream().count(), 2);

    session.begin();
    session
        .command(
            "DELETE EDGE testFromToTwoE from "
                + result.get(1).getIdentity()
                + " to"
                + result.get(0).getIdentity())
        .close();

    resultTwo = session.query("select expand(outE()) from " + result.get(1).getIdentity());

    Assert.assertEquals(resultTwo.stream().count(), 1);

    session.command("DELETE FROM testFromToOneE unsafe").close();
    session.command("DELETE FROM testFromToTwoE unsafe").close();
    session.command("DELETE VERTEX testFromToV").close();
    session.commit();
  }

  public void testDeleteFrom() {
    session.command("CREATE CLASS testFromOneE extends E").close();
    session.command("CREATE CLASS testFromTwoE extends E").close();
    session.command("CREATE CLASS testFromV extends V").close();

    session.begin();
    session.command("create vertex testFromV set name = 'Luca'").close();
    session.command("create vertex testFromV set name = 'Luca'").close();
    session.commit();

    List<Identifiable> result =
        session.query(new SQLSynchQuery<EntityImpl>("select from testFromV"));

    session.begin();
    session
        .command(
            "CREATE EDGE testFromOneE from "
                + result.get(1).getIdentity()
                + " to "
                + result.get(0).getIdentity())
        .close();
    session
        .command(
            "CREATE EDGE testFromTwoE from "
                + result.get(1).getIdentity()
                + " to "
                + result.get(0).getIdentity())
        .close();
    session.commit();

    var resultTwo =
        session.query("select expand(outE()) from " + result.get(1).getIdentity());

    Assert.assertEquals(resultTwo.stream().count(), 2);

    try {
      session.begin();
      session.command("DELETE EDGE testFromTwoE from " + result.get(1).getIdentity()).close();
      session.commit();
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }

    resultTwo = session.query("select expand(outE()) from " + result.get(1).getIdentity());

    Assert.assertEquals(resultTwo.stream().count(), 1);

    session.begin();
    session.command("DELETE FROM testFromOneE unsafe").close();
    session.command("DELETE FROM testFromTwoE unsafe").close();
    session.command("DELETE VERTEX testFromV").close();
    session.commit();
  }

  public void testDeleteTo() {
    session.command("CREATE CLASS testToOneE extends E").close();
    session.command("CREATE CLASS testToTwoE extends E").close();
    session.command("CREATE CLASS testToV extends V").close();

    session.begin();
    session.command("create vertex testToV set name = 'Luca'").close();
    session.command("create vertex testToV set name = 'Luca'").close();
    session.commit();

    var result =
        session.query("select from testToV").entityStream().toList();

    session.begin();
    session
        .command(
            "CREATE EDGE testToOneE from "
                + result.get(1).getIdentity()
                + " to "
                + result.get(0).getIdentity())
        .close();
    session
        .command(
            "CREATE EDGE testToTwoE from "
                + result.get(1).getIdentity()
                + " to "
                + result.get(0).getIdentity())
        .close();
    session.commit();

    var resultTwo =
        session.query("select expand(outE()) from " + result.get(1).getIdentity());

    Assert.assertEquals(resultTwo.stream().count(), 2);

    session.begin();
    session.command("DELETE EDGE testToTwoE to " + result.get(0).getIdentity()).close();
    session.commit();

    resultTwo = session.query("select expand(outE()) from " + result.get(1).getIdentity());

    Assert.assertEquals(resultTwo.stream().count(), 1);

    session.begin();
    session.command("DELETE FROM testToOneE unsafe").close();
    session.command("DELETE FROM testToTwoE unsafe").close();
    session.command("DELETE VERTEX testToV").close();
    session.commit();
  }

  public void testDropClassVandEwithUnsafe() {
    session.command("CREATE CLASS SuperE extends E").close();
    session.command("CREATE CLASS SuperV extends V").close();

    session.begin();
    Identifiable v1 =
        session.command("create vertex SuperV set name = 'Luca'").next().getIdentity();
    Identifiable v2 =
        session.command("create vertex SuperV set name = 'Mark'").next().getIdentity();
    session
        .command("CREATE EDGE SuperE from " + v1.getIdentity() + " to " + v2.getIdentity())
        .close();
    session.commit();

    try {
      session.command("DROP CLASS SuperV").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }

    try {
      session.command("DROP CLASS SuperE").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }

    try {
      session.command("DROP CLASS SuperV unsafe").close();
      Assert.assertTrue(true);
    } catch (CommandExecutionException e) {
      Assert.fail();
    }

    try {
      session.command("DROP CLASS SuperE UNSAFE").close();
      Assert.assertTrue(true);
    } catch (CommandExecutionException e) {
      Assert.fail();
    }
  }

  public void testDropClassVandEwithDeleteElements() {
    session.command("CREATE CLASS SuperE extends E").close();
    session.command("CREATE CLASS SuperV extends V").close();

    session.begin();
    Identifiable v1 =
        session.command("create vertex SuperV set name = 'Luca'").next().getIdentity();
    Identifiable v2 =
        session.command("create vertex SuperV set name = 'Mark'").next().getIdentity();
    session
        .command("CREATE EDGE SuperE from " + v1.getIdentity() + " to " + v2.getIdentity())
        .close();
    session.commit();

    try {
      session.command("DROP CLASS SuperV").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }

    try {
      session.command("DROP CLASS SuperE").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }

    session.begin();
    session.command("DELETE VERTEX SuperV").close();
    session.commit();

    try {
      session.command("DROP CLASS SuperV").close();
      Assert.assertTrue(true);
    } catch (CommandExecutionException e) {
      Assert.fail();
    }

    try {
      session.command("DROP CLASS SuperE").close();
      Assert.assertTrue(true);
    } catch (CommandExecutionException e) {
      Assert.fail();
    }
  }

  public void testFromInString() {
    session.command("CREATE CLASS FromInStringE extends E").close();
    session.command("CREATE CLASS FromInStringV extends V").close();

    session.begin();
    Identifiable v1 =
        session
            .command("create vertex FromInStringV set name = ' from '")
            .next()
            .getIdentity();
    Identifiable v2 =
        session
            .command("create vertex FromInStringV set name = ' FROM '")
            .next()
            .getIdentity();
    Identifiable v3 =
        session
            .command("create vertex FromInStringV set name = ' TO '")
            .next()
            .getIdentity();

    session
        .command("create edge FromInStringE from " + v1.getIdentity() + " to " + v2.getIdentity())
        .close();
    session
        .command("create edge FromInStringE from " + v1.getIdentity() + " to " + v3.getIdentity())
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
        session.command("create vertex V set returning = true").next().getIdentity();

    List<Identifiable> v2s =
        session.command("delete vertex V return before where returning = true").stream()
            .map((r) -> r.getIdentity())
            .collect(Collectors.toList());
    session.commit();

    Assert.assertEquals(v2s.size(), 1);
    Assert.assertTrue(v2s.contains(v1));
  }
}
