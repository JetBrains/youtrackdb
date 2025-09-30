package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
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

    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("ParentClass");
    classA.setAbstract(true);
    var property = classA.createProperty("RevNumberNine", PropertyType.INTEGER);
    var classChild = schema.createClass("ChildClass1", classA);
    assertEquals(classChild.getParents(), List.of(classA));
    var classChild2 = schema.createClass("ChildClass2", classChild);
    assertEquals(classChild2.getParents(), List.of(classChild));
    classChild2.setSuperClasses(List.of(classA));
    assertEquals(classChild2.getParents(), List.of(classA));
  }

  @Test(expected = SchemaException.class)
  public void testPropertyNameConflict() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("ParentClass");
    classA.setAbstract(true);
    var property = classA.createProperty("RevNumberNine", PropertyType.INTEGER);
    var classChild = schema.createClass("ChildClass1", classA);
    assertEquals(classChild.getParents(), List.of(classA));
    var classChild2 = schema.createClass("ChildClass2");
    classChild2.createProperty("RevNumberNine", PropertyType.STRING);
    classChild2.setSuperClasses(List.of(classChild));
  }

  @Test(expected = SchemaException.class)
  public void testHasAlreadySuperclass() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("ParentClass");
    var classChild = schema.createClass("ChildClass1", classA);
    assertEquals(classChild.getParents(), Collections.singletonList(classA));
    classChild.addSuperClass(classA);
  }

  @Test(expected = SchemaException.class)
  public void testSetDuplicateSuperclasses() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("ParentClass");
    var classChild = schema.createClass("ChildClass1", classA);
    assertEquals(classChild.getParents(), Collections.singletonList(classA));
    classChild.setSuperClasses(Arrays.asList(classA, classA));
  }

  /**
   * This tests fixes a problem created in Issue #5586. It should not throw
   * ArrayIndexOutOfBoundsException
   */
  @Test
  public void testBrokenDbAlteringSuperClass() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("BaseClass");
    var classChild = schema.createClass("ChildClass1", classA);
    var classChild2 = schema.createClass("ChildClass2", classA);

    classChild2.setSuperClasses(List.of(classChild));

    schema.dropClass("ChildClass2");
  }
}
