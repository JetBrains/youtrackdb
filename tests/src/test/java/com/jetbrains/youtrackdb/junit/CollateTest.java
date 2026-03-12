package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Locale;
import org.junit.jupiter.api.Test;

public class CollateTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.CollateTest#testQuery
  @Test
  void testQuery() {
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
      assertEquals(5, result.size());

      for (var document : result) {
        assertEquals("VAL", document.getProperty("csp"));
      }
    });

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from collateTest where cip = 'VaL'").entityStream().toList();
      assertEquals(10, result.size());

      for (var document : result) {
        assertEquals("VAL",
            document.<String>getProperty("cip").toUpperCase(Locale.ENGLISH));
      }
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CollateTest#testQueryNotNullCi
  @Test
  void testQueryNotNullCi() {
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
      assertEquals(1, result1.size());

      final var result2 =
          session.query("select from collateTestNotNull where bar is not null").toList();
      assertEquals(1, result2.size());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CollateTest#testIndexQuery
  @Test
  void testIndexQuery() {
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
      assertEquals(5, result.size());

      for (var document : result) {
        assertEquals("VAL", document.getProperty("csp"));
      }
    });

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from collateIndexTest where cip = 'VaL'").toList();
      assertEquals(10, result.size());

      for (var document : result) {
        assertEquals("VAL",
            document.<String>getProperty("cip").toUpperCase(Locale.ENGLISH));
      }
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CollateTest#testIndexQueryCollateWasChanged
  @Test
  void testIndexQueryCollateWasChanged() {
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
      assertEquals(5, result.size());

      for (var document : result) {
        assertEquals("VAL", document.getProperty("cp"));
      }
    });

    cp = clazz.getProperty("cp");
    cp.setCollate(CaseInsensitiveCollate.NAME);

    session.executeInTx(transaction -> {
      final var result =
          session.query("select from collateWasChangedIndexTest where cp = 'VaL'").toList();
      assertEquals(10, result.size());

      for (var document : result) {
        assertEquals("VAL",
            document.<String>getProperty("cp").toUpperCase(Locale.ENGLISH));
      }
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CollateTest#testCompositeIndexQueryCS
  @Test
  void testCompositeIndexQueryCS() {
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
      assertEquals(5, result.size());

      for (var document : result) {
        assertEquals("VAL", document.getProperty("csp"));
      }
    });

    session.executeInTx(transaction -> {
      final var result =
          session.query(
              "select from CompositeIndexQueryCSTest where csp = 'VAL' and cip = 'VaL'")
              .toList();
      assertEquals(5, result.size());

      for (var document : result) {
        assertEquals("VAL", document.getProperty("csp"));
        assertEquals("VAL",
            document.<String>getProperty("cip").toUpperCase(Locale.ENGLISH));
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

        assertEquals(5, value.size());
        for (var identifiable : value) {
          var transaction = session.getActiveTransaction();
          final EntityImpl record = transaction.load(identifiable);
          assertEquals("VAL", record.getProperty("csp"));
          assertEquals("VAL",
              record.<String>getProperty("cip").toUpperCase(Locale.ENGLISH));
        }
      });
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CollateTest#testCompositeIndexQueryCollateWasChanged
  @Test
  void testCompositeIndexQueryCollateWasChanged() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CompositeIndexQueryCollateWasChangedTest");

    var csp = clazz.createProperty("csp", PropertyType.STRING);
    csp.setCollate(DefaultCollate.NAME);

    clazz.createProperty("cip", PropertyType.STRING);

    clazz.createIndex(
        "collateCompositeIndexCollateWasChanged", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "csp", "cip");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var document =
          ((EntityImpl) session.newEntity("CompositeIndexQueryCollateWasChangedTest"));
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
      assertEquals(5, result.size());

      for (var document : result) {
        assertEquals("VAL", document.getProperty("csp"));
      }
    });

    csp = clazz.getProperty("csp");
    csp.setCollate(CaseInsensitiveCollate.NAME);

    session.executeInTx(transaction -> {
      //noinspection deprecation
      final var result = session.query(
          "select from CompositeIndexQueryCollateWasChangedTest where csp = 'VaL'").toList();
      assertEquals(10, result.size());

      for (var document : result) {
        assertEquals("VAL",
            document.<String>getProperty("csp").toUpperCase(Locale.ENGLISH));
      }
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CollateTest#collateThroughSQL
  @Test
  void collateThroughSQL() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("collateTestViaSQL");

    clazz.createProperty("csp", PropertyType.STRING);
    var cipProperty = clazz.createProperty("cip", PropertyType.STRING);
    cipProperty.setCollate(CaseInsensitiveCollate.NAME);

    session.command(
        "create index collateTestViaSQL on collateTestViaSQL (cip) NOTUNIQUE");

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
      assertEquals(5, result.size());

      for (var document : result) {
        assertEquals("VAL", document.getProperty("csp"));
      }
    });

    session.executeInTx(tx -> {
      final var result =
          session.query("select from collateTestViaSQL where cip = 'VaL'").toList();
      assertEquals(10, result.size());

      for (var document : result) {
        assertEquals("VAL",
            document.<String>getProperty("cip").toUpperCase(Locale.ENGLISH));
      }
    });
  }
}
