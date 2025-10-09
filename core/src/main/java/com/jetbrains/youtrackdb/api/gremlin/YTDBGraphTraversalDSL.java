package com.jetbrains.youtrackdb.api.gremlin;


import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBInToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBOutToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBPToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassOutToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassPToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaIndexOutToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaIndexPToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaPropertyInToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaPropertyOutToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaPropertyPToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDsl;
import com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDsl.SkipAsAnonymousMethod;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.IndexBy;
import java.util.HashMap;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

@SuppressWarnings("unused")
@GremlinDsl(traversalSource = "com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSourceDSL")
public interface YTDBGraphTraversalDSL<S, E> extends GraphTraversal.Admin<S, E> {

  default GraphTraversal<S, Vertex> addSchemaClass(String className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL)
        .property(YTDBSchemaClassPToken.name, className);
  }

  default GraphTraversal<S, Vertex> addSchemaClass(String className, String... parentClasses) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).as("result")
        .addE(YTDBSchemaClassOutToken.parentClass).to(__.V().hasLabel(YTDBSchemaClass.LABEL).
            has(YTDBSchemaClassPToken.name, P.within(parentClasses)))
        .select("result");
  }

  default GraphTraversal<S, Vertex> addAbstractSchemaClass(String className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).property(
        YTDBSchemaClassPToken.name, className, YTDBSchemaClassPToken.abstractClass, true);
  }

  default GraphTraversal<S, Vertex> addAbstractSchemaClass(String className,
      String... parentClasses) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).as("result").
        property(YTDBSchemaClassPToken.name, className, YTDBSchemaClassPToken.abstractClass, true).
        addE(YTDBSchemaClassOutToken.parentClass)
        .to(__.V().hasLabel(YTDBSchemaClass.LABEL)
            .has(YTDBSchemaClassPToken.name, P.within(parentClasses)))
        .select("result");
  }

  default GraphTraversal<S, Vertex> addStateFullEdgeClass(String className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).as("result").
        addE(YTDBSchemaClassOutToken.parentClass).to(
            __.V().hasLabel(YTDBSchemaClass.LABEL)
                .has(YTDBSchemaClassPToken.name, P.eq(YTDBSchemaClass.EDGE_CLASS_NAME))
        ).select("result");
  }

  default GraphTraversal<S, Vertex> addSchemaProperty(String propertyName,
      PropertyType propertyType) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    return ytdbGraphTraversal.addE(YTDBSchemaClassOutToken.declaredProperty)
        .to(
            __.addV(YTDBSchemaProperty.LABEL).property(YTDBSchemaPropertyPToken.type,
                propertyType.name())
        ).outV();
  }

  default GraphTraversal<S, Vertex> addSchemaProperty(String propertyName,
      PropertyType propertyType, PropertyType linkedType) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    return ytdbGraphTraversal.addE(YTDBSchemaClassOutToken.declaredProperty)
        .to(
            __.addV(YTDBSchemaProperty.LABEL).property(YTDBSchemaPropertyPToken.type,
                propertyType.name(), YTDBSchemaPropertyPToken.linkedType,
                linkedType.name())
        ).outV();
  }

  default <S1> GraphTraversal<S, Vertex> addSchemaProperty(String propertyName,
      PropertyType propertyType, String linkClassName) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    return ytdbGraphTraversal.addE(YTDBSchemaClassOutToken.declaredProperty).to(
        __.addV(YTDBSchemaProperty.LABEL).
            addE(YTDBSchemaPropertyOutToken.linkedClass).
            to(
                __.V().hasLabel(YTDBSchemaClass.LABEL)
                    .has(YTDBSchemaClassPToken.name, P.eq(linkClassName)
                    )

            ).outV()
    ).outV();
  }

  default GraphTraversal<S, Vertex> addPropertyIndex(String indexName, IndexType indexType) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    var currentProperty = "currentProperty";
    return ytdbGraphTraversal.as(currentProperty).
        addV(YTDBSchemaIndex.LABEL).
        property(
            YTDBSchemaIndexPToken.name, indexName,
            YTDBSchemaIndexPToken.indexType, indexType.name()).
        addE(YTDBSchemaIndexOutToken.propertyToIndex).to(
            __.select(currentProperty)
        ).outV().addE(YTDBSchemaIndexOutToken.classToIndex).to(__.select(currentProperty).in(
            YTDBSchemaPropertyInToken.declaredProperty)
        ).outV();
  }

  default GraphTraversal<S, Vertex> addPropertyIndex(IndexType indexType) {
    return addPropertyIndex(indexType, IndexBy.BY_VALUE);
  }

  default GraphTraversal<S, Vertex> addPropertyIndex(IndexType indexType, IndexBy indexBy) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    var currentProperty = "currentProperty";
    //noinspection unchecked
    return ytdbGraphTraversal.as(currentProperty).
        addV(YTDBSchemaIndex.LABEL).
        property(YTDBSchemaClassPToken.name,
            __.select(currentProperty).schemaPropertyClass().schemaClassName().concat(".").
                concat(__.select(currentProperty).schemaPropertyName())
        ).
        property(YTDBSchemaIndexPToken.indexType, indexType.name()).
        property(YTDBSchemaIndexPToken.indexBy, indexBy.name()).
        addE(YTDBSchemaIndexOutToken.propertyToIndex).to(
            __.select(currentProperty)
        ).outV().
        addE(YTDBSchemaIndexOutToken.classToIndex).to(
            __.select(currentProperty).schemaPropertyClass()
        ).outV();
  }

  default GraphTraversal<S, Vertex> addPropertyIndex(String indexName, IndexType indexType,
      IndexBy indexBy) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    var currentProperty = "currentProperty";
    return ytdbGraphTraversal.as(currentProperty).
        addV(YTDBSchemaIndex.LABEL).
        property(
            YTDBSchemaIndexPToken.name, indexName,
            YTDBSchemaIndexPToken.indexType, indexType.name(),
            YTDBSchemaIndexPToken.indexBy, indexBy.name()).
        addE(YTDBSchemaIndexOutToken.propertyToIndex).to(
            __.select(currentProperty)
        ).outV().addE(YTDBSchemaIndexOutToken.classToIndex).to(
            __.select(currentProperty).in(YTDBSchemaPropertyInToken.declaredProperty)
        ).outV();
  }

  default GraphTraversal<S, Vertex> addClassIndex(String indexName, IndexType indexType,
      String... propertyNames) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    var currentClass = "currentClass";

    //noinspection unchecked
    return ytdbGraphTraversal.as(currentClass).constant(propertyNames).unfold()
        .map(__.schemaClassProperty(__.identity())).addE(YTDBSchemaPropertyInToken.propertyToIndex).
        from(
            __.coalesce(
                    __.schemaIndex(indexName),
                    __.addV(YTDBSchemaIndex.LABEL)
                ).
                property(
                    YTDBSchemaIndexPToken.name, indexName,
                    YTDBSchemaIndexPToken.indexType, indexType.name()
                ).
                addE(YTDBSchemaIndexOutToken.classToIndex).to(
                    __.select(currentClass)
                ).
                outV()
        ).outV();
  }

  default GraphTraversal<S, Vertex> addClassIndex(String indexName, IndexType indexType,
      String[] propertyNames, IndexBy[] indexBy) {
    if (propertyNames.length != indexBy.length) {
      throw new IllegalArgumentException(
          "Count of elements in propertyNames and indexBy parameters must be equal");
    }
    if (propertyNames.length == 0) {
      throw new IllegalArgumentException("List of property names is empty");
    }

    var propertyNameIndexBy = new HashMap<String, String>();
    for (var i = 0; i < propertyNames.length; i++) {
      var propertyName = propertyNames[i];
      var indexByStr = indexBy[i].name();
      propertyNameIndexBy.put(propertyName, indexByStr);
    }

    var currentClass = "currentClass";
    var propertyIndexByMap = "propertyIndexByMap";
    var currentProperty = "currentProperty";

    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    //save current class instance
    //noinspection unchecked
    return ytdbGraphTraversal.as(currentClass).
        //save map between property name and indexBy
            constant(propertyNameIndexBy).as(propertyIndexByMap).
        //process property names one by one
            constant(propertyNames).unfold()
        .map(__.schemaClassProperty(__.identity())).as(currentProperty)
        //connect property to already existing index or create new one
        .addE(YTDBSchemaPropertyInToken.propertyToIndex).
        from(
            __.coalesce(
                    __.schemaIndex(indexName),
                    __.addV(YTDBSchemaIndex.LABEL)
                ).
                property(YTDBSchemaIndexPToken.indexBy,
                    __.select(propertyIndexByMap).select(
                        __.select(currentProperty).schemaPropertyName()
                    )
                ).
                property(
                    YTDBSchemaIndexPToken.name, indexName,
                    YTDBSchemaIndexPToken.indexType, indexType.name()
                ).
                addE(YTDBSchemaIndexOutToken.classToIndex).to(
                    __.select(currentClass)
                ).
                outV()
        ).outV();
  }

  default GraphTraversal<S, Vertex> schemaClass(String... className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    if (className == null || className.length == 0) {
      return ytdbGraphTraversal.V().hasLabel(YTDBSchemaClass.LABEL);
    }

    return ytdbGraphTraversal.V().hasLabel(YTDBSchemaClass.LABEL)
        .has(YTDBSchemaClassPToken.name, P.within(className));
  }

  default GraphTraversal<S, Vertex> schemaIndex(String... indexName) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    if (indexName == null || indexName.length == 0) {
      return ytdbGraphTraversal.V().hasLabel(YTDBSchemaIndex.LABEL);
    }

    return ytdbGraphTraversal.V().hasLabel(YTDBSchemaIndex.LABEL)
        .has(YTDBSchemaIndexPToken.name, P.within(indexName));
  }

  default GraphTraversal<S, Vertex> in(final YTDBInToken<?> in) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.in(in.name());
  }


  default GraphTraversal<S, Vertex> out(final YTDBOutToken<?> out) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.out(out.name());
  }

  default GraphTraversal<S, Edge> outE(final YTDBOutToken<?> out) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.outE(out.name());
  }

  default GraphTraversal<S, Edge> inE(final YTDBInToken<?> out) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.outE(out.name());
  }

  default GraphTraversal<S, Vertex> declaredSchemaClassProperties(String... propertyName) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;

    if (propertyName == null || propertyName.length == 0) {
      return ytdbGraphTraversal.out(YTDBSchemaClassOutToken.declaredProperty);
    }

    return ytdbGraphTraversal.out(YTDBSchemaClassOutToken.declaredProperty)
        .has(YTDBSchemaPropertyPToken.name, P.within(propertyName));
  }

  default GraphTraversal<S, Vertex> schemaClassProperties(String... propertyName) {
    @SuppressWarnings("unchecked")
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, Vertex>) this;

    if (propertyName == null || propertyName.length == 0) {
      return ytdbGraphTraversal.emit().repeat(
          __.out(YTDBSchemaClassOutToken.parentClass)
      ).out(YTDBSchemaClassOutToken.declaredProperty);
    }
    return ytdbGraphTraversal.emit().repeat(
            __.out(YTDBSchemaClassOutToken.parentClass)
        ).out(YTDBSchemaClassOutToken.declaredProperty)
        .has(YTDBSchemaPropertyPToken.name, P.within(propertyName));
  }

  default GraphTraversal<S, Vertex> schemaClassProperty(Traversal<?, String> traversal) {
    @SuppressWarnings("unchecked")
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, Vertex>) this;

    return ytdbGraphTraversal.emit().repeat(
            __.out(YTDBSchemaClassOutToken.parentClass)
        ).out(YTDBSchemaClassOutToken.declaredProperty)
        .has(YTDBSchemaPropertyPToken.name, traversal);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, String> schemaClassName() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.values(YTDBSchemaClassPToken.name);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Boolean> isAbstractClass() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.values(YTDBSchemaClassPToken.abstractClass);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Boolean> isClassInStrictMode() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.values(YTDBSchemaClassPToken.strictMode);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> switchStrictModeOnClass() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.property(YTDBSchemaClassPToken.strictMode, true);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, String> schemaPropertyName() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.values(YTDBSchemaPropertyPToken.name);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Vertex> schemaPropertyClass() {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.in(YTDBSchemaPropertyInToken.declaredProperty);
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
  default GraphTraversal<S, E> has(final YTDBPToken<?> pToken,
      final Traversal<?, ?> traversal) {
    var propertyKey = pToken.name();

    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.has(propertyKey, traversal);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Edge> addE(final YTDBOutToken<?> out) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addE(out.name());
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Edge> addE(final YTDBInToken<?> out) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addE(out.name());
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> by(final YTDBPToken<?> pToken) {
    var propertyKey = pToken.name();
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.by(propertyKey);
  }

}
