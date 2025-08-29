package com.jetbrains.youtrack.db.auto.binarycompat.fetcher.code.maven;

import com.jetbrains.youtrack.db.auto.binarycompat.fetcher.code.JarDownloader;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import java.io.File;
import java.util.Collections;
import org.apache.maven.repository.internal.DefaultArtifactDescriptorReader;
import org.apache.maven.repository.internal.DefaultVersionRangeResolver;
import org.apache.maven.repository.internal.DefaultVersionResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.internal.impl.DefaultDeployer;
import org.eclipse.aether.internal.impl.DefaultInstaller;
import org.eclipse.aether.internal.impl.DefaultMetadataResolver;
import org.eclipse.aether.internal.impl.collect.DefaultDependencyCollector;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RemoteRepository.Builder;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

/**
 * Maven JAR Downloader using Eclipse Aether
 */
public class MavenJarDownloader implements JarDownloader {

  private final RepositorySystem repositorySystem;
  private final RepositorySystemSession session;

  public MavenJarDownloader(String localRepoPath) {
    this.repositorySystem = newRepositorySystem();
    this.session = newRepositorySystemSession(repositorySystem, localRepoPath);
  }

  @Override
  public File prepareArtifact(String source, String version) {
    try {
      return downloadArtifact(source, version);
    } catch (ArtifactResolutionException e) {
      throw new IllegalStateException("Could not resolve artifact", e);
    }
  }

  public File downloadArtifact(String repository, String coordinates)
      throws ArtifactResolutionException {
    return downloadArtifact(new Builder("custom", "default", repository).build(), coordinates);
  }

  public File downloadArtifact(RemoteRepository repository, String coordinates)
      throws ArtifactResolutionException {
    Artifact artifact = new DefaultArtifact(coordinates);

    var request =
        new org.eclipse.aether.resolution.ArtifactRequest();
    request.setArtifact(artifact);
    request.setRepositories(Collections.singletonList(repository));

    var result =
        repositorySystem.resolveArtifact(session, request);

    var jarFile = result.getArtifact().getFile();
    LogManager.instance().info(this, "Downloaded: " + jarFile.getAbsolutePath());
    return jarFile;
  }

  private static RepositorySystem newRepositorySystem() {
    var locator = new DefaultServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

    // THESE are the crucial internal Aether components that need to be added!
    locator.addService(org.eclipse.aether.impl.VersionResolver.class, DefaultVersionResolver.class);
    locator.addService(org.eclipse.aether.impl.VersionRangeResolver.class,
        DefaultVersionRangeResolver.class);
    locator.addService(org.eclipse.aether.impl.MetadataResolver.class,
        DefaultMetadataResolver.class);
    locator.addService(org.eclipse.aether.impl.ArtifactDescriptorReader.class,
        DefaultArtifactDescriptorReader.class);
    locator.addService(org.eclipse.aether.impl.DependencyCollector.class,
        DefaultDependencyCollector.class);
    locator.addService(org.eclipse.aether.impl.Deployer.class, DefaultDeployer.class);
    locator.addService(org.eclipse.aether.impl.Installer.class, DefaultInstaller.class);
    locator.addService(org.apache.maven.repository.internal.ModelCacheFactory.class,
        org.apache.maven.repository.internal.DefaultModelCacheFactory.class);

    locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
      @Override
      public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
        exception.printStackTrace();
      }
    });

    return locator.getService(RepositorySystem.class);
  }

  private static DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem system,
      String localRepoPath) {
    var session = new DefaultRepositorySystemSession();

    var localRepo = new LocalRepository(localRepoPath);
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

    return session;
  }
}