package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Validates that gremlin-server.yaml configuration file references only classes
 * that exist in the classpath.
 *
 * NB:
 * - Validates direct class loading (graphManager, channelizer, authenticator),
 * but processors use ServiceLoader
 * - Test classpath may differ from production, but catches the common case
 * of deleted / mistyped classes referenced in config
 */
public class GremlinConfigValidationTest {
  private static final String GREMLIN_CONFIG_YAML = "server/config/gremlin-server.yaml";

  @Test
  public void productionGremlinConfigShouldReferenceOnlyExistingClasses() throws Exception {
    var configPath = findConfigPath();
    assertThat(configPath).as("gremlin-server.yaml should exist").isNotNull();
    assertThat(Files.exists(configPath))
        .as("Gremlin config file should exist at: " + configPath)
        .isTrue();

    YTDBSettings settings;
    try (var inputStream = new FileInputStream(configPath.toFile())) {
      settings = YTDBSettings.read(inputStream);
    }

    // Collect all class references from the config
    Set<String> classReferences = new LinkedHashSet<>();

    addIfPresent(classReferences, settings.channelizer);
    addIfPresent(classReferences, settings.graphManager);

    if (settings.processors != null) {
      settings.processors.forEach(processor -> addIfPresent(classReferences, processor.className));
    }

    if (settings.serializers != null) {
      settings.serializers.forEach(serializer -> addIfPresent(classReferences, serializer.className));
    }

    if (settings.authentication != null) {
      addIfPresent(classReferences, settings.authentication.authenticator);
      addIfPresent(classReferences, settings.authentication.authenticationHandler);
    }

    if (settings.authorization != null) {
      addIfPresent(classReferences, settings.authorization.authorizer);
    }

    // Validate all class references
    List<String> missingClasses = new ArrayList<>();
    for (var className : classReferences) {
      try {
        Class.forName(className);
      } catch (ClassNotFoundException e) {
        missingClasses.add(className);
      }
    }

    assertThat(missingClasses)
        .as(
            "Gremlin config %s references %d missing class(es): %s",
            GREMLIN_CONFIG_YAML, missingClasses.size(), String.join("\n", missingClasses))
        .isEmpty();
  }

  private static void addIfPresent(Set<String> classReferences, String className) {
    if (className != null && !className.isBlank()) {
      classReferences.add(className);
    }
  }

  private static Path findConfigPath() {
    var current = Paths.get("").toAbsolutePath();

    while (current != null) {
      var configPath = current.resolve(GREMLIN_CONFIG_YAML);
      if (Files.exists(configPath)) {
        return configPath;
      }

      var parent = current.getParent();
      if (parent == null || parent.equals(current)) {
        break;
      }
      current = parent;
    }

    var fallbackPath = Paths.get("").toAbsolutePath().resolve("../" + GREMLIN_CONFIG_YAML);
    if (Files.exists(fallbackPath)) {
      return fallbackPath.normalize();
    }

    return null;
  }
}
