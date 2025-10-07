package com.jetbrains.youtrackdb.api.gremlin;

import static com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassOutToken.declaredProperty;
import static com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassOutToken.parentClass;
import static com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassPToken.abstractClass;
import static com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassPToken.name;
import static com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassPToken.strictMode;
import static com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaPropertyOutToken.linkedClass;

import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBInToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBOutToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBPToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaIndexOutToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaIndexPToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaPropertyInToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaPropertyPToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDsl;
import com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDsl.SkipAsAnonymousMethod;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

@SuppressWarnings("unused")
@GremlinDsl(traversalSource = "com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSourceDSL")
public interface YTDBGraphTraversalDSL<S, E> extends GraphTraversal.Admin<S, E> {

  default GraphTraversal<S, Vertex> addSchemaClass(String className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).property(name, className);
  }

  default GraphTraversal<S, Vertex> addSchemaClass(String className, String... parentClasses) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).as("result")
        .addE(parentClass).to(__.V().hasLabel(YTDBSchemaClass.LABEL).
            has(name, P.within(parentClasses)))
        .select("result");
  }

  default GraphTraversal<S, Vertex> addAbstractSchemaClass(String className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).property(
        name, className, abstractClass, true);
  }

  default GraphTraversal<S, Vertex> addAbstractSchemaClass(String className,
      String... parentClasses) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).as("result").
        property(name, className, abstractClass, true).
        addE(parentClass)
        .to(__.V().hasLabel(YTDBSchemaClass.LABEL).has(name, P.within(parentClasses)))
        .select("result");
  }

  default GraphTraversal<S, Vertex> addStateFullEdgeClass(String className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).as("result").
        addE(parentClass).to(
            __.V().hasLabel(YTDBSchemaClass.LABEL)
                .has(name, P.eq(YTDBSchemaClass.EDGE_CLASS_NAME))
        ).select("result");
  }

  default GraphTraversal<S, Vertex> addSchemaProperty(String propertyName,
      PropertyType propertyType) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    return ytdbGraphTraversal.addE(declaredProperty)
        .to(
            __.addV(YTDBSchemaProperty.LABEL).property(YTDBSchemaPropertyPToken.type,
                propertyType.name())
        ).outV();
  }

  default GraphTraversal<S, Vertex> addSchemaProperty(String propertyName,
      PropertyType propertyType, PropertyType linkedType) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    return ytdbGraphTraversal.addE(declaredProperty)
        .to(
            __.addV(YTDBSchemaProperty.LABEL).property(YTDBSchemaPropertyPToken.type,
                propertyType.name(), YTDBSchemaPropertyPToken.linkedType,
                linkedType.name())
        ).outV();
  }

  default <S1> GraphTraversal<S, Vertex> addSchemaProperty(String propertyName,
      PropertyType propertyType, String linkClassName) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    return ytdbGraphTraversal.addE(declaredProperty).to(
        __.addV(YTDBSchemaProperty.LABEL).
            addE(linkedClass).
            to(
                __.V().hasLabel(YTDBSchemaClass.LABEL)
                    .has(name, P.eq(linkClassName)
                    )

            ).outV()
    ).outV();
  }

  default GraphTraversal<S, Vertex> addPropertyIndex(String indexName, IndexType indexType) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    return ytdbGraphTraversal.as("currentProperty").
        addV(YTDBSchemaIndex.LABEL).
        property(
            YTDBSchemaIndexPToken.name, indexName,
            YTDBSchemaIndexPToken.indexType, indexType.name()).
        addE(YTDBSchemaIndexOutToken.propertyToIndex).to(
            __.select("currentProperty")
        ).outV().addE(YTDBSchemaIndexOutToken.classToIndex).to(__.select("currentProperty").in(
            YTDBSchemaPropertyInToken.declaredProperty)
        ).outV();
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, String> schemaClassName() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.values(name);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Boolean> isAbstractClass() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.values(abstractClass);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Boolean> isClassInStrictMode() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.values(strictMode);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> switchStrictModeOnClass() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.property(strictMode, true);
  }

  @SkipAsAnonymousMethod
  default <E2> GraphTraversal<S, E2> values(final YTDBPToken<?>... pTokens) {
    var propertyKeys = new String[pTokens.length];

    for (var i = 0; i < pTokens.length; i++) {
      propertyKeys[i] = pTokens[i].name();
    }

    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.values(propertyKeys);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> property(final YTDBPToken<?> pToken, final Object value,
      final Object... pTokenValues) {
    var firstKey = pToken.name();
    if (pTokenValues != null && pTokenValues.length > 0) {
      if (pTokenValues.length % 2 != 0) {
        throw Element.Exceptions.providedKeyValuesMustBeAMultipleOfTwo();
      }

      for (var i = 0; i < pTokenValues.length; i = i + 2) {
        if (!(pTokenValues[i] instanceof YTDBPToken<?> domainObjectPToken)) {
          throw new IllegalArgumentException("The provided key/value "
              + "array must have a YTDBDomainObjectPToken on even array indices");
        }
        pTokenValues[i] = domainObjectPToken.name();
      }
    }

    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.property(firstKey, value, pTokenValues);
  }


  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> has(final YTDBPToken<?> pToken,
      final P<?> predicate) {
    var propertyKey = pToken.name();

    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.has(propertyKey, predicate);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Edge> addE(final YTDBOutToken<?> out) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addE(out.name());
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Vertex> in(final YTDBInToken<?> in) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.in(in.name());
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Vertex> out(final YTDBOutToken<?> out) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.out(out.name());
  }
}
