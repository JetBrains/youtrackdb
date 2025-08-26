package com.jetbrains.youtrack.db.auto.binarycompat.fetcher.github;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import java.io.File;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

public class GithubRepoDownloader {

  public String checkoutRepository(String repoUrl, String branch, String destination) {
    try {
      var result = Git.cloneRepository()
          .setURI(repoUrl)
          .setBranch("refs/heads/" + branch)
          .setDirectory(new File(destination))
          .call();

      result.close();
    } catch (GitAPIException e) {
      LogManager.instance()
          .error(this, "Exception occurred while cloning repo", e);
    }
    return destination;
  }
}
