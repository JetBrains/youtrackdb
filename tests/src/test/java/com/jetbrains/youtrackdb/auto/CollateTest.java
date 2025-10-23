package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Locale;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class CollateTest extends BaseDBTest {

  public void testQuery() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("collateTest",
            __.createSchemaProperty("csp", PropertyType.STRING).collateAttr(DefaultCollate.NAME),
            __.createSchemaProperty("cip", PropertyType.STRING)
                .collateAttr(CaseInsensitiveCollate.NAME)
        )
    );

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
    graph.autoExecuteInTx(g -> g.createSchemaClass("collateTestNotNull",
            __.createSchemaProperty("bar", PropertyType.STRING).collateAttr(CaseInsensitiveCollate.NAME)
        )
    );

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
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("collateIndexTest",
            __.createSchemaProperty("csp", PropertyType.STRING).collateAttr(DefaultCollate.NAME)
                .createPropertyIndex("collateIndexCSP", IndexType.NOT_UNIQUE),
            __.createSchemaProperty("cip", PropertyType.STRING)
                .collateAttr(CaseInsensitiveCollate.NAME)
                .createPropertyIndex("collateIndexCIP", IndexType.NOT_UNIQUE)
        )
    );

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
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("collateWasChangedIndexTest")
            .createSchemaProperty("cp", PropertyType.STRING).
            collateAttr(DefaultCollate.NAME).
            createPropertyIndex("collateWasChangedIndex", IndexType.NOT_UNIQUE)
    );

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

    graph.autoExecuteInTx(g ->
        g.schemaClass("collateWasChangedIndexTest").schemaClassProperty("cp")
            .collateAttr(CaseInsensitiveCollate.NAME)
    );

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
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("CompositeIndexQueryCSTest",
            __.createSchemaProperty("csp", PropertyType.STRING).collateAttr(DefaultCollate.NAME),
            __.createSchemaProperty("cip", PropertyType.STRING)
                .collateAttr(CaseInsensitiveCollate.NAME)
        ).createClassIndex("collateCompositeIndexCS", IndexType.NOT_UNIQUE,
            "csp", "cip")
    );

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
        final var index = session.getMetadata().getFastImmutableSchemaSnapshot()
            .getIndex("collateCompositeIndexCS");

        final Collection<RID> value;
        try (var iterator = index.getRids(session, new CompositeKey("VAL", "VaL"))) {
          value = YTDBIteratorUtils.list(iterator);
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
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("CompositeIndexQueryCollateWasChangedTest",
            __.createSchemaProperty("csp", PropertyType.STRING).collateAttr(DefaultCollate.NAME),
            __.createSchemaProperty("cip", PropertyType.STRING)
        ).createClassIndex("collateCompositeIndexCollateWasChanged", IndexType.NOT_UNIQUE, "csp",
            "cip")
    );

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

    graph.autoExecuteInTx(
        g ->
            g.schemaClass("CompositeIndexQueryCollateWasChangedTest").schemaClassProperty("csp")
                .collateAttr(CaseInsensitiveCollate.NAME)
    );

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
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("collateTestViaSQL",
            __.createSchemaProperty("csp", PropertyType.STRING),
            __.createSchemaProperty("cip", PropertyType.STRING)
                .collateAttr(CaseInsensitiveCollate.NAME)
        )
    );

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
