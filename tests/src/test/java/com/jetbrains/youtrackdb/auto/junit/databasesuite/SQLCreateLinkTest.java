/*
 * JUnit 4 version of SQLCreateLinkTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateLinkTest.java
 */
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLCreateLinkTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateLinkTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLCreateLinkTest extends BaseDBTest {

  private static SQLCreateLinkTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLCreateLinkTest();
    instance.beforeClass();
  }

  /**
   * Original: createLinktest (line 25) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateLinkTest.java
   */
  @Test
  public void test01_CreateLinktest() {
    session.execute("CREATE CLASS POST").close();
    session.execute("CREATE PROPERTY POST.comments LINKSET").close();

    session.begin();
    session.execute("INSERT INTO POST (id, title) VALUES ( 10, 'NoSQL movement' )").close();
    session.execute("INSERT INTO POST (id, title) VALUES ( 20, 'New YouTrackDB' )").close();

    session.execute("INSERT INTO POST (id, title) VALUES ( 30, '(')").close();

    session.execute("INSERT INTO POST (id, title) VALUES ( 40, ')')").close();
    session.commit();

    session.execute("CREATE CLASS COMMENT").close();

    session.begin();
    session.execute("INSERT INTO COMMENT (id, postId, text) VALUES ( 0, 10, 'First' )").close();
    session.execute("INSERT INTO COMMENT (id, postId, text) VALUES ( 1, 10, 'Second' )").close();
    session.execute("INSERT INTO COMMENT (id, postId, text) VALUES ( 21, 10, 'Another' )").close();
    session
        .execute("INSERT INTO COMMENT (id, postId, text) VALUES ( 41, 20, 'First again' )")
        .close();
    session
        .execute("INSERT INTO COMMENT (id, postId, text) VALUES ( 82, 20, 'Second Again' )")
        .close();

    Assert.assertEquals(
        ((Number)
            session
                .execute(
                    "CREATE LINK comments TYPE LINKSET FROM comment.postId TO post.id"
                        + " INVERSE")
                .next()
                .getProperty("count"))
            .intValue(),
        5);
    session.commit();

    session.begin();
    Assert.assertEquals(
        ((Number) session.execute("UPDATE comment REMOVE postId").next().getProperty("count"))
            .intValue(),
        5);
    session.commit();
  }

  /**
   * Original: createRIDLinktest (line 72) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateLinkTest.java
   */
  @Test
  public void test02_CreateRIDLinktest() {

    session.execute("CREATE CLASS POST2").close();
    session.execute("CREATE PROPERTY POST2.comments LINKSET").close();

    session.begin();
    Object p1 =
        session
            .execute("INSERT INTO POST2 (id, title) VALUES ( 10, 'NoSQL movement' )")
            .next()
            .asEntity();
    Assert.assertTrue(p1 instanceof EntityImpl);
    Object p2 =
        session
            .execute("INSERT INTO POST2 (id, title) VALUES ( 20, 'New YouTrackDB' )")
            .next()
            .asEntity();
    Assert.assertTrue(p2 instanceof EntityImpl);

    Object p3 =
        session.execute("INSERT INTO POST2 (id, title) VALUES ( 30, '(')").next().asEntity();
    Assert.assertTrue(p3 instanceof EntityImpl);

    Object p4 =
        session.execute("INSERT INTO POST2 (id, title) VALUES ( 40, ')')").next().asEntity();
    Assert.assertTrue(p4 instanceof EntityImpl);
    session.commit();

    session.execute("CREATE CLASS COMMENT2");

    session.begin();
    session
        .execute(
            "INSERT INTO COMMENT2 (id, postId, text) VALUES ( 0, '"
                + ((EntityImpl) p1).getIdentity()
                + "', 'First' )")
        .close();
    session
        .execute(
            "INSERT INTO COMMENT2 (id, postId, text) VALUES ( 1, '"
                + ((EntityImpl) p1).getIdentity()
                + "', 'Second' )")
        .close();
    session
        .execute(
            "INSERT INTO COMMENT2 (id, postId, text) VALUES ( 21, '"
                + ((EntityImpl) p1).getIdentity()
                + "', 'Another' )")
        .close();
    session
        .execute(
            "INSERT INTO COMMENT2 (id, postId, text) VALUES ( 41, '"
                + ((EntityImpl) p2).getIdentity()
                + "', 'First again' )")
        .close();
    session
        .execute(
            "INSERT INTO COMMENT2 (id, postId, text) VALUES ( 82, '"
                + ((EntityImpl) p2).getIdentity()
                + "', 'Second Again' )")
        .close();
    session.commit();

    session.begin();
    Assert.assertEquals(
        ((Number)
            session
                .execute(
                    "CREATE LINK comments TYPE LINKSET FROM comment2.postId TO post2.id"
                        + " INVERSE")
                .next()
                .getProperty("count"))
            .intValue(),
        5);
    session.commit();

    session.begin();
    Assert.assertEquals(
        ((Number) session.execute("UPDATE comment2 REMOVE postId").next().getProperty("count"))
            .intValue(),
        5);
    session.commit();
  }

}
