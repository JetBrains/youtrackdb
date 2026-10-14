package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.Test;

/** Validation and value semantics for the value-free runtime start descriptor. */
public class RuntimeRidStartTest {

  /** A runtime start carries its alias, class and slot without holding a RID value. */
  @Test
  public void validStart_hasValueSemantics() {
    var start = new RuntimeRidStart("a", "V", 2);
    assertThat(start.alias()).isEqualTo("a");
    assertThat(start.aliasClass()).isEqualTo("V");
    assertThat(start.parameterSlot()).isEqualTo(2);
    assertThat(start).isEqualTo(new RuntimeRidStart("a", "V", 2));
    assertThat(start.hashCode()).isEqualTo(new RuntimeRidStart("a", "V", 2).hashCode());
    assertThat(start).isNotEqualTo(new RuntimeRidStart("b", "V", 2));
    assertThat(start).isNotEqualTo(new RuntimeRidStart("a", "E", 2));
    assertThat(start).isNotEqualTo(new RuntimeRidStart("a", "V", 3));
    assertThat(start).isNotEqualTo("a");
  }

  /** A missing alias or class and a negative slot never enter planner input. */
  @Test
  public void invalidStart_failsBeforeConstruction() {
    assertThatNullPointerException().isThrownBy(() -> new RuntimeRidStart(null, "V", 0))
        .withMessageContaining("alias");
    assertThatNullPointerException().isThrownBy(() -> new RuntimeRidStart("a", null, 0))
        .withMessageContaining("class");
    assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeRidStart("", "V", 0))
        .withMessageContaining("empty");
    assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeRidStart("a", "", 0))
        .withMessageContaining("empty");
    assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeRidStart("a", "V", -1))
        .withMessageContaining("slot");
  }
}
