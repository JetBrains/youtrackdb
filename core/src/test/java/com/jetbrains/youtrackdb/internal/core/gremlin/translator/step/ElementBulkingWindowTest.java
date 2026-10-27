package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.B_O_Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.junit.Test;

public class ElementBulkingWindowTest {

  /** Empty, singleton, duplicate, unique and mostly-unique feeds retain first-seen identities. */
  @Test
  public void groupsOnlyIdentityAndKeepsFirstRowAndBulk() {
    for (var ids : List.of(List.<Integer>of(), List.of(1), List.of(1, 2, 1, 1, 2),
        List.of(1, 2, 3), List.of(1, 2, 3, 4, 2))) {
      var rows = ids.stream().map(ElementBulkingWindowTest::row).toList();
      var window = window(rows, 2500);
      var groups = new ArrayList<ElementBulkingWindow.Group>();
      window.forEachRemaining(groups::add);
      var distinct = ids.stream().distinct().toList();
      assertThat(groups).hasSize(distinct.size());
      for (int i = 0; i < distinct.size(); i++) {
        int id = distinct.get(i);
        assertThat(groups.get(i).row).isSameAs(rows.get(ids.indexOf(id)));
        assertThat(groups.get(i).bulk()).isEqualTo(ids.stream().filter(n -> n == id).count());
      }
      assertThat(window.hasNext()).isFalse();
      assertThatThrownBy(window::next).isInstanceOf(java.util.NoSuchElementException.class);
    }
  }

  /** The 2500th distinct row seals a window without consuming the following duplicate. */
  @Test
  public void capacityStopsBeforeFollowingDuplicatesAndBoundsReads() {
    var rows = new ArrayList<Result>();
    rows.add(row(0));
    rows.add(row(0));
    for (int i = 1; i < 2500; i++) {
      rows.add(row(i));
    }
    rows.add(row(0));
    rows.add(row(2500));
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var iterator = rows.iterator();
    var source = new java.util.Iterator<Result>() {
      public boolean hasNext() {
        return iterator.hasNext();
      }

      public Result next() {
        reads.incrementAndGet();
        return iterator.next();
      }
    };
    var window = new ElementBulkingWindow(ExecutionStream.resultIterator(source),
        mock(CommandContext.class), "v", 2500);
    assertThat(window.hasNext()).isTrue();
    assertThat(window.hasNext()).isTrue();
    assertThat(reads.get()).isEqualTo(2501);
    assertThat(window.next().bulk()).isEqualTo(2);
    for (int i = 1; i < 2500; i++) {
      assertThat(window.next().row.<Object>getProperty("v")).isEqualTo(new RecordId(10, i));
    }
    assertThat(reads.get()).isEqualTo(2501);
    assertThat(window.next().row.<Object>getProperty("v")).isEqualTo(new RecordId(10, 0));
    assertThat(window.next().row.<Object>getProperty("v")).isEqualTo(new RecordId(10, 2500));
    assertThat(window.hasNext()).isFalse();
  }

  /** Interleaved rows keep native first-seen group order across capacities 1, 2 and 2500. */
  @Test
  public void firstSeenOrderMatchesNativeBarrierForTheSameInputAcrossWindowBounds() {
    for (int capacity : List.of(1, 2, 2500)) {
      var ids = new ArrayList<RecordId>();
      ids.add(new RecordId(10, 0));
      ids.add(new RecordId(10, 0));
      for (int i = 1; i <= capacity; i++) {
        ids.add(new RecordId(10, i));
        ids.add(new RecordId(10, 0));
      }
      var traversal = new DefaultTraversal<>();
      var nativeBarrier = new NoOpBarrierStep<Object>(traversal, capacity);
      // Feed one arrival per pull. InjectStep's eager starts would bulk before this barrier.
      @SuppressWarnings("unchecked")
      var previous = (Step<Object, Object>) mock(Step.class);
      var arrivals = ids.iterator();
      when(previous.hasNext()).thenAnswer(ignored -> arrivals.hasNext());
      when(previous.next()).thenAnswer(ignored -> new B_O_Traverser<>(arrivals.next(), 1L));
      nativeBarrier.setPreviousStep(previous);
      var rows = ids.stream().map(id -> row((int) id.getCollectionPosition())).toList();
      var window = window(rows, capacity);
      while (nativeBarrier.hasNext()) {
        var expected = nativeBarrier.next();
        assertThat(window.hasNext()).isTrue();
        var group = window.next();
        assertThat(group.row.<Object>getProperty("v")).isEqualTo(expected.get());
        assertThat(group.bulk()).isEqualTo(expected.bulk());
      }
      assertThat(window.hasNext()).isFalse();
    }
  }

  /** Invalid capacities and non-element cells fail loudly instead of silently merging null keys. */
  @Test
  public void rejectsInvalidWindowAndUnboundIdentity() {
    for (int capacity : List.of(0, -1, 2501)) {
      assertThatThrownBy(() -> window(List.of(), capacity))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var row = mock(Result.class);
    assertThatThrownBy(() -> window(List.of(row), 1).hasNext())
        .isInstanceOf(IllegalStateException.class);
  }

  /** A single identity buffers without a grouping map and emits its first row with full bulk. */
  @Test
  public void singleIdentityKeepsFirstRowAndCountsEveryArrival() {
    for (int count : List.of(1, 5)) {
      var rows = new ArrayList<Result>();
      for (int i = 0; i < count; i++) {
        rows.add(row(7));
      }
      var window = window(rows, 2500);
      assertThat(window.hasNext()).isTrue();
      assertThat(window.hasNext()).isTrue();
      assertThat(window.nextRow()).isSameAs(rows.getFirst());
      assertThat(window.bulk()).isEqualTo(count);
      assertThat(window.hasNext()).isFalse();
      assertThatThrownBy(window::next).isInstanceOf(java.util.NoSuchElementException.class);
    }
  }

  /** Promotion on the second identity retains the first group's count, position and row. */
  @Test
  public void secondAndLaterIdentitiesPreserveFirstSeenOrderAndBulk() {
    var rows = List.of(row(3), row(3), row(1), row(3), row(2), row(1), row(2));
    var window = window(rows, 2500);
    for (int index : List.of(0, 2, 4)) {
      var group = window.next();
      assertThat(group.row).isSameAs(rows.get(index));
      assertThat(group.bulk()).isEqualTo(index == 0 ? 3 : 2);
    }
    assertThat(window.hasNext()).isFalse();
  }

  /** Capacity one emits a, b, a in three windows without reading the next arrival early. */
  @Test
  public void capacityOneNeverReadsAheadOrMergesInterleavedIdentities() {
    var context = mock(CommandContext.class);
    var stream = mock(ExecutionStream.class);
    var rows = List.of(row(1), row(2), row(1));
    when(stream.hasNext(context)).thenReturn(true, true, true, false);
    when(stream.next(context)).thenReturn(rows.get(0), rows.get(1), rows.get(2));
    var window = new ElementBulkingWindow(stream, context, "v", 1);
    for (int i = 0; i < rows.size(); i++) {
      assertThat(window.hasNext()).isTrue();
      assertThat(window.hasNext()).isTrue();
      org.mockito.Mockito.verify(stream, org.mockito.Mockito.times(i + 1)).next(context);
      var group = window.next();
      assertThat(group.row).isSameAs(rows.get(i));
      assertThat(group.bulk()).isEqualTo(1);
    }
    assertThat(window.hasNext()).isFalse();
  }

  /** Empty input never creates a pending singleton or emits a group. */
  @Test
  public void emptyInputStaysExhaustedOnRepeatedProbes() {
    var window = window(List.of(), 1);
    assertThat(window.hasNext()).isFalse();
    assertThat(window.hasNext()).isFalse();
    assertThatThrownBy(window::next).isInstanceOf(java.util.NoSuchElementException.class);
  }

  private static ElementBulkingWindow window(List<Result> rows, int capacity) {
    return new ElementBulkingWindow(ExecutionStream.resultIterator(rows.iterator()),
        mock(CommandContext.class), "v", capacity);
  }

  private static Result row(int id) {
    var row = mock(Result.class);
    when(row.getProperty("v")).thenReturn(new RecordId(10, id));
    return row;
  }
}
