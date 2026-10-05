package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Rebuild paths must reject runtime starts instead of silently losing their descriptors. */
public class RuntimeRidStartRebuildTest {

  private static MatchPlanInputs inputs(boolean withStart) {
    var builder = MatchPlanInputs.builder(new Pattern())
        .returnItems(List.of(new SQLExpression(-1)))
        .returnAliases(List.of(new SQLIdentifier("old")))
        .returnNestedProjections(Arrays.asList((SQLNestedProjection) null));
    if (withStart) {
      builder.runtimeRidStarts(Map.of("a", new RuntimeRidStart("a", "V", 0)));
    }
    return builder.build();
  }

  /** Union rewrite rejects a descriptor even when the aliases already agree. */
  @Test
  public void unionRewrite_rejectsRuntimeStartBeforeNoOp() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UnionStepRecogniser.rewriteReturnAlias(inputs(true), "old", "old"))
        .withMessageContaining("runtime RID starts");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UnionStepRecogniser.rewriteReturnAlias(inputs(true), "old", "new"))
        .withMessageContaining("runtime RID starts");
  }

  /** A descriptor-free union child still rewrites a return column and retains its shape. */
  @Test
  public void unionRewrite_withoutRuntimeStart_keepsReturnRewrite() {
    var input = inputs(false);
    assertThat(UnionStepRecogniser.rewriteReturnAlias(input, "old", "old")).isSameAs(input);
    assertThat(UnionStepRecogniser.rewriteReturnAlias(input, "unknown", "new"))
        .isSameAs(input);
    var rewritten = UnionStepRecogniser.rewriteReturnAlias(input, "old", "new");
    assertThat(rewritten.returnAliases().getFirst().getStringValue()).isEqualTo("new");
    assertThat(rewritten.runtimeRidStarts()).isEmpty();
  }

  /** Count rewrite rejects any runtime start rather than silently dropping it. */
  @Test
  public void countRewrite_rejectsRuntimeStart() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PostConcatSupport.rewriteToCountStar(inputs(true)))
        .withMessageContaining("runtime RID starts");
  }

  /** Descriptor-free count rewrite still produces a single count column. */
  @Test
  public void countRewrite_withoutRuntimeStart_keepsCountProjection() {
    var rewritten = PostConcatSupport.rewriteToCountStar(inputs(false));
    assertThat(rewritten.returnItems()).hasSize(1);
    assertThat(rewritten.returnAliases()).containsExactly((SQLIdentifier) null);
    assertThat(rewritten.runtimeRidStarts()).isEmpty();
  }
}
