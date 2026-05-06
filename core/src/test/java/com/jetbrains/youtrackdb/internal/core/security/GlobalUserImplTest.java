package com.jetbrains.youtrackdb.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Tests {@link GlobalUserImpl} as a 3-field POJO with public setters. Each field round-trips
 * via getter/setter and is exposed through the {@link GlobalUser} interface contract.
 */
public class GlobalUserImplTest {

  @Test
  public void shouldStoreConstructorArguments() {
    final var user = new GlobalUserImpl("admin", "pwd123", "*");
    assertThat(user.getName()).isEqualTo("admin");
    assertThat(user.getPassword()).isEqualTo("pwd123");
    assertThat(user.getResources()).isEqualTo("*");
  }

  @Test
  public void shouldRoundTripSetters() {
    final var user = new GlobalUserImpl("orig-name", "orig-pwd", "orig-res");
    user.setName("new-name");
    user.setPassword("new-pwd");
    user.setResources("new-res");
    assertThat(user.getName()).isEqualTo("new-name");
    assertThat(user.getPassword()).isEqualTo("new-pwd");
    assertThat(user.getResources()).isEqualTo("new-res");
  }

  @Test
  public void shouldExposeViaGlobalUserInterface() {
    // GlobalUserImpl is the production-only implementer of GlobalUser; pinning the
    // interface assignability ensures consumers that take a GlobalUser still compile.
    final GlobalUser asInterface = new GlobalUserImpl("a", "b", "c");
    assertThat(asInterface.getName()).isEqualTo("a");
    assertThat(asInterface.getPassword()).isEqualTo("b");
    assertThat(asInterface.getResources()).isEqualTo("c");
  }

  @Test
  public void shouldAllowNullArguments() {
    // The constructor performs no null-checks; this pins the existing nullable contract so
    // a future change that introduces a guard surfaces here.
    final var user = new GlobalUserImpl(null, null, null);
    assertThat(user.getName()).isNull();
    assertThat(user.getPassword()).isNull();
    assertThat(user.getResources()).isNull();
  }
}
