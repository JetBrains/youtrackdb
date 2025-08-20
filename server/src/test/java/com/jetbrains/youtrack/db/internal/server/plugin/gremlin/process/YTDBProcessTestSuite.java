package com.jetbrains.youtrack.db.internal.server.plugin.gremlin.process;

import org.apache.tinkerpop.gremlin.AbstractGremlinSuite;
import org.apache.tinkerpop.gremlin.process.traversal.CoreTraversalTest;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalEngine.Type;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalInterruptionTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.ComplexTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.OrderabilityTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.TernaryBooleanLogicsTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.BranchTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.ChooseTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.LocalTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.OptionalTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.UnionTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.CoinTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.CyclicPathTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DropTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.FilterTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.IsTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.SampleTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.SimplePathTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TailTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CoalesceTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ConstantTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ElementMapTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FlatMapTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.IndexTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.LoopsTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MapTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MatchTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MathTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MaxTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MeanTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MergeEdgeTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MergeVertexTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MinTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PathTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ProfileTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ReadTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SumTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.UnfoldTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ValueMapTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.WriteTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.AggregateTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.ExplainTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.GroupCountTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.GroupTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.InjectTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SackTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SideEffectCapTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SideEffectTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.StoreTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SubgraphTest;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.TreeTest;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.ElementIdStrategyProcessTest;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.EventStrategyProcessTest;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.PartitionStrategyProcessTest;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SeedStrategyProcessTest;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SubgraphStrategyProcessTest;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.TranslationStrategyProcessTest;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.EarlyLimitStrategyProcessTest;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.IncidentToAdjacentStrategyProcessTest;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.ReadOnlyStrategyProcessTest;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

public class YTDBProcessTestSuite extends AbstractGremlinSuite {

  private static final Class<?>[] testsToExecute = new Class<?>[]{
      // branch
      BranchTest.Traversals.class,
      ChooseTest.Traversals.class,
      OptionalTest.Traversals.class,
      LocalTest.Traversals.class,
      RepeatTest.Traversals.class,
      UnionTest.Traversals.class,

      // filter
      AndTest.Traversals.class,
      CoinTest.Traversals.class,
      CyclicPathTest.Traversals.class,
      DedupTest.Traversals.class,
      DropTest.Traversals.class,
      FilterTest.Traversals.class,
      HasTest.Traversals.class,
      IsTest.Traversals.class,
      OrTest.Traversals.class,
      RangeTest.Traversals.class,
      SampleTest.Traversals.class,
      SimplePathTest.Traversals.class,
      TailTest.Traversals.class,
      WhereTest.Traversals.class,

      // map
      AddEdgeTest.Traversals.class,
      AddVertexTest.Traversals.class,
      YTDBTemporaryRidConversionTest.Traversals.class,
      CoalesceTest.Traversals.class,
      ConstantTest.Traversals.class,
      CountTest.Traversals.class,
      ElementMapTest.Traversals.class,
      FlatMapTest.Traversals.class,
      FoldTest.Traversals.class,
      GraphTest.Traversals.class,
      LoopsTest.Traversals.class,
      IndexTest.Traversals.class,
      MapTest.Traversals.class,
      MatchTest.CountMatchTraversals.class,
      MatchTest.GreedyMatchTraversals.class,
      MathTest.Traversals.class,
      MaxTest.Traversals.class,
      MeanTest.Traversals.class,
      MergeEdgeTest.Traversals.class,
      MergeVertexTest.Traversals.class,
      MinTest.Traversals.class,
      SumTest.Traversals.class,
      OrderTest.Traversals.class,
      PathTest.Traversals.class,
      ProfileTest.Traversals.class,
      ProjectTest.Traversals.class,
      PropertiesTest.Traversals.class,
      ReadTest.Traversals.class,
      SelectTest.Traversals.class,
      VertexTest.Traversals.class,
      UnfoldTest.Traversals.class,
      ValueMapTest.Traversals.class,
      WriteTest.Traversals.class,

      // sideEffect
      AggregateTest.Traversals.class,
      ExplainTest.Traversals.class,
      GroupTest.Traversals.class,
      GroupCountTest.Traversals.class,
      InjectTest.Traversals.class,
      SackTest.Traversals.class,
      SideEffectCapTest.Traversals.class,
      SideEffectTest.Traversals.class,
      StoreTest.Traversals.class,
      SubgraphTest.Traversals.class,
      TreeTest.Traversals.class,

      // compliance
      ComplexTest.Traversals.class,
      CoreTraversalTest.class,
      TraversalInterruptionTest.class,

      // creations
      TranslationStrategyProcessTest.class,

      // decorations
      ElementIdStrategyProcessTest.class,
      EventStrategyProcessTest.class,
      ReadOnlyStrategyProcessTest.class,
      PartitionStrategyProcessTest.class,
      SeedStrategyProcessTest.class,
      SubgraphStrategyProcessTest.class,

      // optimizations
      IncidentToAdjacentStrategyProcessTest.class,
      EarlyLimitStrategyProcessTest.class,

      // semantics
      OrderabilityTest.Traversals.class,
      TernaryBooleanLogicsTest.class,
  };

  public YTDBProcessTestSuite(Class<?> klass, RunnerBuilder builder)
      throws InitializationError {
    super(klass, builder, testsToExecute, testsToExecute, true, Type.STANDARD);
  }
}
