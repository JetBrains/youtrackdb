package com.jetbrains.youtrack.db.auto.binarycompat.fetcher.github;

import java.io.File;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenBuilder {

  private static final Logger logger = LoggerFactory.getLogger(MavenBuilder.class);

  public String build(String repositoryPath) throws IOException, InterruptedException {
    var permissionGiverBuilder = new ProcessBuilder("chmod", "+x", "./mvnw");
    permissionGiverBuilder.directory(new File(repositoryPath));
    permissionGiverBuilder.inheritIO();
    var permissionGiverProcess = permissionGiverBuilder.start();
    var exitCode = permissionGiverProcess.waitFor();
    logger.info("Altering permission for maven wrapper finished with exit code: " + exitCode);

    var builder = new ProcessBuilder("./mvnw", "clean", "package", "-DskipTests",
        "-pl", "!distribution");
    builder.directory(new File(repositoryPath));
    builder.inheritIO();
    var process = builder.start();
    exitCode = process.waitFor();
    logger.info("Build finished with exit code: " + exitCode);
    if (exitCode != 0) {
      throw new IllegalStateException("Maven build failed with exit code: " + exitCode);
    }
    return repositoryPath;
  }
}
