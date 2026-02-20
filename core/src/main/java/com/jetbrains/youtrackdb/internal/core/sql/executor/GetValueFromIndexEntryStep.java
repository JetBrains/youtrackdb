package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Intermediate step that extracts the record RID from an index entry and loads
 * the actual record from storage.
 *
 * <p>Index steps ({@link FetchFromIndexStep}) return key-RID pairs, not full records.
 * This step extracts the "rid" property from each index entry, optionally filters
 * it by allowed collection IDs (to ensure the record belongs to the target class),
 * and loads the full record.
 *
 * <pre>
 *  Pipeline (index-based query):
 *    FetchFromIndexStep -&gt; GetValueFromIndexEntryStep -&gt; [DistinctStep] -&gt; [FilterStep]
 *                          ^^^ this step ^^^
 *
 *  Input:  { key: "Alice", rid: #10:5 }  (index entry)
 *  Output: { @rid: #10:5, name: "Alice", age: 30, ... }  (full record)
 * </pre>
 *
 * @see FetchFromIndexStep
 */
public class GetValueFromIndexEntryStep extends AbstractExecutionStep {

  /**
   * Collection IDs to filter by (only records from these collections pass through).
   * Null means no filtering (all collections accepted).
   */
  private final IntArrayList filterCollectionIds;

  /**
   * @param ctx              the execution context
   * @param filterCollectionIds only extract values from these collections. Pass null if no filtering is
   *                         needed
   * @param profilingEnabled enable profiling
   */
  public GetValueFromIndexEntryStep(
      CommandContext ctx, IntArrayList filterCollectionIds, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.filterCollectionIds = filterCollectionIds;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {

    if (prev == null) {
      throw new IllegalStateException("GetValueFromIndexEntryStep requires a previous step");
    }
    var resultSet = prev.start(ctx);
    return resultSet.filter(this::filterMap);
  }

  @Nullable
  private Result filterMap(Result result, CommandContext ctx) {
    var finalVal = result.getProperty("rid");
    if (filterCollectionIds != null) {
      if (!(finalVal instanceof Identifiable id)) {
        return null;
      }
      var rid = id.getIdentity();
      var found = false;
      for (int filterCollectionId : filterCollectionIds) {
        // Negative collection ID means new (not-yet-committed) record; allow through.
        if (rid.getCollectionId() < 0 || filterCollectionId == rid.getCollectionId()) {
          found = true;
          break;
        }
      }
      if (!found) {
        return null;
      }
    }
    // Normal case: load the full record from storage using the RID.
    if (finalVal instanceof Identifiable) {
      return new ResultInternal(ctx.getDatabaseSession(), (Identifiable) finalVal);

    // The index entry already contains a full result (e.g. from a subquery index).
    } else if (finalVal instanceof Result) {
      return (Result) finalVal;
    }
    return null;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ EXTRACT VALUE FROM INDEX ENTRY";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    if (filterCollectionIds != null) {
      result += "\n";
      result += spaces;
      result += "  filtering collections [";
      result +=
          filterCollectionIds
              .intStream()
              .boxed()
              .map(String::valueOf)
              .collect(Collectors.joining(","));
      result += "]";
    }
    return result;
  }

  /** Cacheable: collection ID filter list is fixed at construction time. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new GetValueFromIndexEntryStep(ctx, this.filterCollectionIds, this.profilingEnabled);
  }
}
