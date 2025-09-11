package com.jetbrains.youtrackdb.internal.server.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.common.io.YTDBIOUtils;
import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrackdb.internal.core.sql.functions.CustomSQLFunctionFactory;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.plugin.ServerPluginAbstract;
import com.jetbrains.youtrackdb.internal.tools.config.ServerParameterConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Server Plugin to register custom SQL functions.
 */
public class CustomSQLFunctionPlugin extends ServerPluginAbstract {
  private static final char PREFIX_NAME_SEPARATOR = '_';
  private static final Pattern PATTERN = Pattern.compile("^[\\pL]+$");

  private Map<String, Object> configuration;

  @Override
  public String getName() {
    return "custom-sql-functions-manager";
  }

  @Override
  public void config(YouTrackDBServer youTrackDBServer, ServerParameterConfiguration[] iParams) {
    configuration = new HashMap<>();

    final var configFile =
        Arrays.stream(iParams)
            .filter(p -> p.name.equalsIgnoreCase("config"))
            .map(p -> p.value.trim())
            .map(SystemVariableResolver::resolveSystemVariables)
            .map(File::new)
            .filter(File::exists)
            .findFirst()
            .orElseThrow(
                () ->
                    new ConfigurationException(
                        "Custom SQL functions configuration file not found"));

    try {
      var configurationContent = YTDBIOUtils.readFileAsString(configFile);
      configurationContent = removeComments(configurationContent);
      var objectMapper = new ObjectMapper();
      var typeReference = objectMapper.getTypeFactory()
          .constructMapType(Map.class, String.class, Object.class);
      configuration = objectMapper.readValue(configurationContent, typeReference);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new ConfigurationException(
              "Cannot load Custom SQL configuration file '"
                  + configFile
                  + "'. No custom functions will be disabled"),
          e, (String) null);
    }
  }

  @Nullable
  private static String removeComments(String configurationContent) {
    if (configurationContent == null) {
      return null;
    }
    var result = new StringBuilder();
    var split = configurationContent.split("\n");
    var first = true;
    for (var row : split) {
      if (row.trim().startsWith("//")) {
        continue;
      }
      if (!first) {
        result.append("\n");
      }
      result.append(row);
      first = false;
    }
    return result.toString();
  }

  @Override
  public void startup() {
    if (Boolean.TRUE.equals(configuration.get("enabled"))) {
      @SuppressWarnings("unchecked")
      var functions = (List<Map<String, String>>) configuration.get("functions");
      for (var function : functions) {
        final var prefix = function.get("prefix");
        final var clazz = function.get("class");
        if (prefix == null || clazz == null) {
          throw new ConfigurationException(
              "Unable to load functions without prefix and / or class ");
        }
        if (!PATTERN.matcher(prefix).matches()) {
          throw new ConfigurationException(
              "Unable to load functions with prefix '"
                  + prefix
                  + "'. Prefixes can be letters only");
        }

        try {
          var functionsClass = Class.forName(clazz);
          CustomSQLFunctionFactory.register(prefix + PREFIX_NAME_SEPARATOR, functionsClass);
        } catch (ClassNotFoundException e) {
          throw BaseException.wrapException(
              new ConfigurationException(
                  "Unable to load class " + clazz + " for custom functions with prefix " + prefix),
              e, (String) null);
        }
      }
    }
  }
}
