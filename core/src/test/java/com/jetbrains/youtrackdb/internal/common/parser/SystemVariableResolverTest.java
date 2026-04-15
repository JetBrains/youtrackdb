package com.jetbrains.youtrackdb.internal.common.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Tests for {@link SystemVariableResolver} — resolves system properties and environment variables
 * embedded in strings using the ${variable} syntax.
 */
public class SystemVariableResolverTest {

  // ---------------------------------------------------------------------------
  // resolveVariable — system property lookup
  // ---------------------------------------------------------------------------

  /** Resolves a known system property (java.version is always present). */
  @Test
  public void resolveVariableReturnsKnownSystemProperty() {
    String expected = System.getProperty("java.version");
    String result = SystemVariableResolver.resolveVariable("java.version");
    assertThat(result).isEqualTo(expected);
  }

  /** Unknown property without default returns null. */
  @Test
  public void resolveVariableUnknownPropertyReturnsNull() {
    String result =
        SystemVariableResolver.resolveVariable(
            "nonexistent.property.for.test.xyz.12345");
    assertThat(result).isNull();
  }

  /** Unknown property with default returns the default value. */
  @Test
  public void resolveVariableUnknownPropertyWithDefaultReturnsDefault() {
    String result =
        SystemVariableResolver.resolveVariable(
            "nonexistent.property.for.test.xyz.12345", "myDefault");
    assertThat(result).isEqualTo("myDefault");
  }

  /** Null variable name returns null. */
  @Test
  public void resolveVariableNullReturnsNull() {
    String result = SystemVariableResolver.resolveVariable(null);
    assertThat(result).isNull();
  }

  /** Null variable with default still returns null (null check is first). */
  @Test
  public void resolveVariableNullWithDefaultReturnsNull() {
    String result = SystemVariableResolver.resolveVariable(null, "default");
    assertThat(result).isNull();
  }

  // ---------------------------------------------------------------------------
  // resolveVariable — environment variable fallback
  // ---------------------------------------------------------------------------

  /**
   * Resolves an environment variable when no system property exists. PATH is present on virtually
   * all systems.
   */
  @Test
  public void resolveVariableFallsBackToEnvironment() {
    String envPath = System.getenv("PATH");
    // Only test if PATH is actually set
    if (envPath != null) {
      // Use an env var name that's not a system property
      String result = SystemVariableResolver.resolveVariable("PATH");
      assertThat(result).isEqualTo(envPath);
    }
  }

  // ---------------------------------------------------------------------------
  // resolveSystemVariables — string interpolation
  // ---------------------------------------------------------------------------

  /** Resolves a system variable embedded in a path string. */
  @Test
  public void resolveSystemVariablesInterpolatesKnownProperty() {
    String javaVersion = System.getProperty("java.version");
    String result =
        SystemVariableResolver.resolveSystemVariables("v${java.version}/lib");
    assertThat(result).isEqualTo("v" + javaVersion + "/lib");
  }

  /** String without variables is returned as-is. */
  @Test
  public void resolveSystemVariablesPassthroughWithoutVariables() {
    String result = SystemVariableResolver.resolveSystemVariables("/plain/path");
    assertThat(result).isEqualTo("/plain/path");
  }

  /** Null path returns null (uses the default). */
  @Test
  public void resolveSystemVariablesNullPathReturnsNull() {
    String result = SystemVariableResolver.resolveSystemVariables(null);
    assertThat(result).isNull();
  }

  /** Null path with explicit default returns that default. */
  @Test
  public void resolveSystemVariablesNullPathWithDefaultReturnsDefault() {
    String result =
        SystemVariableResolver.resolveSystemVariables(null, "/fallback");
    assertThat(result).isEqualTo("/fallback");
  }

  /** Multiple variables in a single string are all resolved. */
  @Test
  public void resolveSystemVariablesMultipleVariables() {
    String javaVersion = System.getProperty("java.version");
    String osName = System.getProperty("os.name");
    String result =
        SystemVariableResolver.resolveSystemVariables(
            "${java.version}-${os.name}");
    assertThat(result).isEqualTo(javaVersion + "-" + osName);
  }

  /** Unknown variable in a mixed string is replaced with empty string. */
  @Test
  public void resolveSystemVariablesUnknownVariableReplacedWithEmpty() {
    String result =
        SystemVariableResolver.resolveSystemVariables(
            "before-${nonexistent.xyz.12345}-after");
    assertThat(result).isEqualTo("before--after");
  }

  /** Default value is used when the path itself is null. */
  @Test
  public void resolveSystemVariablesDefaultWhenPathNull() {
    String result =
        SystemVariableResolver.resolveSystemVariables(null, "default-value");
    assertThat(result).isEqualTo("default-value");
  }

  // ---------------------------------------------------------------------------
  // resolve (instance method — VariableParserListener interface)
  // ---------------------------------------------------------------------------

  /** The instance resolve method delegates to the static resolveVariable. */
  @Test
  public void instanceResolveMethodDelegatesToStatic() {
    SystemVariableResolver resolver = new SystemVariableResolver();
    String expected = System.getProperty("java.version");
    String result = resolver.resolve("java.version");
    assertThat(result).isEqualTo(expected);
  }

  /** The instance resolve method returns null for unknown variables. */
  @Test
  public void instanceResolveMethodReturnsNullForUnknown() {
    SystemVariableResolver resolver = new SystemVariableResolver();
    String result = resolver.resolve("nonexistent.property.for.test.xyz.12345");
    assertThat(result).isNull();
  }

  // ---------------------------------------------------------------------------
  // Constants
  // ---------------------------------------------------------------------------

  /** VAR_BEGIN and VAR_END have the expected values. */
  @Test
  public void constantsHaveExpectedValues() {
    assertThat(SystemVariableResolver.VAR_BEGIN).isEqualTo("${");
    assertThat(SystemVariableResolver.VAR_END).isEqualTo("}");
  }
}
