package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.github;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import java.io.File;
import java.io.IOException;

public class WindowsMavenBuilder implements MavenBuilder {

  @Override
  public String build(String repositoryPath) throws IOException, InterruptedException {
    var builder = new ProcessBuilder(".\\mvnw.bat", "clean", "package", "-DskipTests",
        "-pl", "!distribution");
    builder.directory(new File(repositoryPath));
    builder.inheritIO();
    var process = builder.start();
    int exitCode = process.waitFor();
    LogManager.instance().info(this, "Build finished with exit code: " + exitCode);
    if (exitCode != 0) {
      throw new IllegalStateException("Maven build failed with exit code: " + exitCode);
    }
    return repositoryPath;
  }
}
