package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
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
