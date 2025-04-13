package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrack.db.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class CollateTest extends BaseDBTest {

  @Parameters(value = "remote")
  public CollateTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  public void testQuery() {
    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz = schema.createClass("collateTest");

    SchemaProperty csp = clazz.createProperty(database, "csp", PropertyType.STRING);
    csp.setCollate(database, DefaultCollate.NAME);

    SchemaProperty cip = clazz.createProperty(database, "cip", PropertyType.STRING);
    cip.setCollate(database, CaseInsensitiveCollate.NAME);

    for (int i = 0; i < 10; i++) {
      EntityImpl document = new EntityImpl("collateTest");

      if (i % 2 == 0) {
        document.field("csp", "VAL");
        document.field("cip", "VAL");
      } else {
        document.field("csp", "val");
        document.field("cip", "val");
      }

      database.begin();
      document.save();
      database.commit();
    }

    @SuppressWarnings("deprecation")
    List<EntityImpl> result =
        database.query(
            new SQLSynchQuery<EntityImpl>("select from collateTest where csp = 'VAL'"));
    Assert.assertEquals(result.size(), 5);

    for (EntityImpl document : result) {
      Assert.assertEquals(document.field("csp"), "VAL");
    }

    //noinspection deprecation
    result =
        database.query(
            new SQLSynchQuery<EntityImpl>("select from collateTest where cip = 'VaL'"));
    Assert.assertEquals(result.size(), 10);

    for (EntityImpl document : result) {
      Assert.assertEquals((document.<String>field("cip")).toUpperCase(Locale.ENGLISH), "VAL");
    }
  }

  public void testQueryNotNullCi() {
    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz = schema.createClass("collateTestNotNull");

    SchemaProperty csp = clazz.createProperty(database, "bar", PropertyType.STRING);
    csp.setCollate(database, CaseInsensitiveCollate.NAME);

    EntityImpl document = new EntityImpl("collateTestNotNull");
    document.field("bar", "baz");

    database.begin();
    document.save();
    database.commit();

    document = new EntityImpl("collateTestNotNull");
    document.field("nobar", true);

    database.begin();
    document.save();
    database.commit();

    @SuppressWarnings("deprecation")
    List<EntityImpl> result =
        database.query(
            new SQLSynchQuery<EntityImpl>("select from collateTestNotNull where bar is null"));
    Assert.assertEquals(result.size(), 1);

    //noinspection deprecation
    result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from collateTestNotNull where bar is not null"));
    Assert.assertEquals(result.size(), 1);
  }

  public void testIndexQuery() {
    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz = schema.createClass("collateIndexTest");

    SchemaProperty csp = clazz.createProperty(database, "csp", PropertyType.STRING);
    csp.setCollate(database, DefaultCollate.NAME);

    SchemaProperty cip = clazz.createProperty(database, "cip", PropertyType.STRING);
    cip.setCollate(database, CaseInsensitiveCollate.NAME);

    clazz.createIndex(database, "collateIndexCSP", SchemaClass.INDEX_TYPE.NOTUNIQUE, "csp");
    clazz.createIndex(database, "collateIndexCIP", SchemaClass.INDEX_TYPE.NOTUNIQUE, "cip");

    for (int i = 0; i < 10; i++) {
      EntityImpl document = new EntityImpl("collateIndexTest");

      if (i % 2 == 0) {
        document.field("csp", "VAL");
        document.field("cip", "VAL");
      } else {
        document.field("csp", "val");
        document.field("cip", "val");
      }

      database.begin();
      document.save();
      database.commit();
    }

    String query = "select from collateIndexTest where csp = 'VAL'";
    @SuppressWarnings("deprecation")
    List<EntityImpl> result = database.query(new SQLSynchQuery<EntityImpl>(query));
    Assert.assertEquals(result.size(), 5);

    for (EntityImpl document : result) {
      Assert.assertEquals(document.field("csp"), "VAL");
    }

    @SuppressWarnings("deprecation")
    EntityImpl explain = database.command(new CommandSQL("explain " + query)).execute(database);
    Assert.assertTrue(explain.<Set<String>>field("involvedIndexes").contains("collateIndexCSP"));

    query = "select from collateIndexTest where cip = 'VaL'";
    //noinspection deprecation
    result = database.query(new SQLSynchQuery<EntityImpl>(query));
    Assert.assertEquals(result.size(), 10);

    for (EntityImpl document : result) {
      Assert.assertEquals((document.<String>field("cip")).toUpperCase(Locale.ENGLISH), "VAL");
    }

    //noinspection deprecation
    explain = database.command(new CommandSQL("explain " + query)).execute(database);
    Assert.assertTrue(explain.<Set<String>>field("involvedIndexes").contains("collateIndexCIP"));
  }

  public void testIndexQueryCollateWasChanged() {
    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz = schema.createClass("collateWasChangedIndexTest");

    SchemaProperty cp = clazz.createProperty(database, "cp", PropertyType.STRING);
    cp.setCollate(database, DefaultCollate.NAME);

    clazz.createIndex(database, "collateWasChangedIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "cp");

    for (int i = 0; i < 10; i++) {
      EntityImpl document = new EntityImpl("collateWasChangedIndexTest");

      if (i % 2 == 0) {
        document.field("cp", "VAL");
      } else {
        document.field("cp", "val");
      }

      database.begin();
      document.save();
      database.commit();
    }

    String query = "select from collateWasChangedIndexTest where cp = 'VAL'";
    @SuppressWarnings("deprecation")
    List<EntityImpl> result = database.query(new SQLSynchQuery<EntityImpl>(query));
    Assert.assertEquals(result.size(), 5);

    for (EntityImpl document : result) {
      Assert.assertEquals(document.field("cp"), "VAL");
    }

    @SuppressWarnings("deprecation")
    EntityImpl explain = database.command(new CommandSQL("explain " + query)).execute(database);
    Assert.assertTrue(
        explain.<Set<String>>field("involvedIndexes").contains("collateWasChangedIndex"));

    cp = clazz.getProperty(database, "cp");
    cp.setCollate(database, CaseInsensitiveCollate.NAME);

    query = "select from collateWasChangedIndexTest where cp = 'VaL'";
    //noinspection deprecation
    result = database.query(new SQLSynchQuery<EntityImpl>(query));
    Assert.assertEquals(result.size(), 10);

    for (EntityImpl document : result) {
      Assert.assertEquals((document.<String>field("cp")).toUpperCase(Locale.ENGLISH), "VAL");
    }

    //noinspection deprecation
    explain = database.command(new CommandSQL("explain " + query)).execute(database);
    Assert.assertTrue(
        explain.<Set<String>>field("involvedIndexes").contains("collateWasChangedIndex"));
  }

  public void testCompositeIndexQueryCS() {
    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz = schema.createClass("CompositeIndexQueryCSTest");

    SchemaProperty csp = clazz.createProperty(database, "csp", PropertyType.STRING);
    csp.setCollate(database, DefaultCollate.NAME);

    SchemaProperty cip = clazz.createProperty(database, "cip", PropertyType.STRING);
    cip.setCollate(database, CaseInsensitiveCollate.NAME);

    clazz.createIndex(database, "collateCompositeIndexCS", SchemaClass.INDEX_TYPE.NOTUNIQUE, "csp",
        "cip");

    for (int i = 0; i < 10; i++) {
      EntityImpl document = new EntityImpl("CompositeIndexQueryCSTest");

      if (i % 2 == 0) {
        document.field("csp", "VAL");
        document.field("cip", "VAL");
      } else {
        document.field("csp", "val");
        document.field("cip", "val");
      }

      database.begin();
      document.save();
      database.commit();
    }

    String query = "select from CompositeIndexQueryCSTest where csp = 'VAL'";
    @SuppressWarnings("deprecation")
    List<EntityImpl> result = database.query(new SQLSynchQuery<EntityImpl>(query));
    Assert.assertEquals(result.size(), 5);

    for (EntityImpl document : result) {
      Assert.assertEquals(document.field("csp"), "VAL");
    }

    @SuppressWarnings("deprecation")
    EntityImpl explain = database.command(new CommandSQL("explain " + query)).execute(database);
    Assert.assertTrue(
        explain.<Set<String>>field("involvedIndexes").contains("collateCompositeIndexCS"));

    query = "select from CompositeIndexQueryCSTest where csp = 'VAL' and cip = 'VaL'";
    //noinspection deprecation
    result = database.query(new SQLSynchQuery<EntityImpl>(query));
    Assert.assertEquals(result.size(), 5);

    for (EntityImpl document : result) {
      Assert.assertEquals(document.field("csp"), "VAL");
      Assert.assertEquals((document.<String>field("cip")).toUpperCase(Locale.ENGLISH), "VAL");
    }

    //noinspection deprecation
    explain = database.command(new CommandSQL("explain " + query)).execute(database);
    Assert.assertTrue(
        explain.<Set<String>>field("involvedIndexes").contains("collateCompositeIndexCS"));

    if (!database.getStorage().isRemote()) {
      final IndexManagerAbstract indexManager = database.getMetadata().getIndexManagerInternal();
      final Index index = indexManager.getIndex(database, "collateCompositeIndexCS");

      final Collection<RID> value;
      try (Stream<RID> stream = index.getInternal()
          .getRids(database, new CompositeKey("VAL", "VaL"))) {
        value = stream.toList();
      }

      Assert.assertEquals(value.size(), 5);
      for (RID identifiable : value) {
        final EntityImpl record = identifiable.getRecord();
        Assert.assertEquals(record.field("csp"), "VAL");
        Assert.assertEquals((record.<String>field("cip")).toUpperCase(Locale.ENGLISH), "VAL");
      }
    }
  }

  public void testCompositeIndexQueryCollateWasChanged() {
    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz = schema.createClass("CompositeIndexQueryCollateWasChangedTest");

    SchemaProperty csp = clazz.createProperty(database, "csp", PropertyType.STRING);
    csp.setCollate(database, DefaultCollate.NAME);

    clazz.createProperty(database, "cip", PropertyType.STRING);

    clazz.createIndex(database,
        "collateCompositeIndexCollateWasChanged", SchemaClass.INDEX_TYPE.NOTUNIQUE, "csp", "cip");

    for (int i = 0; i < 10; i++) {
      EntityImpl document = new EntityImpl("CompositeIndexQueryCollateWasChangedTest");
      if (i % 2 == 0) {
        document.field("csp", "VAL");
        document.field("cip", "VAL");
      } else {
        document.field("csp", "val");
        document.field("cip", "val");
      }

      database.begin();
      document.save();
      database.commit();
    }

    String query = "select from CompositeIndexQueryCollateWasChangedTest where csp = 'VAL'";
    @SuppressWarnings("deprecation")
    List<EntityImpl> result = database.query(new SQLSynchQuery<EntityImpl>(query));
    Assert.assertEquals(result.size(), 5);

    for (EntityImpl document : result) {
      Assert.assertEquals(document.field("csp"), "VAL");
    }

    @SuppressWarnings("deprecation")
    EntityImpl explain = database.command(new CommandSQL("explain " + query)).execute(database);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("collateCompositeIndexCollateWasChanged"));

    csp = clazz.getProperty(database,"csp");
    csp.setCollate(database, CaseInsensitiveCollate.NAME);

    query = "select from CompositeIndexQueryCollateWasChangedTest where csp = 'VaL'";
    //noinspection deprecation
    result = database.query(new SQLSynchQuery<EntityImpl>(query));
    Assert.assertEquals(result.size(), 10);

    for (EntityImpl document : result) {
      Assert.assertEquals(document.<String>field("csp").toUpperCase(Locale.ENGLISH), "VAL");
    }

    //noinspection deprecation
    explain = database.command(new CommandSQL("explain " + query)).execute(database);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("collateCompositeIndexCollateWasChanged"));
  }

  public void collateThroughSQL() {
    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz = schema.createClass("collateTestViaSQL");

    clazz.createProperty(database, "csp", PropertyType.STRING);
    clazz.createProperty(database, "cip", PropertyType.STRING);

    //noinspection deprecation
    database
        .command(
            new CommandSQL(
                "create index collateTestViaSQL.index on collateTestViaSQL (cip COLLATE CI)"
                    + " NOTUNIQUE"))
        .execute(database);

    for (int i = 0; i < 10; i++) {
      EntityImpl document = new EntityImpl("collateTestViaSQL");

      if (i % 2 == 0) {
        document.field("csp", "VAL");
        document.field("cip", "VAL");
      } else {
        document.field("csp", "val");
        document.field("cip", "val");
      }

      database.begin();
      document.save();
      database.commit();
    }

    @SuppressWarnings("deprecation")
    List<EntityImpl> result =
        database.query(
            new SQLSynchQuery<EntityImpl>("select from collateTestViaSQL where csp = 'VAL'"));
    Assert.assertEquals(result.size(), 5);

    for (EntityImpl document : result) {
      Assert.assertEquals(document.field("csp"), "VAL");
    }

    //noinspection deprecation
    result =
        database.query(
            new SQLSynchQuery<EntityImpl>("select from collateTestViaSQL where cip = 'VaL'"));
    Assert.assertEquals(result.size(), 10);

    for (EntityImpl document : result) {
      Assert.assertEquals((document.<String>field("cip")).toUpperCase(Locale.ENGLISH), "VAL");
    }
  }
}
