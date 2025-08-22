package com.jetbrains.youtrack.db.auto.binarycompat.fetcher.github;

import com.jetbrains.youtrack.db.auto.binarycompat.fetcher.JarDownloader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GithubJarBuilder implements JarDownloader {

  private static final Logger logger = LoggerFactory.getLogger(GithubJarBuilder.class);

  private final GithubRepoDownloader githubRepoDownloader;
  private final MavenBuilder mavenBuilder;

  public GithubJarBuilder(GithubRepoDownloader githubRepoDownloader, MavenBuilder mavenBuilder) {
    this.githubRepoDownloader = githubRepoDownloader;
    this.mavenBuilder = mavenBuilder;
  }

  @Override
  public File prepareArtifact(String source, String version) {
    try {
      return checkoutAndBuildProject(source, version);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public File checkoutAndBuildProject(String repoUrl, String branch)
      throws InterruptedException, IOException {
    var destination = Files.createTempDirectory("ytdb-" + branch);
    try {
      cleanUp(destination);
    } catch (IOException e) {
      logger.error("Failed to clean up temporary directory: " + destination);
    }
    var localRepoPath = githubRepoDownloader.checkoutRepository(repoUrl, branch,
        destination.toAbsolutePath().toString());

    var repoDirContent = Files.list(Path.of(localRepoPath)).toList();
    if (repoDirContent.size() == 1) {
      // If the repo contains only one directory, we assume it's the project root
      localRepoPath = repoDirContent.getFirst().toString();
    }

    var repo = mavenBuilder.build(localRepoPath);
    return Path.of(repo + "/core/target/youtrackdb-core-1.0.0-SNAPSHOT.jar").toFile();
  }

  private void cleanUp(Path dir) throws IOException {
    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file); // delete files
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        Files.delete(dir); // delete directory after its contents
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
