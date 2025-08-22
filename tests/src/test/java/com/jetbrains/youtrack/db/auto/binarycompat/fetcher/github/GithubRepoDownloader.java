package com.jetbrains.youtrack.db.auto.binarycompat.fetcher.github;

import java.io.File;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GithubRepoDownloader {

  private static final Logger logger = LoggerFactory.getLogger(GithubRepoDownloader.class);


  public String checkoutRepository(String repoUrl, String branch, String destination) {
    try {
      var result = Git.cloneRepository()
          .setURI(repoUrl)
          .setBranch("refs/heads/" + branch)
          .setDirectory(new File(destination))
          .call();

      result.close();
    } catch (GitAPIException e) {
      logger.error("Exception occurred while cloning repo: " + e.getMessage(), e);
    }
    return destination;
  }
}
