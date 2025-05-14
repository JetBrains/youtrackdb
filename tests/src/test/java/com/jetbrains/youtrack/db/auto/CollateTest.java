package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrack.db.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Locale;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class CollateTest extends BaseDBTest {
  public void testQuery() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("collateTest");

    var csp = clazz.createProperty("csp", PropertyType.STRING);
    csp.setCollate(DefaultCollate.NAME);

    var cip = clazz.createProperty("cip", PropertyType.STRING);
    cip.setCollate(CaseInsensitiveCollate.NAME);

    for (var i = 0; i < 10; i++) {
      final var upper = i % 2 == 0;
      session.executeInTx(transaction -> {
        var document = ((EntityImpl) session.newEntity("collateTest"));

        if (upper) {
          document.setProperty("csp", "VAL");
          document.setProperty("cip", "VAL");
        } else {
          document.setProperty("csp", "val");
          document.setProperty("cip", "val");
        }
      });
    }

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from collateTest where csp = 'VAL'").entityStream().toList();
      Assert.assertEquals(result.size(), 5);

      for (var document : result) {
        Assert.assertEquals(document.getProperty("csp"), "VAL");
      }
    });

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from collateTest where cip = 'VaL'").entityStream().toList();
      Assert.assertEquals(result.size(), 10);

      for (var document : result) {
        Assert.assertEquals((document.<String>getProperty("cip")).toUpperCase(Locale.ENGLISH),
            "VAL");
      }
    });
  }

  public void testQueryNotNullCi() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("collateTestNotNull");

    var csp = clazz.createProperty("bar", PropertyType.STRING);
    csp.setCollate(CaseInsensitiveCollate.NAME);

    session.executeInTx(transaction -> {
      session.newEntity("collateTestNotNull").setProperty("bar", "baz");

      session.newEntity("collateTestNotNull").setProperty("nobar", true);
    });

    session.executeInTx(transaction -> {
      final var result1 =
          session.query("select from collateTestNotNull where bar is null").toList();
      Assert.assertEquals(result1.size(), 1);

      final var result2 =
          session.query("select from collateTestNotNull where bar is not null").toList();
      Assert.assertEquals(result2.size(), 1);
    });
  }

  public void testIndexQuery() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("collateIndexTest");

    var csp = clazz.createProperty("csp", PropertyType.STRING);
    csp.setCollate(DefaultCollate.NAME);

    var cip = clazz.createProperty("cip", PropertyType.STRING);
    cip.setCollate(CaseInsensitiveCollate.NAME);

    clazz.createIndex("collateIndexCSP", SchemaClass.INDEX_TYPE.NOTUNIQUE, "csp");
    clazz.createIndex("collateIndexCIP", SchemaClass.INDEX_TYPE.NOTUNIQUE, "cip");

    for (var i = 0; i < 10; i++) {
      final var upper = i % 2 == 0;
      session.executeInTx(transaction -> {
        var document = ((EntityImpl) session.newEntity("collateIndexTest"));

        if (upper) {
          document.setProperty("csp", "VAL");
          document.setProperty("cip", "VAL");
        } else {
          document.setProperty("csp", "val");
          document.setProperty("cip", "val");
        }
      });
    }

    session.executeInTx(transaction -> {
      final var result = session
          .query("select from collateIndexTest where csp = 'VAL'")
          .entityStream().toList();
      Assert.assertEquals(result.size(), 5);

      for (var document : result) {
        Assert.assertEquals(document.getProperty("csp"), "VAL");
      }
    });

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from collateIndexTest where cip = 'VaL'").toList();
      Assert.assertEquals(result.size(), 10);

      for (var document : result) {
        Assert.assertEquals((document.<String>getProperty("cip")).toUpperCase(Locale.ENGLISH),
            "VAL");
      }
    });
  }

  public void testIndexQueryCollateWasChanged() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("collateWasChangedIndexTest");

    var cp = clazz.createProperty("cp", PropertyType.STRING);
    cp.setCollate(DefaultCollate.NAME);

    clazz.createIndex("collateWasChangedIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "cp");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var document = ((EntityImpl) session.newEntity("collateWasChangedIndexTest"));

      if (i % 2 == 0) {
        document.setProperty("cp", "VAL");
      } else {
        document.setProperty("cp", "val");
      }
      session.commit();
    }

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from collateWasChangedIndexTest where cp = 'VAL'").toList();
      Assert.assertEquals(result.size(), 5);

      for (var document : result) {
        Assert.assertEquals(document.getProperty("cp"), "VAL");
      }
    });

    cp = clazz.getProperty("cp");
    cp.setCollate(CaseInsensitiveCollate.NAME);

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from collateWasChangedIndexTest where cp = 'VaL'").toList();
      Assert.assertEquals(result.size(), 10);

      for (var document : result) {
        Assert.assertEquals((document.<String>getProperty("cp")).toUpperCase(Locale.ENGLISH),
            "VAL");
      }
    });
  }

  public void testCompositeIndexQueryCS() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CompositeIndexQueryCSTest");

    var csp = clazz.createProperty("csp", PropertyType.STRING);
    csp.setCollate(DefaultCollate.NAME);

    var cip = clazz.createProperty("cip", PropertyType.STRING);
    cip.setCollate(CaseInsensitiveCollate.NAME);

    clazz.createIndex("collateCompositeIndexCS", SchemaClass.INDEX_TYPE.NOTUNIQUE, "csp",
        "cip");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var document = ((EntityImpl) session.newEntity("CompositeIndexQueryCSTest"));

      if (i % 2 == 0) {
        document.setProperty("csp", "VAL");
        document.setProperty("cip", "VAL");
      } else {
        document.setProperty("csp", "val");
        document.setProperty("cip", "val");
      }

      session.commit();
    }

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from CompositeIndexQueryCSTest where csp = 'VAL'").toList();
      Assert.assertEquals(result.size(), 5);

      for (var document : result) {
        Assert.assertEquals(document.getProperty("csp"), "VAL");
      }
    });

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from CompositeIndexQueryCSTest where csp = 'VAL' and cip = 'VaL'")
              .toList();
      Assert.assertEquals(result.size(), 5);

      for (var document : result) {
        Assert.assertEquals(document.getProperty("csp"), "VAL");
        Assert.assertEquals((document.<String>getProperty("cip")).toUpperCase(Locale.ENGLISH),
            "VAL");
      }
    });
    if (!session.getStorage().isRemote()) {
      session.executeInTx(tx -> {
        final var indexManager = session.getSharedContext().getIndexManager();
        final var index = indexManager.getIndex("collateCompositeIndexCS");

        final Collection<RID> value;
        try (var stream = index.getRids(session, new CompositeKey("VAL", "VaL"))) {
          value = stream.toList();
        }

        Assert.assertEquals(value.size(), 5);
        for (var identifiable : value) {
          var transaction = session.getActiveTransaction();
          final EntityImpl record = transaction.load(identifiable);
          Assert.assertEquals(record.getProperty("csp"), "VAL");
          Assert.assertEquals((record.<String>getProperty("cip")).toUpperCase(Locale.ENGLISH),
              "VAL");
        }
      });
    }
  }

  public void testCompositeIndexQueryCollateWasChanged() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CompositeIndexQueryCollateWasChangedTest");

    var csp = clazz.createProperty("csp", PropertyType.STRING);
    csp.setCollate(DefaultCollate.NAME);

    clazz.createProperty("cip", PropertyType.STRING);

    clazz.createIndex(
        "collateCompositeIndexCollateWasChanged", SchemaClass.INDEX_TYPE.NOTUNIQUE, "csp", "cip");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var document = ((EntityImpl) session.newEntity("CompositeIndexQueryCollateWasChangedTest"));
      if (i % 2 == 0) {
        document.setProperty("csp", "VAL");
        document.setProperty("cip", "VAL");
      } else {
        document.setProperty("csp", "val");
        document.setProperty("cip", "val");
      }
      session.commit();
    }

    session.executeInTx(transaction -> {
      final var result = session.query(
          "select from CompositeIndexQueryCollateWasChangedTest where csp = 'VAL'").toList();
      Assert.assertEquals(result.size(), 5);

      for (var document : result) {
        Assert.assertEquals(document.getProperty("csp"), "VAL");
      }
    });

    csp = clazz.getProperty("csp");
    csp.setCollate(CaseInsensitiveCollate.NAME);

    session.executeInTx(transaction -> {
      //noinspection deprecation
      final var result = session.query(
          "select from CompositeIndexQueryCollateWasChangedTest where csp = 'VaL'").toList();
      Assert.assertEquals(result.size(), 10);

      for (var document : result) {
        Assert.assertEquals(document.<String>getProperty("csp").toUpperCase(Locale.ENGLISH), "VAL");
      }
    });
  }

  public void collateThroughSQL() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("collateTestViaSQL");

    clazz.createProperty("csp", PropertyType.STRING);
    clazz.createProperty("cip", PropertyType.STRING);

    session.command(
        "create index collateTestViaSQL.index on collateTestViaSQL (cip COLLATE CI) NOTUNIQUE");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var document = ((EntityImpl) session.newEntity("collateTestViaSQL"));

      if (i % 2 == 0) {
        document.setProperty("csp", "VAL");
        document.setProperty("cip", "VAL");
      } else {
        document.setProperty("csp", "val");
        document.setProperty("cip", "val");
      }

      session.commit();
    }

    session.executeInTx(tx -> {
      final var result =
          session.query("select from collateTestViaSQL where csp = 'VAL'").toList();
      Assert.assertEquals(result.size(), 5);

      for (var document : result) {
        Assert.assertEquals(document.getProperty("csp"), "VAL");
      }
    });

    session.executeInTx(tx -> {
      final var result =
          session.query("select from collateTestViaSQL where cip = 'VaL'").toList();
      Assert.assertEquals(result.size(), 10);

      for (var document : result) {
        Assert.assertEquals((document.<String>getProperty("cip")).toUpperCase(Locale.ENGLISH),
            "VAL");
      }
    });
  }
}
