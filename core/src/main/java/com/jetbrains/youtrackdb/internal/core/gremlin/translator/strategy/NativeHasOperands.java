package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.Collection;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.PBiPredicate;
import org.apache.tinkerpop.gremlin.process.traversal.Text;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;

/** Literal operands captured before SQL normalisation, in the order native HasContainers read them. */
record NativeHasOperands(List<NativeHasOperands.Container> containers) {

  NativeHasOperands {
    containers = List.copyOf(containers);
  }

  static NativeHasOperands capture(List<HasContainer> containers) {
    return new NativeHasOperands(containers.stream()
        .map(c -> new Container(c.getKey(), capturePredicate(c.getPredicate()))).toList());
  }

  /** Only known, structurally encoded operators may enter a reusable ordered-filter template. */
  static boolean cacheable(P<?> predicate) {
    if (predicate == null) {
      return false;
    }
    if (predicate instanceof NotP<?> not) {
      return predicate.getClass() == NotP.class && cacheable(not.negate());
    }
    if (predicate instanceof AndP<?> and) {
      return predicate.getClass() == AndP.class
          && and.getPredicates().stream().allMatch(NativeHasOperands::cacheable);
    }
    if (predicate instanceof OrP<?> or) {
      return predicate.getClass() == OrP.class
          && or.getPredicates().stream().allMatch(NativeHasOperands::cacheable);
    }
    var operator = predicate.getBiPredicate();
    if (predicate.getClass() == TextP.class) {
      return operator instanceof Text || (operator != null
          && operator.getClass() == Text.RegexPredicate.class);
    }
    if (predicate.getClass() != P.class) {
      return false;
    }
    Object value = predicate.getValue();
    // P retains collection-versus-singleton state internally. getValue() cannot reproduce it
    // reliably, even when it returns a List for an originally non-List operand.
    return !(value instanceof Collection<?>)
        && (operator instanceof Compare || operator instanceof Contains
            || operator instanceof Text);
  }

  /** Regex has a bindable native pattern even though its MATCH adapter allocates no SQL slot. */
  static boolean regexOnly(P<?> predicate) {
    if (predicate == null) {
      return false;
    }
    if (predicate.getClass() == NotP.class) {
      return regexOnly(((NotP<?>) predicate).negate());
    }
    if (predicate.getClass() == AndP.class) {
      return ((AndP<?>) predicate).getPredicates().stream()
          .allMatch(NativeHasOperands::regexOnly);
    }
    if (predicate.getClass() == OrP.class) {
      return ((OrP<?>) predicate).getPredicates().stream()
          .allMatch(NativeHasOperands::regexOnly);
    }
    var operator = predicate.getBiPredicate();
    return (predicate.getClass() == TextP.class || predicate.getClass() == P.class)
        && operator != null && operator.getClass() == Text.RegexPredicate.class;
  }

  List<HasContainer> rebuild() {
    return containers.stream().map(c -> new HasContainer(c.key(), c.predicate().rebuild()))
        .toList();
  }

  record Container(String key, Predicate predicate) {
  }

  sealed interface Predicate {
    P<?> rebuild();
  }

  private record Junction(boolean and, List<Predicate> children) implements Predicate {
    @Override
    public P<?> rebuild() {
      var predicates = children.stream().map(Predicate::rebuild).toList();
      @SuppressWarnings({"rawtypes", "unchecked"})
      P<?> rebuilt = and ? new AndP((List) predicates) : new OrP((List) predicates);
      return rebuilt;
    }
  }

  private record Negation(Predicate child) implements Predicate {
    @Override
    public P<?> rebuild() {
      return P.not(child.rebuild());
    }
  }

  /** Unsafe predicates stay on this invocation's fresh path and never enter a shared template. */
  private record Original(P<?> predicate) implements Predicate {
    @Override
    public P<?> rebuild() {
      return predicate;
    }
  }

  private record Leaf(PBiPredicate<?, ?> operator, Object operand, boolean text)
      implements Predicate {
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public P<?> rebuild() {
      // RegexPredicate carries a compiled pattern of its own. P.setValue cannot replace it.
      if (operator instanceof Text.RegexPredicate regex) {
        return regex.isNegate() ? TextP.notRegex((String) operand) : TextP.regex((String) operand);
      }
      return text ? new TextP((PBiPredicate) operator, (String) operand)
          : new P((PBiPredicate) operator, operand);
    }
  }

  private static Predicate capturePredicate(P<?> predicate) {
    if (!cacheable(predicate)) {
      return new Original(predicate);
    }
    if (predicate instanceof NotP<?> not) {
      return new Negation(capturePredicate(not.negate()));
    }
    if (predicate instanceof AndP<?> and) {
      return new Junction(true, and.getPredicates().stream()
          .map(NativeHasOperands::capturePredicate).toList());
    }
    if (predicate instanceof OrP<?> or) {
      return new Junction(false, or.getPredicates().stream()
          .map(NativeHasOperands::capturePredicate).toList());
    }
    Object value = predicate.getBiPredicate() instanceof Text.RegexPredicate regex
        ? regex.getPattern() : predicate.getValue();
    return new Leaf(predicate.getBiPredicate(), value, predicate.getClass() == TextP.class);
  }
}
