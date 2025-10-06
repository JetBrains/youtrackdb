package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class StatementIndexFinderTest {

  private DatabaseSessionEmbedded session;
  private YouTrackDBImpl youTrackDb;

  @Before
  public void before() {
    this.youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()));
    this.youTrackDb.execute(
        "create database "
            + StatementIndexFinderTest.class.getSimpleName()
            + " memory users (admin identified by 'adminpwd' role admin)");
    this.session =
        (DatabaseSessionEmbedded)
            this.youTrackDb.open(
                StatementIndexFinderTest.class.getSimpleName(), "admin", "adminpwd");
  }

  @Test
  public void simpleMatchTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    var stat = parseQuery("select from cl where name='a'");
    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.name", result.getName());
    assertEquals(Operation.Eq, result.getOperation());
  }

  @Test
  public void simpleRangeTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    var stat = parseQuery("select from cl where name > 'a'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.name", result.getName());
    assertEquals(Operation.Gt, result.getOperation());

    var stat1 = parseQuery("select from cl where name < 'a'");
    var result1 = stat1.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.name", result1.getName());
    assertEquals(Operation.Lt, result1.getOperation());
  }

  @Test
  public void multipleSimpleAndMatchTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    var stat = parseQuery("select from cl where name='a' and name='b'");
    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertTrue((result instanceof MultipleIndexCanditate));
    var multiple = (MultipleIndexCanditate) result;
    assertEquals("cl.name", multiple.getCanditates().get(0).getName());
    assertEquals(Operation.Eq, multiple.getCanditates().get(0).getOperation());
    assertEquals("cl.name", multiple.getCanditates().get(1).getName());
    assertEquals(Operation.Eq, multiple.getCanditates().get(0).getOperation());
  }

  @Test
  public void requiredRangeOrMatchTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    var stat = parseQuery("select from cl where name='a' or name='b'");
    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertTrue((result instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result;
    assertEquals("cl.name", required.getCanditates().get(0).getName());
    assertEquals(Operation.Eq, required.getCanditates().get(0).getOperation());
    assertEquals("cl.name", required.getCanditates().get(1).getName());
    assertEquals(Operation.Eq, required.getCanditates().get(1).getOperation());
  }

  @Test
  public void multipleRangeAndTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat = parseQuery("select from cl where name < 'a' and name > 'b'");
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertTrue((result instanceof MultipleIndexCanditate));
    var multiple = (MultipleIndexCanditate) result;
    assertEquals("cl.name", multiple.getCanditates().get(0).getName());
    assertEquals(Operation.Lt, multiple.getCanditates().get(0).getOperation());
    assertEquals("cl.name", multiple.getCanditates().get(1).getName());
    assertEquals(Operation.Gt, multiple.getCanditates().get(1).getOperation());
  }

  @Test
  public void requiredRangeOrTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat = parseQuery("select from cl where name < 'a' or name > 'b'");
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertTrue((result instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result;
    assertEquals("cl.name", required.getCanditates().get(0).getName());
    assertEquals(Operation.Lt, required.getCanditates().get(0).getOperation());
    assertEquals("cl.name", required.getCanditates().get(1).getName());
    assertEquals(Operation.Gt, required.getCanditates().get(1).getOperation());
  }

  @Test
  public void simpleRangeNotTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat = parseQuery("select from cl where not name < 'a' ");
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.name", result.getName());
    assertEquals(Operation.Ge, result.getOperation());
  }

  @Test
  public void simpleChainTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("friend", PropertyTypeInternal.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat = parseQuery("select from cl where friend.friend.name = 'a' ");
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.friend->cl.friend->cl.name->", result.getName());
    assertEquals(Operation.Eq, result.getOperation());
  }

  @Test
  public void simpleNestedAndOrMatchTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("friend", PropertyTypeInternal.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat =
        parseQuery(
            "select from cl where (friend.name = 'a' and name='a') or (friend.name='b' and"
                + " name='b') ");
    var result = stat.getWhereClause().findIndex(finder, ctx);

    assertTrue((result instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result;
    assertTrue((required.getCanditates().getFirst() instanceof MultipleIndexCanditate));
    var first = (MultipleIndexCanditate) required.getCanditates().getFirst();
    assertEquals("cl.friend->cl.name->", first.getCanditates().get(0).getName());
    assertEquals(Operation.Eq, first.getCanditates().get(0).getOperation());
    assertEquals("cl.name", first.getCanditates().get(1).getName());
    assertEquals(Operation.Eq, first.getCanditates().get(1).getOperation());

    var second = (MultipleIndexCanditate) required.getCanditates().get(1);
    assertEquals("cl.friend->cl.name->", second.getCanditates().get(0).getName());
    assertEquals(Operation.Eq, second.getCanditates().get(0).getOperation());
    assertEquals("cl.name", second.getCanditates().get(1).getName());
    assertEquals(Operation.Eq, second.getCanditates().get(1).getOperation());
  }

  @Test
  public void simpleNestedAndOrPartialMatchTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat =
        parseQuery(
            "select from cl where (friend.name = 'a' and name='a') or (friend.name='b' and"
                + " name='b') ");
    var result = stat.getWhereClause().findIndex(finder, ctx);

    assertTrue((result instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result;
    var first = required.getCanditates().getFirst();
    assertEquals("cl.name", first.getName());
    assertEquals(Operation.Eq, first.getOperation());

    var second = required.getCanditates().get(1);
    assertEquals("cl.name", second.getName());
    assertEquals(Operation.Eq, second.getOperation());
  }

  @Test
  public void simpleNestedOrNotMatchTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    var prop = cl.createProperty("name", PropertyTypeInternal.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("friend", PropertyTypeInternal.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat =
        parseQuery(
            "select from cl where (friend.name = 'a' and name='a') or (friend.other='b' and"
                + " other='b') ");
    var result = stat.getWhereClause().findIndex(finder, ctx);

    assertNull(result);
  }

  @Test
  public void multivalueMatchTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    cl.createProperty("name", PropertyTypeInternal.STRING);
    cl.createProperty("surname", PropertyTypeInternal.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat = parseQuery("select from cl where name = 'a' and surname = 'b'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.normalize(ctx);
    assertEquals("cl.name_surname", result.getName());
    assertEquals(Operation.Eq, result.getOperation());
  }

  @Test
  public void multivalueMatchOneTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    cl.createProperty("name", PropertyTypeInternal.STRING);
    cl.createProperty("surname", PropertyTypeInternal.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat = parseQuery("select from cl where name = 'a' and other = 'b'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.normalize(ctx);
    assertEquals("cl.name_surname", result.getName());
    assertEquals(Operation.Eq, result.getOperation());
  }

  @Test
  public void multivalueNotMatchSecondPropertyTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    cl.createProperty("name", PropertyTypeInternal.STRING);
    cl.createProperty("surname", PropertyTypeInternal.STRING);
    cl.createProperty("other", PropertyTypeInternal.STRING);
    cl.createIndex("cl.name_surname_other", INDEX_TYPE.NOTUNIQUE, "name", "surname",
        "other");

    var stat = parseQuery("select from cl where surname = 'a' and other = 'b'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.normalize(ctx);
    assertNull(result);
  }

  @Test
  public void multivalueNotMatchSecondPropertySingleConditionTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    cl.createProperty("name", PropertyTypeInternal.STRING);
    cl.createProperty("surname", PropertyTypeInternal.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat = parseQuery("select from cl where surname = 'a'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.normalize(ctx);
    assertNull(result);
  }

  @Test
  public void multivalueMatchPropertyORTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    cl.createProperty("name", PropertyTypeInternal.STRING);
    cl.createProperty("surname", PropertyTypeInternal.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat =
        parseQuery(
            "select from cl where (name = 'a' and surname = 'b') or (name='d' and surname='e')");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.normalize(ctx);
    assertNotNull(result);
    assertTrue((result instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result;
    assertEquals("cl.name_surname", required.getCanditates().get(0).getName());
    assertEquals(Operation.Eq, required.getCanditates().get(0).getOperation());
    assertEquals("cl.name_surname", required.getCanditates().get(1).getName());
    assertEquals(Operation.Eq, required.getCanditates().get(1).getOperation());
    assertEquals(2, required.getCanditates().size());
  }

  @Test
  public void multivalueNotMatchPropertyORTest() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    cl.createProperty("name", PropertyTypeInternal.STRING);
    cl.createProperty("surname", PropertyTypeInternal.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat =
        parseQuery(
            "select from cl where (name = 'a' and surname = 'b') or (other='d' and surname='e')");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.normalize(ctx);
    assertNull(result);
  }

  @Test
  public void testMutipleConditionBetween() {
    var cl = this.session.getMetadata().getSlowMutableSchema().createClass("cl");
    cl.createProperty("name", PropertyTypeInternal.STRING);
    cl.createIndex("cl.name", INDEX_TYPE.NOTUNIQUE, "name");

    var stat = parseQuery("select from cl where name < 'a' and name > 'b'");
    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.normalize(ctx);
    assertTrue((result instanceof RangeIndexCanditate));
    assertEquals("cl.name", result.getName());
    assertEquals(Operation.Range, result.getOperation());
  }

  private static SQLSelectStatement parseQuery(String query) {
    InputStream is = new ByteArrayInputStream(query.getBytes());
    var osql = new YouTrackDBSql(is);
    try {
      SimpleNode n = osql.parse();
      return (SQLSelectStatement) n;
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  @After
  public void after() {
    this.session.close();
    this.youTrackDb.close();
  }
}
