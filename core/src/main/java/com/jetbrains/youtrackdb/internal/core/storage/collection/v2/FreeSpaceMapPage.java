package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

/**
 * A single page of a {@link FreeSpaceMap}, implementing a <em>max-heap segment tree</em> packed
 * into a flat byte array. Each cell stores a single byte (0..255) representing a normalized
 * free-space value. The tree supports two operations:
 * <ul>
 *   <li>{@link #findPage(int)} - find a leaf whose value is {@code >= requiredSize} (O(log N))</li>
 *   <li>{@link #updatePageMaxFreeSpace(int, int)} - update a leaf and propagate the max up to
 *       the root (O(log N))</li>
 * </ul>
 *
 * <h2>Segment Tree Layout</h2>
 *
 * <p>The tree is stored in a level-order (BFS) layout within the page's usable area (bytes
 * {@link DurablePage#NEXT_FREE_POSITION} through {@link DurablePage#MAX_PAGE_SIZE_BYTES}).
 * Internal nodes store the maximum of their two children. Leaf nodes store the normalized
 * free-space value for one data page.
 *
 * <pre>{@code
 *  Page byte layout (level-order segment tree):
 *
 *  Offset NEXT_FREE_POSITION:
 *  +--------+--------+--------+--------+--------+--------+-----+
 *  | root   | L2[0]  | L2[1]  | L3[0]  | L3[1]  | L3[2]  | ... |
 *  | (L1)   |        |        |        |        |        |     |
 *  +--------+--------+--------+--------+--------+--------+-----+
 *  |<-- internal nodes ---------------------------------->|
 *                                            +--------+--------+-----+
 *                                            | leaf0  | leaf1  | ... |
 *                                            +--------+--------+-----+
 *                                            |<-- CELLS_PER_PAGE leaves -->|
 *
 *  Level 1 (root):   1 node   -> max of everything
 *  Level 2:          2 nodes  -> max of left/right halves
 *  Level 3:          4 nodes
 *  ...
 *  Level LEVELS:     CELLS_PER_PAGE leaves (each = one data page's normalized free space)
 *
 *  Node at level L, index I is stored at byte offset:
 *    NEXT_FREE_POSITION + ((1 << (L-1)) - 1 + I) * CELL_SIZE
 *
 *  For the default 8 KB page size (MAX_PAGE_SIZE_BYTES = 8192, NEXT_FREE_POSITION = 28):
 *    - Total usable bytes = 8164
 *    - LEVELS = 12 (tree depth)
 *    - Internal nodes = 2^11 - 1 = 2047
 *    - CELLS_PER_PAGE = 8164 - 2047 = 6117 (leaves / tracked data pages)
 * }</pre>
 *
 * <h2>Search Algorithm ({@link #findPage})</h2>
 *
 * <p>Starting at the root (level 1), descend through the tree by always preferring the left
 * child if its value is {@code >= requiredSize}; otherwise take the right child. This greedily
 * finds the leftmost (lowest-index) leaf that satisfies the requirement.
 *
 * <h2>Update Algorithm ({@link #updatePageMaxFreeSpace})</h2>
 *
 * <p>Set the leaf value, then walk up to the root, recalculating each parent as the max of its
 * two children. Returns the root value (the global maximum for this page).
 *
 * @see FreeSpaceMap
 */
public final class FreeSpaceMapPage extends DurablePage {

  /** Size of each cell in bytes. Each cell holds a single unsigned byte (0..255). */
  private static final int CELL_SIZE = 1;

  /**
   * Number of leaf cells (data pages tracked) per FSM page. This equals the total usable
   * cells minus the internal (non-leaf) nodes of the segment tree.
   */
  static final int CELLS_PER_PAGE;

  /** Depth of the segment tree (number of levels, root = level 1, leaves = level LEVELS). */
  private static final int LEVELS;

  /** Byte offset within the page where the leaf nodes begin. */
  private static final int LEAVES_START_OFFSET;

  static {
    // Maximum number of 1-byte cells that could fit in the full page.
    final var pageCells = MAX_PAGE_SIZE_BYTES / CELL_SIZE;

    // Tree depth: floor(log2(pageCells)). The tree is as deep as possible while fitting
    // all nodes into the usable page area.
    LEVELS = Integer.SIZE - Integer.numberOfLeadingZeros(pageCells) - 1;

    // Total cells that fit in the usable area (after the DurablePage header).
    final var totalCells = (MAX_PAGE_SIZE_BYTES - NEXT_FREE_POSITION) / CELL_SIZE;
    // Internal nodes at levels 1..(LEVELS-1) = 2^(LEVELS-1) - 1.
    // Leaves = totalCells - internalNodes.
    CELLS_PER_PAGE = totalCells - ((1 << (LEVELS - 1)) - 1);

    LEAVES_START_OFFSET = nodeOffset(LEVELS, 0);
  }

  public FreeSpaceMapPage(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  /** Zeroes out the entire usable area, resetting all segment-tree nodes to 0. */
  public void init() {
    final var zeros = new byte[MAX_PAGE_SIZE_BYTES - NEXT_FREE_POSITION];
    setBinaryValue(NEXT_FREE_POSITION, zeros);
  }

  /**
   * Searches the segment tree for the leftmost leaf whose value is {@code >= requiredSize}.
   *
   * <p>Algorithm: start at the root. At each level, prefer the left child if it satisfies the
   * requirement; otherwise descend into the right child. This greedily finds the lowest-index
   * qualifying leaf in O(LEVELS) time.
   *
   * <pre>{@code
   *  Example (LEVELS=4, requiredSize=50):
   *
   *          [120]           <- root: max=120 >= 50, proceed
   *         /     \
   *      [80]     [120]      <- left=80 >= 50, go left
   *      / \       / \
   *   [40] [80]  [90][120]   <- left=40 < 50, go right (80 >= 50)
   *              ...
   *         [80]             <- leaf found at this index
   * }</pre>
   *
   * @param requiredSize the minimum normalized free-space value needed
   * @return the leaf index (0-based) of a qualifying page, or {@code -1} if none qualifies
   */
  public int findPage(int requiredSize) {
    var nodeIndex = 0;

    // Check the root first: if the global maximum is too small, no leaf can satisfy.
    final var maxValue = 0xFF & getByteValue(nodeOffset(1, 0));
    if (maxValue == 0 || maxValue < requiredSize) {
      return -1;
    }

    // Descend through the tree levels, choosing left or right child.
    for (var level = 2; level <= LEVELS; level++) {
      final var leftNodeIndex = nodeIndex << 1;
      if (leftNodeIndex >= CELLS_PER_PAGE) {
        return -1;
      }
      final var leftNodeOffset = nodeOffset(level, leftNodeIndex);

      final var leftMax = 0xFF & getByteValue(leftNodeOffset);
      if (leftMax >= requiredSize) {
        // Left child satisfies -- prefer lower indices for locality.
        nodeIndex <<= 1;
      } else {
        // Left child insufficient -- the right child must satisfy (guaranteed by parent).
        final var rightNodeIndex = (nodeIndex << 1) + 1;

        if (rightNodeIndex >= CELLS_PER_PAGE) {
          return -1;
        }

        assert (0xFF & getByteValue(nodeOffset(level, rightNodeIndex))) >= requiredSize;
        nodeIndex = (nodeIndex << 1) + 1;
      }
    }

    return nodeIndex;
  }

  /**
   * Updates the normalized free-space value for the leaf at {@code pageIndex} and propagates
   * the change up to the root, recalculating each ancestor as the max of its two children.
   *
   * <pre>{@code
   *  Example: update leaf[3] from 40 to 90
   *
   *  Before:                    After:
   *        [80]                       [90]       <- root updated
   *       /    \                     /    \
   *    [80]    [60]              [90]    [60]     <- parent updated
   *    / \     / \               / \     / \
   *  [50][80][40][60]          [50][80][90][60]   <- leaf updated
   *              ^                      ^
   *         pageIndex=3            pageIndex=3
   * }</pre>
   *
   * <p>If the leaf already holds the requested value, the method short-circuits and returns the
   * current root value without writing anything.
   *
   * @param pageIndex the 0-based leaf index to update
   * @param freeSpace the new normalized free-space value (0..255)
   * @return the new root value (global maximum) after the update
   */
  public int updatePageMaxFreeSpace(final int pageIndex, final int freeSpace) {
    assert freeSpace < (1 << (CELL_SIZE * 8));
    assert pageIndex >= 0;

    if (pageIndex >= CELLS_PER_PAGE) {
      throw new IllegalArgumentException("Page index " + pageIndex + " exceeds tree capacity");
    }

    // Start at the leaf level and walk upward to the root.
    var nodeOffset = LEAVES_START_OFFSET + pageIndex * CELL_SIZE;
    var nodeIndex = pageIndex;
    var nodeValue = freeSpace;

    for (var level = LEVELS; level > 0; level--) {
      final var prevValue = 0xFF & getByteValue(nodeOffset);
      if (prevValue == nodeValue) {
        // Value unchanged at this level -- the ancestors won't change either.
        return 0xFF & getByteValue(nodeOffset(1, 0));
      }

      setByteValue(nodeOffset, (byte) nodeValue);
      if (level == 1) {
        // Reached the root -- return the new root value.
        return nodeValue;
      }

      // Determine the sibling index (left <-> right).
      final int siblingIndex;
      if ((nodeIndex & 1) == 0) {
        siblingIndex = nodeIndex + 1;
      } else {
        siblingIndex = nodeIndex - 1;
      }

      // Read the sibling's value. If the sibling is beyond the page boundary (can happen
      // for the rightmost node at a level), use the current node's value as the sibling.
      final var siblingOffset = nodeOffset(level, siblingIndex);
      final int siblingValue;
      if (siblingOffset + 2 <= MAX_PAGE_SIZE_BYTES) {
        siblingValue = 0xFF & getByteValue(siblingOffset);
      } else {
        siblingValue = nodeValue;
      }

      // The parent's value is the max of the two children.
      nodeValue = Math.max(nodeValue, siblingValue);
      nodeIndex = nodeIndex >> 1;
      nodeOffset = nodeOffset(level - 1, nodeIndex);
    }

    // unreachable -- the loop always returns when level == 1
    assert false;
    return 0;
  }

  /**
   * Computes the byte offset within the page for the node at the given level and index.
   *
   * <p>The formula uses the level-order (BFS) layout where level {@code L} starts at
   * offset {@code NEXT_FREE_POSITION + (2^(L-1) - 1) * CELL_SIZE}, and node index {@code I}
   * within that level is at an additional offset of {@code I * CELL_SIZE}.
   *
   * @param nodeLevel the tree level (1 = root, LEVELS = leaves)
   * @param nodeIndex the 0-based index within the level
   * @return the byte offset within the page
   */
  private static int nodeOffset(int nodeLevel, int nodeIndex) {
    return NEXT_FREE_POSITION + ((1 << (nodeLevel - 1)) - 1 + nodeIndex) * CELL_SIZE;
  }
}
