package com.jetbrains.youtrackdb.internal.docker.console;

import com.jetbrains.youtrackdb.internal.docker.StdOutConsumer;
import io.github.classgraph.ClassGraph;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@RunWith(Parameterized.class)
public class REPLConsoleTest {

  private final String scriptFilePath;
  private final String scriptName;

  public REPLConsoleTest(String scriptFileName, String scriptName) {
    this.scriptFilePath = scriptFileName;
    this.scriptName = scriptName;
  }

  @Test
  public void testScripts() {
    var debug = Boolean.getBoolean("ytdb.testcontainer.debug.container");
    try (var console = new GenericContainer<>(
        DockerImageName.parse("youtrackdb/youtrackdb-console"))) {
      console.withCopyFileToContainer(MountableFile.forClasspathResource(
              scriptFilePath),
          "/" + scriptName);
      console.withCommand("-e", "/" + scriptName);

      if (debug) {
        console.withEnv("JAVA_OPTIONS",
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005");
        console.setPortBindings(List.of("5005:6006"));
        console.withExposedPorts(5005);
      }

      var arrayStream = new ByteArrayOutputStream();
      var accumulatedErrStream =
          new PrintStream(arrayStream, true, StandardCharsets.UTF_8);
      console.withLogConsumer(new StdOutConsumer<>("console:", accumulatedErrStream));
      var errString = arrayStream.toString(StandardCharsets.UTF_8);

      if (errString.toLowerCase(Locale.ROOT).contains("exception")) {
        Assert.fail("Error during script execution: " + errString.trim());
      }

      if (!debug) {
        console.withStartupCheckStrategy(
            new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(30))
        );
      } else {
        console.withStartupCheckStrategy(
            new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(30))
        );
      }

      console.start();
    }
  }

  @Parameters(name = "Test {index}, script name: {1} ")
  public static Collection<Object[]> data() {
    try (var scanResult = new ClassGraph()
        .acceptPaths(REPLConsoleTest.class.getPackageName().replace(".", "/") + "/scripts")
        .scan()) {
      var resources = scanResult.getAllResources();

      return resources.stream().map(resource -> {
            var path = Paths.get(resource.getPath());
            var scriptName = path.getFileName().toString();

            return new Object[]{
                path.toString(), scriptName
            };
          }
      ).toList();
    }
  }
}
