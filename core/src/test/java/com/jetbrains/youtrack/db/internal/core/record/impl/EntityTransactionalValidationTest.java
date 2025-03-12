package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class EntityTransactionalValidationTest extends BaseMemoryInternalDatabase {

  @Test(expected = ValidationException.class)
  public void simpleConstraintShouldBeCheckedOnCommitFalseTest() {
    var clazz = session.createVertexClass("Validation");
    clazz.createProperty("int", PropertyType.INTEGER).setMandatory(true);
    session.begin();
    var vertex = session.newVertex(clazz.getName());
    session.commit();
  }

  @Test()
  public void simpleConstraintShouldBeCheckedOnCommitTrueTest() {
    var clazz = session.createVertexClass("Validation");
    clazz.createProperty("int", PropertyType.INTEGER).setMandatory(true);
    session.begin();
    var vertex = session.newVertex(clazz.getName());
    vertex.setProperty("int", 11);
    session.commit();
    session.begin();
    session.begin();
    Integer value = session.bindToSession(vertex).getProperty("int");
    Assert.assertEquals((Integer) 11, value);
  }

  @Test()
  public void simpleConstraintShouldBeCheckedOnCommitWithTypeConvert() {
    var clazz = session.createVertexClass("Validation");
    clazz.createProperty("int", PropertyType.INTEGER).setMandatory(true);
    session.begin();
    var vertex = session.newVertex(clazz.getName());
    vertex.setProperty("int", "11");
    session.commit();
    session.begin();
    Integer value = session.bindToSession(vertex).getProperty("int");
    Assert.assertEquals((Integer) 11, value);
  }

  @Test
  public void stringRegexpPatternValidationCheck() {
    var clazz = session.createVertexClass("Validation");
    clazz.createProperty("str", PropertyType.STRING).setMandatory(true)
        .setRegexp("aba.*");
    Vertex vertex;
    session.begin();
    vertex = session.newVertex(clazz.getName());
    vertex.setProperty("str", "first");
    vertex.setProperty("str", "second");
    vertex.setProperty("str", "abacorrect");
    session.commit();
    session.begin();
    Assert.assertEquals("abacorrect", session.bindToSession(vertex).getProperty("str"));
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void stringRegexpPatternValidationCheckFails() {
    var clazz = session.createVertexClass("Validation");
    clazz.createProperty("str", PropertyType.STRING).setMandatory(true)
        .setRegexp("aba.*");
    Vertex vertex;
    session.begin();
    vertex = session.newVertex(clazz.getName());
    vertex.setProperty("str", "first");
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void requiredLinkBagNegativeTest() {
    var edgeClass = session.createEdgeClass("lst");
    var clazz = session.createVertexClass("Validation");
    var linkClass = session.createVertexClass("links");
    var edgePropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClass.getName());
    clazz.createProperty(edgePropertyName, PropertyType.LINKBAG, linkClass)
        .setMandatory(true);
    session.begin();
    session.newVertex(clazz.getName());
    session.commit();
  }

  @Test
  public void requiredLinkBagPositiveTest() {
    var edgeClass = session.createLightweightEdgeClass("lst");
    var clazz = session.createVertexClass("Validation");
    var linkClass = session.createVertexClass("links");
    var edgePropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClass.getName());
    clazz.createProperty(edgePropertyName, PropertyType.LINKBAG, linkClass)
        .setMandatory(true);
    session.begin();
    var vrt = session.newVertex(clazz.getName());
    var link = session.newVertex(linkClass.getName());
    vrt.addLightWeightEdge(link, edgeClass);
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void requiredLinkBagFailsIfBecomesEmpty() {
    var edgeClass = session.createEdgeClass("lst");
    var clazz = session.createVertexClass("Validation");
    var linkClass = session.createVertexClass("links");
    var edgePropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClass.getName());
    clazz.createProperty(edgePropertyName, PropertyType.LINKBAG, linkClass)
        .setMandatory(true)
        .setMin("1");
    session.begin();
    var vrt = session.newVertex(clazz.getName());
    var link = session.newVertex(linkClass.getName());
    vrt.addEdge(link, edgeClass);
    session.commit();
    session.begin();
    vrt.getEdges(Direction.OUT, edgeClass).forEach(Edge::delete);
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void requiredArrayFailsIfBecomesEmpty() {
    var clazz = session.createVertexClass("Validation");
    clazz.createProperty("arr", PropertyType.EMBEDDEDLIST).setMandatory(true)
        .setMin("1");
    session.begin();
    var vrt = session.newVertex(clazz.getName());
    vrt.getOrCreateEmbeddedList("arr").addAll(Arrays.asList(1, 2, 3));
    session.commit();
    session.begin();
    vrt = session.bindToSession(vrt);
    List<Integer> arr = vrt.getProperty("arr");
    arr.clear();
    session.commit();
  }

  @Test
  public void requiredLinkBagCanBeEmptyDuringTransaction() {
    var edgeClass = session.createLightweightEdgeClass("lst");
    var clazz = session.createVertexClass("Validation");
    var linkClass = session.createVertexClass("links");
    var edgePropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClass.getName());
    clazz.createProperty(edgePropertyName, PropertyType.LINKBAG, linkClass)
        .setMandatory(true);
    session.begin();
    var vrt = session.newVertex(clazz.getName());
    var link = session.newVertex(linkClass.getName());
    vrt.addLightWeightEdge(link, edgeClass);
    session.commit();
    session.begin();
    vrt = session.bindToSession(vrt);
    vrt.getEdges(Direction.OUT, edgeClass).forEach(Edge::delete);
    var link2 = session.newVertex(linkClass.getName());
    vrt.addLightWeightEdge(link2, edgeClass);
    session.commit();
    session.begin();
    vrt = session.load(vrt.getIdentity());
    Assert.assertEquals(
        link2.getIdentity(),
        vrt.getVertices(Direction.OUT, edgeClass).iterator().next().getIdentity());
    session.commit();
  }

  @Test
  public void maxConstraintOnFloatPropertyDuringTransaction() {
    var clazz = session.createVertexClass("Validation");
    clazz.createProperty("dbl", PropertyType.FLOAT).setMandatory(true).setMin(
        "-10");
    session.begin();
    var vertex = session.newVertex(clazz.getName());
    vertex.setProperty("dbl", -100.0);
    vertex.setProperty("dbl", 2.39);
    session.commit();
    session.begin();
    vertex = session.bindToSession(vertex);
    float actual = vertex.getProperty("dbl");
    Assert.assertEquals(2.39, actual, 0.01);
    session.commit();
  }

  @Test(expected = ValidationException.class)
  public void maxConstraintOnFloatPropertyOnTransaction() {
    var clazz = session.createVertexClass("Validation");
    clazz.createProperty("dbl", PropertyType.FLOAT).setMandatory(true).setMin(
        "-10");
    session.begin();
    var vertex = session.newVertex(clazz.getName());
    vertex.setProperty("dbl", -100.0);
    session.commit();
  }
}
