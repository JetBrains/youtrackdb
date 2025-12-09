package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectInToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectObjectOutToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectPToken;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBDomainObjectEdge;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBDomainObjectVertexProperty;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality;
import org.apache.tinkerpop.gremlin.util.iterator.MultiIterator;

public interface YTDBDomainObject extends Vertex {

  @SuppressWarnings("rawtypes")
  YTDBDomainObjectPToken[] pTokens();

  @SuppressWarnings("rawtypes")
  YTDBDomainObjectInToken[] inTokens();

  @SuppressWarnings("rawtypes")
  YTDBDomainObjectObjectOutToken[] outTokens();

  YTDBDomainObjectPToken<YTDBDomainObject> pToken(String name);

  YTDBDomainObjectInToken<YTDBDomainObject> inToken(String label);

  YTDBDomainObjectObjectOutToken<YTDBDomainObject> outToken(String label);

  @Override
  RID id();

  @Override
  default Edge addEdge(String label, Vertex inVertex, Object... keyValues) {
    throw Exceptions.edgesCanNotBeModifiedDirectly();
  }

  @Override
  default Iterator<Vertex> vertices(Direction direction, String... edgeLabels) {
    if (edgeLabels == null || edgeLabels.length == 0) {
      var multiIterator = new MultiIterator<Vertex>();

      if (direction == Direction.IN) {
        for (var inToken : inTokens()) {
          @SuppressWarnings("unchecked")
          var res = (Iterator<Vertex>) inToken.apply(this);
          multiIterator.addIterator(res);
        }
      } else if (direction == Direction.OUT) {
        for (var outToken : outTokens()) {
          @SuppressWarnings("unchecked")
          var res = (Iterator<Vertex>) outToken.apply(this);
          multiIterator.addIterator(res);
        }
      } else {
        multiIterator.addIterator(vertices(Direction.OUT, edgeLabels));
        multiIterator.addIterator(vertices(Direction.IN, edgeLabels));
      }

      return multiIterator;
    }

    if (direction == Direction.IN) {
      if (edgeLabels.length == 1) {
        var inToken = inToken(edgeLabels[0]);
        if (inToken != null) {
          return inToken.apply(this);
        }

        return IteratorUtils.emptyIterator();
      } else {
        var multiIterator = new MultiIterator<Vertex>();
        for (var label : edgeLabels) {
          var inToken = inToken(label);
          if (inToken != null) {
            multiIterator.addIterator(inToken.apply(this));
          }
        }

        return multiIterator;
      }
    } else if (direction == Direction.OUT) {
      if (edgeLabels.length == 1) {
        var outToken = outToken(edgeLabels[0]);
        if (outToken != null) {
          return outToken.apply(this);
        }

        return IteratorUtils.emptyIterator();
      } else {
        var multiIterator = new MultiIterator<Vertex>();
        for (var label : edgeLabels) {
          var inToken = inToken(label);
          multiIterator.addIterator(inToken.apply(this));
        }

        return multiIterator;
      }
    }

    assert direction == Direction.BOTH;

    var multiIterator = new MultiIterator<Vertex>();

    multiIterator.addIterator(vertices(Direction.IN, edgeLabels));
    multiIterator.addIterator(vertices(Direction.OUT, edgeLabels));

    return multiIterator;
  }

  @Override
  default Set<String> keys() {
    var keys = new HashSet<String>();

    for (var pToken : pTokens()) {
      keys.add(pToken.getAccessor());
    }

    return Collections.unmodifiableSet(keys);
  }

  @Override
  default <V> V value(String key) throws NoSuchElementException {
    var pToken = pToken(key);
    if (pToken == null) {
      throw new NoSuchElementException("Property " + key + " not found");
    }

    //noinspection unchecked
    return (V) pToken.fetch(this);
  }

  @Override
  default <V> Iterator<V> values(String... propertyKeys) {
    if (propertyKeys == null || propertyKeys.length == 0) {
      var pTokens = pTokens();
      var list = new ArrayList<V>();

      for (var pToken : pTokens) {
        //noinspection unchecked
        list.add((V) pToken.fetch(this));
      }

      return list.iterator();
    }

    if (propertyKeys.length == 1) {
      var pToken = pToken(propertyKeys[0]);
      if (pToken == null) {
        throw new NoSuchElementException("Property " + propertyKeys[0] + " not found");
      }

      //noinspection unchecked
      return IteratorUtils.singletonIterator(((V) pToken.fetch(this)));
    } else {
      var values = new ArrayList<V>();

      for (var key : propertyKeys) {
        var pToken = pToken(key);
        if (pToken == null) {
          throw new NoSuchElementException("Property " + key + " not found");
        }
        //noinspection unchecked
        values.add((V) pToken.fetch(this));
      }

      return values.iterator();
    }
  }

  @Override
  default <V> VertexProperty<V> property(Cardinality cardinality, String key, V value,
      Object... keyValues) {
    if (cardinality != Cardinality.single) {
      throw new IllegalArgumentException(
          "Only 'single' cardinality is supported for vertex properties.");
    }

    if (keyValues != null && keyValues.length > 0) {
      throw VertexProperty.Exceptions.metaPropertiesNotSupported();
    }

    if (key == null) {
      throw Property.Exceptions.propertyKeyCanNotBeNull();
    }
    if (Graph.Hidden.isHidden(key)) {
      throw Property.Exceptions.propertyKeyCanNotBeAHiddenKey(key);
    }

    var pToken = pToken(key);
    if (pToken == null) {
      throw new NoSuchElementException("Property " + key + " does not exist.");
    }

    pToken.update(this, value);

    return new YTDBDomainObjectVertexProperty<>(this, pToken);
  }

  @Override
  default <V> Iterator<VertexProperty<V>> properties(String... propertyKeys) {
    if (propertyKeys == null || propertyKeys.length == 0) {
      var pTokens = pTokens();
      var list = new ArrayList<VertexProperty<V>>();

      for (var pToken : pTokens) {
        //noinspection rawtypes,unchecked
        list.add(new YTDBDomainObjectVertexProperty<>(this, pToken));
      }

      return list.iterator();
    }

    var properties = new ArrayList<VertexProperty<V>>();
    for (var key : propertyKeys) {
      var pToken = pToken(key);
      if (pToken == null) {
        continue;
      }
      properties.add(new YTDBDomainObjectVertexProperty<>(this, pToken));
    }

    return properties.iterator();
  }

  @Override
  default <V> VertexProperty<V> property(String key) {
    var pToken = pToken(key);
    if (pToken == null) {
      return VertexProperty.empty();
    }

    return new YTDBDomainObjectVertexProperty<>(this, pToken);
  }

  @Override
  YTDBGraph graph();

  @Override
  default Iterator<Edge> edges(Direction direction, String... edgeLabels) {
    var graph = graph();

    if (edgeLabels == null || edgeLabels.length == 0) {
      return allEdges(direction, edgeLabels, graph);
    }

    var multiIterator = new MultiIterator<Edge>();
    for (var edgeLabel : edgeLabels) {
      if (direction == Direction.IN) {
        var inToken = inToken(edgeLabel);
        if (inToken != null) {
          var vIterator = inToken.apply(this);
          var edgeIterator = org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(vIterator,
              v
                  -> (Edge) new YTDBDomainObjectEdge(graph, (YTDBDomainObject) v, this,
                  inToken.name()));
          multiIterator.addIterator(edgeIterator);
        }
      } else if (direction == Direction.OUT) {
        var outToken = outToken(edgeLabel);
        if (outToken != null) {
          var vIterator = outToken.apply(this);
          var edgeIterator = org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(vIterator,
              v -> (Edge) new YTDBDomainObjectEdge(graph, this, (YTDBDomainObject) v,
                  outToken.name()));
          multiIterator.addIterator(edgeIterator);
        }
      }
    }

    assert direction == Direction.BOTH;
    multiIterator.addIterator(edges(Direction.IN, edgeLabels));
    multiIterator.addIterator(edges(Direction.OUT, edgeLabels));

    return multiIterator;
  }

  private MultiIterator<Edge> allEdges(Direction direction, String[] edgeLabels,
      YTDBGraph graph) {
    var multiIterator = new MultiIterator<Edge>();

    if (direction == Direction.IN) {
      var inTokens = inTokens();
      for (var inToken : inTokens) {
        @SuppressWarnings("unchecked")
        var vIterator = (Iterator<YTDBDomainObject>) inToken.apply(
            this);
        var edgeIterator = org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(vIterator,
            v
                -> (Edge) new YTDBDomainObjectEdge(graph, v, this, inToken.name()));
        multiIterator.addIterator(edgeIterator);
      }

      return multiIterator;
    } else if (direction == Direction.OUT) {
      var outTokens = outTokens();

      for (var outToken : outTokens) {
        @SuppressWarnings("unchecked")
        var vIterator = (Iterator<YTDBDomainObject>) outToken.apply(this);
        var edgeIterator = org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(vIterator,
            v -> (Edge) new YTDBDomainObjectEdge(graph, this, v, outToken.name()));
        multiIterator.addIterator(edgeIterator);
      }
    }

    assert direction == Direction.BOTH;

    multiIterator.addIterator(edges(Direction.IN, edgeLabels));
    multiIterator.addIterator(edges(Direction.OUT, edgeLabels));

    return multiIterator;
  }

  interface Exceptions {

    static UnsupportedOperationException edgesCanNotBeModifiedDirectly() {
      return new UnsupportedOperationException(
          "Edges can not be modified directly either use related methods of domain objects or GraphTraversal steps.");
    }
  }
}
