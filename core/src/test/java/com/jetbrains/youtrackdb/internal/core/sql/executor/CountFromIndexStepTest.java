package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexName;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 *
 */
@RunWith(Parameterized.class)
public class CountFromIndexStepTest extends TestUtilsFixture {

  private static final String PROPERTY_NAME = "testPropertyName";
  private static final String PROPERTY_VALUE = "testPropertyValue";
  private static final String ALIAS = "size";
  private String indexName;

  private final SQLIndexIdentifier.Type identifierType;

  public CountFromIndexStepTest(SQLIndexIdentifier.Type identifierType) {
    this.identifierType = identifierType;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> types() {
    return Arrays.asList(
        new Object[][]{
            {SQLIndexIdentifier.Type.INDEX},
            {SQLIndexIdentifier.Type.VALUES},
            {SQLIndexIdentifier.Type.VALUESASC},
            {SQLIndexIdentifier.Type.VALUESDESC},
        });
  }

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    var clazz = createClassInstance();
    clazz.createProperty(PROPERTY_NAME, PropertyType.STRING);
    var className = clazz.getName();
    indexName = className + "." + PROPERTY_NAME;
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, PROPERTY_NAME);

    for (var i = 0; i < 20; i++) {
      session.begin();
      var document = (EntityImpl) session.newEntity(className);
      document.setProperty(PROPERTY_NAME, PROPERTY_VALUE);

      session.commit();
    }
  }

  @Test
  public void shouldCountRecordsOfIndex() {
    var name = new SQLIndexName(-1);
    name.setValue(indexName);
    var identifier = new SQLIndexIdentifier(-1);
    identifier.setIndexName(name);
    identifier.setIndexNameString(name.getValue());
    identifier.setType(identifierType);

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var step = new CountFromIndexStep(identifier, ALIAS, context, false);

    var result = step.start(context);
    Assert.assertEquals(20, (long) result.next(context).getProperty(ALIAS));
    Assert.assertFalse(result.hasNext(context));
  }
}
