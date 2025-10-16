package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class AlterSuperclassTest extends DbTestBase {

  @Test
  public void testSamePropertyCheck() {
    var parentClass = "ParentClass";
    var childClass = "ChildClass1";
    var childClass2 = "ChildClass2";

    graph.autoExecuteInTx(g ->
        g.addSchemaClass(parentClass).
            makeClassAbstract().
            addSchemaProperty("RevNumberNine", PropertyType.INTEGER).
            addSchemaClass(childClass)
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    assertEquals(schema.getClass(childClass).getParentClasses(),
        List.of(schema.getClass(parentClass)));

    graph.autoExecuteInTx(g -> g.addSchemaClass(childClass2).
        addParentClass(childClass)
    );

    schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    assertEquals(schema.getClass(childClass2).getParentClasses(),
        List.of(schema.getClass(childClass)));

    graph.autoExecuteInTx(
        g -> g.schemaClass(childClass2).removeParentClass(childClass).addParentClass(parentClass));

    assertEquals(schema.getClass(childClass2).getParentClasses(),
        List.of(schema.getClass(parentClass)));
  }

  @Test(expected = SchemaException.class)
  public void testPropertyNameConflict() {
    var parentClass = "ParentClass";
    var childClass = "ChildClass1";
    var childClass2 = "ChildClass2";

    graph.autoExecuteInTx(g ->
        g.addSchemaClass(parentClass).makeClassAbstract()
            .addSchemaProperty("RevNumberNine", PropertyType.INTEGER).
            addSchemaClass(childClass).addParentClass(parentClass)
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    assertEquals(schema.getClass(childClass).getParentClasses(),
        List.of(schema.getClass(parentClass)));

    graph.autoExecuteInTx(g -> g.addSchemaClass(childClass2).
        addSchemaProperty("RevNumberNine", PropertyType.STRING).
        addParentClass(childClass)
    );
  }

  @Test
  public void testHasAlreadySuperclass() {
    var parentClass = "ParentClass";
    var childClass = "ChildClass1";

    graph.autoExecuteInTx(g ->
        g.addSchemaClass(parentClass).addSchemaClass(childClass).addParentClass(parentClass)
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    assertEquals(schema.getClass(childClass).getParentClasses(),
        Collections.singletonList(schema.getClass(parentClass)));

    graph.autoExecuteInTx(g ->
        g.schemaClass(childClass).addParentClass(parentClass)
    );
  }

  @Test
  public void testAlteringSuperClass() {
    var parentClass = "ParentClass";
    var childClass = "ChildClass1";
    var childClass2 = "ChildClass2";

    graph.autoExecuteInTx(g ->
        g.addSchemaClass("BaseClass").
            addSchemaClass(childClass).
            addParentClass(parentClass).
            addSchemaClass(childClass2).
            addParentClass(parentClass)
    );

    graph.autoExecuteInTx(
        g ->
            g.schemaClass(childClass2).removeParentClass(parentClass).
                addParentClass(childClass)
    );

    graph.autoExecuteInTx(g -> g.schemaClass(childClass2).drop());
  }
}
