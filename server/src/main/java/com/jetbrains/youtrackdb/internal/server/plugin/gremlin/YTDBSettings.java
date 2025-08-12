package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class YTDBSettings extends Settings {
  public YouTrackDBServer server;

  protected static Constructor createDefaultYamlConstructor() {
    final var options = new LoaderOptions();

    final var constructor = new Constructor(YTDBSettings.class, options);
    final var settingsDescription = new TypeDescription(Settings.class);
    settingsDescription.setExcludes("server", "scheduledExecutorService");

    settingsDescription.addPropertyParameters("graphs", String.class, String.class);
    settingsDescription.addPropertyParameters("scriptEngines", String.class,
        ScriptEngineSettings.class);
    settingsDescription.addPropertyParameters("serializers", SerializerSettings.class);
    settingsDescription.addPropertyParameters("plugins", String.class);
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

  /**
   * Read configuration from a file into a new {@link Settings} object.
   *
   * @param stream an input stream containing a Gremlin Server YAML configuration
   * @return a new {@link Optional} object wrapping the created {@link Settings}
   */
  public static YTDBSettings read(final InputStream stream) {
    Objects.requireNonNull(stream);

    final var constructor = createDefaultYamlConstructor();
    final var yaml = new Yaml(constructor);
    return yaml.loadAs(stream, YTDBSettings.class);
  }
}
