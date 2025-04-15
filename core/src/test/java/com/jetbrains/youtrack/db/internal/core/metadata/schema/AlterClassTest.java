package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Test;

public class AlterClassTest extends DbTestBase {
  @Test
  public void testSetAbstractClass() {
    Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.getClass("O");
    var v = oSchema.getClass("V");
    v.addSuperClass(oClass);

    var ovt = oSchema.createClass("Some", v);
    ovt.setAbstract(true);
    assertTrue(ovt.isAbstract());
  }
}
