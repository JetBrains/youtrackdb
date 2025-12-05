package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.apache.commons.lang.RandomStringUtils;

/**
 *
 */
public class TestUtilsFixture extends DbTestBase {

  protected SchemaClassInternal createClassInstance() {
    return (SchemaClassInternal) getDBSchema().createClass(generateClassName());
  }

  protected SchemaClass createChildClassInstance(SchemaClass superclass) {
    return getDBSchema().createClass(generateClassName(), superclass);
  }

  private Schema getDBSchema() {
    return session.getMetadata().getSchema();
  }

  private static String generateClassName() {
    return "Class" + RandomStringUtils.randomNumeric(10);
  }
}
