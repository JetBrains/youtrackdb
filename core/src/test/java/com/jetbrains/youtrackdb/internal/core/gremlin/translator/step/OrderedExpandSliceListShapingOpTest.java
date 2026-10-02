package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.junit.Test;

/**
 * Unit tests for {@link OrderedExpandSliceListShapingOp}: constructor guards, expand/filter/cut,
 * drop-on-absent {@code values}, and iterator contract ({@code hasNext}/{@code next}).
 */
public class OrderedExpandSliceListShapingOpTest extends GraphBaseTest {

  /**
   * Negative {@code skip} is rejected at construction — the recogniser never builds this shape, so
   * the constructor must refuse rather than emit a silently wrong cut.
   */
  @Test
  public void constructor_rejectsNegativeSkip() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> new OrderedExpandSliceListShapingOp(
                Direction.OUT, new String[] {"knows"}, -1, 1, null, List.of()))
        .withMessageContaining("skip");
  }

  /**
   * {@code limit < -1} is rejected; {@code -1} means unbounded and is the only negative sentinel the
   * expand-after-source-slice path uses.
   */
  @Test
  public void constructor_rejectsLimitBelowMinusOne() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> new OrderedExpandSliceListShapingOp(
                Direction.OUT, new String[] {"knows"}, 0, -2, null, List.of()))
        .withMessageContaining("limit");
  }

  /** Accessors round-trip construction args, including a defensive clone of edge labels. */
  @Test
  public void accessors_roundTripConstructionArgs() {
    var labels = new String[] {"knows", "likes"};
    var containers = List.of(new HasContainer("name", P.eq("Ann")));
    var op =
        new OrderedExpandSliceListShapingOp(Direction.IN, labels, 1, 3, "age", containers);

    assertThat(op.direction()).isEqualTo(Direction.IN);
    assertThat(op.edgeLabels()).containsExactly("knows", "likes");
    assertThat(op.edgeLabels()).isNotSameAs(labels);
    assertThat(op.skip()).isEqualTo(1);
    assertThat(op.limit()).isEqualTo(3);
    assertThat(op.propertyKey()).isEqualTo("age");
    assertThat(op.hasContainers()).containsExactlyElementsOf(containers);
  }

  /** Null edge labels mean "all edge types" and stay null through the accessor. */
  @Test
  public void accessors_nullEdgeLabelsStayNull() {
    var op =
        new OrderedExpandSliceListShapingOp(Direction.OUT, null, 0, -1, null, List.of());
    assertThat(op.edgeLabels()).isNull();
  }

  /**
   * Labelled expand walks {@code vertex.vertices(direction, labels)} and applies skip/limit on the
   * flat neighbour stream.
   */
  @Test
  public void apply_labelledExpand_appliesSkipAndLimit() {
    var n1 = vertexWithName("n1");
    var n2 = vertexWithName("n2");
    var n3 = vertexWithName("n3");
    var source = mock(Vertex.class);
    when(source.vertices(eq(Direction.OUT), eq("knows")))
        .thenReturn(List.of(n1, n2, n3).iterator());

    var op =
        new OrderedExpandSliceListShapingOp(
            Direction.OUT, new String[] {"knows"}, 1, 1, null, List.of());

    assertThat(drain(op.apply(List.<Object>of(source).iterator()))).containsExactly(n2);
  }

  /**
   * Null edge labels call the all-edges overload {@code vertices(direction)} rather than the labelled
   * one — the expand-after-source-slice spelling of bare {@code out()}.
   */
  @Test
  public void apply_nullEdgeLabels_usesAllEdgesExpand() {
    var neighbour = vertexWithName("n");
    var source = mock(Vertex.class);
    when(source.vertices(Direction.OUT)).thenReturn(List.of(neighbour).iterator());

    var op =
        new OrderedExpandSliceListShapingOp(Direction.OUT, null, 0, -1, null, List.of());

    assertThat(drain(op.apply(List.<Object>of(source).iterator()))).containsExactly(neighbour);
  }

  /**
   * With a property key, an absent property still consumes the limit quota (native {@code
   * limit(n).values(k)}), then drops without emitting.
   */
  @Test
  public void apply_absentProperty_consumesQuotaWithoutEmitting() {
    var missing = vertexWithoutProperty("age");
    var present = vertexWithProperty("age", 42);
    var source = mock(Vertex.class);
    when(source.vertices(eq(Direction.OUT), eq("knows")))
        .thenReturn(List.of(missing, present).iterator());

    var op =
        new OrderedExpandSliceListShapingOp(
            Direction.OUT, new String[] {"knows"}, 0, 1, "age", List.of());

    assertThat(drain(op.apply(List.<Object>of(source).iterator())))
        .as("absent age consumed the quota; present neighbour is past the cut")
        .isEmpty();
  }

  /** Present property projection emits the value, not the vertex. */
  @Test
  public void apply_presentProperty_emitsValue() {
    var neighbour = vertexWithProperty("age", 7);
    var source = mock(Vertex.class);
    when(source.vertices(eq(Direction.OUT), eq("knows")))
        .thenReturn(List.of(neighbour).iterator());

    var op =
        new OrderedExpandSliceListShapingOp(
            Direction.OUT, new String[] {"knows"}, 0, -1, "age", List.of());

    assertThat(drain(op.apply(List.<Object>of(source).iterator()))).containsExactly(7);
  }

  /** A projected String raises the same cast error as native VertexStep. */
  @Test
  public void apply_nonVertexSource_throwsClassCast() {
    var op =
        new OrderedExpandSliceListShapingOp(
            Direction.OUT, new String[] {"knows"}, 0, 1, null, List.of());
    var shaped = op.apply(List.<Object>of("not-a-vertex").iterator());

    assertThatExceptionOfType(ClassCastException.class).isThrownBy(shaped::hasNext);
  }

  /** A zero-width cut still attempts the first expansion before its range rejects the row. */
  @Test
  public void apply_zeroWidthCut_castsNonVertexButLeavesEmptyInputEmpty() {
    var op = new OrderedExpandSliceListShapingOp(
        Direction.OUT, new String[] {"knows"}, 1, 0, null, List.of());

    assertThatExceptionOfType(ClassCastException.class)
        .isThrownBy(() -> op.apply(List.<Object>of("scalar").iterator()).hasNext());
    assertThat(drain(op.apply(Collections.emptyIterator()))).isEmpty();
  }

  /** A vertex source may be expanded by an empty cut without emitting its neighbour. */
  @Test
  public void apply_zeroWidthCut_vertexSourceEmitsNothing() {
    var source = mock(Vertex.class);
    var target = vertexWithName("target");
    when(source.vertices(eq(Direction.OUT), eq("knows")))
        .thenReturn(List.of(target).iterator());
    var op = new OrderedExpandSliceListShapingOp(
        Direction.OUT, new String[] {"knows"}, 0, 0, null, List.of());

    assertThat(drain(op.apply(List.<Object>of(source).iterator()))).isEmpty();
  }

  /** A reached cut still pulls once more, so a later invalid source cannot escape the cast. */
  @Test
  public void apply_positiveCut_pullsPastQuotaLikeNativeFilterStep() {
    var source = mock(Vertex.class);
    var neighbour = vertexWithName("target");
    when(source.vertices(eq(Direction.OUT), eq("knows")))
        .thenReturn(List.of(neighbour).iterator());
    var op = new OrderedExpandSliceListShapingOp(
        Direction.OUT, new String[] {"knows"}, 0, 1, null, List.of());
    var shaped = op.apply(List.<Object>of(source, "scalar").iterator());

    assertThat(shaped.next()).isSameAs(neighbour);
    assertThatExceptionOfType(ClassCastException.class).isThrownBy(shaped::hasNext);
  }

  /** Native VertexStep dereferences a null payload after the Vertex cast. */
  @Test
  public void apply_nullSource_throwsNullPointer() {
    var payloads = new ArrayList<>();
    payloads.add(null);
    var op =
        new OrderedExpandSliceListShapingOp(
            Direction.OUT, new String[] {"knows"}, 0, 1, null, List.of());
    var shaped = op.apply(payloads.iterator());

    assertThatExceptionOfType(NullPointerException.class).isThrownBy(shaped::hasNext);
  }

  /**
   * {@code next()} without a prior successful {@code hasNext()} still arms the buffer; an empty
   * stream throws {@link NoSuchElementException}.
   */
  @Test
  public void apply_nextOnEmpty_throwsNoSuchElement() {
    var op =
        new OrderedExpandSliceListShapingOp(
            Direction.OUT, new String[] {"knows"}, 0, 1, null, List.of());
    var shaped = op.apply(Collections.emptyIterator());

    assertThat(shaped.hasNext()).isFalse();
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(shaped::next);
  }

  /**
   * Repeated {@code hasNext()} while a row is buffered stays true without advancing; after {@code
   * next()} drains the buffer, a second empty probe reports exhausted.
   */
  @Test
  public void apply_hasNext_isIdempotentWhileBuffered() {
    var neighbour = vertexWithName("n");
    var source = mock(Vertex.class);
    when(source.vertices(eq(Direction.OUT), eq("knows")))
        .thenReturn(List.of(neighbour).iterator());
    var op =
        new OrderedExpandSliceListShapingOp(
            Direction.OUT, new String[] {"knows"}, 0, 1, null, List.of());
    var shaped = op.apply(List.<Object>of(source).iterator());

    assertThat(shaped.hasNext()).isTrue();
    assertThat(shaped.hasNext()).as("buffered probe must not re-expand").isTrue();
    assertThat(shaped.next()).isSameAs(neighbour);
    assertThat(shaped.hasNext()).isFalse();
    assertThat(shaped.hasNext()).as("exhausted probe stays false").isFalse();
  }

  /** Polymorphic labels use the neighbour's superclass, not its concrete TinkerPop label. */
  @Test
  public void apply_parentLabelUsesNativePolymorphism() {
    var parent = session.createVertexClass("OpParent");
    session.getSchema().createClass("OpChild", parent);
    var source = graph.addVertex(T.label, "OpParent", "name", "Source");
    var child = graph.addVertex(T.label, "OpChild", "name", "Child");
    source.addEdge("knows", child);
    graph.tx().commit();
    var containers = List.of(new HasContainer(T.label.getAccessor(), P.eq("OpParent")));
    assertThat(HasContainer.testAll(child, containers)).isFalse();

    var poly = new OrderedExpandSliceListShapingOp(
        Direction.OUT, new String[] {"knows"}, 0, -1, null, containers, true);
    var exact = new OrderedExpandSliceListShapingOp(
        Direction.OUT, new String[] {"knows"}, 0, -1, null, containers, false);
    assertThat(drain(poly.apply(List.<Object>of(source).iterator()))).containsExactly(child);
    assertThat(drain(exact.apply(List.<Object>of(source).iterator()))).isEmpty();
  }

  /** Labels OR inside a HasStep, while collation-aware properties AND with that label result. */
  @Test
  public void apply_multiLabelAndCollationCombineLikeNative() {
    var parent = session.createVertexClass("OpParent");
    parent.createProperty("nickname", PropertyType.STRING).setCollate("ci");
    session.getSchema().createClass("OpChild", parent);
    session.createVertexClass("OpOther");
    var source = graph.addVertex(T.label, "OpParent", "name", "Source");
    var child = graph.addVertex(T.label, "OpChild", "nickname", "MiXeD");
    var other = graph.addVertex(T.label, "OpOther", "nickname", "mixed");
    source.addEdge("knows", child);
    source.addEdge("knows", other);
    graph.tx().commit();
    var containers = List.of(
        new HasContainer(T.label.getAccessor(), P.within("OpParent", "OpOther")),
        new HasContainer("nickname", P.eq("mixed")));
    assertThat(HasContainer.testAll(child, containers)).isFalse();

    var poly = new OrderedExpandSliceListShapingOp(
        Direction.OUT, new String[] {"knows"}, 0, -1, null, containers, true);
    var exact = new OrderedExpandSliceListShapingOp(
        Direction.OUT, new String[] {"knows"}, 0, -1, null, containers, false);
    assertThat(drain(poly.apply(List.<Object>of(source).iterator())))
        .containsExactlyInAnyOrder(child, other);
    assertThat(drain(exact.apply(List.<Object>of(source).iterator()))).containsExactly(other);
  }

  /** A collated property and an id filter both apply, with the id remaining exact. */
  @Test
  public void apply_collatedPropertyAndId_useNativeCombination() {
    var parent = session.createVertexClass("OpParent");
    parent.createProperty("nickname", PropertyType.STRING).setCollate("ci");
    var source = graph.addVertex(T.label, "OpParent", "name", "Source");
    var match = graph.addVertex(T.label, "OpParent", "nickname", "MiXeD");
    var wrongId = graph.addVertex(T.label, "OpParent", "nickname", "MiXeD");
    source.addEdge("knows", wrongId);
    source.addEdge("knows", match);
    graph.tx().commit();
    var containers = List.of(
        new HasContainer(T.id.getAccessor(), P.eq(match.id())),
        new HasContainer("nickname", P.eq("mixed")));
    assertThat(HasContainer.testAll(match, containers)).isFalse();
    var op = new OrderedExpandSliceListShapingOp(
        Direction.OUT, new String[] {"knows"}, 0, -1, null, containers, false);
    assertThat(drain(op.apply(List.<Object>of(source).iterator()))).containsExactly(match);
  }

  private static Vertex stubVertexForHas(String key, String value) {
    var vertex = mock(Vertex.class);
    when(vertex.keys()).thenReturn(java.util.Set.of(key));
    when(vertex.values(key)).thenAnswer(inv -> List.of(value).iterator());
    when(vertex.value(key)).thenReturn(value);
    when(vertex.property(key)).thenAnswer(inv -> {
      @SuppressWarnings("unchecked")
      VertexProperty<Object> property = mock(VertexProperty.class);
      when(property.isPresent()).thenReturn(true);
      when(property.value()).thenReturn(value);
      return property;
    });
    return vertex;
  }

  private static Vertex vertexWithName(String name) {
    return stubVertexForHas("name", name);
  }

  private static Vertex vertexWithProperty(String key, Object value) {
    var vertex = mock(Vertex.class);
    @SuppressWarnings("unchecked")
    VertexProperty<Object> property = mock(VertexProperty.class);
    when(property.isPresent()).thenReturn(true);
    when(property.value()).thenReturn(value);
    when(vertex.property(key)).thenReturn(property);
    when(vertex.keys()).thenReturn(java.util.Set.of(key));
    return vertex;
  }

  private static Vertex vertexWithoutProperty(String key) {
    var vertex = mock(Vertex.class);
    @SuppressWarnings("unchecked")
    VertexProperty<Object> property = mock(VertexProperty.class);
    when(property.isPresent()).thenReturn(false);
    when(vertex.property(key)).thenReturn(property);
    when(vertex.keys()).thenReturn(java.util.Set.of());
    return vertex;
  }

  private static List<Object> drain(Iterator<Object> iterator) {
    var out = new ArrayList<>();
    while (iterator.hasNext()) {
      out.add(iterator.next());
    }
    return out;
  }
}
