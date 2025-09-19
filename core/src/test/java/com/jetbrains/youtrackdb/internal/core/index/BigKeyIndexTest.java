package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.exception.TooBigIndexKeyException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Test;

public class BigKeyIndexTest extends DbTestBase {

  @Test
  public void testBigKey() {
    var cl = session.createClass("One");
    var prop = cl.createProperty("two", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    for (var i = 0; i < 100; i++) {
      session.begin();
      EntityImpl doc = session.newInstance("One");
      var bigValue = new StringBuilder(i % 1000 + "one10000");
      for (var z = 0; z < 218; z++) {
        bigValue.append("one").append(z);
      }
      doc.setProperty("two", bigValue.toString());

      session.commit();
    }
  }

  @Test(expected = TooBigIndexKeyException.class)
  public void testTooBigKey() {
    var cl = session.createClass("One");
    var prop = cl.createProperty("two", PropertyType.STRING);
    prop.createIndex(INDEX_TYPE.NOTUNIQUE);

    session.begin();
    EntityImpl doc = session.newInstance("One");
    var bigValue = new StringBuilder();
    for (var z = 0; z < 5000; z++) {
      bigValue.append("one").append(z);
    }
    doc.setProperty("two", bigValue.toString());
    session.commit();
  }
}
