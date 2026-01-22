package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;

public class YTDBSettings extends Settings {
  public YouTrackDBServer server;
  public boolean isAfterFirstTime;

  public List<YTDBUser> users = new ArrayList<>();
  public Map<String, String> properties = new HashMap<>();
  public List<YTDBStorage> storages = new ArrayList<>();

  protected static NodeMapper createDefaultYamlConstructor() {
    final var options = new LoaderOptions();

    final var constructor = new NodeMapper(YTDBSettings.class, options);
    final var settingsDescription = new TypeDescription(YTDBSettings.class);
    settingsDescription.setExcludes("server", "scheduledExecutorService", "isAfterFirstTime");

    settingsDescription.addPropertyParameters("users", YTDBUser.class);
    settingsDescription.addPropertyParameters("properties", String.class, String.class);
    settingsDescription.addPropertyParameters("storages", YTDBStorage.class);

    settingsDescription.addPropertyParameters("graphs", String.class, String.class);
    settingsDescription.addPropertyParameters("scriptEngines", String.class,
        ScriptEngineSettings.class);
    settingsDescription.addPropertyParameters("serializers", SerializerSettings.class);
    settingsDescription.addPropertyParameters("processors", ProcessorSettings.class);
    constructor.addTypeDescription(settingsDescription);

    final var serializerSettingsDescription = new TypeDescription(
        SerializerSettings.class);
    serializerSettingsDescription.addPropertyParameters("config", String.class, Object.class);
    constructor.addTypeDescription(serializerSettingsDescription);

    final var scriptEngineSettingsDescription = new TypeDescription(
        ScriptEngineSettings.class);
    scriptEngineSettingsDescription.addPropertyParameters("imports", String.class);
    scriptEngineSettingsDescription.addPropertyParameters("staticImports", String.class);
    scriptEngineSettingsDescription.addPropertyParameters("scripts", String.class);
    scriptEngineSettingsDescription.addPropertyParameters("config", String.class, Object.class);
    scriptEngineSettingsDescription.addPropertyParameters("plugins", String.class, Object.class);
    constructor.addTypeDescription(scriptEngineSettingsDescription);

    final var userSettings = new TypeDescription(YTDBUser.class);
    constructor.addTypeDescription(userSettings);

    final var storageSettings = new TypeDescription(YTDBStorage.class);
    constructor.addTypeDescription(storageSettings);

    final var sslSettings = new TypeDescription(SslSettings.class);
    constructor.addTypeDescription(sslSettings);

    final var authenticationSettings = new TypeDescription(
        AuthenticationSettings.class);
    constructor.addTypeDescription(authenticationSettings);

    final var serverMetricsDescription = new TypeDescription(ServerMetrics.class);
    constructor.addTypeDescription(serverMetricsDescription);

    final var consoleReporterDescription = new TypeDescription(
        ConsoleReporterMetrics.class);
    constructor.addTypeDescription(consoleReporterDescription);

    final var csvReporterDescription = new TypeDescription(CsvReporterMetrics.class);
    constructor.addTypeDescription(csvReporterDescription);

    final var jmxReporterDescription = new TypeDescription(JmxReporterMetrics.class);
    constructor.addTypeDescription(jmxReporterDescription);

    final var slf4jReporterDescription = new TypeDescription(
        Slf4jReporterMetrics.class);
    constructor.addTypeDescription(slf4jReporterDescription);

    final var gangliaReporterDescription = new TypeDescription(
        GangliaReporterMetrics.class);
    constructor.addTypeDescription(gangliaReporterDescription);

    final var graphiteReporterDescription = new TypeDescription(
        GraphiteReporterMetrics.class);
    constructor.addTypeDescription(graphiteReporterDescription);
    return constructor;
  }

  /// Read configuration from a file into a new [YTDBSettings] object.
  ///
  /// @param  file an input file containing a Gremlin Server YAML configuration
  /// @return a new [YTDBSettings] object
  public static YTDBSettings read(final String file) {
    final var constructor = createDefaultYamlConstructor();
    final var yaml = new Yaml();

    var loadStack = new HashSet<String>();

    // Normalize the initial path
    var normalizedPath = normalizeInitialPath(file);
    var finalNode = loadNodeRecursive(yaml, normalizedPath, loadStack);
    if (finalNode == null) {
      return new YTDBSettings();
    }

    finalNode.setTag(new Tag(YTDBSettings.class));
    return (YTDBSettings) constructor.map(finalNode);
  }

  public static class YTDBUser {

    public YTDBUser() {
    }

    public YTDBUser(String name, String password, String resources) {
      this.name = name;
      this.password = password;
      this.resources = resources;
    }

    public String name;
    public String password;
    public String resources;
  }

  public static class YTDBStorage {

    public String name;
    public String path;
    public boolean loadOnStartup;
  }
}
