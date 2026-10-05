package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import javax.annotation.Nullable;

/**
 * Conservative durable-page candidates, independent of cache flush bookkeeping.
 *
 * <p>Marking takes neither ordering domain. The caller excludes page writers at a backup switch
 * and excludes writers for a file during its identity reset or deletion. The total lock order is
 * save order, external file inventory, then generation state. Save-order holders must not wait for
 * transactions, write pauses, or exclusive storage state.
 */
final class ChangedPageTracker {

  static final int SEGMENT_WORDS = 512;
  static final int SEGMENT_BYTES = SEGMENT_WORDS * Long.BYTES;
  private static final VarHandle WORD = MethodHandles.arrayElementVarHandle(long[].class);
  private final ReentrantLock saveOrder = new ReentrantLock();
  private final ReentrantLock generationState = new ReentrantLock();
  private final AtomicLong failures = new AtomicLong();
  private final AllocationObserver allocations;
  private final FileIndex<FileIdentity> identities = new FileIndex<>();
  private volatile Generation active;
  @Nullable private SealedGeneration sealed;
  private UUID trackerId = UUID.randomUUID();
  @Nullable private UUID lastCompleted;
  private long savedFailureVersion = -1;
  private boolean historyTrusted;

  ChangedPageTracker() {
    this(kind -> {
    });
  }

  /** Test-only allocation observation and failure injection, not an operator setting. */
  ChangedPageTracker(AllocationObserver allocations) {
    this.allocations = allocations;
    active = new Generation(allocations);
  }

  /** Sets a mark before transaction completion, including for an already dirty page. */
  void mark(int fileId, long pageIndex) {
    try {
      checkFileId(fileId);
      if (pageIndex < 0) {
        throw new IllegalArgumentException("Negative page index");
      }
      var generation = active;
      var bitmap = generation.files.get(fileId);
      // The steady path needs only the generation's file lookup, not the identity registry.
      if (bitmap == null || !bitmap.identity.valid) {
        bitmap = generation.file(fileId, identity(fileId));
      }
      if (bitmap == null) {
        throw new IllegalStateException("File identity changed during marking");
      }
      bitmap.or(pageIndex >>> 6, 1L << (pageIndex & 63));
    } catch (RuntimeException | Error failure) {
      invalidate();
      throw failure;
    }
  }

  private FileIdentity identity(int fileId) {
    var existing = identities.get(fileId);
    if (existing != null) {
      return existing;
    }
    allocations.beforeAllocation(Allocation.IDENTITY);
    var created = new FileIdentity();
    var winner = identities.putIfAbsent(fileId, created);
    return winner == null ? created : winner;
  }

  /** Allocation-free loss of trust, also safe when the cause is exhausted heap memory. */
  void invalidate() {
    failures.incrementAndGet();
  }

  boolean isTrusted() {
    generationState.lock();
    try {
      return historyTrusted && savedFailureVersion == failures.get();
    } finally {
      generationState.unlock();
    }
  }

  /**
   * Establishes a boundary under the caller's short write pause, without waiting for save order.
   * A null result signals a pending backup and requires a full read without a third generation.
   */
  @Nullable SealedGeneration beginBackup() {
    generationState.lock();
    try {
      if (sealed != null) {
        invalidate();
        return null;
      }
      var replacement = new Generation(allocations);
      var boundary = new SealedGeneration(UUID.randomUUID(), active, trackerId, failures.get());
      sealed = boundary;
      active = replacement;
      return boundary;
    } catch (RuntimeException | Error failure) {
      invalidate();
      throw failure;
    } finally {
      generationState.unlock();
    }
  }

  /**
   * Records confirmed durable completion. An untrusted backup must have read all pages.
   * Only an unchanged history since the boundary can establish new continuity.
   */
  void retire(SealedGeneration expected) {
    saveOrder.lock();
    generationState.lock();
    try {
      requireSealed(expected);
      long version = failures.get();
      historyTrusted = expected.failureVersion() == version && savedFailureVersion == version
          && expected.trackerIdentifier().equals(trackerId);
      lastCompleted = historyTrusted ? expected.identifier() : null;
      sealed = null;
    } finally {
      generationState.unlock();
      saveOrder.unlock();
    }
  }

  /** Failure and Q-f custom-output cleanup share this merge without advancing continuity. */
  void mergeBack(SealedGeneration expected) {
    saveOrder.lock();
    try {
      Generation target;
      generationState.lock();
      try {
        requireSealed(expected);
        target = active;
      } finally {
        generationState.unlock();
      }
      // Keep sealed published until merging finishes. A switch must not overtake this merge.
      expected.pages().forEachFile((fileId, source) -> {
        var destination = target.file(fileId, source.identity);
        if (destination != null) {
          source.forEachWord(destination::or);
        }
      });
      generationState.lock();
      try {
        sealed = null;
      } finally {
        generationState.unlock();
      }
    } catch (RuntimeException | Error failure) {
      invalidate();
      throw failure;
    } finally {
      saveOrder.unlock();
    }
  }

  /** Startup discards the loaded seal identifier, but preserves the last completed identifier. */
  void mergeLoadedSealed() {
    saveOrder.lock();
    try {
      SealedGeneration loaded;
      generationState.lock();
      try {
        loaded = sealed;
      } finally {
        generationState.unlock();
      }
      if (loaded != null) {
        mergeBack(loaded);
      }
    } finally {
      saveOrder.unlock();
    }
  }

  /** Creation and reuse start an empty file identity. The next mark creates its metadata lazily. */
  void resetFile(int fileId) {
    deleteFile(fileId);
  }

  /**
   * Takes only generation state after the caller's file inventory operation. Detached bitmaps are
   * not cleared or reused. Captured references remain readable. A merge that publishes after
   * deletion removes its invalid entry, without touching a reused identifier's bitmap.
   */
  void deleteFile(int fileId) {
    checkFileId(fileId);
    generationState.lock();
    try {
      var identity = identities.remove(fileId);
      if (identity != null) {
        identity.valid = false;
      }
      active.files.remove(fileId);
      if (sealed != null) {
        sealed.pages().files.remove(fileId);
      }
    } finally {
      generationState.unlock();
    }
  }

  /** Later save code holds this domain from capture through publication and the associated cut. */
  ReentrantLock saveOrderLock() {
    return saveOrder;
  }

  /**
   * Captures identifiers and live references together. It does not clone any bitmap.
   * A pending reset proposes the identifier that the side file must store on a successful save.
   */
  SaveState capture() {
    requireSaveOrder();
    generationState.lock();
    try {
      long version = failures.get();
      boolean reset = version != savedFailureVersion;
      return new SaveState(reset ? UUID.randomUUID() : trackerId,
          reset ? null : lastCompleted, !reset && historyTrusted, active, sealed, version, reset);
    } finally {
      generationState.unlock();
    }
  }

  /** A captured state cannot certify coverage if a mark failed after capture. */
  boolean isCaptureValid(SaveState state) {
    requireSaveOrder();
    return state.failureVersion() == failures.get();
  }

  /**
   * Explicit hook for successful durable publication. Check its result before certifying a cut.
   * A checkpoint reset does not recover missing backup history or enable selective reads.
   */
  boolean saveSucceeded(SaveState state) {
    requireSaveOrder();
    generationState.lock();
    try {
      if (!isCaptureValid(state)) {
        return false;
      }
      if (state.resetsTracker()) {
        trackerId = state.trackerIdentifier();
        lastCompleted = null;
        historyTrusted = false;
        savedFailureVersion = state.failureVersion();
      }
      return isCaptureValid(state);
    } finally {
      generationState.unlock();
    }
  }

  private void requireSaveOrder() {
    if (!saveOrder.isHeldByCurrentThread()) {
      throw new IllegalStateException("Save-order domain is required");
    }
  }

  private void requireSealed(SealedGeneration expected) {
    if (sealed != expected) {
      invalidate();
      throw new IllegalStateException("Sealed generation does not match");
    }
  }

  private static void checkFileId(int fileId) {
    if (fileId <= 0) {
      throw new IllegalArgumentException("Internal file identifier must be positive");
    }
  }

  /**
   * No-I/O streaming restoration for a validated side file. Trust still needs the caller's WAL
   * coverage check. The builder shares one identity per file across both loaded generations.
   */
  static Restoration restoration(UUID trackerIdentifier, @Nullable UUID lastCompletedIdentifier,
      boolean trusted, @Nullable UUID sealedIdentifier) {
    return new Restoration(trackerIdentifier, lastCompletedIdentifier, trusted, sealedIdentifier);
  }

  static final class Restoration {

    private final ChangedPageTracker tracker = new ChangedPageTracker();
    private boolean finished;

    private Restoration(UUID trackerIdentifier, @Nullable UUID lastCompletedIdentifier,
        boolean trusted, @Nullable UUID sealedIdentifier) {
      tracker.trackerId = Objects.requireNonNull(trackerIdentifier);
      tracker.lastCompleted = lastCompletedIdentifier;
      tracker.historyTrusted = trusted;
      tracker.savedFailureVersion = 0;
      if (sealedIdentifier != null) {
        tracker.sealed = new SealedGeneration(sealedIdentifier, new Generation(tracker.allocations),
            trackerIdentifier, 0);
      }
    }

    void activeWord(int fileId, long wordIndex, long bits) {
      word(tracker.active, fileId, wordIndex, bits);
    }

    void sealedWord(int fileId, long wordIndex, long bits) {
      var loaded = tracker.sealed;
      if (loaded == null) {
        throw new IllegalStateException("No loaded sealed generation");
      }
      word(loaded.pages(), fileId, wordIndex, bits);
    }

    private void word(Generation generation, int fileId, long wordIndex, long bits) {
      requireOpen();
      checkFileId(fileId);
      if (wordIndex < 0 || wordIndex > (Long.MAX_VALUE >>> 6)) {
        throw new IllegalArgumentException("Invalid bitmap word index");
      }
      if (bits != 0) {
        var bitmap = generation.file(fileId, tracker.identity(fileId));
        Objects.requireNonNull(bitmap).or(wordIndex, bits);
      }
    }

    ChangedPageTracker build() {
      requireOpen();
      finished = true;
      return tracker;
    }

    private void requireOpen() {
      if (finished) {
        throw new IllegalStateException("Restoration is complete");
      }
    }
  }

  record SaveState(UUID trackerIdentifier, @Nullable UUID lastCompletedIdentifier, boolean trusted,
      Generation active, @Nullable SealedGeneration sealed, long failureVersion,
      boolean resetsTracker) {
  }

  record SealedGeneration(UUID identifier, Generation pages, UUID trackerIdentifier,
      long failureVersion) {
  }

  static final class Generation {

    private final FileIndex<FileBitmap> files = new FileIndex<>();
    private final AllocationObserver allocations;

    private Generation(AllocationObserver allocations) {
      this.allocations = allocations;
    }

    @Nullable private FileBitmap file(int fileId, FileIdentity identity) {
      while (identity.valid) {
        var existing = files.get(fileId);
        if (existing != null) {
          if (existing.identity == identity) {
            return existing;
          }
          if (existing.identity.valid) {
            // An old merge must not replace marks belonging to a reused file identifier.
            return null;
          }
        }
        allocations.beforeAllocation(Allocation.FILE);
        var created = new FileBitmap(identity, allocations);
        boolean published = existing == null ? files.putIfAbsent(fileId, created) == null
            : files.replace(fileId, existing, created);
        if (published) {
          // Deletion can run while allocation or publication is in flight. Remove only our entry.
          if (!identity.valid) {
            files.remove(fileId, created);
            return null;
          }
          return created;
        }
      }
      return null;
    }

    /** Ascending candidates, without duplicates or a materialized page list. */
    void forEachCandidate(int fileId, LongConsumer consumer) {
      checkFileId(fileId);
      var bitmap = files.get(fileId);
      if (bitmap != null) {
        bitmap.forEachWord((wordIndex, value) -> {
          long remaining = value;
          while (remaining != 0) {
            consumer.accept((wordIndex << 6) + Long.numberOfTrailingZeros(remaining));
            remaining &= remaining - 1;
          }
        });
      }
    }

    /** Streaming access for later persistence, with ascending internal file identifiers. */
    void forEachFile(BiConsumer<Integer, FileBitmap> consumer) {
      files.forEach((fileId, bitmap) -> {
        if (bitmap.identity.valid) {
          consumer.accept(fileId, bitmap);
        }
      });
    }
  }

  /**
   * Primitive-key compressed radix index. Each immutable branch tests the highest bit that
   * distinguishes its children. A lookup reads at most 31 branches and creates no objects.
   * Structural updates copy only their search path, then publish the complete tree by root CAS.
   * Removal collapses the parent into its surviving child, leaving no historical key paths.
   */
  private static final class FileIndex<T> {

    private final AtomicReference<FileEntry<T>> root = new AtomicReference<>();

    @Nullable private T get(int key) {
      return get(root.get(), key);
    }

    @Nullable private static <T> T get(@Nullable FileEntry<T> entry, int key) {
      while (entry != null && entry.bit != 0) {
        entry = (key & entry.bit) == 0 ? entry.left : entry.right;
      }
      return entry != null && entry.key == key ? entry.value : null;
    }

    @Nullable private T putIfAbsent(int key, T value) {
      while (true) {
        var before = root.get();
        var existing = get(before, key);
        if (existing != null) {
          return existing;
        }
        if (root.compareAndSet(before, put(before, key, value))) {
          return null;
        }
      }
    }

    private boolean replace(int key, T expected, T value) {
      return change(key, expected, value);
    }

    @Nullable private T remove(int key) {
      while (true) {
        var before = root.get();
        var existing = get(before, key);
        if (existing == null || root.compareAndSet(before, remove(before, key))) {
          return existing;
        }
      }
    }

    private boolean remove(int key, T expected) {
      return change(key, expected, null);
    }

    private boolean change(int key, T expected, @Nullable T value) {
      while (true) {
        var before = root.get();
        // Identity comparison prevents a late cleanup from removing a reused file's bitmap.
        if (get(before, key) != expected) {
          return false;
        }
        var after = value == null ? remove(before, key) : put(before, key, value);
        if (root.compareAndSet(before, after)) {
          return true;
        }
      }
    }

    private static <T> FileEntry<T> put(@Nullable FileEntry<T> entry, int key, T value) {
      if (entry == null) {
        return new FileEntry<>(key, 0, value, null, null);
      }
      int differingBit = Integer.highestOneBit(key ^ entry.key);
      if (differingBit > entry.bit) {
        var leaf = new FileEntry<>(key, 0, value, null, null);
        return (key & differingBit) == 0
            ? new FileEntry<>(key, differingBit, null, leaf, entry)
            : new FileEntry<>(key, differingBit, null, entry, leaf);
      }
      if (entry.bit == 0) {
        return new FileEntry<>(key, 0, value, null, null);
      }
      if ((key & entry.bit) == 0) {
        return new FileEntry<>(entry.key, entry.bit, null,
            put(entry.left, key, value), entry.right);
      }
      return new FileEntry<>(entry.key, entry.bit, null,
          entry.left, put(entry.right, key, value));
    }

    @Nullable private static <T> FileEntry<T> remove(FileEntry<T> entry, int key) {
      // The caller found this key in the same immutable root before entering this recursion.
      if (entry.bit == 0) {
        return null;
      }
      if ((key & entry.bit) == 0) {
        var remaining = remove(Objects.requireNonNull(entry.left), key);
        return remaining == null ? entry.right
            : new FileEntry<>(entry.key, entry.bit, null, remaining, entry.right);
      }
      var remaining = remove(Objects.requireNonNull(entry.right), key);
      return remaining == null ? entry.left
          : new FileEntry<>(entry.key, entry.bit, null, entry.left, remaining);
    }

    private void forEach(BiConsumer<Integer, T> consumer) {
      visit(root.get(), consumer);
    }

    private static <T> void visit(@Nullable FileEntry<T> entry, BiConsumer<Integer, T> consumer) {
      if (entry == null) {
        return;
      }
      if (entry.bit == 0) {
        consumer.accept(entry.key, entry.value);
      } else {
        visit(entry.left, consumer);
        visit(entry.right, consumer);
      }
    }

    private static final class FileEntry<T> {

      private final int key;
      private final int bit;
      @Nullable private final T value;
      @Nullable private final FileEntry<T> left;
      @Nullable private final FileEntry<T> right;

      private FileEntry(int key, int bit, @Nullable T value, @Nullable FileEntry<T> left,
          @Nullable FileEntry<T> right) {
        this.key = key;
        this.bit = bit;
        this.value = value;
        this.left = left;
        this.right = right;
      }
    }
  }

  private static final class FileIdentity {

    private volatile boolean valid = true;
  }

  static final class FileBitmap {

    private final FileIdentity identity;
    private final RadixTree segments;
    private final AllocationObserver allocations;

    private FileBitmap(FileIdentity identity, AllocationObserver allocations) {
      this.identity = identity;
      this.allocations = allocations;
      segments = new RadixTree(allocations);
    }

    private void or(long wordIndex, long bits) {
      if (!identity.valid) {
        return;
      }
      long segmentIndex = wordIndex >>> 9;
      var slot = segments.leaf(segmentIndex);
      int index = (int) (segmentIndex & 255);
      var segment = (long[]) slot.get(index);
      if (segment == null) {
        allocations.beforeAllocation(Allocation.SEGMENT);
        var created = new long[SEGMENT_WORDS];
        segment = slot.compareAndSet(index, null, created) ? created : (long[]) slot.get(index);
      }
      int word = (int) (wordIndex & (SEGMENT_WORDS - 1));
      long existing = (long) WORD.getVolatile(segment, word);
      if ((existing & bits) != bits) {
        WORD.getAndBitwiseOr(segment, word, bits);
      }
    }

    /** Nonzero atomic words only. Concurrent additions require the caller's WAL coverage proof. */
    void forEachWord(WordConsumer consumer) {
      segments.visit((segmentIndex, value) -> {
        var segment = (long[]) value;
        for (int word = 0; word < SEGMENT_WORDS; word++) {
          long bits = (long) WORD.getVolatile(segment, word);
          if (bits != 0 && identity.valid) {
            consumer.accept((segmentIndex << 9) + word, bits);
          }
        }
      });
    }
  }

  @FunctionalInterface
  interface WordConsumer {

    void accept(long wordIndex, long bits);
  }

  enum Allocation {
    NODE, IDENTITY, FILE, SEGMENT
  }

  @FunctionalInterface
  interface AllocationObserver {

    void beforeAllocation(Allocation kind);
  }

  /**
   * One level covers the first 256 segments. CAS root growth adds eight key bits at a time, up
   * to the 48 segment-index bits needed for Long.MAX_VALUE pages. Published subtrees never move
   * or disappear, so writers that keep an older root still publish into the current tree.
   */
  private static final class RadixTree {

    private final AllocationObserver allocations;
    private final AtomicReference<Node> root;

    private RadixTree(AllocationObserver allocations) {
      this.allocations = allocations;
      root = new AtomicReference<>(newNode(0));
    }

    private Node newNode(int shift) {
      allocations.beforeAllocation(Allocation.NODE);
      return new Node(shift);
    }

    private AtomicReferenceArray<Object> leaf(long key) {
      var node = root.get();
      while ((key >>> (node.shift + 8)) != 0) {
        var grown = newNode(node.shift + 8);
        grown.slots.set(0, node);
        if (root.compareAndSet(node, grown)) {
          node = grown;
        } else {
          node = root.get();
        }
      }
      while (node.shift > 0) {
        int index = (int) ((key >>> node.shift) & 255);
        var child = (Node) node.slots.get(index);
        if (child == null) {
          var created = newNode(node.shift - 8);
          child = node.slots.compareAndSet(index, null, created)
              ? created : (Node) node.slots.get(index);
        }
        node = child;
      }
      return node.slots;
    }

    private void visit(EntryConsumer consumer) {
      visit(root.get(), 0, consumer);
    }

    private static void visit(Node node, long prefix, EntryConsumer consumer) {
      for (int index = 0; index < 256; index++) {
        var value = node.slots.get(index);
        if (value != null) {
          long key = prefix | ((long) index << node.shift);
          if (node.shift == 0) {
            consumer.accept(key, value);
          } else {
            visit((Node) value, key, consumer);
          }
        }
      }
    }

    private static final class Node {

      private final int shift;
      private final AtomicReferenceArray<Object> slots = new AtomicReferenceArray<>(256);

      private Node(int shift) {
        this.shift = shift;
      }
    }
  }

  @FunctionalInterface
  private interface EntryConsumer {

    void accept(long key, Object value);
  }
}
