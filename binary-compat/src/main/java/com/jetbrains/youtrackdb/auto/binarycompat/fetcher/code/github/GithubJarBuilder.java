package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.github;

import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.JarDownloader;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.maven.UnixMavenBuilder;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.annotation.Nonnull;

public class GithubJarBuilder implements JarDownloader {

  private final GithubRepoDownloader githubRepoDownloader;
  private final Map<OS, MavenBuilder> mavenBuilders;
  private final File root;

  public GithubJarBuilder(GithubRepoDownloader githubRepoDownloader) {
    this.githubRepoDownloader = githubRepoDownloader;
    UnixMavenBuilder unixMavenBuilder = new UnixMavenBuilder();
    this.mavenBuilders = Map.of(
        OS.LINUX, unixMavenBuilder,
        OS.MAC, unixMavenBuilder,
         OS.WINDOWS, new WindowsMavenBuilder()
    );
    try {
      this.root = Files.createTempDirectory("ytdb-github-jar-builder").toFile();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
    var localRepoPath = checkout(repoUrl, branch);
    return buildWithMaven(localRepoPath);
  }

  @Nonnull
  private String checkout(String repoUrl, String branch) throws IOException, InterruptedException {
    var branchDir = root.toPath().resolve(branch);
    if (!branchDir.toFile().exists()) {
      Files.createDirectory(branchDir);
    }
    String localRepoPath;
    if (Files.list(branchDir).findAny().isPresent()) {
      // directory already exists checking out the branch
      localRepoPath = githubRepoDownloader.pullBranch(branch,
          branchDir.toAbsolutePath().toString());
    } else {
      localRepoPath = githubRepoDownloader.checkoutRepository(repoUrl, branch,
          branchDir.toAbsolutePath().toString());
    }
    return localRepoPath;
  }

  @Nonnull
  private File buildWithMaven(String localRepoPath) throws IOException, InterruptedException {
    var repoDirContent = Files.list(Path.of(localRepoPath)).toList();
    if (repoDirContent.size() == 1) {
      // If the repo contains only one directory, we assume it's the project root
      localRepoPath = repoDirContent.getFirst().toString();
    }

    var repo = mavenBuilders.get(getCanonicalOsName()).build(localRepoPath);
    return Path.of(repo + "/core/target/youtrackdb-core-1.0.0-SNAPSHOT.jar").toFile();
  }

  private static OS getCanonicalOsName() {
    String osName = System.getProperty("os.name").toLowerCase();
    if (osName.startsWith("windows")) {
      return OS.WINDOWS;
    }
    if (osName.startsWith("linux")) {
      return OS.LINUX;
    }
    if (osName.startsWith("macos") || osName.startsWith("mac os") || osName.startsWith("darwin")) {
      return OS.MAC;
    }
    throw new IllegalArgumentException("Unsupported operating system: " + osName);
  }

  private enum OS {
    WINDOWS,
    LINUX,
    MAC,
  }
}
