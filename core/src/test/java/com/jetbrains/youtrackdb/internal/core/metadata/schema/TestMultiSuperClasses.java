package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import java.util.Collections;
import org.junit.Test;

public class TestMultiSuperClasses extends BaseMemoryInternalDatabase {

  @Test
  public void testClassCreation() {
    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.addAbstractSchemaClass("javaA",
            __.addSchemaProperty("propertyInt", PropertyType.INTEGER)
        ).addAbstractSchemaClass("javaB",
            __.addSchemaProperty("propertyDouble", PropertyType.DOUBLE)
        ).addSchemaClass("javaC").addParentClass("javaA", "javaB")
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var aClass = schema.getClass("javaA");
    var bClass = schema.getClass("javaB");
    var cClass = schema.getClass("javaC");
    testClassCreationBranch(aClass, bClass, cClass);
  }

  private static void testClassCreationBranch(ImmutableSchemaClass aClass,
      ImmutableSchemaClass bClass,
      ImmutableSchemaClass cClass) {
    assertNotNull(aClass.getParentClasses());
    assertEquals(0, aClass.getParentClasses().size());
    assertNotNull(bClass.getParentClassesNames());
    assertEquals(0, bClass.getParentClassesNames().size());
    assertNotNull(cClass.getParentClassesNames());
    assertEquals(2, cClass.getParentClassesNames().size());

    var superClasses = cClass.getParentClasses();
    assertTrue(superClasses.contains(aClass));
    assertTrue(superClasses.contains(bClass));
    assertTrue(cClass.isChildOf(aClass));
    assertTrue(cClass.isChildOf(bClass));
    assertTrue(aClass.isParentOf(cClass));
    assertTrue(bClass.isParentOf(cClass));

    var property = cClass.getProperty("propertyInt");
    assertEquals(PropertyTypeInternal.INTEGER, property.getType());
    property = cClass.getPropertiesMap().get("propertyInt");
    assertEquals(PropertyTypeInternal.INTEGER, property.getType());

    property = cClass.getProperty("propertyDouble");
    assertEquals(PropertyTypeInternal.DOUBLE, property.getType());
    property = cClass.getPropertiesMap().get("propertyDouble");
    assertEquals(PropertyTypeInternal.DOUBLE, property.getType());
  }

  @Test
  public void testSql() {
    final Schema oSchema = session.getMetadata().getSlowMutableSchema();

    var aClass = oSchema.createAbstractClass("sqlA");
    var bClass = oSchema.createAbstractClass("sqlB");
    var cClass = oSchema.createClass("sqlC");
    session.execute("alter class sqlC superclasses sqlA, sqlB").close();
    assertTrue(cClass.isChildOf(aClass));
    assertTrue(cClass.isChildOf(bClass));
  }

  @Test
  public void testCreationBySql() {
    final Schema oSchema = session.getMetadata().getSlowMutableSchema();

    session.execute("create class sql2A abstract").close();
    session.execute("create class sql2B abstract").close();
    session.execute("create class sql2C extends sql2A, sql2B abstract").close();

    var aClass = oSchema.getClass("sql2A");
    var bClass = oSchema.getClass("sql2B");
    var cClass = oSchema.getClass("sql2C");
    assertNotNull(aClass);
    assertNotNull(bClass);
    assertNotNull(cClass);
    assertTrue(cClass.isChildOf(aClass));
    assertTrue(cClass.isChildOf(bClass));
  }

  @Test(
      expected = SchemaException.class) // , expectedExceptionsMessageRegExp = "(?s).*recursion.*"
  // )
  public void testPreventionOfCycles() {
    final Schema oSchema = session.getMetadata().getSlowMutableSchema();
    var aClass = oSchema.createAbstractClass("cycleA");
    var bClass = oSchema.createAbstractClass("cycleB", aClass);
    var cClass = oSchema.createAbstractClass("cycleC", bClass);

    aClass.setParentClasses(Collections.singletonList(cClass));
  }

  @Test
  public void testParametersImpactGoodScenario() {
    final Schema oSchema = session.getMetadata().getSlowMutableSchema();
    var aClass = oSchema.createAbstractClass("impactGoodA");
    aClass.createProperty("property", PropertyTypeInternal.STRING);
    var bClass = oSchema.createAbstractClass("impactGoodB");
    bClass.createProperty("property", PropertyTypeInternal.STRING);
    var cClass = oSchema.createAbstractClass("impactGoodC", aClass, bClass);
    assertTrue(cClass.existsProperty("property"));
  }

  @Test(expected = SchemaException.class)
  public void testParametersImpactBadScenario() {
    final Schema oSchema = session.getMetadata().getSlowMutableSchema();
    var aClass = oSchema.createAbstractClass("impactBadA");
    aClass.createProperty("property", PropertyTypeInternal.STRING);
    var bClass = oSchema.createAbstractClass("impactBadB");
    bClass.createProperty("property", PropertyTypeInternal.INTEGER);
    oSchema.createAbstractClass("impactBadC", aClass, bClass);
  }

  @Test
  public void testCreationOfClassWithV() {
    final Schema oSchema = session.getMetadata().getSlowMutableSchema();
    var oClass = oSchema.getClass("O");
    var vClass = oSchema.getClass("V");
    vClass.setParentClasses(Collections.singletonList(oClass));
    var dummy1Class = oSchema.createClass("Dummy1", oClass, vClass);
    var dummy2Class = oSchema.createClass("Dummy2");
    var dummy3Class = oSchema.createClass("Dummy3", dummy1Class, dummy2Class);
    assertNotNull(dummy3Class);
  }
}
