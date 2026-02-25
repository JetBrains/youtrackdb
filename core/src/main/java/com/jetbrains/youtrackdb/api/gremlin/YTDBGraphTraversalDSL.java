package com.jetbrains.youtrackdb.api.gremlin;

import static com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaClassOutToken.declaredProperty;
import static com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaClassOutToken.superClass;
import static com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaClassPToken.abstractClass;
import static com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaClassPToken.name;
import static com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaClassPToken.strictMode;

import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectObjectOutToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectPToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaPropertyPToken;
import com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDsl;
import com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDsl.SkipAsAnonymousMethod;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.service.YTDBRemovePropertyService;
import java.util.HashSet;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.Vertex;

@SuppressWarnings("unused")
@GremlinDsl(traversalSource = "com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSourceDSL")
public interface YTDBGraphTraversalDSL<S, E> extends GraphTraversal.Admin<S, E> {

  default GraphTraversal<S, Vertex> addSchemaClass(String className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).property(name, className);
  }

  default GraphTraversal<S, Vertex> addSchemaClass(String className, String... superClasses) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).as("result")
        .addE(superClass).to(__.V().hasLabel(YTDBSchemaClass.LABEL).
            has(name, P.within(superClasses)))
        .select("result");
  }

  default GraphTraversal<S, Vertex> addAbstractSchemaClass(String className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).property(
        name, className, abstractClass, true);
  }

  default GraphTraversal<S, Vertex> addAbstractSchemaClass(String className,
      String... superClasses) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).as("result").
        property(name, className, abstractClass, true).
        addE(superClass).to(__.V()
            .hasLabel(YTDBSchemaClass.LABEL).has(name, P.within(superClasses)))
        .select("result");
  }

  default GraphTraversal<S, Vertex> addStateFullEdgeClass(String className) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addV(YTDBSchemaClass.LABEL).as("result").
        addE(superClass).to(
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
  default <E2> GraphTraversal<S, E2> values(final YTDBDomainObjectPToken<?>... pTokens) {
    var propertyKeys = new String[pTokens.length];

    for (var i = 0; i < pTokens.length; i++) {
      propertyKeys[i] = pTokens[i].name();
    }

    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.values(propertyKeys);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> property(final YTDBDomainObjectPToken<?> pToken, final Object value,
      final Object... pTokenValues) {
    var firstKey = pToken.name();
    if (pTokenValues != null && pTokenValues.length > 0) {
      if (pTokenValues.length % 2 != 0) {
        throw Element.Exceptions.providedKeyValuesMustBeAMultipleOfTwo();
      }

      for (var i = 0; i < pTokenValues.length; i = i + 2) {
        if (!(pTokenValues[i] instanceof YTDBDomainObjectPToken<?> domainObjectPToken)) {
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
  default GraphTraversal<S, E> has(final YTDBDomainObjectPToken<?> pToken,
      final P<?> predicate) {
    var propertyKey = pToken.name();

    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.has(propertyKey, predicate);
  }

  @SkipAsAnonymousMethod
  default GraphTraversal<S, Edge> addE(final YTDBDomainObjectObjectOutToken<?> out) {
    var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
    return ytdbGraphTraversal.addE(out.name());
  }

  /// Removes one or several {@link Property}s from an element.
  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> removeProperty(String key, String... otherKeys) {

    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("The provided name must not be null or blank");
    }
    final var allKeys = new HashSet<String>();
    allKeys.add(key);

    if (otherKeys != null) {
      for (var k : otherKeys) {
        if (k == null || k.isBlank()) {
          throw new IllegalArgumentException("The provided name must not be null or blank");
        }
        allKeys.add(k);
      }
    }

    return call(
        YTDBRemovePropertyService.NAME,
        Map.of(YTDBRemovePropertyService.PROPERTIES, allKeys)
    );
  }

  @Override
  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> from(String fromStepLabel) {
    if (fromStepLabel != null && !fromStepLabel.isBlank() && fromStepLabel.charAt(0) == '#') {
      var rid = RID.of(fromStepLabel);
      var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
      return ytdbGraphTraversal.from(__.V(rid));
    }

    return GraphTraversal.Admin.super.from(fromStepLabel);
  }


  @SkipAsAnonymousMethod
  @Override
  default GraphTraversal<S, E> to(String toStepLabel) {
    if (toStepLabel != null && !toStepLabel.isBlank() && toStepLabel.charAt(0) == '#') {
      var rid = RID.of(toStepLabel);
      var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
      return ytdbGraphTraversal.to(__.V(rid));
    }

    return GraphTraversal.Admin.super.to(toStepLabel);
  }
}
