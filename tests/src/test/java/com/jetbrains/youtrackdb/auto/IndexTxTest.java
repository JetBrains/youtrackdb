package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashMap;
import java.util.Iterator;
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

    final Schema schema = session.getMetadata().getSchema();
    final var cls = schema.createClass("IndexTxTestClass");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("IndexTxTestIndex", SchemaClass.INDEX_TYPE.UNIQUE, "name");
  }

  @Override
  @BeforeMethod
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    var schema = session.getMetadata().getSchema();
    var cls = schema.getClassInternal("IndexTxTestClass");
    if (cls != null) {
      cls.truncate();
    }
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
    Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();

        final var expectedValue = expectedResult.get(key);
        final RID value;
        try (var stream = index.getRids(session, key)) {
          value = stream.findAny().orElse(null);
        }

        Assert.assertNotNull(value);
        Assert.assertTrue(value.isPersistent());
        Assert.assertEquals(value, expectedValue);
      }
    }
  }
}
