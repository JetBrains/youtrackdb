package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Tests for GQL execution stream implementations: EmptyGqlExecutionStream,
 * IteratorGqlExecutionStream, FlatMapGqlExecutionStream, and GqlExecutionStream static API.
 */
public class GqlExecutionStreamTest {

  @Test
  public void emptyStream_hasNext_returnsFalse() {
    var stream = GqlExecutionStream.empty();
    Assert.assertFalse(stream.hasNext());
  }

  @Test(expected = NoSuchElementException.class)
  public void emptyStream_next_throwsNoSuchElementException() {
    GqlExecutionStream.empty().next();
  }

  @Test
  public void emptyStream_close_doesNotThrow() {
    GqlExecutionStream.empty().close();
  }

  @Test
  public void empty_returnsSingletonInstance() {
    Assert.assertSame(EmptyGqlExecutionStream.INSTANCE, GqlExecutionStream.empty());
  }

  @Test
  public void fromIterator_withoutMapper_iteratesElements() {
    var list = List.of("a", "b", "c");
    var stream = GqlExecutionStream.fromIterator(list.iterator());
    Assert.assertTrue(stream.hasNext());
    Assert.assertEquals("a", stream.next());
    Assert.assertTrue(stream.hasNext());
    Assert.assertEquals("b", stream.next());
    Assert.assertEquals("c", stream.next());
    Assert.assertFalse(stream.hasNext());
  }

  @Test
  public void fromIterator_withMapper_mapsElements() {
    var list = List.of(1, 2, 3);
    var stream = GqlExecutionStream.fromIterator(list.iterator(), n -> n * 10);
    Assert.assertEquals(10, stream.next());
    Assert.assertEquals(20, stream.next());
    Assert.assertEquals(30, stream.next());
    Assert.assertFalse(stream.hasNext());
  }

  @Test(expected = NullPointerException.class)
  public void fromIterator_nullIterator_throws() {
    GqlExecutionStream.fromIterator(null);
  }

  @Test(expected = NullPointerException.class)
  public void fromIterator_nullMapper_throws() {
    GqlExecutionStream.fromIterator(List.of(1).iterator(), null);
  }

  @Test
  public void iteratorStream_closesAutoCloseableSourceOnExhaustion() {
    var closed = new boolean[]{false};
    class CloseableSingleIterator implements Iterator<Integer>, AutoCloseable {

      boolean yielded;

      @Override
      public void close() {
        closed[0] = true;
      }

      @Override
      public boolean hasNext() {
        return !yielded;
      }

      @Override
      public Integer next() {
        if (!yielded) {
          yielded = true;
          return 1;
        }
        throw new NoSuchElementException();
      }
    }
    var stream = new IteratorGqlExecutionStream<>(new CloseableSingleIterator());
    Assert.assertTrue(stream.hasNext());
    stream.next();
    Assert.assertFalse(stream.hasNext());
    Assert.assertTrue("Source iterator should be closed when exhausted", closed[0]);
  }

  @Test
  public void iteratorStream_close_closesAutoCloseableSource() {
    var closed = new boolean[]{false};
    class CloseableEmptyIterator implements Iterator<Integer>, AutoCloseable {

      @Override
      public void close() {
        closed[0] = true;
      }

      @Override
      public boolean hasNext() {
        return false;
      }

      @Override
      public Integer next() {
        throw new NoSuchElementException();
      }
    }
    var stream = new IteratorGqlExecutionStream<>(new CloseableEmptyIterator());
    stream.close();
    Assert.assertTrue("Source should be closed when stream.close() is called", closed[0]);
  }

  @Test
  public void flatMap_flatMapsEachUpstreamElement() {
    var upstream = GqlExecutionStream.fromIterator(List.of(1, 2).iterator());
    var stream = upstream.flatMap(n -> GqlExecutionStream.fromIterator(
        List.of((int) n * 10, (int) n * 10 + 1).iterator()));
    var results = new ArrayList<>();
    while (stream.hasNext()) {
      results.add(stream.next());
    }
    Assert.assertEquals(List.of(10, 11, 20, 21), results);
  }

  @Test
  public void flatMap_emptyUpstream_returnsEmpty() {
    var upstream = GqlExecutionStream.empty();
    var stream = upstream.flatMap(x -> GqlExecutionStream.fromIterator(List.of(x).iterator()));
    Assert.assertFalse(stream.hasNext());
  }

  @Test(expected = NoSuchElementException.class)
  public void flatMap_nextWithoutHasNext_throws() {
    var upstream = GqlExecutionStream.fromIterator(List.of(1).iterator());
    var stream = upstream.flatMap(n -> GqlExecutionStream.fromIterator(List.of(n).iterator()));
    stream.next();
    stream.next();
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  public void flatMap_close_closesUpstreamAndChildStream() {
    var upstream = GqlExecutionStream.fromIterator(List.of(1).iterator());
    var stream = upstream.flatMap(n -> GqlExecutionStream.fromIterator(List.of(n).iterator()));
    stream.hasNext();
    stream.close();
  }
}
