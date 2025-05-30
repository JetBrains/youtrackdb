package com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization;

import com.jetbrain.youtrack.db.gremlin.internal.io.YTDBIoRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.apache.tinkerpop.gremlin.process.traversal.IO;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IoStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;

public class YTDBGraphIoStepStrategy extends
    AbstractTraversalStrategy<TraversalStrategy.FinalizationStrategy>
    implements TraversalStrategy.FinalizationStrategy {

  public static final YTDBGraphIoStepStrategy INSTANCE = new YTDBGraphIoStepStrategy();

  public static YTDBGraphIoStepStrategy instance() {
    return INSTANCE;
  }

  private YTDBGraphIoStepStrategy() {
  }

  @Override
  public void apply(Admin<?, ?> traversal) {
    var steps = traversal.getSteps();
    for (var step : steps) {
      if (step instanceof IoStep<?> ioStep) {
        var parameters = ioStep.getParameters();
        var registries = parameters.get(IO.registry, null);
        if (registries == null) {
          registries = new ArrayList<>();
          registries.add(YTDBIoRegistry.instance());
        } else {
          if (registries.size() > 8) {
            var hashedRegistries = Collections.newSetFromMap(
                new IdentityHashMap<>(registries.size()));
            hashedRegistries.addAll(registries);
            if (!hashedRegistries.contains(YTDBIoRegistry.instance())) {
              parameters.set(null, IO.registry, YTDBIoRegistry.instance());
            }
          } else if (!registries.contains(YTDBIoRegistry.instance())) {
            parameters.set(null, IO.registry, YTDBIoRegistry.instance());
          }
        }
      }
    }
  }
}
