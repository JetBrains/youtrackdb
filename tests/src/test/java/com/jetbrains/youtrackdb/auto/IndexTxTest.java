package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashMap;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 *
 */
public class IndexTxTest extends BaseDBTest {

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("IndexTxTestClass").createSchemaProperty("name", PropertyType.STRING)
            .createPropertyIndex("IndexTxTestIndex", IndexType.UNIQUE)
    );
  }

  @Override
  @BeforeMethod
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    graph.autoExecuteInTx(g -> g.V().hasLabel("IndexTxTestClass").drop());
  }

  @Test
  public void testIndexCrossReferencedDocuments() {

    session.begin();

    final var doc1 = ((EntityImpl) session.newEntity("IndexTxTestClass"));
    final var doc2 = ((EntityImpl) session.newEntity("IndexTxTestClass"));

    doc1.setProperty("ref", doc2.getIdentity());
    doc1.setProperty("name", "doc1");
    doc2.setProperty("ref", doc1.getIdentity());
    doc2.setProperty("name", "doc2");

    session.commit();

    Map<String, RID> expectedResult = new HashMap<>();
    expectedResult.put("doc1", doc1.getIdentity());
    expectedResult.put("doc2", doc2.getIdentity());

    var index = getIndex("IndexTxTestIndex");
    try (var keyIterator = index.keys()) {
      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();

        final var expectedValue = expectedResult.get(key);
        final RID value;
        try (var iterator = index.getRids(session, key)) {
          value = YTDBIteratorUtils.findFirst(iterator).orElse(null);
        }

        Assert.assertNotNull(value);
        Assert.assertTrue(value.isPersistent());
        Assert.assertEquals(value, expectedValue);
      }
    }
  }
}
