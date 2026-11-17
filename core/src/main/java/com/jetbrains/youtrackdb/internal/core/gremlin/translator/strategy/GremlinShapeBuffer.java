package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.Arrays;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/** Finite, thread-confined scratch. Only frozen keys may be published to the cache. */
final class GremlinShapeBuffer implements GremlinShapeTokens, AutoCloseable {
  static final int TOKEN_LIMIT = 1024;
  static final int EVENT_LIMIT = 128;
  static final int DEPTH_LIMIT = 16;
  static final long SEED = 0xcbf29ce484222325L;
  static volatile java.util.function.LongUnaryOperator hasherForTests;
  static final ThreadLocal<Counters> TEST_COUNTERS = new ThreadLocal<>();

  static final class Counters {
    int leases;
    int emissions;
    int fallbacks;
    int hits;
    int scopes;
    int countScans;
  }

  static long finishHash(long hash) {
    var hasher = hasherForTests;
    return hasher == null ? hash : hasher.applyAsLong(hash);
  }

  static void count(java.util.function.Consumer<Counters> action) {
    var counters = TEST_COUNTERS.get();
    if (counters != null) {
      action.accept(counters);
    }
  }

  private static final ThreadLocal<GremlinShapeBuffer> LOCAL =
      ThreadLocal.withInitial(GremlinShapeBuffer::new);

  /** Internal control flow. Unsupported fast emission restarts on the complete extractor. */
  static final class Unsupported extends RuntimeException {
    private Unsupported() {
      super(null, null, false, false);
    }
  }

  static final Unsupported UNSUPPORTED = new Unsupported();
  private final Object[] tokens = new Object[TOKEN_LIMIT];
  private final Runnable[] events = new Runnable[EVENT_LIMIT];
  private final Traversal.Admin<?, ?>[] scopes = new Traversal.Admin<?, ?>[DEPTH_LIMIT];
  private boolean leased;
  private long epoch;

  long epoch() {
    return epoch;
  }

  // A later acquire reuses this object, so checking only the leased flag is not sufficient.
  void requireLease(long expectedEpoch) {
    if (!leased || epoch != expectedEpoch) {
      throw new IllegalStateException("Shape extraction outlived its scratch lease");
    }
  }

  private int size;
  private int eventCount;
  private int depth;
  private long hash = SEED;

  static GremlinShapeBuffer acquire() {
    var buffer = LOCAL.get();
    if (buffer.leased) {
      return null;
    }
    count(counters -> counters.leases++);
    buffer.leased = true;
    buffer.epoch++;
    return buffer;
  }

  void append(Object token) {
    if (size == TOKEN_LIMIT) {
      throw UNSUPPORTED;
    }
    tokens[size++] = token;
    hash = GremlinShapeTokens.mix(hash, token);
  }

  void defer(Runnable event) {
    if (eventCount == EVENT_LIMIT) {
      throw UNSUPPORTED;
    }
    events[eventCount++] = event;
  }

  void replay() {
    for (int i = 0; i < eventCount; i++) {
      events[i].run();
    }
  }

  void enter(Traversal.Admin<?, ?> traversal) {
    if (depth == DEPTH_LIMIT) {
      throw UNSUPPORTED;
    }
    for (int i = 0; i < depth; i++) {
      if (scopes[i] == traversal) {
        throw UNSUPPORTED;
      }
    }
    scopes[depth++] = traversal;
  }

  void leave() {
    scopes[--depth] = null;
  }

  GremlinShapeKey freeze() {
    return new GremlinShapeKey(Arrays.copyOf(tokens, size));
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public Object token(int index) {
    return tokens[index];
  }

  @Override
  public long hash64() {
    return finishHash(hash);
  }

  @Override
  public int hashCode() {
    return GremlinShapeTokens.fold(hash64());
  }

  @Override
  public boolean equals(Object other) {
    return GremlinShapeTokens.equal(this, other);
  }

  @Override
  public void close() {
    Arrays.fill(tokens, 0, size, null);
    Arrays.fill(events, 0, eventCount, null);
    Arrays.fill(scopes, 0, depth, null);
    size = 0;
    eventCount = 0;
    depth = 0;
    hash = SEED;
    leased = false;
  }
}
