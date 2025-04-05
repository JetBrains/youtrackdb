package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class TestMultiSuperClasses extends BaseMemoryInternalDatabase {

  @Test
  public void testClassCreation() {
    Schema oSchema = db.getMetadata().getSchema();

    SchemaClass aClass = oSchema.createAbstractClass("javaA");
    SchemaClass bClass = oSchema.createAbstractClass("javaB");
    aClass.createProperty(db, "propertyInt", PropertyType.INTEGER);
    bClass.createProperty(db, "propertyDouble", PropertyType.DOUBLE);
    SchemaClass cClass = oSchema.createClass("javaC", aClass, bClass);
    testClassCreationBranch(aClass, bClass, cClass);
    testClassCreationBranch(aClass, bClass, cClass);
    oSchema = db.getMetadata().getImmutableSchemaSnapshot();
    aClass = oSchema.getClass("javaA");
    bClass = oSchema.getClass("javaB");
    cClass = oSchema.getClass("javaC");
    testClassCreationBranch(aClass, bClass, cClass);
  }

  private void testClassCreationBranch(SchemaClass aClass, SchemaClass bClass, SchemaClass cClass) {
    assertNotNull(aClass.getSuperClasses(db));
    assertEquals(aClass.getSuperClasses(db).size(), 0);
    assertNotNull(bClass.getSuperClassesNames());
    assertEquals(bClass.getSuperClassesNames().size(), 0);
    assertNotNull(cClass.getSuperClassesNames());
    assertEquals(cClass.getSuperClassesNames().size(), 2);

    List<? extends SchemaClass> superClasses = cClass.getSuperClasses(db);
    assertTrue(superClasses.contains(aClass));
    assertTrue(superClasses.contains(bClass));
    assertTrue(cClass.isSubClassOf(db, aClass));
    assertTrue(cClass.isSubClassOf(db, bClass));
    assertTrue(aClass.isSuperClassOf(db, cClass));
    assertTrue(bClass.isSuperClassOf(db, cClass));

    SchemaProperty property = cClass.getProperty(db, "propertyInt");
    assertEquals(PropertyType.INTEGER, property.getType());
    property = cClass.propertiesMap(db).get("propertyInt");
    assertEquals(PropertyType.INTEGER, property.getType());

    property = cClass.getProperty(db, "propertyDouble");
    assertEquals(PropertyType.DOUBLE, property.getType());
    property = cClass.propertiesMap(db).get("propertyDouble");
    assertEquals(PropertyType.DOUBLE, property.getType());
  }

  @Test
  public void testSql() {
    final Schema oSchema = db.getMetadata().getSchema();

    SchemaClass aClass = oSchema.createAbstractClass("sqlA");
    SchemaClass bClass = oSchema.createAbstractClass("sqlB");
    SchemaClass cClass = oSchema.createClass("sqlC");
    db.command("alter class sqlC superclasses sqlA, sqlB").close();
    assertTrue(cClass.isSubClassOf(db, aClass));
    assertTrue(cClass.isSubClassOf(db, bClass));
    db.command("alter class sqlC superclass sqlA").close();
    assertTrue(cClass.isSubClassOf(db, aClass));
    assertFalse(cClass.isSubClassOf(db, bClass));
    db.command("alter class sqlC superclass +sqlB").close();
    assertTrue(cClass.isSubClassOf(db, aClass));
    assertTrue(cClass.isSubClassOf(db, bClass));
    db.command("alter class sqlC superclass -sqlA").close();
    assertFalse(cClass.isSubClassOf(db, aClass));
    assertTrue(cClass.isSubClassOf(db, bClass));
  }

  @Test
  public void testCreationBySql() {
    final Schema oSchema = db.getMetadata().getSchema();

    db.command("create class sql2A abstract").close();
    db.command("create class sql2B abstract").close();
    db.command("create class sql2C extends sql2A, sql2B abstract").close();

    SchemaClass aClass = oSchema.getClass("sql2A");
    SchemaClass bClass = oSchema.getClass("sql2B");
    SchemaClass cClass = oSchema.getClass("sql2C");
    assertNotNull(aClass);
    assertNotNull(bClass);
    assertNotNull(cClass);
    assertTrue(cClass.isSubClassOf(db, aClass));
    assertTrue(cClass.isSubClassOf(db, bClass));
  }

  @Test(
      expected = SchemaException.class) // , expectedExceptionsMessageRegExp = "(?s).*recursion.*"
  // )
  public void testPreventionOfCycles() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass aClass = oSchema.createAbstractClass("cycleA");
    SchemaClass bClass = oSchema.createAbstractClass("cycleB", aClass);
    SchemaClass cClass = oSchema.createAbstractClass("cycleC", bClass);

    aClass.setSuperClasses(db, Collections.singletonList(cClass));
  }

  @Test
  public void testParametersImpactGoodScenario() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass aClass = oSchema.createAbstractClass("impactGoodA");
    aClass.createProperty(db, "property", PropertyType.STRING);
    SchemaClass bClass = oSchema.createAbstractClass("impactGoodB");
    bClass.createProperty(db, "property", PropertyType.STRING);
    SchemaClass cClass = oSchema.createAbstractClass("impactGoodC", aClass, bClass);
    assertTrue(cClass.existsProperty(db, "property"));
  }

  @Test(
      expected = SchemaException.class)
  // }, expectedExceptionsMessageRegExp = "(?s).*conflict.*")
  public void testParametersImpactBadScenario() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass aClass = oSchema.createAbstractClass("impactBadA");
    aClass.createProperty(db, "property", PropertyType.STRING);
    SchemaClass bClass = oSchema.createAbstractClass("impactBadB");
    bClass.createProperty(db, "property", PropertyType.INTEGER);
    oSchema.createAbstractClass("impactBadC", aClass, bClass);
  }

  @Test
  public void testCreationOfClassWithV() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oRestrictedClass = oSchema.getClass("ORestricted");
    SchemaClass vClass = oSchema.getClass("V");
    vClass.setSuperClasses(db, Collections.singletonList(oRestrictedClass));
    SchemaClass dummy1Class = oSchema.createClass("Dummy1", oRestrictedClass, vClass);
    SchemaClass dummy2Class = oSchema.createClass("Dummy2");
    SchemaClass dummy3Class = oSchema.createClass("Dummy3", dummy1Class, dummy2Class);
    assertNotNull(dummy3Class);
  }
}
