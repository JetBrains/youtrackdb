package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.FilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

/// A filtering step that replaces the standard TinkerPop "hasLabel" step. It tests "~label"
/// predicates natively (via YouTrackDB API) and respects the "polymorphicQuery" traversal setting.
public class YTDBHasLabelStep<S extends YTDBElement> extends FilterStep<S> {

  private List<P<? super String>> predicates;
  private boolean polymorphic;

  public YTDBHasLabelStep(
      final Traversal.Admin<?, ?> traversal,
      List<P<? super String>> predicates,
      boolean polymorphic) {
    super(traversal);
    this.predicates = predicates;
    this.polymorphic = polymorphic;
  }

  @Override
  public Set<TraverserRequirement> getRequirements() {
    return EnumSet.of(TraverserRequirement.OBJECT);
  }

  public List<P<? super String>> getPredicates() {
    return predicates;
  }

  @Override
  protected boolean filter(Admin<S> traverser) {

    // will it make sense to add a step-level cache for storing the names of the classes
    // that fit or don't fit the predicate? should it be thread-safe?

    // Delegate to the shared matcher so the class-scan path (this step) and the by-id path in
    // YTDBGraphStep apply identical polymorphism-aware label matching.
    return YTDBLabelMatcher.matches(traverser.get(), predicates, polymorphic);
  }

  @Override
  public String toString() {
    return StringFactory.stepString(this, predicates, polymorphic);
  }

  @Override
  public boolean equals(Object o) {
    return super.equals(o);
  }

  @Override
  public int hashCode() {
    return super.hashCode() ^
        predicates.hashCode() ^
        Boolean.hashCode(polymorphic);
  }

  @Override
  public AbstractStep<S, S> clone() {
    final var clone = (YTDBHasLabelStep<S>) super.clone();
    clone.polymorphic = this.polymorphic;
    // predicates appear to be mutable (setValue method)
    clone.predicates = this.predicates.stream()
        .<P<? super String>>map(P::clone)
        .toList();
    return clone;
  }
}
