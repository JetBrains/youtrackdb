package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import java.util.Objects;

/** Scoped post-pattern predicates. Detached bindings never escape a leaf. */
public sealed interface MatchBooleanCheck {

  record And(List<MatchBooleanCheck> children) implements MatchBooleanCheck {
    public And {
      children = List.copyOf(children);
    }
  }

  record Or(List<MatchBooleanCheck> children) implements MatchBooleanCheck {
    public Or {
      children = List.copyOf(children);
    }
  }

  record Not(MatchBooleanCheck child) implements MatchBooleanCheck {
    public Not {
      Objects.requireNonNull(child);
    }
  }

  record Filter(String alias, SQLWhereClause condition) implements MatchBooleanCheck {
    public Filter {
      Objects.requireNonNull(alias);
      Objects.requireNonNull(condition);
    }
  }

  /** Positive existence. Use a NOT node to express negative existence. */
  record Probe(SQLMatchExpression expression) implements MatchBooleanCheck {
    public Probe {
      Objects.requireNonNull(expression);
    }
  }
}
