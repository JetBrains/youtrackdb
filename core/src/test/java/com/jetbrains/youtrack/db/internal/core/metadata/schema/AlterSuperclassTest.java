package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 *
 */
public class AlterSuperclassTest extends DbTestBase {

  @Test
  public void testSamePropertyCheck() {

    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("ParentClass");
    classA.setAbstract(db, true);
    SchemaProperty property = classA.createProperty(db, "RevNumberNine", PropertyType.INTEGER);
    SchemaClass classChild = schema.createClass("ChildClass1", classA);
    assertEquals(classChild.getSuperClasses(db), List.of(classA));
    SchemaClass classChild2 = schema.createClass("ChildClass2", classChild);
    assertEquals(classChild2.getSuperClasses(db), List.of(classChild));
    classChild2.setSuperClasses(db, List.of(classA));
    assertEquals(classChild2.getSuperClasses(db), List.of(classA));
  }

  @Test(expected = SchemaException.class)
  public void testPropertyNameConflict() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("ParentClass");
    classA.setAbstract(db, true);
    SchemaProperty property = classA.createProperty(db, "RevNumberNine", PropertyType.INTEGER);
    SchemaClass classChild = schema.createClass("ChildClass1", classA);
    assertEquals(classChild.getSuperClasses(db), List.of(classA));
    SchemaClass classChild2 = schema.createClass("ChildClass2");
    classChild2.createProperty(db, "RevNumberNine", PropertyType.STRING);
    classChild2.setSuperClasses(db, List.of(classChild));
  }

  @Test(expected = SchemaException.class)
  public void testHasAlreadySuperclass() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("ParentClass");
    SchemaClass classChild = schema.createClass("ChildClass1", classA);
    assertEquals(classChild.getSuperClasses(db), Collections.singletonList(classA));
    classChild.addSuperClass(db, classA);
  }

  @Test(expected = SchemaException.class)
  public void testSetDuplicateSuperclasses() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("ParentClass");
    SchemaClass classChild = schema.createClass("ChildClass1", classA);
    assertEquals(classChild.getSuperClasses(db), Collections.singletonList(classA));
    classChild.setSuperClasses(db, Arrays.asList(classA, classA));
  }

  /**
   * This tests fixes a problem created in Issue #5586. It should not throw
   * ArrayIndexOutOfBoundsException
   */
  @Test
  public void testBrokenDbAlteringSuperClass() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("BaseClass");
    SchemaClass classChild = schema.createClass("ChildClass1", classA);
    SchemaClass classChild2 = schema.createClass("ChildClass2", classA);

    classChild2.setSuperClass(db, classChild);

    schema.dropClass("ChildClass2");
  }
}
