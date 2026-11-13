package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.EdgeLabelVerificationStrategy;

/** Borrowed session and strategy flags for one application, including its synthetic union forks. */
record TranslationEnvironment(DatabaseSessionEmbedded session, boolean edgeLabelVerification,
    @Nullable Set<String> productiveKeys) {

  static TranslationEnvironment resolve(Traversal.Admin<?, ?> traversal,
      DatabaseSessionEmbedded session) {
    var strategies = traversal.getStrategies();
    return new TranslationEnvironment(session,
        strategies.getStrategy(EdgeLabelVerificationStrategy.class).isPresent(),
        strategies.getStrategy(ProductiveByStrategy.class)
            .map(ProductiveByStrategy::getProductiveKeys).orElse(null));
  }
}
