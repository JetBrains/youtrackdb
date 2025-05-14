package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexFinder.Operation;
import com.jetbrains.youtrack.db.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SimpleNode;
import com.jetbrains.youtrack.db.internal.core.sql.parser.YouTrackDBSql;
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
    this.youTrackDb = (YouTrackDBImpl) YourTracks.embedded(
        DbTestBase.getBaseDirectoryPath(getClass()),
        YouTrackDBConfig.defaultConfig());
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
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    var stat = parseQuery("select from cl where name='a'");
    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.name", result.get().getName());
    assertEquals(Operation.Eq, result.get().getOperation());
  }

  @Test
  public void simpleRangeTest() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    var stat = parseQuery("select from cl where name > 'a'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.name", result.get().getName());
    assertEquals(Operation.Gt, result.get().getOperation());

    var stat1 = parseQuery("select from cl where name < 'a'");
    var result1 = stat1.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.name", result1.get().getName());
    assertEquals(Operation.Lt, result1.get().getOperation());
  }

  @Test
  public void multipleSimpleAndMatchTest() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    var stat = parseQuery("select from cl where name='a' and name='b'");
    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertTrue((result.get() instanceof MultipleIndexCanditate));
    var multiple = (MultipleIndexCanditate) result.get();
    assertEquals("cl.name", multiple.getCanditates().get(0).getName());
    assertEquals(Operation.Eq, multiple.getCanditates().get(0).getOperation());
    assertEquals("cl.name", multiple.getCanditates().get(1).getName());
    assertEquals(Operation.Eq, multiple.getCanditates().get(0).getOperation());
  }

  @Test
  public void requiredRangeOrMatchTest() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    var stat = parseQuery("select from cl where name='a' or name='b'");
    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertTrue((result.get() instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result.get();
    assertEquals("cl.name", required.getCanditates().get(0).getName());
    assertEquals(Operation.Eq, required.getCanditates().get(0).getOperation());
    assertEquals("cl.name", required.getCanditates().get(1).getName());
    assertEquals(Operation.Eq, required.getCanditates().get(1).getOperation());
  }

  @Test
  public void multipleRangeAndTest() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat = parseQuery("select from cl where name < 'a' and name > 'b'");
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertTrue((result.get() instanceof MultipleIndexCanditate));
    var multiple = (MultipleIndexCanditate) result.get();
    assertEquals("cl.name", multiple.getCanditates().get(0).getName());
    assertEquals(Operation.Lt, multiple.getCanditates().get(0).getOperation());
    assertEquals("cl.name", multiple.getCanditates().get(1).getName());
    assertEquals(Operation.Gt, multiple.getCanditates().get(1).getOperation());
  }

  @Test
  public void requiredRangeOrTest() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat = parseQuery("select from cl where name < 'a' or name > 'b'");
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertTrue((result.get() instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result.get();
    assertEquals("cl.name", required.getCanditates().get(0).getName());
    assertEquals(Operation.Lt, required.getCanditates().get(0).getOperation());
    assertEquals("cl.name", required.getCanditates().get(1).getName());
    assertEquals(Operation.Gt, required.getCanditates().get(1).getOperation());
  }

  @Test
  public void simpleRangeNotTest() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat = parseQuery("select from cl where not name < 'a' ");
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.name", result.get().getName());
    assertEquals(Operation.Ge, result.get().getOperation());
  }

  @Test
  public void simpleChainTest() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("friend", PropertyType.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat = parseQuery("select from cl where friend.friend.name = 'a' ");
    var result = stat.getWhereClause().findIndex(finder, ctx);
    assertEquals("cl.friend->cl.friend->cl.name->", result.get().getName());
    assertEquals(Operation.Eq, result.get().getOperation());
  }

  @Test
  public void simpleNestedAndOrMatchTest() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("friend", PropertyType.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat =
        parseQuery(
            "select from cl where (friend.name = 'a' and name='a') or (friend.name='b' and"
                + " name='b') ");
    var result = stat.getWhereClause().findIndex(finder, ctx);

    assertTrue((result.get() instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result.get();
    assertTrue((required.getCanditates().get(0) instanceof MultipleIndexCanditate));
    var first = (MultipleIndexCanditate) required.getCanditates().get(0);
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
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat =
        parseQuery(
            "select from cl where (friend.name = 'a' and name='a') or (friend.name='b' and"
                + " name='b') ");
    var result = stat.getWhereClause().findIndex(finder, ctx);

    assertTrue((result.get() instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result.get();
    var first = required.getCanditates().get(0);
    assertEquals("cl.name", first.getName());
    assertEquals(Operation.Eq, first.getOperation());

    var second = required.getCanditates().get(1);
    assertEquals("cl.name", second.getName());
    assertEquals(Operation.Eq, second.getOperation());
  }

  @Test
  public void simpleNestedOrNotMatchTest() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("friend", PropertyType.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var stat =
        parseQuery(
            "select from cl where (friend.name = 'a' and name='a') or (friend.other='b' and"
                + " other='b') ");
    var result = stat.getWhereClause().findIndex(finder, ctx);

    assertFalse(result.isPresent());
  }

  @Test
  public void multivalueMatchTest() {
    var cl = this.session.createClass("cl");
    cl.createProperty("name", PropertyType.STRING);
    cl.createProperty("surname", PropertyType.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat = parseQuery("select from cl where name = 'a' and surname = 'b'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.get().normalize(ctx);
    assertEquals("cl.name_surname", result.get().getName());
    assertEquals(Operation.Eq, result.get().getOperation());
  }

  @Test
  public void multivalueMatchOneTest() {
    var cl = this.session.createClass("cl");
    cl.createProperty("name", PropertyType.STRING);
    cl.createProperty("surname", PropertyType.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat = parseQuery("select from cl where name = 'a' and other = 'b'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.get().normalize(ctx);
    assertEquals("cl.name_surname", result.get().getName());
    assertEquals(Operation.Eq, result.get().getOperation());
  }

  @Test
  public void multivalueNotMatchSecondPropertyTest() {
    var cl = this.session.createClass("cl");
    cl.createProperty("name", PropertyType.STRING);
    cl.createProperty("surname", PropertyType.STRING);
    cl.createProperty("other", PropertyType.STRING);
    cl.createIndex("cl.name_surname_other", INDEX_TYPE.NOTUNIQUE, "name", "surname",
        "other");

    var stat = parseQuery("select from cl where surname = 'a' and other = 'b'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.get().normalize(ctx);
    assertFalse(result.isPresent());
  }

  @Test
  public void multivalueNotMatchSecondPropertySingleConditionTest() {
    var cl = this.session.createClass("cl");
    cl.createProperty("name", PropertyType.STRING);
    cl.createProperty("surname", PropertyType.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat = parseQuery("select from cl where surname = 'a'");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.get().normalize(ctx);
    assertFalse(result.isPresent());
  }

  @Test
  public void multivalueMatchPropertyORTest() {
    var cl = this.session.createClass("cl");
    cl.createProperty("name", PropertyType.STRING);
    cl.createProperty("surname", PropertyType.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat =
        parseQuery(
            "select from cl where (name = 'a' and surname = 'b') or (name='d' and surname='e')");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.get().normalize(ctx);
    assertTrue(result.isPresent());
    assertTrue((result.get() instanceof RequiredIndexCanditate));
    var required = (RequiredIndexCanditate) result.get();
    assertEquals("cl.name_surname", required.getCanditates().get(0).getName());
    assertEquals(Operation.Eq, required.getCanditates().get(0).getOperation());
    assertEquals("cl.name_surname", required.getCanditates().get(1).getName());
    assertEquals(Operation.Eq, required.getCanditates().get(1).getOperation());
    assertEquals(required.getCanditates().size(), 2);
  }

  @Test
  public void multivalueNotMatchPropertyORTest() {
    var cl = this.session.createClass("cl");
    cl.createProperty("name", PropertyType.STRING);
    cl.createProperty("surname", PropertyType.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    var stat =
        parseQuery(
            "select from cl where (name = 'a' and surname = 'b') or (other='d' and surname='e')");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.get().normalize(ctx);
    assertFalse(result.isPresent());
  }

  @Test
  public void testMutipleConditionBetween() {
    var cl = this.session.createClass("cl");
    cl.createProperty("name", PropertyType.STRING);
    cl.createIndex("cl.name", INDEX_TYPE.NOTUNIQUE, "name");

    var stat = parseQuery("select from cl where name < 'a' and name > 'b'");
    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);

    var result = stat.getWhereClause().findIndex(finder, ctx);
    result = result.get().normalize(ctx);
    assertTrue((result.get() instanceof RangeIndexCanditate));
    assertEquals("cl.name", result.get().getName());
    assertEquals(Operation.Range, result.get().getOperation());
  }

  private SQLSelectStatement parseQuery(String query) {
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
