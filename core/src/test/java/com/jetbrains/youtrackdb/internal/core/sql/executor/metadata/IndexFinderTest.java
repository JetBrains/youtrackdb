package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.IndexBy;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class IndexFinderTest {

  private DatabaseSessionEmbedded session;
  private YouTrackDBImpl youTrackDb;

  @Before
  public void before() {
    this.youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()));
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
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g -> g.addSchemaClass("cl").as("cl").
          addSchemaProperty("name", PropertyType.STRING).addPropertyIndex(IndexType.NOT_UNIQUE)
          .select("cl").
          addSchemaProperty("surname", PropertyType.STRING).addPropertyIndex(IndexType.UNIQUE));
    }

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
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g -> g.addSchemaClass("cl").as("cl").
          addSchemaProperty("name", PropertyType.STRING).addPropertyIndex(IndexType.NOT_UNIQUE)
          .select("cl").
          addSchemaProperty("surname", PropertyType.STRING).addPropertyIndex(IndexType.UNIQUE));
    }

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
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g ->
          g.addSchemaClass("cl").as("cl").
              addSchemaProperty("name", PropertyType.STRING).addPropertyIndex(IndexType.NOT_UNIQUE)
              .select("cl").
              addSchemaProperty("surname", PropertyType.STRING).addPropertyIndex(IndexType.UNIQUE)
              .select("cl").
              addSchemaProperty("third", PropertyType.STRING)
      );
    }

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
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g ->
          g.addSchemaClass("cl").addSchemaProperty("map", PropertyType.EMBEDDEDMAP)
              .addPropertyIndex(IndexType.NOT_UNIQUE, IndexBy.BY_KEY)

      );
    }

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = finder.findByKeyIndex(new IndexMetadataPath("map"), null, ctx);

    assertEquals("cl.map", result.getName());
  }

  @Test
  public void testFindByValue() {
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g ->
          g.addSchemaClass("cl")
              .addSchemaProperty("map", PropertyType.EMBEDDEDMAP, PropertyType.STRING)
              .addPropertyIndex(IndexType.NOT_UNIQUE, IndexBy.BY_VALUE)
      );
    }

    IndexFinder finder = new ClassIndexFinder("cl");
    var ctx = new BasicCommandContext(session);
    var result = finder.findByValueIndex(new IndexMetadataPath("map"), null, ctx);

    assertEquals("cl.map", result.getName());
  }

  @Test
  public void testFindChainMatchIndex() {
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g ->
          g.addSchemaClass("cl").as("cl")
              .addSchemaProperty("name", PropertyType.STRING).addPropertyIndex(IndexType.NOT_UNIQUE)
              .select("cl")
              .addSchemaProperty("friend", PropertyType.LINK, "cl")
              .addPropertyIndex(IndexType.UNIQUE)

      );
    }

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
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g ->
          g.addSchemaClass("cl").as("cl")
              .addSchemaProperty("name", PropertyType.STRING).addPropertyIndex(IndexType.NOT_UNIQUE)
              .select("cl")
              .addSchemaProperty("friend", PropertyType.LINK, "cl")
              .addPropertyIndex(IndexType.UNIQUE)

      );
    }
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
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g ->
          g.addSchemaClass("cl").as("cl")
              .addSchemaProperty("map", PropertyType.EMBEDDEDMAP, PropertyType.STRING)
              .addPropertyIndex(IndexType.NOT_UNIQUE, IndexBy.BY_KEY)
              .select("cl")
              .addSchemaProperty("friend", PropertyType.LINK, "cl")
              .addPropertyIndex(IndexType.UNIQUE)

      );
    }

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
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g ->
          g.addSchemaClass("cl").as("cl")
              .addSchemaProperty("map", PropertyType.EMBEDDEDMAP, PropertyType.STRING)
              .addPropertyIndex(IndexType.NOT_UNIQUE, IndexBy.BY_VALUE)
              .select("cl")
              .addSchemaProperty("friend", PropertyType.LINK, "cl")
              .addPropertyIndex(IndexType.UNIQUE)

      );
    }

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
    try (var graph = youTrackDb.openGraph(IndexFinderTest.class.getSimpleName(), "admin",
        "adminpwd")) {
      graph.autoExecuteInTx(g -> g.addSchemaClass("cl").as("cl").
          addSchemaProperty("name", PropertyType.STRING)
          .select("cl").
          addSchemaProperty("surname", PropertyType.STRING)
          .select("cl").addClassIndex("cl.name_surname", IndexType.NOT_UNIQUE, "name", "surname")
      );
    }

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
