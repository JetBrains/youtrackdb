package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LinkBasedMatchStatementExecutionNewTest extends DbTestBase {

  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();
    session.execute("CREATE class Person").close();
    session.begin();

    session.execute("INSERT INTO Person set name = 'n1'").close();
    session.execute("INSERT INTO Person set name = 'n2'").close();
    session.execute("INSERT INTO Person set name = 'n3'").close();
    session.execute("INSERT INTO Person set name = 'n4'").close();
    session.execute("INSERT INTO Person set name = 'n5'").close();
    session.execute("INSERT INTO Person set name = 'n6'").close();

    var friendList =
        new String[][]{{"n1", "n2"}, {"n1", "n3"}, {"n2", "n4"}, {"n4", "n5"}, {"n4", "n6"}};

    for (var pair : friendList) {
      var fromPersons = session.query("select from Person where name = ?", pair[0]).entityStream()
          .toList();

      for (var fromPerson : fromPersons) {
        var toPersons = session.query("select from Person where name = ?", pair[1]).toRidList();
        fromPerson.getOrCreateLinkSet("friends").addAll(toPersons);
      }
    }

    session.commit();

    session.execute("CREATE class MathOp").close();

    session.begin();
    session.execute("INSERT INTO MathOp set a = 1, b = 3, c = 2").close();
    session.execute("INSERT INTO MathOp set a = 5, b = 3, c = 2").close();
    session.commit();
  }

  @Test
  public void testSimple() throws Exception {
    session.begin();
    var qResult = session.query("match {class:Person, as: person} return person");
    printExecutionPlan(qResult);

    for (var i = 0; i < 6; i++) {
      var item = qResult.next();
      Assert.assertEquals(1, item.getPropertyNames().size());
      Entity person = session.load(item.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(!name.isEmpty() && name.charAt(0) == 'n');
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleWhere() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person");

    for (var i = 0; i < 2; i++) {
      var item = qResult.next();
      Assert.assertEquals(1, item.getPropertyNames().size());
      Entity personId = session.load(item.getProperty("person"));

      var transaction = session.getActiveTransaction();
      EntityImpl person = transaction.load(personId);
      String name = person.getProperty("name");
      Assert.assertTrue(name.equals("n1") || name.equals("n2"));
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleLimit() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person"
                + " limit 1");
    Assert.assertTrue(qResult.hasNext());
    qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleLimit2() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person"
                + " limit -1");
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(qResult.hasNext());
      qResult.next();
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleLimit3() {

    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person"
                + " limit 3");
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(qResult.hasNext());
      qResult.next();
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleUnnamedParams() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = ? or name = ?)} return person",
            "n1",
            "n2");

    printExecutionPlan(qResult);
    for (var i = 0; i < 2; i++) {
      var item = qResult.next();
      Assert.assertEquals(1, item.getPropertyNames().size());
      Entity person = session.load(item.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(name.equals("n1") || name.equals("n2"));
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriends() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return friend)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriendsPatterns() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return $patterns)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testPattens() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return $patterns");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals(1, item.getPropertyNames().size());
    Assert.assertEquals("friend", item.getPropertyNames().getFirst());
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testPaths() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return $paths");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals(3, item.getPropertyNames().size());
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testElements() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return $elements");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testPathElements() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return $pathElements");
    printExecutionPlan(qResult);
    Set<String> expected = new HashSet<>();
    expected.add("n1");
    expected.add("n2");
    expected.add("n4");

    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(qResult.hasNext());
      var item = qResult.next();
      expected.remove(item.getString("name"));
    }

    Assert.assertFalse(qResult.hasNext());
    Assert.assertTrue(expected.isEmpty());
    qResult.close();

    session.commit();
  }

  @Test
  public void testCommonFriendsMatches() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return $matches)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriendsArrows() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends-{as:friend}-friends-{class: Person, where:(name = 'n4')} return"
                + " friend)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriendsArrowsPatterns() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends-{as:friend}-friends-{class: Person, where:(name = 'n4')} return"
                + " $patterns)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriends2() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriends2Arrows() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-friends-{as:friend}-friends-{class: Person,"
                + " where:(name = 'n4')} return friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnMethod() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return friend.name.toUpperCase(Locale.ENGLISH) as name");
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("N2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnMethodArrows() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-friends-{as:friend}-friends-{class: Person,"
                + " where:(name = 'n4')} return friend.name.toUpperCase(Locale.ENGLISH) as name");
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("N2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnExpression() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return friend.name + ' ' +friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2 n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnExpressionArrows() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-friends-{as:friend}-friends-{class: Person,"
                + " where:(name = 'n4')} return friend.name + ' ' +friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2 n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnDefaultAlias() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name ="
                + " 'n4')} return friend.name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("friend.name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnDefaultAliasArrows() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-friends-{as:friend}-friends-{class: Person,"
                + " where:(name = 'n4')} return friend.name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("friend.name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsOfFriends() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends').out('friends'){as:friend} return $matches)");

    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n4", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsOfFriendsArrows() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{}-friends->{as:friend} return $matches)");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n4", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsOfFriends2() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                + " me}.both('friends').both('friends'){as:friend, where: ($matched.me !="
                + " $currentMatch)} return $matches)");

    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    while (qResult.hasNext()) {
      Assert.assertNotEquals("n1", qResult.next().getProperty("name"));
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsOfFriends2Arrows() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                + " me}-friends-{}-friends-{as:friend, where: ($matched.me != $currentMatch)} return"
                + " $matches)");

    Assert.assertTrue(qResult.hasNext());
    while (qResult.hasNext()) {
      Assert.assertNotEquals("n1", qResult.next().getProperty("name"));
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsWithName() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1 ="
                + " 2)}.out('friends'){as:friend, where:(name = 'n2' and 1 + 1 = 2)} return"
                + " friend)");

    Assert.assertTrue(qResult.hasNext());
    Assert.assertEquals("n2", qResult.next().getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsWithNameArrows() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1 ="
                + " 2)}-friends->{as:friend, where:(name = 'n2' and 1 + 1 = 2)} return friend)");
    Assert.assertTrue(qResult.hasNext());
    Assert.assertEquals("n2", qResult.next().getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testWhile() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, while: ($depth < 1)} return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, while: ($depth < 2), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, while: ($depth < 4), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, while: (true) } return friend)");
    Assert.assertEquals(6, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, while: (true) } return friend limit 3)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, while: (true) } return friend) limit 3");
    Assert.assertEquals(3, size(qResult));
    qResult.close();
    session.commit();
  }

  private static int size(ResultSet qResult) {
    var result = 0;
    while (qResult.hasNext()) {
      result++;
      qResult.next();
    }
    return result;
  }

  @Test
  public void testWhileArrows() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, while: ($depth < 1)} return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, while: ($depth < 2), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, while: ($depth < 4), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, while: (true) } return friend)");
    Assert.assertEquals(6, size(qResult));
    qResult.close();
    session.commit();
  }

  @Test
  public void testMaxDepth() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, maxDepth: 1, where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, maxDepth: 1 } return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, maxDepth: 0 } return friend)");
    Assert.assertEquals(1, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, maxDepth: 1, where: ($depth > 0) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();
    session.commit();
  }

  @Test
  public void testMaxDepthArrow() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, maxDepth: 1, where: ($depth=1) } return friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, maxDepth: 1 } return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, maxDepth: 0 } return friend)");
    Assert.assertEquals(1, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, maxDepth: 1, where: ($depth > 0) } return friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();
    session.commit();
  }

  @Test
  public void testManager() {
    initOrgChart();
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one
    session.begin();
    var entity7 = getManager("p10");
    Assert.assertEquals("c", entity7.getProperty("name"));
    var entity6 = getManager("p12");
    Assert.assertEquals("c", entity6.getProperty("name"));
    var entity5 = getManager("p6");
    Assert.assertEquals("b", entity5.getProperty("name"));
    var entity4 = getManager("p11");
    Assert.assertEquals("b", entity4.getProperty("name"));

    var entity3 = getManagerArrows("p10");
    Assert.assertEquals("c", entity3.getProperty("name"));
    var entity2 = getManagerArrows("p12");
    Assert.assertEquals("c", entity2.getProperty("name"));
    var entity1 = getManagerArrows("p6");
    Assert.assertEquals("b", entity1.getProperty("name"));
    var entity = getManagerArrows("p11");
    Assert.assertEquals("b", entity.getProperty("name"));
    session.commit();
  }

  private EntityImpl getManager(String personName) {
    var query =
        "select expand(manager) from ("
            + "  match {class:Employee, where: (name = '"
            + personName
            + "')}"
            + "  .out('worksAt')"
            + "  .out('parentDepartment'){"
            + "      while: (in('managerOf').size() == 0),"
            + "      where: (in('managerOf').size() > 0)"
            + "  }"
            + "  .in('managerOf'){as: manager}"
            + "  return manager"
            + ")";

    var qResult = session.query(query);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    Identifiable identifiable = item.asEntity();
    var transaction = session.getActiveTransaction();
    return transaction.load(identifiable);
  }

  private EntityImpl getManagerArrows(String personName) {
    var query =
        "select expand(manager) from ("
            + "  match {class:Employee, where: (name = '"
            + personName
            + "')}"
            + "  -worksAt->{}-parentDepartment->{"
            + "      while: (in('managerOf').size() == 0),"
            + "      where: (in('managerOf').size() > 0)"
            + "  }<-managerOf-{as: manager}"
            + "  return manager"
            + ")";

    var qResult = session.query(query);
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    Identifiable identifiable = item.asEntity();
    var transaction = session.getActiveTransaction();
    return transaction.load(identifiable);
  }

  @Test
  public void testManager2() {
    initOrgChart();
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one

    session.begin();
    Assert.assertEquals("c", getManager2("p10").getProperty("name"));
    Assert.assertEquals("c", getManager2("p12").getProperty("name"));
    Assert.assertEquals("b", getManager2("p6").getProperty("name"));
    Assert.assertEquals("b", getManager2("p11").getProperty("name"));

    Assert.assertEquals("c", getManager2Arrows("p10").getProperty("name"));
    Assert.assertEquals("c", getManager2Arrows("p12").getProperty("name"));
    Assert.assertEquals("b", getManager2Arrows("p6").getProperty("name"));
    Assert.assertEquals("b", getManager2Arrows("p11").getProperty("name"));
    session.commit();
  }

  private Result getManager2(String personName) {
    var query =
        "select expand(manager) from ("
            + "  match {class:Employee, where: (name = '"
            + personName
            + "')}"
            + "   .( out('worksAt')"
            + "     .out('parentDepartment'){"
            + "       while: (in('managerOf').size() == 0),"
            + "       where: (in('managerOf').size() > 0)"
            + "     }"
            + "   )"
            + "  .in('managerOf'){as: manager}"
            + "  return manager"
            + ")";

    var qResult = session.query(query);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    return item;
  }

  private Result getManager2Arrows(String personName) {
    var query =
        "select expand(manager) from ("
            + "  match {class:Employee, where: (name = '"
            + personName
            + "')}"
            + "   .( -worksAt->{}-parentDepartment->{"
            + "       while: (in('managerOf').size() == 0),"
            + "       where: (in('managerOf').size() > 0)"
            + "     }"
            + "   )<-managerOf-{as: manager}"
            + "  return manager"
            + ")";

    var qResult = session.query(query);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    return item;
  }

  @Test
  public void testManaged() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedBy("a");
    Assert.assertTrue(managedByA.hasNext());
    var item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();

    var managedByB = getManagedBy("b");

    Set<String> expectedNames = new HashSet<>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<>();
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private ResultSet getManagedBy(String managerName) {
    var query =
        "select expand(managed) from ("
            + "  match {class:Employee, where: (name = '"
            + managerName
            + "')}"
            + "  .out('managerOf')"
            + "  .in('parentDepartment'){"
            + "      while: ($depth = 0 or in('managerOf').size() = 0),"
            + "      where: ($depth = 0 or in('managerOf').size() = 0)"
            + "  }"
            + "  .in('worksAt'){as: managed}"
            + "  return managed"
            + ")";

    return session.query(query);
  }

  @Test
  public void testManagedArrows() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedByArrows("a");
    Assert.assertTrue(managedByA.hasNext());
    var item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();
    var managedByB = getManagedByArrows("b");

    Set<String> expectedNames = new HashSet<>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<>();
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private ResultSet getManagedByArrows(String managerName) {
    var query =
        "select expand(managed) from ("
            + "  match {class:Employee, where: (name = '"
            + managerName
            + "')}"
            + "  -managerOf->{}<-parentDepartment-{"
            + "      while: ($depth = 0 or in('managerOf').size() = 0),"
            + "      where: ($depth = 0 or in('managerOf').size() = 0)"
            + "  }<-worksAt-{as: managed}"
            + "  return managed"
            + ")";

    return session.query(query);
  }

  @Test
  public void testManaged2() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedBy2("a");
    Assert.assertTrue(managedByA.hasNext());
    var item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();
    var managedByB = getManagedBy2("b");

    Set<String> expectedNames = new HashSet<>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<>();
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private ResultSet getManagedBy2(String managerName) {
    var query =
        "select expand(managed) from ("
            + "  match {class:Employee, where: (name = '"
            + managerName
            + "')}"
            + "  .out('managerOf')"
            + "  .(inE('parentDepartment').outV()){"
            + "      while: ($depth = 0 or in('managerOf').size() = 0),"
            + "      where: ($depth = 0 or in('managerOf').size() = 0)"
            + "  }"
            + "  .in('worksAt'){as: managed}"
            + "  return managed"
            + ")";

    return session.query(query);
  }

  @Test
  public void testManaged2Arrows() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedBy2Arrows("a");
    Assert.assertTrue(managedByA.hasNext());
    var item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();
    var managedByB = getManagedBy2Arrows("b");

    Set<String> expectedNames = new HashSet<>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<>();
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private ResultSet getManagedBy2Arrows(String managerName) {
    var query =
        "select expand(managed) from ("
            + "  match {class:Employee, where: (name = '"
            + managerName
            + "')}"
            + "  -managerOf->{}"
            + "  .(inE('parentDepartment').outV()){"
            + "      while: ($depth = 0 or in('managerOf').size() = 0),"
            + "      where: ($depth = 0 or in('managerOf').size() = 0)"
            + "  }<-worksAt-{as: managed}"
            + "  return managed"
            + ")";

    return session.query(query);
  }

  @Test
  public void testTriangle1() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1, where: (uid = 0)}"
            + "  .out('triangle'){as: friend2}"
            + "  .out('triangle'){as: friend3},"
            + "{class:Triangle, as: friend1}"
            + "  .out('triangle'){as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);

    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle1Arrows() {
    initTriangleTest();
    var query =
        "match {class:Triangle, as: friend1, where: (uid = 0)} -triangle-> {as: friend2}"
            + " -triangle-> {as: friend3},{class:Triangle, as: friend1} -triangle-> {as:"
            + " friend3}return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle2Old() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1}"
            + "  .out('triangle'){class:Triangle, as: friend2, where: (uid = 1)}"
            + "  .out('triangle'){as: friend3},"
            + "{class:Triangle, as: friend1}"
            + "  .out('triangle'){as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Entity friend1 = session.load(doc.getProperty("friend1"));
    Entity friend2 = session.load(doc.getProperty("friend2"));
    Entity friend3 = session.load(doc.getProperty("friend3"));
    Assert.assertEquals(0, friend1.<Object>getProperty("uid"));
    Assert.assertEquals(1, friend2.<Object>getProperty("uid"));
    Assert.assertEquals(2, friend3.<Object>getProperty("uid"));
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle2() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1}"
            + "  .out('triangle'){class:Triangle, as: friend2, where: (uid = 1)}"
            + "  .out('triangle'){as: friend3},"
            + "{class:Triangle, as: friend1}"
            + "  .out('triangle'){as: friend3}"
            + "return $patterns";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());
    Entity friend1 = session.load(doc.getProperty("friend1"));
    Entity friend2 = session.load(doc.getProperty("friend2"));
    Entity friend3 = session.load(doc.getProperty("friend3"));
    Assert.assertEquals(0, friend1.<Object>getProperty("uid"));
    Assert.assertEquals(1, friend2.<Object>getProperty("uid"));
    Assert.assertEquals(2, friend3.<Object>getProperty("uid"));
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle2Arrows() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1}"
            + "  -triangle->{class:Triangle, as: friend2, where: (uid = 1)}"
            + "  -triangle->{as: friend3},"
            + "{class:Triangle, as: friend1}"
            + "  -triangle->{as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());
    Entity friend1 = session.load(doc.getProperty("friend1"));
    Entity friend2 = session.load(doc.getProperty("friend2"));
    Entity friend3 = session.load(doc.getProperty("friend3"));
    Assert.assertEquals(0, friend1.<Object>getProperty("uid"));
    Assert.assertEquals(1, friend2.<Object>getProperty("uid"));
    Assert.assertEquals(2, friend3.<Object>getProperty("uid"));
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle3() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1}"
            + "  -triangle->{as: friend2}"
            + "  -triangle->{as: friend3, where: (uid = 2)},"
            + "{class:Triangle, as: friend1}"
            + "  -triangle->{as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle4() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1}"
            + "  .out('triangle'){as: friend2, where: (uid = 1)}"
            + "  .out('triangle'){as: friend3},"
            + "{class:Triangle, as: friend1}"
            + "  .out('triangle'){as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle4Arrows() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1}"
            + "  -triangle->{as: friend2, where: (uid = 1)}"
            + "  -triangle->{as: friend3},"
            + "{class:Triangle, as: friend1}"
            + "  -triangle->{as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangleWithEdges4() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1}"
            + "  .outE('triangle').inV(){as: friend2, where: (uid = 1)}"
            + "  .outE('triangle').inV(){as: friend3},"
            + "{class:Triangle, as: friend1}"
            + "  .outE('triangle').inV(){as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testCartesianProduct() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1, where:(uid = 1)},"
            + "{class:Triangle, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    printExecutionPlan(result);

    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var doc = result.next();
      Entity friend1 = session.load(doc.getProperty("friend1"));
      Assert.assertEquals(1, friend1.<Object>getProperty("uid"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testCartesianProductLimit() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1, where:(uid = 1)},"
            + "{class:Triangle, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches LIMIT 1";

    session.begin();
    var result = session.query(query);

    Assert.assertTrue(result.hasNext());
    var d = result.next();
    Entity friend1 = session.load(d.getProperty("friend1"));
    Assert.assertEquals(1, friend1.<Object>getProperty("uid"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testArrayNumber() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1, where: (uid = 0)}"
            + "return friend1.out('triangle')[0] as foo";

    session.begin();
    var result = session.query(query);

    Assert.assertTrue(result.hasNext());

    var doc = result.next();
    session.loadEntity(doc.getLink("foo"));
    result.close();
    session.commit();
  }

  @Test
  public void testArraySingleSelectors2() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1, where: (uid = 0)}"
            + "return friend1.out('triangle')[0,1] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());
    var foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(2, ((List<?>) foo).size());
    result.close();
    session.commit();
  }

  @Test
  public void testArrayRangeSelectors1() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1, where: (uid = 0)}"
            + "return friend1.out('triangle')[0..1] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());

    var foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(1, ((List<?>) foo).size());
    result.close();
    session.commit();
  }

  @Test
  public void testArrayRange2() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1, where: (uid = 0)}"
            + "return friend1.out('triangle')[0..2] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());

    var foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(2, ((List<?>) foo).size());
    result.close();
    session.commit();
  }

  @Test
  public void testArrayRange3() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1, where: (uid = 0)}"
            + "return friend1.out('triangle')[0..3] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());

    var foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(2, ((List<?>) foo).size());
    result.close();
    session.commit();
  }

  @Test
  public void testConditionInSquareBrackets() {
    initTriangleTest();
    var query =
        "match "
            + "{class:Triangle, as: friend1, where: (uid = 0)}"
            + "return friend1.out('triangle')[uid = 2] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());

    var foo = doc.getLinkList("foo");
    Assert.assertNotNull(foo);
    Assert.assertEquals(1, (foo).size());
    var identifiable = foo.getFirst();
    var transaction = session.getActiveTransaction();
    var resultEntity = transaction.loadEntity(identifiable);
    Assert.assertEquals(2, resultEntity.<Object>getProperty("uid"));
    result.close();
    session.commit();
  }

  @Test
  public void testUnique() {
    initDiamondTest();
    var query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:Diamond, as: one, where: (uid = 0)}.out('diamond').out('diamond'){as: two} ");
    query.append("return DISTINCT one, two");

    session.begin();
    var result = session.query(query.toString());
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());

    query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:Diamond, as: one, where: (uid = 0)}.out('diamond').out('diamond'){as: two} ");
    query.append("return DISTINCT one.uid, two.uid");

    result.close();

    result = session.query(query.toString());
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub[0].uuid"));
    session.commit();
  }

  @Test
  public void testNotUnique() {
    initDiamondTest();
    var query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:Diamond, as: one, where: (uid = 0)}.out('diamond').out('diamond'){as: two} ");
    query.append("return one, two");

    session.begin();
    var result = session.query(query.toString());
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();

    query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:Diamond, as: one, where: (uid = 0)}.out('diamond').out('diamond'){as: two} ");
    query.append("return one.uid, two.uid");

    result = session.query(query.toString());
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub[0].uuid"));
    session.commit();
  }

  @Test
  public void testManagedElements() {
    initOrgChart();
    session.begin();
    var managedByB = getManagedElements("b");

    Set<String> expectedNames = new HashSet<>();
    expectedNames.add("b");
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<>();
    for (var i = 0; i < 6; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var doc = managedByB.next();
      String name = doc.getProperty("name");
      names.add(name);
    }
    Assert.assertFalse(managedByB.hasNext());
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private ResultSet getManagedElements(@SuppressWarnings("SameParameterValue") String managerName) {
    var query =
        "  match {class:Employee, as:boss, where: (name = '"
            + managerName
            + "')}"
            + "  -managerOf->{}<-parentDepartment-{"
            + "      while: ($depth = 0 or in('managerOf').size() = 0),"
            + "      where: ($depth = 0 or in('managerOf').size() = 0)"
            + "  }<-worksAt-{as: managed}"
            + "  return distinct $elements";

    return session.query(query);
  }

  @Test
  public void testManagedPathElements() {
    initOrgChart();
    session.begin();
    var managedByB = getManagedPathElements("b");

    Set<String> expectedNames = new HashSet<>();
    expectedNames.add("department1");
    expectedNames.add("department3");
    expectedNames.add("department4");
    expectedNames.add("department8");
    expectedNames.add("b");
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<>();
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var doc = managedByB.next();
      String name = doc.getProperty("name");
      names.add(name);
    }
    Assert.assertFalse(managedByB.hasNext());
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  @Test
  public void testOptional() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person} -nonExistingEdge-> {as:b, optional:true} return"
                + " person, b.name");

    printExecutionPlan(qResult);
    for (var i = 0; i < 6; i++) {
      Assert.assertTrue(qResult.hasNext());
      var doc = qResult.next();
      Assert.assertEquals(2, doc.getPropertyNames().size());
      Entity person = session.load(doc.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(!name.isEmpty() && name.charAt(0) == 'n');
    }
    session.commit();
  }

  @Test
  public void testOptional2() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person} --> {as:b, optional:true, where:(nonExisting = 12)}"
                + " return person, b.name");

    for (var i = 0; i < 6; i++) {
      Assert.assertTrue(qResult.hasNext());
      var doc = qResult.next();
      Assert.assertEquals(2, doc.getPropertyNames().size());
      Entity person = session.load(doc.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(!name.isEmpty() && name.charAt(0) == 'n');
    }
    session.commit();
  }

  @Test
  public void testOptional3() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name, b from (match {class:Person, as:a, where:(name = 'n1' and"
                + " 1 + 1 = 2)}.out('friends'){as:friend, where:(name = 'n2' and 1 + 1 ="
                + " 2)},{as:a}.out(){as:b, where:(nonExisting = 12),"
                + " optional:true},{as:friend}.out(){as:b, optional:true} return friend, b)");

    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var doc = qResult.next();
    Assert.assertEquals("n2", doc.getProperty("name"));
    Assert.assertNull(doc.getProperty("b"));
    Assert.assertFalse(qResult.hasNext());
    session.commit();
  }

  @Test
  public void testOrderByAsc() {
    session.execute("CREATE CLASS testOrderByAsc").close();

    session.begin();
    session.execute("INSERT INTO testOrderByAsc SET name = 'bbb'").close();
    session.execute("INSERT INTO testOrderByAsc SET name = 'zzz'").close();
    session.execute("INSERT INTO testOrderByAsc SET name = 'aaa'").close();
    session.execute("INSERT INTO testOrderByAsc SET name = 'ccc'").close();
    session.commit();

    var query = "MATCH { class: testOrderByAsc, as:a} RETURN a.name as name order by name asc";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("aaa", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("bbb", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("ccc", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("zzz", result.next().getProperty("name"));
    Assert.assertFalse(result.hasNext());
    session.commit();
  }

  @Test
  public void testOrderByDesc() {
    session.execute("CREATE CLASS testOrderByDesc").close();

    session.begin();
    session.execute("INSERT INTO testOrderByDesc SET name = 'bbb'").close();
    session.execute("INSERT INTO testOrderByDesc SET name = 'zzz'").close();
    session.execute("INSERT INTO testOrderByDesc SET name = 'aaa'").close();
    session.execute("INSERT INTO testOrderByDesc SET name = 'ccc'").close();
    session.commit();

    var query = "MATCH { class: testOrderByDesc, as:a} RETURN a.name as name order by name desc";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("zzz", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("ccc", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("bbb", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("aaa", result.next().getProperty("name"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testNestedProjections() {
    var clazz = "testNestedProjections";
    session.execute("CREATE CLASS " + clazz).close();

    session.begin();
    session.execute("INSERT INTO " + clazz + " SET name = 'bbb', surname = 'ccc'").close();
    session.commit();

    var query = "MATCH { class: " + clazz + ", as:a} RETURN a:{name}, 'x' ";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Result a = item.getProperty("a");
    Assert.assertEquals("bbb", a.getProperty("name"));
    Assert.assertNull(a.getProperty("surname"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testAggregate() {
    var clazz = "testAggregate";
    session.execute("CREATE CLASS " + clazz).close();

    session.begin();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', num = 1").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', num = 2").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', num = 3").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'bbb', num = 4").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'bbb', num = 5").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'bbb', num = 6").close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name, max(a.num) as maxNum group by a.name order by name";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("aaa", item.getProperty("name"));
    Assert.assertEquals(3, (int) item.getProperty("maxNum"));

    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertEquals("bbb", item.getProperty("name"));
    Assert.assertEquals(6, (int) item.getProperty("maxNum"));

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testOrderByOutOfProjAsc() {
    var clazz = "testOrderByOutOfProjAsc";
    session.execute("CREATE CLASS " + clazz).close();

    session.begin();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', num = 0, num2 = 1").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', num = 1, num2 = 2").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', num = 2, num2 = 3").close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name, a.num as num order by a.num2 asc";

    session.begin();
    var result = session.query(query);
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals("aaa", item.getProperty("name"));
      Assert.assertEquals(i, (int) item.getProperty("num"));
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testOrderByOutOfProjDesc() {
    var clazz = "testOrderByOutOfProjDesc";
    session.execute("CREATE CLASS " + clazz).close();

    session.begin();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', num = 0, num2 = 1").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', num = 1, num2 = 2").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', num = 2, num2 = 3").close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name, a.num as num order by a.num2 desc";

    session.begin();
    var result = session.query(query);
    for (var i = 2; i >= 0; i--) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals("aaa", item.getProperty("name"));
      Assert.assertEquals(i, (int) item.getProperty("num"));
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testUnwind() {
    var clazz = "testUnwind";
    session.execute("CREATE CLASS " + clazz).close();

    session.begin();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa', coll = [1, 2]").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'bbb', coll = [3, 4]").close();
    session.commit();

    var query =
        "MATCH { class: " + clazz + ", as:a} RETURN a.name as name, a.coll as num unwind num";

    session.begin();
    var sum = 0;
    var result = session.query(query);
    for (var i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      sum += item.<Integer>getProperty("num");
    }

    Assert.assertFalse(result.hasNext());

    result.close();
    Assert.assertEquals(10, sum);
    session.commit();
  }

  @Test
  public void testSkip() {
    var clazz = "testSkip";
    session.execute("CREATE CLASS " + clazz).close();

    session.begin();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa'").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'bbb'").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'ccc'").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'ddd'").close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name ORDER BY name ASC skip 1 limit 2";

    session.begin();
    var result = session.query(query);

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("bbb", item.getProperty("name"));

    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertEquals("ccc", item.getProperty("name"));

    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testDepthAlias() {
    var clazz = "testDepthAlias";
    session.execute("CREATE CLASS " + clazz).close();

    session.begin();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa'").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'bbb'").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'ccc'").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'ddd'").close();

    try (var fromResult = session.query("SELECT * FROM " + clazz + " where name = 'aaa'")) {
      try (var toResult = session.query("SELECT * FROM " + clazz + " where name = 'bbb'")) {
        fromResult.findFirstEntity().setLink("link", toResult.findFirstEntity());
      }
    }

    try (var fromResult = session.query("SELECT * FROM " + clazz + " where name = 'bbb'")) {
      try (var toResult = session.query("SELECT * FROM " + clazz + " where name = 'ccc'")) {
        fromResult.findFirstEntity().setLink("link", toResult.findFirstEntity());
      }
    }

    try (var fromResult = session.query("SELECT * FROM " + clazz + " where name = 'ccc'")) {
      try (var toResult = session.query("SELECT * FROM " + clazz + " where name = 'ddd'")) {
        fromResult.findFirstEntity().setLink("link", toResult.findFirstEntity());
      }
    }

    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a, where:(name = 'aaa')} -link-> {as:b, while:($depth<10), depthAlias: xy} RETURN"
            + " a.name as name, b.name as bname, xy";

    session.begin();
    var result = session.query(query);

    var sum = 0;
    for (var i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      var depth = item.getProperty("xy");
      Assert.assertTrue(depth instanceof Integer);
      Assert.assertEquals("aaa", item.getProperty("name"));
      switch ((int) depth) {
        case 0:
          Assert.assertEquals("aaa", item.getProperty("bname"));
          break;
        case 1:
          Assert.assertEquals("bbb", item.getProperty("bname"));
          break;
        case 2:
          Assert.assertEquals("ccc", item.getProperty("bname"));
          break;
        case 3:
          Assert.assertEquals("ddd", item.getProperty("bname"));
          break;
        default:
          Assert.fail();
      }
      sum += (int) depth;
    }

    Assert.assertEquals(6, sum);
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testPathAlias() {
    var clazz = "testPathAlias";
    session.execute("CREATE CLASS " + clazz).close();

    session.begin();
    session.execute("INSERT INTO " + clazz + " SET name = 'aaa'").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'bbb'").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'ccc'").close();
    session.execute("INSERT INTO " + clazz + " SET name = 'ddd'").close();

    try (var fromResult = session.query("SELECT * FROM " + clazz + " where name = 'aaa'")) {
      try (var toResult = session.query("SELECT * FROM " + clazz + " where name = 'bbb'")) {
        fromResult.findFirstEntity().setLink("link", toResult.findFirstEntity());
      }
    }

    try (var fromResult = session.query("SELECT * FROM " + clazz + " where name = 'bbb'")) {
      try (var toResult = session.query("SELECT * FROM " + clazz + " where name = 'ccc'")) {
        fromResult.findFirstEntity().setLink("link", toResult.findFirstEntity());
      }
    }

    try (var fromResult = session.query("SELECT * FROM " + clazz + " where name = 'ccc'")) {
      try (var toResult = session.query("SELECT * FROM " + clazz + " where name = 'ddd'")) {
        fromResult.findFirstEntity().setLink("link", toResult.findFirstEntity());
      }
    }
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a, where:(name = 'aaa')} -link-> {as:b, while:($depth<10), pathAlias: xy} RETURN"
            + " a.name as name, b.name as bname, xy";

    session.begin();
    var result = session.query(query);

    for (var i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      var path = item.getProperty("xy");
      Assert.assertTrue(path instanceof List);

      @SuppressWarnings("unchecked")
      var thePath = (List<Identifiable>) path;

      String bname = item.getProperty("bname");
      switch (bname) {
        case "aaa" -> Assert.assertEquals(0, thePath.size());
        case "ccc" -> {
          Assert.assertEquals(2, thePath.size());
          var transaction1 = session.getActiveTransaction();
          Assert.assertEquals("bbb",
              ((Entity) transaction1.load(thePath.get(0))).getProperty("name"));
          var transaction = session.getActiveTransaction();
          Assert.assertEquals("ccc",
              ((Entity) transaction.load(thePath.get(1))).getProperty("name"));
        }
        case "ddd" -> {
          Assert.assertEquals(3, thePath.size());
          var transaction2 = session.getActiveTransaction();
          Assert.assertEquals("bbb",
              ((Entity) transaction2.load(thePath.get(0))).getProperty("name"));
          var transaction1 = session.getActiveTransaction();
          Assert.assertEquals("ccc",
              ((Entity) transaction1.load(thePath.get(1))).getProperty("name"));
          var transaction = session.getActiveTransaction();
          Assert.assertEquals("ddd",
              ((Entity) transaction.load(thePath.get(2))).getProperty("name"));
        }
      }
    }
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testNegativePattern() {
    var clazz = "testNegativePattern";
    session.execute("CREATE CLASS " + clazz).close();

    session.executeInTx(
        transaction -> {
          var v1 = session.newEntity(clazz);
          v1.setProperty("name", "a");

          var v2 = session.newEntity(clazz);
          v2.setProperty("name", "b");

          var v3 = session.newEntity(clazz);
          v3.setProperty("name", "c");

          v1.setLink("link", v2);
          v2.setLink("link", v3);
        });

    var query = "MATCH { class:" + clazz + ", as:a} -link-> {as:b} -link-> {as:c}, ";
    query += " NOT {as:a} -link-> {as:c}";
    query += " RETURN $patterns";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testNegativePattern2() {
    var clazz = "testNegativePattern2";
    session.execute("CREATE CLASS " + clazz).close();

    session.executeInTx(
        transaction -> {
          var v1 = session.newEntity(clazz);
          v1.setProperty("name", "a");

          var v2 = session.newEntity(clazz);
          v2.setProperty("name", "b");

          var v3 = session.newEntity(clazz);
          v3.setProperty("name", "c");

          v1.setLink("link", v2);
          v2.setLink("link", v3);
          v1.setLink("link", v3);
        });

    var query = "MATCH { class:" + clazz + ", as:a} -link-> {as:b} -link-> {as:c}, ";
    query += " NOT {as:a} -link-> {as:c}";
    query += " RETURN $patterns";

    session.begin();
    var result = session.query(query);
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testNegativePattern3() {
    var clazz = "testNegativePattern3";
    session.execute("CREATE CLASS " + clazz).close();

    session.executeInTx(
        transaction -> {
          var v1 = session.newEntity(clazz);
          v1.setProperty("name", "a");

          var v2 = session.newEntity(clazz);
          v2.setProperty("name", "b");

          var v3 = session.newEntity(clazz);
          v3.setProperty("name", "c");

          v1.getOrCreateLinkList("link").add(v2);
          v2.setLink("link", v3);
          v1.getLinkList("link").add(v3);
        });

    var query = "MATCH { class:" + clazz + ", as:a} -link-> {as:b} -link-> {as:c}, ";
    query += " NOT {as:a} -link-> {as:c, where:(name <> 'c')}";
    query += " RETURN $patterns";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testPathTraversal() {
    var clazz = "testPathTraversal";
    session.execute("CREATE CLASS " + clazz).close();

    session.executeInTx(
        transaction -> {
          var v1 = session.newEntity(clazz);
          v1.setProperty("name", "a");

          var v2 = session.newEntity(clazz);
          v2.setProperty("name", "b");

          var v3 = session.newEntity(clazz);
          v3.setProperty("name", "c");

          v1.setLink("next", v2);
          v2.setLink("next", v3);
        });

    var query = "MATCH { class:" + clazz + ", as:a}.next{as:b, where:(name ='b')}";
    query += " RETURN a.name as a, b.name as b";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("a", item.getProperty("a"));
    Assert.assertEquals("b", item.getProperty("b"));

    Assert.assertFalse(result.hasNext());

    result.close();

    query = "MATCH { class:" + clazz + ", as:a, where:(name ='a')}.next{as:b}";
    query += " RETURN a.name as a, b.name as b";

    result = session.query(query);
    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertEquals("a", item.getProperty("a"));
    Assert.assertEquals("b", item.getProperty("b"));

    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  private ResultSet getManagedPathElements(
      @SuppressWarnings("SameParameterValue") String managerName) {
    var query =
        "  match {class:Employee, as:boss, where: (name = '"
            + managerName
            + "')}"
            + "  -managerOf->{}<-parentDepartment-{"
            + "      while: ($depth = 0 or in('managerOf').size() = 0),"
            + "      where: ($depth = 0 or in('managerOf').size() = 0)"
            + "  }<-worksAt-{as: managed}"
            + "  return distinct $pathElements";

    return session.query(query);
  }

  @Test
  public void testQuotedClassName() {
    var className = "testQuotedClassName";
    session.execute("CREATE CLASS " + className).close();

    session.begin();
    session.execute("INSERT INTO " + className + " SET name = 'a'").close();
    session.commit();

    var query = "MATCH {class: `" + className + "`, as:foo} RETURN $elements";

    session.begin();
    try (var rs = session.query(query)) {
      Assert.assertEquals(1L, rs.stream().count());
    }
    session.commit();
  }

  @SuppressWarnings("unused")
  private void printExecutionPlan(ResultSet result) {
    //    result.getExecutionPlan().ifPresent(x -> System.out.println(x.prettyPrint(0, 3)));
    //    System.out.println();
  }

  private void initOrgChart() {

    // ______ [manager] department _______
    // _____ (employees in department)____
    // ___________________________________
    // ___________________________________
    // ____________[a]0___________________
    // _____________(p1)__________________
    // _____________/___\_________________
    // ____________/_____\________________
    // ___________/_______\_______________
    // _______[b]1_________2[d]___________
    // ______(p2, p3)_____(p4, p5)________
    // _________/_\_________/_\___________
    // ________3___4_______5___6__________
    // ______(p6)_(p7)___(p8)__(p9)_______
    // ______/__\_________________________
    // __[c]7_____8_______________________
    // __(p10)___(p11)____________________
    // ___/_______________________________
    // __9________________________________
    // (p12, p13)_________________________
    //
    // short description:
    // Department 0 is the company itself, "a" is the CEO
    // p10 works at department 7, his manager is "c"
    // p12 works at department 9, this department has no direct manager, so p12's manager is c (the
    // upper manager)

    session.execute("CREATE class Employee").close();
    session.execute("CREATE class Department").close();

    var deptHierarchy = new int[10][];
    deptHierarchy[0] = new int[]{1, 2};
    deptHierarchy[1] = new int[]{3, 4};
    deptHierarchy[2] = new int[]{5, 6};
    deptHierarchy[3] = new int[]{7, 8};
    deptHierarchy[4] = new int[]{};
    deptHierarchy[5] = new int[]{};
    deptHierarchy[6] = new int[]{};
    deptHierarchy[7] = new int[]{9};
    deptHierarchy[8] = new int[]{};
    deptHierarchy[9] = new int[]{};

    var deptManagers = new String[]{"a", "b", "d", null, null, null, null, "c", null, null};

    var employees = new String[10][];
    employees[0] = new String[]{"p1"};
    employees[1] = new String[]{"p2", "p3"};
    employees[2] = new String[]{"p4", "p5"};
    employees[3] = new String[]{"p6"};
    employees[4] = new String[]{"p7"};
    employees[5] = new String[]{"p8"};
    employees[6] = new String[]{"p9"};
    employees[7] = new String[]{"p10"};
    employees[8] = new String[]{"p11"};
    employees[9] = new String[]{"p12", "p13"};

    session.begin();
    for (var i = 0; i < deptHierarchy.length; i++) {
      session.execute("INSERT INTO Department set name = 'department" + i + "' ").close();
    }

    for (var parent = 0; parent < deptHierarchy.length; parent++) {
      var children = deptHierarchy[parent];
      for (var child : children) {
        var childDepartment = session.query(
                "select from Department where name = 'department" + child + "'")
            .findFirstEntity();
        var parentDepartment = session.query(
                "select from Department where name = 'department" + parent + "'")
            .findFirstEntity();
        childDepartment.setLink("parentDepartment", parentDepartment);
      }
    }

    for (var dept = 0; dept < deptManagers.length; dept++) {
      var managerName = deptManagers[dept];
      if (managerName != null) {
        var manager =
            session.execute("INSERT INTO Employee set name = '" + managerName + "' ").
                entityStream().findFirst().orElseThrow();
        var department = session.query(
                "select from Department where name = 'department" + dept + "'")
            .entityStream().findFirst().orElseThrow();
        manager.setLink("managerOf", department);
      }
    }

    for (var dept = 0; dept < employees.length; dept++) {
      var employeesForDept = employees[dept];
      for (var employeeName : employeesForDept) {
        var employee = session.execute("INSERT INTO Employee set name = '" + employeeName + "' ")
            .findFirstEntity();
        var department = session.query(
            "select from Department where name = 'department" + dept + "'").findFirstEntity();
        employee.setLink("worksAt", department);
      }
    }

    session.commit();
  }

  private void initTriangleTest() {
    session.execute("CREATE class Triangle").close();
    session.execute("CREATE property Triangle.uid INTEGER").close();
    session.execute("CREATE index Triangle_uid on Triangle (uid) UNIQUE").close();

    session.begin();
    for (var i = 0; i < 10; i++) {
      session.execute("INSERT INTO Triangle set uid = ?", i).close();
    }
    var edges = new int[][]{
        {0, 1}, {0, 2}, {1, 2}, {1, 3}, {2, 4}, {3, 4}, {3, 5}, {4, 0}, {4, 7}, {6, 7}, {7, 8},
        {7, 9}, {8, 9}, {9, 1}, {8, 3}, {8, 4}
    };
    for (var edge : edges) {
      var fromEntity = session.query(
          "select from Triangle where uid = ?", edge[0]).findFirstEntity();
      var toEntity = session.query(
          "select from Triangle where uid = ?", edge[1]).findFirstEntity();
      fromEntity.getOrCreateLinkList("triangle").add(toEntity);
    }

    session.commit();
  }

  private void initDiamondTest() {
    session.execute("CREATE class Diamond").close();

    var tx = session.begin();
    for (var i = 0; i < 4; i++) {
      tx.execute("INSERT INTO Diamond set uid = ?", i).close();
    }
    var edges = new int[][]{{0, 1}, {0, 2}, {1, 3}, {2, 3}};
    for (var edge : edges) {
      tx.query("select from Diamond where uid = ?", edge[0])
          .findFirstEntity()
          .getOrCreateLinkList("diamond")
          .add(tx.query("select from Diamond where uid = ?", edge[1]).findFirstEntity());
    }
    session.commit();
  }

}
