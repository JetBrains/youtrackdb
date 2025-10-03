package com.jetbrains.youtrackdb.internal.core.gremlin.domain;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.iterator.MultiIterator;

public abstract class YTDBDomainVertexAbstract<E extends EntityImpl> implements YTDBDomainVertex {
  private final ThreadLocal<E> threadLocalEntity = new ThreadLocal<>();

  @Nullable
  private final E fastPathEntity;
  protected YTDBGraphInternal graph;
  protected final RID rid;

  public YTDBDomainVertexAbstract(final YTDBGraphInternal graph, final Identifiable identifiable) {
    this.graph = checkNotNull(graph);
    var id = checkNotNull(identifiable);

    this.rid = id.getIdentity();

    if (identifiable instanceof Entity entity) {
      //noinspection unchecked
      this.fastPathEntity = (E) entity;
    } else {
      this.fastPathEntity = null;
    }
  }


  @SuppressWarnings("rawtypes")
  protected abstract YTDBPTokenInternal[] pTokens();

  @SuppressWarnings("rawtypes")
  protected abstract YTDBInTokenInternal[] inTokens();

  @SuppressWarnings("rawtypes")
  protected abstract YTDBOutTokenInternal[] outTokens();

  protected abstract YTDBPTokenInternal<YTDBDomainVertexAbstract<?>> pToken(String name);

  protected abstract YTDBInTokenInternal<YTDBDomainVertexAbstract<?>> inToken(String label);

  protected abstract YTDBOutTokenInternal<YTDBDomainVertexAbstract<?>> outToken(String label);

  @Override
  public YTDBDomainEdgeImpl<?, ?> addEdge(String label, Vertex inVertex, Object... keyValues) {
    if (inVertex == null) {
      throw new IllegalArgumentException("Destination vertex is null");
    }
    checkArgument(!isNullOrEmpty(label), "label is invalid");

    if (keyValues.length > 0) {
      throw new IllegalArgumentException("Edges of domain vertices can not have properties");
    }

    var tx = graph.tx();
    tx.readWrite();

    var outToken = outToken(label);
    if (outToken == null) {
      throw new IllegalArgumentException("Edge with " + label + " is not defined in vertex");
    }
    if (!(inVertex instanceof YTDBDomainVertex domainVertex)) {
      throw new IllegalArgumentException(
          "Edge with " + label + " is not of type " + YTDBDomainVertex.class.getSimpleName());
    }

    return outToken.add(this, domainVertex);
  }

  @Override
  public Iterator<Vertex> vertices(Direction direction, String... edgeLabels) {
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
          //noinspection unchecked,rawtypes
          return (Iterator) inToken.apply(this);
        }

        return IteratorUtils.emptyIterator();
      } else {
        var multiIterator = new MultiIterator<Vertex>();
        for (var label : edgeLabels) {
          var inToken = inToken(label);
          if (inToken != null) {
            //noinspection rawtypes,unchecked
            multiIterator.addIterator((Iterator) inToken.apply(this));
          }
        }

        return multiIterator;
      }
    } else if (direction == Direction.OUT) {
      if (edgeLabels.length == 1) {
        var outToken = outToken(edgeLabels[0]);
        if (outToken != null) {
          //noinspection unchecked,rawtypes
          return (Iterator) outToken.apply(this);
        }

        return IteratorUtils.emptyIterator();
      } else {
        var multiIterator = new MultiIterator<Vertex>();
        for (var label : edgeLabels) {
          var inToken = inToken(label);
          //noinspection rawtypes,unchecked
          multiIterator.addIterator((Iterator) inToken.apply(this));
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
  public Set<String> keys() {
    var keys = new HashSet<String>();

    for (var pToken : pTokens()) {
      keys.add(pToken.name());
    }

    return Collections.unmodifiableSet(keys);
  }

  @Override
  public <V> V value(String key) throws NoSuchElementException {
    var pToken = pToken(key);
    if (pToken == null) {
      throw new NoSuchElementException("Property " + key + " not found");
    }

    //noinspection unchecked
    return (V) pToken.fetch(this);
  }

  @Override
  public <V> Iterator<V> values(String... propertyKeys) {
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
  public <V> VertexProperty<V> property(Cardinality cardinality, String key, V value,
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

    return new YTDBDomainVertexPropertyImpl<>(this, pToken);
  }

  @Override
  public <V> Iterator<VertexProperty<V>> properties(String... propertyKeys) {
    if (propertyKeys == null || propertyKeys.length == 0) {
      var pTokens = pTokens();
      var list = new ArrayList<VertexProperty<V>>();

      for (var pToken : pTokens) {
        //noinspection rawtypes,unchecked
        list.add(new YTDBDomainVertexPropertyImpl<>(this, pToken));
      }

      return list.iterator();
    }

    var properties = new ArrayList<VertexProperty<V>>();
    for (var key : propertyKeys) {
      var pToken = pToken(key);
      if (pToken == null) {
        continue;
      }
      properties.add(new YTDBDomainVertexPropertyImpl<>(this, pToken));
    }

    return properties.iterator();
  }

  @Override
  public <V> VertexProperty<V> property(String key) {
    var pToken = pToken(key);

    if (pToken == null) {
      return VertexProperty.empty();
    }

    graph.tx().readWrite();
    return new YTDBDomainVertexPropertyImpl<>(this, pToken);
  }

  @Override
  public Iterator<Edge> edges(Direction direction, String... edgeLabels) {
    if (edgeLabels == null || edgeLabels.length == 0) {
      return allEdges(direction, edgeLabels, graph);
    }

    var multiIterator = new MultiIterator<Edge>();
    for (var edgeLabel : edgeLabels) {
      if (direction == Direction.IN) {
        var inToken = inToken(edgeLabel);

        if (inToken != null) {
          var vIterator = inToken.apply(this);
          var edgeIterator = YTDBIteratorUtils.map(vIterator,
              v -> {
                //noinspection rawtypes,unchecked
                return (Edge) new YTDBDomainEdgeImpl(graph, v, this,
                    ((YTDBDomainVertexAbstract<?>) v).outToken(inToken.name()));
              });
          multiIterator.addIterator(edgeIterator);
        }
      } else if (direction == Direction.OUT) {
        var outToken = outToken(edgeLabel);
        if (outToken != null) {
          var vIterator = outToken.apply(this);
          var edgeIterator = YTDBIteratorUtils.map(vIterator,
              v -> {
                //noinspection rawtypes,unchecked
                return (Edge) new YTDBDomainEdgeImpl(graph, this, v, outToken);
              });
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
        var vIterator = (Iterator<YTDBDomainVertex>) inToken.apply(
            this);
        var edgeIterator = YTDBIteratorUtils.map(vIterator,
            v
                -> {
              //noinspection rawtypes,unchecked
              return (Edge) new YTDBDomainEdgeImpl(graph, v, this,
                  ((YTDBDomainVertexAbstract<?>) v).outToken(inToken.name()));
            });
        multiIterator.addIterator(edgeIterator);
      }

      return multiIterator;
    } else if (direction == Direction.OUT) {
      var outTokens = outTokens();

      for (var outToken : outTokens) {
        @SuppressWarnings("unchecked")
        var vIterator = (Iterator<YTDBDomainVertex>) outToken.apply(this);
        var edgeIterator = YTDBIteratorUtils.map(vIterator,
            v -> {
              //noinspection rawtypes,unchecked
              return (Edge) new YTDBDomainEdgeImpl(graph, this, v, outToken);
            });
        multiIterator.addIterator(edgeIterator);
      }
    }

    assert direction == Direction.BOTH;

    multiIterator.addIterator(edges(Direction.IN, edgeLabels));
    multiIterator.addIterator(edges(Direction.OUT, edgeLabels));

    return multiIterator;
  }


  @Override
  public RID id() {
    return rid;
  }

  @Override
  public String label() {
    this.graph.tx().readWrite();
    return getRawEntity().getSchemaClassName();
  }

  @Override
  public YTDBGraph graph() {
    return graph;
  }

  public void property(Object... keyValues) {
    ElementHelper.legalPropertyKeyValueArray(keyValues);

    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw Vertex.Exceptions.userSuppliedIdsNotSupported();
    }

    // copied from ElementHelper.attachProperties
    // can't use ElementHelper here because we only want to save the
    // document at the very end
    for (var i = 0; i < keyValues.length; i = i + 2) {
      if (!keyValues[i].equals(T.id) && !keyValues[i].equals(T.label)) {
        property((String) keyValues[i], keyValues[i + 1]);
      }
    }
  }

  @Override
  public void remove() {
    this.graph.tx().readWrite();
    getRawEntity().delete();
  }

  public YTDBGraphInternal getGraph() {
    return graph;
  }

  @Override
  public final int hashCode() {
    return ElementHelper.hashCode(this);
  }

  @SuppressWarnings("EqualsDoesntCheckParameterClass")
  @Override
  public final boolean equals(final Object object) {
    return ElementHelper.areEqual(this, object);
  }

  @Override
  public String toString() {
    return StringFactory.vertexString(this);
  }

  public final E getRawEntity() {
    var graphTx = graph.tx();
    var session = graphTx.getDatabaseSession();

    if (fastPathEntity == null || fastPathEntity.isNotBound(session)) {
      var tx = session.getActiveTransaction();

      var entity = threadLocalEntity.get();
      if (entity == null) {
        //noinspection unchecked
        entity = (E) tx.loadEntity(rid);
        threadLocalEntity.set(entity);

        return entity;
      }

      if (entity.isNotBound(session)) {
        entity = tx.load(entity);
        threadLocalEntity.set(entity);
      }

      return entity;
    } else {
      return fastPathEntity;
    }
  }
}
