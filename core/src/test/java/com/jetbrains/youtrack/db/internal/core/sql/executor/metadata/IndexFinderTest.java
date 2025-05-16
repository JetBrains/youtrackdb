package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexFinder.Operation;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class IndexFinderTest {

  private DatabaseSessionEmbedded session;
  private YouTrackDBImpl youTrackDb;

  @Before
  public void before() {
    this.youTrackDb = (YouTrackDBImpl) YourTracks.embedded(
        DbTestBase.getBaseDirectoryPath(getClass()),
        YouTrackDBConfig.defaultConfig());
    this.youTrackDb.execute(
        "create database "
            + IndexFinderTest.class.getSimpleName()
            + " memory users (admin identified by 'adminpwd' role admin)");
    this.session =
        (DatabaseSessionEmbedded)
            this.youTrackDb.open(IndexFinderTest.class.getSimpleName(), "admin", "adminpwd");
  }

  @Test
  public void testFindSimpleMatchIndex() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("surname", PropertyType.STRING);
    prop1.createIndex(INDEX_TYPE.UNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = finder.findExactIndex(new IndexMetadataPath("name"), null, ctx);

    assertEquals("cl.name", result.getName());

    var result1 = finder.findExactIndex(new IndexMetadataPath("surname"), null,
        ctx);

    assertEquals("cl.surname", result1.getName());
  }

  @Test
  public void testFindSimpleMatchHashIndex() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("surname", PropertyType.STRING);
    prop1.createIndex(INDEX_TYPE.UNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = finder.findExactIndex(new IndexMetadataPath("name"), null, ctx);

    assertEquals("cl.name", result.getName());

    var result1 = finder.findExactIndex(new IndexMetadataPath("surname"), null,
        ctx);

    assertEquals("cl.surname", result1.getName());
  }

  @Test
  public void testFindRangeMatchIndex() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("surname", PropertyType.STRING);
    prop1.createIndex(INDEX_TYPE.UNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result =
        finder.findAllowRangeIndex(new IndexMetadataPath("name"), Operation.Ge, null, ctx);

    assertEquals("cl.name", result.getName());

    var result1 =
        finder.findAllowRangeIndex(new IndexMetadataPath("surname"), Operation.Ge, null, ctx);

    assertEquals("cl.surname", result1.getName());
  }

  @Test
  public void testFindRangeNotMatchIndex() {
    var cl = this.session.createClass("cl");

    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    var prop1 = cl.createProperty("surname", PropertyType.STRING);
    prop1.createIndex(INDEX_TYPE.UNIQUE);

    cl.createProperty("third", PropertyType.STRING);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result =
        finder.findAllowRangeIndex(new IndexMetadataPath("name"), Operation.Ge, null, ctx);

    assertNotNull(result);

    var result1 =
        finder.findAllowRangeIndex(new IndexMetadataPath("surname"), Operation.Ge, null, ctx);

    assertNotNull(result1);

    var result2 =
        finder.findAllowRangeIndex(new IndexMetadataPath("third"), Operation.Ge, null, ctx);

    Assert.assertNull(result2);
  }

  @Test
  public void testFindByKey() {
    var cl = this.session.createClass("cl");
    cl.createProperty("map", PropertyType.EMBEDDEDMAP);
    this.session.execute("create index cl.map on cl(map by key) NOTUNIQUE").close();

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = finder.findByKeyIndex(new IndexMetadataPath("map"), null, ctx);

    assertEquals("cl.map", result.getName());
  }

  @Test
  public void testFindByValue() {
    var cl = this.session.createClass("cl");
    cl.createProperty("map", PropertyType.EMBEDDEDMAP, PropertyType.STRING);
    this.session.execute("create index cl.map on cl(map by value) NOTUNIQUE").close();

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = finder.findByValueIndex(new IndexMetadataPath("map"), null, ctx);

    assertEquals("cl.map", result.getName());
  }

  @Test
  public void testFindChainMatchIndex() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("friend", PropertyType.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var path = new IndexMetadataPath("name");
    path.addPre("friend");
    path.addPre("friend");
    var result = finder.findExactIndex(path, null, ctx);
    assertEquals("cl.friend->cl.friend->cl.name->", result.getName());
  }

  @Test
  public void testFindChainRangeIndex() {
    var cl = this.session.createClass("cl");
    var prop = cl.createProperty("name", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);
    var prop1 = cl.createProperty("friend", PropertyType.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var path = new IndexMetadataPath("name");
    path.addPre("friend");
    path.addPre("friend");
    var result = finder.findAllowRangeIndex(path, Operation.Ge, null, ctx);
    assertEquals("cl.friend->cl.friend->cl.name->", result.getName());
  }

  @Test
  public void testFindChainByKeyIndex() {
    var cl = this.session.createClass("cl");
    cl.createProperty("map", PropertyType.EMBEDDEDMAP, PropertyType.STRING);
    this.session.execute("create index cl.map on cl(map by key) NOTUNIQUE").close();
    var prop1 = cl.createProperty("friend", PropertyType.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var path = new IndexMetadataPath("map");
    path.addPre("friend");
    path.addPre("friend");
    var result = finder.findByKeyIndex(path, null, ctx);
    assertEquals("cl.friend->cl.friend->cl.map->", result.getName());
  }

  @Test
  public void testFindChainByValueIndex() {
    var cl = this.session.createClass("cl");
    cl.createProperty("map", PropertyType.EMBEDDEDMAP, PropertyType.STRING);
    this.session.execute("create index cl.map on cl(map by value) NOTUNIQUE").close();
    var prop1 = cl.createProperty("friend", PropertyType.LINK, cl);
    prop1.createIndex(INDEX_TYPE.NOTUNIQUE);

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var path = new IndexMetadataPath("map");
    path.addPre("friend");
    path.addPre("friend");
    var result = finder.findByValueIndex(path, null, ctx);
    assertEquals("cl.friend->cl.friend->cl.map->", result.getName());
  }

  @Test
  public void testFindMultivalueMatchIndex() {
    var cl = this.session.createClass("cl");
    cl.createProperty("name", PropertyType.STRING);
    cl.createProperty("surname", PropertyType.STRING);
    cl.createIndex("cl.name_surname", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = finder.findExactIndex(new IndexMetadataPath("name"), null, ctx);

    assertEquals("cl.name_surname", result.getName());

    var result1 = finder.findExactIndex(new IndexMetadataPath("surname"), null,
        ctx);

    assertEquals("cl.name_surname", result1.getName());
  }

  @After
  public void after() {
    this.session.close();
    this.youTrackDb.close();
  }
}
