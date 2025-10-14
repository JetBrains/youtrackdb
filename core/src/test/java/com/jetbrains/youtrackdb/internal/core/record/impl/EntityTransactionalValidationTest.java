package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class EntityTransactionalValidationTest extends BaseMemoryInternalDatabase {

  @Test(expected = ValidationException.class)
  public void simpleConstraintShouldBeCheckedOnCommitFalseTest() {

    graph.autoExecuteInTx(g ->
        g.addSchemaClass("Validation",
            __.addSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true)
        )
    );

    session.begin();
    session.newVertex("Validation");
    session.commit();
  }

  @Test()
  public void simpleConstraintShouldBeCheckedOnCommitTrueTest() {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("Validation",
            __.addSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true)
        )
    );

    session.begin();
    var vertex = session.newVertex("Validation");
    vertex.setProperty("int", 11);
    session.commit();
    session.begin();
    session.begin();
    var activeTx = session.getActiveTransaction();
    Integer value = activeTx.<Vertex>load(vertex).getProperty("int");
    Assert.assertEquals((Integer) 11, value);
  }

  @Test()
  public void simpleConstraintShouldBeCheckedOnCommitWithTypeConvert() {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("Validation",
            __.addSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true)
        )
    );

    session.begin();
    var vertex = session.newVertex("Validation");
    vertex.setProperty("int", "11");
    session.commit();
    session.begin();
    var activeTx = session.getActiveTransaction();
    Integer value = activeTx.<Vertex>load(vertex).getProperty("int");
    Assert.assertEquals((Integer) 11, value);
  }

  @Test
  public void stringRegexpPatternValidationCheck() {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("Validation").addSchemaProperty("str", PropertyType.STRING)
            .mandatoryAttr(true).regExpAttr("aba.*")
    );

    Vertex vertex;
    session.begin();
    vertex = session.newVertex("Validation");
    vertex.setProperty("str", "first");
    vertex.setProperty("str", "second");
    vertex.setProperty("str", "abacorrect");
    session.commit();
    session.begin();
    var activeTx = session.getActiveTransaction();
    Assert.assertEquals("abacorrect", activeTx.<Vertex>load(vertex).getProperty("str"));
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void stringRegexpPatternValidationCheckFails() {
    graph.autoExecuteInTx(
        g -> g.addSchemaClass("Validation").
            addSchemaProperty("str", PropertyType.STRING).
            mandatoryAttr(true).
            regExpAttr("aba.*")
    );

    Vertex vertex;
    session.begin();
    vertex = session.newVertex("Validation");
    vertex.setProperty("str", "first");
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void requiredLinkBagNegativeTest() {

    graph.executeInTx(g -> {
          var traversal = g.addStateFullEdgeClass("lst").
              addSchemaClass("Validation").addSchemaClass("links");
          var edgePropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, "lst");
          traversal.schemaClass("Validation").
              addSchemaProperty(edgePropertyName, PropertyType.LINKBAG, "links").
              mandatoryAttr(true).iterate();
        }
    );

    session.begin();
    session.newVertex("Validation");
    session.commit();
  }

  @Test
  public void requiredLinkBagPositiveTest() {
    graph.executeInTx(g -> {
      var traversal = g.addAbstractSchemaClass("lst").addParentClass("E").
          addSchemaClass("Validation").addSchemaClass("links");

      var edgePropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, "lst");
      traversal.schemaClass("Validation")
          .addSchemaProperty(edgePropertyName, PropertyType.LINKBAG, "links").mandatoryAttr(true)
          .iterate();
    });

    session.begin();
    var vrt = session.newVertex("Validation");
    var link = session.newVertex("links");
    vrt.addLightWeightEdge(link, "lst");
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void requiredLinkBagFailsIfBecomesEmpty() {
    graph.executeInTx(g -> {
      var traversal = g.schemaClass("Validation").
          addStateFullEdgeClass("lst").
          addSchemaClass("links");

      var edgePropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, "lst");
      traversal.schemaClass("Validation")
          .addSchemaProperty(edgePropertyName, PropertyType.LINKBAG, "links").
          mandatoryAttr(true).minAttr("1").iterate();
    });

    session.begin();
    var vrt = session.newVertex("Validation");
    var link = session.newVertex("links");
    vrt.addEdge(link, "lst");
    session.commit();
    session.begin();
    vrt.getEdges(Direction.OUT, "lst").forEach(Edge::delete);
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void requiredArrayFailsIfBecomesEmpty() {
    var className = "Validation";

    graph.autoExecuteInTx(g ->
        g.addSchemaClass(className).
            addSchemaProperty("arr", PropertyType.EMBEDDEDLIST).
            mandatoryAttr(true).minAttr("1").iterate()
    );

    session.begin();
    var vrt = session.newVertex(className);
    vrt.getOrCreateEmbeddedList("arr").addAll(Arrays.asList(1, 2, 3));
    session.commit();
    session.begin();
    var activeTx = session.getActiveTransaction();
    vrt = activeTx.load(vrt);
    List<Integer> arr = vrt.getProperty("arr");
    arr.clear();
    session.commit();
  }

  @Test
  public void requiredLinkBagCanBeEmptyDuringTransaction() {
    var edgeClassName = "lst";
    var className = "Validation";
    var linkClassName = "links";

    graph.executeInTx(g -> {
      var traversal = g.addSchemaClass(className).addAbstractSchemaClass(edgeClassName)
          .addParentClass("E")
          .addSchemaClass(linkClassName);

      var edgePropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClassName);
      traversal.schemaClass("Validation")
          .addSchemaProperty(edgePropertyName, PropertyType.LINKBAG, linkClassName)
          .mandatoryAttr(true).iterate();
    });

    session.begin();
    var vrt = session.newVertex(className);
    var link = session.newVertex(linkClassName);
    vrt.addLightWeightEdge(link, edgeClassName);
    session.commit();
    session.begin();
    var activeTx = session.getActiveTransaction();
    vrt = activeTx.load(vrt);
    vrt.getEdges(Direction.OUT, edgeClassName).forEach(Edge::delete);
    var link2 = session.newVertex(linkClassName);
    vrt.addLightWeightEdge(link2, edgeClassName);
    session.commit();
    session.begin();
    vrt = session.load(vrt.getIdentity());
    Assert.assertEquals(
        link2.getIdentity(),
        vrt.getVertices(Direction.OUT, edgeClassName).iterator().next().getIdentity());
    session.commit();
  }

  @Test
  public void maxConstraintOnFloatPropertyDuringTransaction() {
    var className = "Validation";

    graph.autoExecuteInTx(g ->
        g.addSchemaClass(className).addSchemaProperty("dbl", PropertyType.FLOAT).
            mandatoryAttr(true).minAttr("-10").iterate()
    );

    session.begin();
    var vertex = session.newVertex(className);
    vertex.setProperty("dbl", -100.0);
    vertex.setProperty("dbl", 2.39);
    session.commit();
    session.begin();
    var activeTx = session.getActiveTransaction();
    vertex = activeTx.load(vertex);
    float actual = vertex.getProperty("dbl");
    Assert.assertEquals(2.39, actual, 0.01);
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void maxConstraintOnFloatPropertyOnTransaction() {
    var className = "Validation";
    graph.autoExecuteInTx(g ->
        g.addSchemaClass(className).addSchemaProperty("dbl", PropertyType.FLOAT).mandatoryAttr(true)
            .minAttr("-10").iterate()
    );

    session.begin();
    var vertex = session.newVertex(className);
    vertex.setProperty("dbl", -100.0);
    session.commit();
  }
}
