package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.RequirementsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.EdgeLabelVerificationStrategy;

/** Borrowed session and live strategy references for one application and its synthetic forks. */
record TranslationEnvironment(DatabaseSessionEmbedded session, boolean edgeLabelVerification,
    @Nullable Set<String> productiveKeys, @Nullable References references) {

  TranslationEnvironment(DatabaseSessionEmbedded session, boolean edgeLabelVerification,
      @Nullable Set<String> productiveKeys) {
    this(session, edgeLabelVerification, productiveKeys, null);
  }

  static final class References {
    final TraversalStrategies strategies;
    OptionsStrategy options;
    boolean standard;
    boolean edgeVerification;
    ProductiveByStrategy productive;
    RequirementsStrategy requirements;
    SackStrategy sack;
    TraverserMergeFacts.AnalysisObserver observer;

    References(TraversalStrategies strategies) {
      this.strategies = strategies;
    }

    private void scan(TraversalStrategies strategies, boolean root, boolean current) {
      for (var strategy : strategies) {
        // Match getStrategy's assignable, first-match contract.
        if (root) {
          if (strategy instanceof OptionsStrategy resolved && options == null) {
            options = resolved;
          } else if (strategy instanceof StandardOrderSemanticsStrategy) {
            standard = true;
          }
        }
        if (current) {
          if (strategy instanceof EdgeLabelVerificationStrategy) {
            edgeVerification = true;
          } else if (strategy instanceof ProductiveByStrategy resolved && productive == null) {
            productive = resolved;
          } else if (strategy instanceof RequirementsStrategy resolved && requirements == null) {
            requirements = resolved;
          } else if (strategy instanceof SackStrategy resolved && sack == null) {
            sack = resolved;
          } else if (strategy instanceof TraverserMergeFacts.AnalysisObserver resolved
              && observer == null) {
            observer = resolved;
          }
        }
      }
    }
  }

  static @Nullable References references(Traversal.Admin<?, ?> traversal,
      @Nullable TranslationEnvironment environment) {
    var refs = environment == null ? null : environment.references();
    // Synthetic forks share the scanned list, but sack setup still targets the walked traversal.
    return refs != null && refs.strategies == traversal.getStrategies() ? refs : null;
  }

  static TranslationEnvironment resolve(Traversal.Admin<?, ?> traversal,
      DatabaseSessionEmbedded session) {
    var current = traversal.getStrategies();
    var root = YTDBStrategyUtil.rootTraversal(traversal).getStrategies();
    var refs = new References(current);
    refs.scan(root, true, root == current);
    if (root != current) {
      refs.scan(current, false, true);
    }
    return new TranslationEnvironment(session, refs.edgeVerification,
        refs.productive == null ? null : refs.productive.getProductiveKeys(), refs);
  }
}
