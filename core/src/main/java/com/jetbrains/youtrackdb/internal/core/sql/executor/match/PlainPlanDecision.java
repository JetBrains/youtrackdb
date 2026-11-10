package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import java.util.Set;

/** Immutable plan analysis. This value does not execute or route queries. */
public record PlainPlanDecision(Reason reason, String offendingStepClass, Facts facts,
    boolean sliceAfterFilter, boolean ridProtected) {

  public PlainPlanDecision(Reason reason, String offendingStepClass, Facts facts) {
    this(reason, offendingStepClass, facts, reason == Reason.SLICE_AFTER_FILTER,
        reason == Reason.RID_SINGLE_HOP_PROTECTED);
  }

  public enum Reason {
    /** Every physical step and boundary condition passed. */
    PLAIN,
    /** An exact runtime class has no plain entry at this pipeline position. */
    UNKNOWN_STEP,
    /** The input is not a single ordinary linear pattern. */
    INPUT_SHAPE,
    /** A required pipeline stage is missing. */
    STEP_ORDER,
    /** The root source does not prove equivalent native start access. */
    START_ACCESS,
    /** Optional native source proof failed. The exception itself is not retained. */
    NATIVE_PROOF_ERROR,
    /** Metadata changed during cold routing. */
    STALE_GENERATION,
    /** The scheduled root or a prefetch alias differs from the Gremlin start alias. */
    ROOT_CHANGED,
    /** A hop changes direction, order, access or ordinary traversal semantics. */
    HOP_ACCESS,
    /** A predicate depends on another alias, parent or hidden query work. */
    PREDICATE,
    /** A projection is not a direct read of the boundary row. */
    PROJECTION,
    /** A boolean node owns detached probe, hash or repeated-probe memory work. */
    BOOLEAN_PROBE,
    /** A logical boolean node has no audited shape. */
    BOOLEAN_SHAPE,
    /** A root slice follows filtered streaming scan or hop work. */
    SLICE_AFTER_FILTER,
    /** The single-hop RID-start measurement protection keeps translation. */
    RID_SINGLE_HOP_PROTECTED,
    /** Concatenation or a post-concatenation reduction keeps translation. */
    MULTI_PLAN,
    /** A boundary field requests work outside direct current-row shaping. */
    BOUNDARY_SHAPE,
    /** List operations are not exact reverses around at most one fold. */
    BOUNDARY_OPERATION
  }

  public enum StartKind {
    UNKNOWN, CLASS_SCAN, RID, INDEX
  }

  /** Estimate dependence includes root choice and the estimate-dependent prefetch threshold. */
  public record Facts(StartKind startKind, boolean prefetchedStart, Set<String> scannedClasses,
      int hops) {
    public Facts {
      scannedClasses = Set.copyOf(scannedClasses);
    }
  }

  public boolean plain() {
    return reason == Reason.PLAIN;
  }

  public static PlainPlanDecision rejected(Reason reason, Object step) {
    return new PlainPlanDecision(reason, step == null ? null : step.getClass().getName(),
        new Facts(StartKind.UNKNOWN, false, Set.of(), 0));
  }
}
