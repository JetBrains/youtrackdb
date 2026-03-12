package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class IndexTxTest extends BaseDBJUnit5Test {

  @Override
  @BeforeAll
  void beforeAll() throws Exception {
    super.beforeAll();
    final var schema = session.getMetadata().getSchema();
    final var cls = schema.createClass("IndexTxTestClass");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("IndexTxTestIndex", SchemaClass.INDEX_TYPE.UNIQUE, "name");
  }

  @Override
  @BeforeEach
  void beforeEach() throws Exception {
    super.beforeEach();
    var schema = session.getMetadata().getSchema();
    var cls = schema.getClassInternal("IndexTxTestClass");
    if (cls != null) {
      cls.truncate();
    }
  }

  @Test
  void testIndexCrossReferencedDocuments() {
    session.begin();
    final var doc1 = ((EntityImpl) session.newEntity("IndexTxTestClass"));
    final var doc2 = ((EntityImpl) session.newEntity("IndexTxTestClass"));
    doc1.setProperty("ref", doc2.getIdentity());
    doc1.setProperty("name", "doc1");
    doc2.setProperty("ref", doc1.getIdentity());
    doc2.setProperty("name", "doc2");
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    Map<String, RID> expectedResult = new HashMap<>();
    expectedResult.put("doc1", doc1.getIdentity());
    expectedResult.put("doc2", doc2.getIdentity());

    var index = getIndex("IndexTxTestIndex");
    Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();
        final var expectedValue = expectedResult.get(key);
        final RID value;
        try (var stream = index.getRids(session, key)) {
          value = stream.findAny().orElse(null);
        }
        assertNotNull(value);
        assertTrue(value.isPersistent());
        assertEquals(expectedValue, value);
      }
    }
    session.rollback();
  }
}
