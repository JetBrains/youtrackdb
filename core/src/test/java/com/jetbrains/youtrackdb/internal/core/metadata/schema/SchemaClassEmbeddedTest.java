package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import org.junit.Test;

public class SchemaClassEmbeddedTest extends BaseMemoryInternalDatabase {

  @Test
  public void shouldAddSuperClass() {
    final Schema oSchema = session.getMetadata().getSchema();

    var oClass = oSchema.createClass("Test1");
    var newSuperClass = oSchema.createClass("Super");

    oClass.addSuperClass(newSuperClass);

    assertTrue(oClass.getSuperClasses().contains(newSuperClass));
  }
}