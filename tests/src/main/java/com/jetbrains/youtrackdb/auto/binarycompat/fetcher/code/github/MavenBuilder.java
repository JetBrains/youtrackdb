package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.github;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import java.io.File;
import java.io.IOException;

public class MavenBuilder {

  public String build(String repositoryPath) throws IOException, InterruptedException {
    var permissionGiverBuilder = new ProcessBuilder("chmod", "+x", "./mvnw");
    permissionGiverBuilder.directory(new File(repositoryPath));
    permissionGiverBuilder.inheritIO();
    var permissionGiverProcess = permissionGiverBuilder.start();
    var exitCode = permissionGiverProcess.waitFor();
    LogManager.instance()
        .info(this, "Altering permission for maven wrapper finished with exit code: " + exitCode);

    var builder = new ProcessBuilder("./mvnw", "clean", "package", "-DskipTests",
        "-pl", "!distribution");
    builder.directory(new File(repositoryPath));
    builder.inheritIO();
    var process = builder.start();
    exitCode = process.waitFor();
    LogManager.instance().info(this, "Build finished with exit code: " + exitCode);
    if (exitCode != 0) {
      throw new IllegalStateException("Maven build failed with exit code: " + exitCode);
    }
    return repositoryPath;
  }
}
