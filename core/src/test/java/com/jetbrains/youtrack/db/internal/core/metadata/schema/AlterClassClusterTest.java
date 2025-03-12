package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Test;

public class AlterClassClusterTest extends DbTestBase {
  @Test
  public void testSetAbstractRestrictedClass() {
    Schema oSchema = session.getMetadata().getSchema();
    var oRestricted = oSchema.getClass("ORestricted");
    var v = oSchema.getClass("V");
    v.addSuperClass(oRestricted);

    var ovt = oSchema.createClass("Some", v);
    ovt.setAbstract(true);
    assertTrue(ovt.isAbstract());
  }
}
