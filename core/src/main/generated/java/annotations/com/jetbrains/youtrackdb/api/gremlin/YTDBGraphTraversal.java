package com.jetbrains.youtrackdb.api.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectObjectOutToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectPToken;
import java.lang.Boolean;
import java.lang.Comparable;
import java.lang.Double;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Number;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.tinkerpop.gremlin.process.computer.VertexProgram;
import org.apache.tinkerpop.gremlin.process.traversal.DT;
import org.apache.tinkerpop.gremlin.process.traversal.GType;
import org.apache.tinkerpop.gremlin.process.traversal.Merge;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.GValue;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Tree;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalMetrics;
import org.apache.tinkerpop.gremlin.structure.Column;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

public interface YTDBGraphTraversal<S, E> extends YTDBGraphTraversalDSL<S, E> {
  @Override
  default YTDBGraphTraversal<S, Vertex> addSchemaClass(String className) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addSchemaClass(className);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addSchemaClass(String className, String... superClasses) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addSchemaClass(className,superClasses);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addAbstractSchemaClass(String className) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addAbstractSchemaClass(className);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addAbstractSchemaClass(String className,
      String... superClasses) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addAbstractSchemaClass(className,superClasses);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addStateFullEdgeClass(String className) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addStateFullEdgeClass(className);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addSchemaProperty(String propertyName,
      PropertyType propertyType) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addSchemaProperty(propertyName,propertyType);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addSchemaProperty(String propertyName,
      PropertyType propertyType, PropertyType linkedType) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addSchemaProperty(propertyName,propertyType,linkedType);
  }

  @Override
  default YTDBGraphTraversal<S, String> schemaClassName() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.schemaClassName();
  }

  @Override
  default YTDBGraphTraversal<S, Boolean> isAbstractClass() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.isAbstractClass();
  }

  @Override
  default YTDBGraphTraversal<S, Boolean> isClassInStrictMode() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.isClassInStrictMode();
  }

  @Override
  default YTDBGraphTraversal<S, E> switchStrictModeOnClass() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.switchStrictModeOnClass();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> values(final YTDBDomainObjectPToken<?>... pTokens) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.values(pTokens);
  }

  @Override
  default YTDBGraphTraversal<S, E> property(final YTDBDomainObjectPToken<?> pToken,
      final Object value, final Object... pTokenValues) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.property(pToken,value,pTokenValues);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final YTDBDomainObjectPToken<?> pToken,
      final P<?> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(pToken,predicate);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> addE(final YTDBDomainObjectObjectOutToken<?> out) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addE(out);
  }

  @Override
  default YTDBGraphTraversal<S, E> removeProperty(String key, String... otherKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.removeProperty(key,otherKeys);
  }

  @Override
  default YTDBGraphTraversal<S, E> from(String fromStepLabel) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.from(fromStepLabel);
  }

  @Override
  default YTDBGraphTraversal<S, E> to(String toStepLabel) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.to(toStepLabel);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> map(final Function<Traverser<E>, E2> function) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.map(function);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> map(final Traversal<?, E2> mapTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.map(mapTraversal);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> flatMap(
      final Function<Traverser<E>, Iterator<E2>> function) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.flatMap(function);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> flatMap(final Traversal<?, E2> flatMapTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.flatMap(flatMapTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, Object> id() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.id();
  }

  @Override
  default YTDBGraphTraversal<S, String> label() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.label();
  }

  @Override
  default YTDBGraphTraversal<S, E> identity() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.identity();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> constant(final E2 e) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.constant(e);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> V(final Object... vertexIdsOrElements) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.V(vertexIdsOrElements);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> E(final Object... edgeIdsOrElements) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.E(edgeIdsOrElements);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> to(final Direction direction) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.to(direction);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> to(final Direction direction, final String... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.to(direction,edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> to(final Direction direction,
      final GValue<String>... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.to(direction,edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> out() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.out();
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> out(final String... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.out(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> out(final GValue<String>... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.out(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> in() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.in();
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> in(final String... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.in(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> in(final GValue<String>... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.in(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> both() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.both();
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> both(final String... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.both(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> both(final GValue<String>... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.both(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> toE(final Direction direction) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.toE(direction);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> toE(final Direction direction, final String... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.toE(direction,edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> toE(final Direction direction,
      final GValue<String>... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.toE(direction,edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> outE() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.outE();
  }

  @Override
  default YTDBGraphTraversal<S, Edge> outE(final String... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.outE(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> outE(final GValue<String>... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.outE(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> inE() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.inE();
  }

  @Override
  default YTDBGraphTraversal<S, Edge> inE(final String... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.inE(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> inE(final GValue<String>... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.inE(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> bothE() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.bothE();
  }

  @Override
  default YTDBGraphTraversal<S, Edge> bothE(final String... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.bothE(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> bothE(final GValue<String>... edgeLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.bothE(edgeLabels);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> toV(final Direction direction) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.toV(direction);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> inV() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.inV();
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> outV() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.outV();
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> bothV() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.bothV();
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> otherV() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.otherV();
  }

  @Override
  default YTDBGraphTraversal<S, E> order() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.order();
  }

  @Override
  default YTDBGraphTraversal<S, E> order(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.order(scope);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, ? extends Property<E2>> properties(
      final String... propertyKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.properties(propertyKeys);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> values(final String... propertyKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.values(propertyKeys);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, Map<String, E2>> propertyMap(final String... propertyKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.propertyMap(propertyKeys);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, Map<Object, E2>> elementMap(final String... propertyKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.elementMap(propertyKeys);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, Map<Object, E2>> valueMap(final String... propertyKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.valueMap(propertyKeys);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, Map<Object, E2>> valueMap(final boolean includeTokens,
      final String... propertyKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.valueMap(includeTokens,propertyKeys);
  }

  @Override
  default YTDBGraphTraversal<S, String> key() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.key();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> value() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.value();
  }

  @Override
  default YTDBGraphTraversal<S, Path> path() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.path();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, Map<String, E2>> match(
      final Traversal<?, ?>... matchTraversals) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.match(matchTraversals);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> sack() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.sack();
  }

  @Override
  default YTDBGraphTraversal<S, Integer> loops() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.loops();
  }

  @Override
  default YTDBGraphTraversal<S, Integer> loops(final String loopName) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.loops(loopName);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, Map<String, E2>> project(final String projectKey,
      final String... otherProjectKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.project(projectKey,otherProjectKeys);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, Map<String, E2>> select(final Pop pop, final String selectKey1,
      final String selectKey2, String... otherSelectKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.select(pop,selectKey1,selectKey2,otherSelectKeys);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, Map<String, E2>> select(final String selectKey1,
      final String selectKey2, String... otherSelectKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.select(selectKey1,selectKey2,otherSelectKeys);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> select(final Pop pop, final String selectKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.select(pop,selectKey);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> select(final String selectKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.select(selectKey);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> select(final Pop pop,
      final Traversal<S, E2> keyTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.select(pop,keyTraversal);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> select(final Traversal<S, E2> keyTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.select(keyTraversal);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, Collection<E2>> select(final Column column) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.select(column);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> unfold() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.unfold();
  }

  @Override
  default YTDBGraphTraversal<S, List<E>> fold() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.fold();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> fold(final E2 seed,
      final BiFunction<E2, E, E2> foldFunction) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.fold(seed,foldFunction);
  }

  @Override
  default YTDBGraphTraversal<S, Long> count() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.count();
  }

  @Override
  default YTDBGraphTraversal<S, Long> count(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.count(scope);
  }

  @Override
  default <E2 extends Number> YTDBGraphTraversal<S, E2> sum() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.sum();
  }

  @Override
  default <E2 extends Number> YTDBGraphTraversal<S, E2> sum(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.sum(scope);
  }

  @Override
  default <E2 extends Comparable> YTDBGraphTraversal<S, E2> max() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.max();
  }

  @Override
  default <E2 extends Comparable> YTDBGraphTraversal<S, E2> max(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.max(scope);
  }

  @Override
  default <E2 extends Comparable> YTDBGraphTraversal<S, E2> min() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.min();
  }

  @Override
  default <E2 extends Comparable> YTDBGraphTraversal<S, E2> min(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.min(scope);
  }

  @Override
  default <E2 extends Number> YTDBGraphTraversal<S, E2> mean() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mean();
  }

  @Override
  default <E2 extends Number> YTDBGraphTraversal<S, E2> mean(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mean(scope);
  }

  @Override
  default <K, V> YTDBGraphTraversal<S, Map<K, V>> group() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.group();
  }

  @Override
  default <K> YTDBGraphTraversal<S, Map<K, Long>> groupCount() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.groupCount();
  }

  @Override
  default YTDBGraphTraversal<S, Tree> tree() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.tree();
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addV(final String vertexLabel) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addV(vertexLabel);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addV(final Traversal<?, String> vertexLabelTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addV(vertexLabelTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addV(final GValue<String> vertexLabel) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addV(vertexLabel);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> addV() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addV();
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> mergeV() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mergeV();
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> mergeV(final Map<Object, Object> searchCreate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mergeV(searchCreate);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> mergeV(
      final Traversal<?, Map<Object, Object>> searchCreate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mergeV(searchCreate);
  }

  @Override
  default YTDBGraphTraversal<S, Vertex> mergeV(final GValue<Map<Object, Object>> searchCreate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mergeV(searchCreate);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> mergeE() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mergeE();
  }

  @Override
  default YTDBGraphTraversal<S, Edge> mergeE(final Map<Object, Object> searchCreate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mergeE(searchCreate);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> mergeE(final Traversal<?, Map<Object, Object>> searchCreate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mergeE(searchCreate);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> mergeE(final GValue<Map<Object, Object>> searchCreate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.mergeE(searchCreate);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> addE(final String edgeLabel) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addE(edgeLabel);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> addE(final Traversal<?, String> edgeLabelTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addE(edgeLabelTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> addE(final GValue<String> edgeLabel) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.addE(edgeLabel);
  }

  @Override
  default YTDBGraphTraversal<S, E> from(final GValue<?> fromVertex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.from(fromVertex);
  }

  @Override
  default YTDBGraphTraversal<S, E> from(final Vertex fromVertex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.from(fromVertex);
  }

  @Override
  default YTDBGraphTraversal<S, E> to(final GValue<?> toVertex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.to(toVertex);
  }

  @Override
  default YTDBGraphTraversal<S, E> to(final Traversal<?, ?> toVertex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.to(toVertex);
  }

  @Override
  default YTDBGraphTraversal<S, E> from(final Traversal<?, ?> fromVertex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.from(fromVertex);
  }

  @Override
  default YTDBGraphTraversal<S, E> to(final Vertex toVertex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.to(toVertex);
  }

  @Override
  default YTDBGraphTraversal<S, Double> math(final String expression) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.math(expression);
  }

  @Override
  default YTDBGraphTraversal<S, Element> element() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.element();
  }

  @Override
  default <E> YTDBGraphTraversal<S, E> call(final String service) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.call(service);
  }

  @Override
  default <E> YTDBGraphTraversal<S, E> call(final String service, final Map params) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.call(service,params);
  }

  @Override
  default <E> YTDBGraphTraversal<S, E> call(final String service, final GValue<Map<?, ?>> params) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.call(service,params);
  }

  @Override
  default <E> YTDBGraphTraversal<S, E> call(final String service,
      final Traversal<?, Map<?, ?>> childTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.call(service,childTraversal);
  }

  @Override
  default <E> YTDBGraphTraversal<S, E> call(final String service, final Map params,
      final Traversal<?, Map<?, ?>> childTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.call(service,params,childTraversal);
  }

  @Override
  default <E> YTDBGraphTraversal<S, E> call(final String service, final GValue<Map<?, ?>> params,
      final Traversal<S, Map<?, ?>> childTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.call(service,params,childTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, String> concat(final Traversal<?, String> concatTraversal,
      final Traversal<?, String>... otherConcatTraversals) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.concat(concatTraversal,otherConcatTraversals);
  }

  @Override
  default YTDBGraphTraversal<S, String> concat(final String... concatStrings) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.concat(concatStrings);
  }

  @Override
  default YTDBGraphTraversal<S, String> asString() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.asString();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> asString(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.asString(scope);
  }

  @Override
  default YTDBGraphTraversal<S, Integer> length() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.length();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> length(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.length(scope);
  }

  @Override
  default YTDBGraphTraversal<S, String> toLower() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.toLower();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> toLower(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.toLower(scope);
  }

  @Override
  default YTDBGraphTraversal<S, String> toUpper() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.toUpper();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> toUpper(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.toUpper(scope);
  }

  @Override
  default YTDBGraphTraversal<S, String> trim() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.trim();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> trim(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.trim(scope);
  }

  @Override
  default YTDBGraphTraversal<S, String> lTrim() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.lTrim();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> lTrim(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.lTrim(scope);
  }

  @Override
  default YTDBGraphTraversal<S, String> rTrim() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.rTrim();
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> rTrim(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.rTrim(scope);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> reverse() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.reverse();
  }

  @Override
  default YTDBGraphTraversal<S, String> replace(final String oldChar, final String newChar) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.replace(oldChar,newChar);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> replace(final Scope scope, final String oldChar,
      final String newChar) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.replace(scope,oldChar,newChar);
  }

  @Override
  default YTDBGraphTraversal<S, List<String>> split(final String separator) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.split(separator);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, List<E2>> split(final Scope scope, final String separator) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.split(scope,separator);
  }

  @Override
  default YTDBGraphTraversal<S, String> substring(final int startIndex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.substring(startIndex);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> substring(final Scope scope, final int startIndex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.substring(scope,startIndex);
  }

  @Override
  default YTDBGraphTraversal<S, String> substring(final int startIndex, final int endIndex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.substring(startIndex,endIndex);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> substring(final Scope scope, final int startIndex,
      final int endIndex) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.substring(scope,startIndex,endIndex);
  }

  @Override
  default YTDBGraphTraversal<S, String> format(final String format) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.format(format);
  }

  @Override
  default YTDBGraphTraversal<S, Boolean> asBool() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.asBool();
  }

  @Override
  default YTDBGraphTraversal<S, OffsetDateTime> asDate() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.asDate();
  }

  @Override
  default YTDBGraphTraversal<S, Number> asNumber() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.asNumber();
  }

  @Override
  default YTDBGraphTraversal<S, Number> asNumber(final GType typeToken) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.asNumber(typeToken);
  }

  @Override
  default YTDBGraphTraversal<S, OffsetDateTime> dateAdd(final DT dateToken, final int value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.dateAdd(dateToken,value);
  }

  @Override
  default YTDBGraphTraversal<S, Long> dateDiff(final Date value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.dateDiff(value);
  }

  @Override
  default YTDBGraphTraversal<S, Long> dateDiff(final OffsetDateTime value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.dateDiff(value);
  }

  @Override
  default YTDBGraphTraversal<S, Long> dateDiff(final Traversal<?, ?> dateTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.dateDiff(dateTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, Set<?>> difference(final Object values) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.difference(values);
  }

  @Override
  default YTDBGraphTraversal<S, Set<?>> disjunct(final Object values) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.disjunct(values);
  }

  @Override
  default YTDBGraphTraversal<S, Set<?>> intersect(final Object values) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.intersect(values);
  }

  @Override
  default YTDBGraphTraversal<S, String> conjoin(final String delimiter) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.conjoin(delimiter);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> merge(final Object values) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.merge(values);
  }

  @Override
  default YTDBGraphTraversal<S, List<?>> combine(final Object values) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.combine(values);
  }

  @Override
  default YTDBGraphTraversal<S, List<List<?>>> product(final Object values) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.product(values);
  }

  @Override
  default YTDBGraphTraversal<S, E> filter(final Predicate<Traverser<E>> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.filter(predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> filter(final Traversal<?, ?> filterTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.filter(filterTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, E> discard() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.discard();
  }

  @Override
  default YTDBGraphTraversal<S, E> or(final Traversal<?, ?>... orTraversals) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.or(orTraversals);
  }

  @Override
  default YTDBGraphTraversal<S, E> and(final Traversal<?, ?>... andTraversals) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.and(andTraversals);
  }

  @Override
  default YTDBGraphTraversal<S, E> inject(final E... injections) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.inject(injections);
  }

  @Override
  default YTDBGraphTraversal<S, E> dedup(final Scope scope, final String... dedupLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.dedup(scope,dedupLabels);
  }

  @Override
  default YTDBGraphTraversal<S, E> dedup(final String... dedupLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.dedup(dedupLabels);
  }

  @Override
  default YTDBGraphTraversal<S, E> where(final String startKey, final P<String> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.where(startKey,predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> where(final P<String> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.where(predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> where(final Traversal<?, ?> whereTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.where(whereTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final String propertyKey, final P<?> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(propertyKey,predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final T accessor, final P<?> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(accessor,predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final String propertyKey, final Object value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(propertyKey,value);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final T accessor, final Object value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(accessor,value);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final String label, final String propertyKey,
      final P<?> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(label,propertyKey,predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final String label, final String propertyKey,
      final Object value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(label,propertyKey,value);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final String propertyKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(propertyKey);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final GValue<String> label, final String propertyKey,
      final Object value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(label,propertyKey,value);
  }

  @Override
  default YTDBGraphTraversal<S, E> has(final GValue<String> label, final String propertyKey,
      final P<?> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.has(label,propertyKey,predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasNot(final String propertyKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasNot(propertyKey);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasLabel(final String label, final String... otherLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasLabel(label,otherLabels);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasLabel(final P<String> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasLabel(predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasLabel(final GValue<String> label,
      final GValue<String>... otherLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasLabel(label,otherLabels);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasId(final Object id, final Object... otherIds) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasId(id,otherIds);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasId(final P<?> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasId(predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasKey(final String label, final String... otherLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasKey(label,otherLabels);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasKey(final P<String> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasKey(predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasValue(final Object value, final Object... otherValues) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasValue(value,otherValues);
  }

  @Override
  default YTDBGraphTraversal<S, E> hasValue(final P<?> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.hasValue(predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> is(final P<E> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.is(predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> is(final Object value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.is(value);
  }

  @Override
  default YTDBGraphTraversal<S, E> not(final Traversal<?, ?> notTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.not(notTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, E> coin(final double probability) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.coin(probability);
  }

  @Override
  default YTDBGraphTraversal<S, E> range(final long low, final long high) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.range(low,high);
  }

  @Override
  default YTDBGraphTraversal<S, E> range(final GValue<Long> low, final GValue<Long> high) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.range(low,high);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> range(final Scope scope, final long low, final long high) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.range(scope,low,high);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> range(final Scope scope, final GValue<Long> low,
      final GValue<Long> high) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.range(scope,low,high);
  }

  @Override
  default YTDBGraphTraversal<S, E> limit(final long limit) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.limit(limit);
  }

  @Override
  default YTDBGraphTraversal<S, E> limit(final GValue<Long> limit) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.limit(limit);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> limit(final Scope scope, final long limit) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.limit(scope,limit);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> limit(final Scope scope, final GValue<Long> limit) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.limit(scope,limit);
  }

  @Override
  default YTDBGraphTraversal<S, E> tail() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.tail();
  }

  @Override
  default YTDBGraphTraversal<S, E> tail(final long limit) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.tail(limit);
  }

  @Override
  default YTDBGraphTraversal<S, E> tail(final GValue<Long> limit) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.tail(limit);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> tail(final Scope scope) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.tail(scope);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> tail(final Scope scope, final long limit) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.tail(scope,limit);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> tail(final Scope scope, final GValue<Long> limit) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.tail(scope,limit);
  }

  @Override
  default YTDBGraphTraversal<S, E> skip(final long skip) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.skip(skip);
  }

  @Override
  default YTDBGraphTraversal<S, E> skip(final GValue<Long> skip) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.skip(skip);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> skip(final Scope scope, final long skip) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.skip(scope,skip);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> skip(final Scope scope, final GValue<Long> skip) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.skip(scope,skip);
  }

  @Override
  default YTDBGraphTraversal<S, E> timeLimit(final long timeLimit) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.timeLimit(timeLimit);
  }

  @Override
  default YTDBGraphTraversal<S, E> simplePath() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.simplePath();
  }

  @Override
  default YTDBGraphTraversal<S, E> cyclicPath() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.cyclicPath();
  }

  @Override
  default YTDBGraphTraversal<S, E> sample(final int amountToSample) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.sample(amountToSample);
  }

  @Override
  default YTDBGraphTraversal<S, E> sample(final Scope scope, final int amountToSample) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.sample(scope,amountToSample);
  }

  @Override
  default YTDBGraphTraversal<S, E> drop() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.drop();
  }

  @Override
  default <S2> YTDBGraphTraversal<S, E> all(final P<S2> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.all(predicate);
  }

  @Override
  default <S2> YTDBGraphTraversal<S, E> any(final P<S2> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.any(predicate);
  }

  @Override
  default <S2> YTDBGraphTraversal<S, E> none(final P<S2> predicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.none(predicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> sideEffect(final Consumer<Traverser<E>> consumer) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.sideEffect(consumer);
  }

  @Override
  default YTDBGraphTraversal<S, E> sideEffect(final Traversal<?, ?> sideEffectTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.sideEffect(sideEffectTraversal);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> cap(final String sideEffectKey,
      final String... sideEffectKeys) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.cap(sideEffectKey,sideEffectKeys);
  }

  @Override
  default YTDBGraphTraversal<S, Edge> subgraph(final String sideEffectKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.subgraph(sideEffectKey);
  }

  @Override
  default YTDBGraphTraversal<S, E> aggregate(final String sideEffectKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.aggregate(sideEffectKey);
  }

  @Override
  default YTDBGraphTraversal<S, E> group(final String sideEffectKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.group(sideEffectKey);
  }

  @Override
  default YTDBGraphTraversal<S, E> groupCount(final String sideEffectKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.groupCount(sideEffectKey);
  }

  @Override
  default YTDBGraphTraversal<S, E> fail() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.fail();
  }

  @Override
  default YTDBGraphTraversal<S, E> fail(final String message) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.fail(message);
  }

  @Override
  default YTDBGraphTraversal<S, E> tree(final String sideEffectKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.tree(sideEffectKey);
  }

  @Override
  default <V, U> YTDBGraphTraversal<S, E> sack(final BiFunction<V, U, V> sackOperator) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.sack(sackOperator);
  }

  @Override
  default YTDBGraphTraversal<S, E> profile(final String sideEffectKey) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.profile(sideEffectKey);
  }

  @Override
  default YTDBGraphTraversal<S, TraversalMetrics> profile() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.profile();
  }

  @Override
  default YTDBGraphTraversal<S, E> property(final VertexProperty.Cardinality cardinality,
      final Object key, final Object value, final Object... keyValues) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.property(cardinality,key,value,keyValues);
  }

  @Override
  default YTDBGraphTraversal<S, E> property(final Object key, final Object value,
      final Object... keyValues) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.property(key,value,keyValues);
  }

  @Override
  default YTDBGraphTraversal<S, E> property(final Map<Object, Object> value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.property(value);
  }

  @Override
  default <M, E2> YTDBGraphTraversal<S, E2> branch(final Traversal<?, M> branchTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.branch(branchTraversal);
  }

  @Override
  default <M, E2> YTDBGraphTraversal<S, E2> branch(final Function<Traverser<E>, M> function) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.branch(function);
  }

  @Override
  default <M, E2> YTDBGraphTraversal<S, E2> choose(final Traversal<?, M> choiceTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.choose(choiceTraversal);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> choose(final Traversal<?, ?> traversalPredicate,
      final Traversal<?, E2> trueChoice, final Traversal<?, E2> falseChoice) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.choose(traversalPredicate,trueChoice,falseChoice);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> choose(final Traversal<?, ?> traversalPredicate,
      final Traversal<?, E2> trueChoice) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.choose(traversalPredicate,trueChoice);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> choose(final T t) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.choose(t);
  }

  @Override
  default <M, E2> YTDBGraphTraversal<S, E2> choose(final Function<E, M> choiceFunction) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.choose(choiceFunction);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> choose(final Predicate<E> choosePredicate,
      final Traversal<?, E2> trueChoice, final Traversal<?, E2> falseChoice) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.choose(choosePredicate,trueChoice,falseChoice);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> choose(final P<E> choosePredicate,
      final Traversal<?, E2> trueChoice, final Traversal<?, E2> falseChoice) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.choose(choosePredicate,trueChoice,falseChoice);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> choose(final Predicate<E> choosePredicate,
      final Traversal<?, E2> trueChoice) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.choose(choosePredicate,trueChoice);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> choose(final P<E> choosePredicate,
      final Traversal<?, E2> trueChoice) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.choose(choosePredicate,trueChoice);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> optional(final Traversal<?, E2> optionalTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.optional(optionalTraversal);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> union(final Traversal<?, E2>... unionTraversals) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.union(unionTraversals);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> coalesce(final Traversal<?, E2>... coalesceTraversals) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.coalesce(coalesceTraversals);
  }

  @Override
  default YTDBGraphTraversal<S, E> repeat(final Traversal<?, E> repeatTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.repeat(repeatTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, E> repeat(final String loopName,
      final Traversal<?, E> repeatTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.repeat(loopName,repeatTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, E> emit(final Traversal<?, ?> emitTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.emit(emitTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, E> emit(final Predicate<Traverser<E>> emitPredicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.emit(emitPredicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> emit() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.emit();
  }

  @Override
  default YTDBGraphTraversal<S, E> until(final Traversal<?, ?> untilTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.until(untilTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, E> until(final Predicate<Traverser<E>> untilPredicate) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.until(untilPredicate);
  }

  @Override
  default YTDBGraphTraversal<S, E> times(final int maxLoops) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.times(maxLoops);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> local(final Traversal<?, E2> localTraversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.local(localTraversal);
  }

  @Override
  default YTDBGraphTraversal<S, E> pageRank() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.pageRank();
  }

  @Override
  default YTDBGraphTraversal<S, E> pageRank(final double alpha) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.pageRank(alpha);
  }

  @Override
  default YTDBGraphTraversal<S, E> peerPressure() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.peerPressure();
  }

  @Override
  default YTDBGraphTraversal<S, E> connectedComponent() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.connectedComponent();
  }

  @Override
  default YTDBGraphTraversal<S, Path> shortestPath() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.shortestPath();
  }

  @Override
  default YTDBGraphTraversal<S, E> program(final VertexProgram<?> vertexProgram) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.program(vertexProgram);
  }

  @Override
  default YTDBGraphTraversal<S, E> as(final String stepLabel, final String... stepLabels) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.as(stepLabel,stepLabels);
  }

  @Override
  default YTDBGraphTraversal<S, E> barrier() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.barrier();
  }

  @Override
  default YTDBGraphTraversal<S, E> barrier(final int maxBarrierSize) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.barrier(maxBarrierSize);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E2> index() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.index();
  }

  @Override
  default YTDBGraphTraversal<S, E> barrier(final Consumer<TraverserSet<Object>> barrierConsumer) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.barrier(barrierConsumer);
  }

  @Override
  default YTDBGraphTraversal<S, E> with(final String key) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.with(key);
  }

  @Override
  default YTDBGraphTraversal<S, E> with(final String key, final Object value) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.with(key,value);
  }

  @Override
  default YTDBGraphTraversal<S, E> by() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by();
  }

  @Override
  default YTDBGraphTraversal<S, E> by(final Traversal<?, ?> traversal) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by(traversal);
  }

  @Override
  default YTDBGraphTraversal<S, E> by(final T token) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by(token);
  }

  @Override
  default YTDBGraphTraversal<S, E> by(final String key) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by(key);
  }

  @Override
  default <V> YTDBGraphTraversal<S, E> by(final Function<V, Object> function) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by(function);
  }

  @Override
  default <V> YTDBGraphTraversal<S, E> by(final Traversal<?, ?> traversal,
      final Comparator<V> comparator) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by(traversal,comparator);
  }

  @Override
  default YTDBGraphTraversal<S, E> by(final Comparator<E> comparator) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by(comparator);
  }

  @Override
  default YTDBGraphTraversal<S, E> by(final Order order) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by(order);
  }

  @Override
  default <V> YTDBGraphTraversal<S, E> by(final String key, final Comparator<V> comparator) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by(key,comparator);
  }

  @Override
  default <U> YTDBGraphTraversal<S, E> by(final Function<U, Object> function,
      final Comparator comparator) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.by(function,comparator);
  }

  @Override
  default <M, E2> YTDBGraphTraversal<S, E> option(final M token,
      final Traversal<?, E2> traversalOption) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.option(token,traversalOption);
  }

  @Override
  default <M, E2> YTDBGraphTraversal<S, E> option(final M token, final Map<Object, Object> m) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.option(token,m);
  }

  @Override
  default <M, E2> YTDBGraphTraversal<S, E> option(final Merge merge, final Map<Object, Object> m,
      final VertexProperty.Cardinality cardinality) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.option(merge,m,cardinality);
  }

  @Override
  default <E2> YTDBGraphTraversal<S, E> option(final Traversal<?, E2> traversalOption) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.option(traversalOption);
  }

  @Override
  default <M, E2> YTDBGraphTraversal<S, E> option(final M token, final GValue<Map<?, ?>> m) {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.option(token,m);
  }

  @Override
  default YTDBGraphTraversal<S, E> read() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.read();
  }

  @Override
  default YTDBGraphTraversal<S, E> write() {
    return (YTDBGraphTraversal) YTDBGraphTraversalDSL.super.write();
  }

  @Override
  default YTDBGraphTraversal<S, E> iterate() {
    YTDBGraphTraversalDSL.super.iterate();
    return this;
  }
}
