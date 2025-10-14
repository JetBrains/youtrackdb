package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SchemaPropertyIndexDefinitionTest extends DbTestBase {

  private PropertyIndexDefinition propertyIndex;

  @Before
  public void beforeMethod() {
    session.begin();
    propertyIndex = new PropertyIndexDefinition("testClass", "fOne", PropertyTypeInternal.INTEGER);
  }

  @After
  public void afterMethod() {
    session.rollback();
  }

  @Test
  public void testCreateValueSingleParameter() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Collections.singletonList("12"));
    Assert.assertEquals(12, result);
  }

  @Test
  public void testCreateValueTwoParameters() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Arrays.asList("12", "25"));
    Assert.assertEquals(12, result);
  }

  @Test(expected = NumberFormatException.class)
  public void testCreateValueWrongParameter() {
    propertyIndex.createValue(session.getActiveTransaction(), Collections.singletonList("tt"));
  }

  @Test
  public void testCreateValueSingleParameterArrayParams() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(), "12");
    Assert.assertEquals(12, result);
  }

  @Test
  public void testCreateValueTwoParametersArrayParams() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(), "12", "25");
    Assert.assertEquals(12, result);
  }

  @Test(expected = NumberFormatException.class)
  public void testCreateValueWrongParameterArrayParams() {
    propertyIndex.createValue(session.getActiveTransaction(), "tt");
  }

  @Test
  public void testConvertEntityPropertiesToIndexKey() {
    session.begin();
    final var document = (EntityImpl) session.newEntity();

    document.setProperty("fOne", "15");
    document.setProperty("fTwo", 10);

    final var result = propertyIndex.convertEntityPropertiesToIndexKey(
        session.getActiveTransaction(),
        document);
    Assert.assertEquals(15, result);
    session.rollback();
  }

  @Test
  public void testGetProperties() {
    final var result = propertyIndex.getProperties();
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("fOne", result.getFirst());
  }

  @Test
  public void testGetTypes() {
    final var result = propertyIndex.getTypes();
    Assert.assertEquals(1, result.length);
    Assert.assertEquals(PropertyTypeInternal.INTEGER, result[0]);
  }

  @Test
  public void testGetParamCount() {
    Assert.assertEquals(1, propertyIndex.getParamCount());
  }

  @Test
  public void testClassName() {
    Assert.assertEquals("testClass", propertyIndex.getClassName());
  }
}
