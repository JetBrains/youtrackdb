package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SchemaPropertyIndexDefinitionTest extends DbTestBase {

  private PropertyIndexDefinition propertyIndex;

  @Before
  public void beforeMethod() {
    propertyIndex = new PropertyIndexDefinition("testClass", "fOne", PropertyType.INTEGER);
  }

  @Test
  public void testCreateValueSingleParameter() {
    final var result = propertyIndex.createValue(session, Collections.singletonList("12"));
    Assert.assertEquals(12, result);
  }

  @Test
  public void testCreateValueTwoParameters() {
    final var result = propertyIndex.createValue(session, Arrays.asList("12", "25"));
    Assert.assertEquals(12, result);
  }

  @Test(expected = NumberFormatException.class)
  public void testCreateValueWrongParameter() {
    propertyIndex.createValue(session, Collections.singletonList("tt"));
  }

  @Test
  public void testCreateValueSingleParameterArrayParams() {
    final var result = propertyIndex.createValue(session, "12");
    Assert.assertEquals(12, result);
  }

  @Test
  public void testCreateValueTwoParametersArrayParams() {
    final var result = propertyIndex.createValue(session, "12", "25");
    Assert.assertEquals(12, result);
  }

  @Test(expected = NumberFormatException.class)
  public void testCreateValueWrongParameterArrayParams() {
    propertyIndex.createValue(session, "tt");
  }

  @Test
  public void testGetDocumentValueToIndex() {
    session.begin();
    final var document = (EntityImpl) session.newEntity();

    document.setProperty("fOne", "15");
    document.setProperty("fTwo", 10);

    final var result = propertyIndex.getDocumentValueToIndex(session, document);
    Assert.assertEquals(15, result);
    session.rollback();
  }

  @Test
  public void testGetFields() {
    final var result = propertyIndex.getFields();
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("fOne", result.getFirst());
  }

  @Test
  public void testGetTypes() {
    final var result = propertyIndex.getTypes();
    Assert.assertEquals(1, result.length);
    Assert.assertEquals(PropertyType.INTEGER, result[0]);
  }

  @Test
  public void testEmptyIndexReload() {
    propertyIndex = new PropertyIndexDefinition("tesClass", "fOne", PropertyType.INTEGER);

    final var map = propertyIndex.toMap(session);
    final var result = new PropertyIndexDefinition();
    result.fromMap(map);

    Assert.assertEquals(result, propertyIndex);
  }

  @Test
  public void testIndexReload() {
    final var map = propertyIndex.toMap(session);

    final var result = new PropertyIndexDefinition();
    result.fromMap(map);

    Assert.assertEquals(result, propertyIndex);
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
