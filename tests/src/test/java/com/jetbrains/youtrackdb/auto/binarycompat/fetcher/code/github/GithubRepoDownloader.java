package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.github;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ProgressMonitor;

public class GithubRepoDownloader {

  public String checkoutRepository(String repoUrl, String branch, String destination) {
    try {
      var monitor = new ProgressMonitor() {
        @Override
        public void start(int totalTasks) {
          LogManager.instance().info(this, "Starting " + totalTasks + " tasks");
        }

        @Override
        public void beginTask(String title, int totalWork) {
          LogManager.instance().info(this, "Begin task: " + title + " (" + totalWork + ")");
        }

        @Override
        public void update(int completed) {
          // this method it too verbose, we don't need it
        }

        @Override
        public void endTask() {
          LogManager.instance().info(this, "Task finished");
        }

        @Override
        public boolean isCancelled() {
          return false;
        }

        @Override
        public void showDuration(boolean b) {
          LogManager.instance().info(this, "Clone duration: " + b);
        }
      };

      var result = Git.cloneRepository()
          .setURI(repoUrl)
          .setBranch("refs/heads/" + branch)
          .setDirectory(new File(destination))
          .setProgressMonitor(monitor)
          .call();

      result.close();
    } catch (GitAPIException e) {
      LogManager.instance()
          .error(this, "Exception occurred while cloning repo", e);
      throw new RuntimeException(e);
    }
    return destination;
  }

  public String pullBranch(String branch, String destination) {
    try {
      var repo = Git.open(new File(destination));
      var refs = repo.branchList().call();
      var branchExists = refs.stream().anyMatch(ref -> ref.getName().endsWith("/" + branch));

      var checkout = repo.checkout().setName(branch);
      if (!branchExists) {
        checkout.setCreateBranch(true).setStartPoint("origin/" + branch);
      }
      checkout.call();

      repo
          .pull()
          .call();

      repo.close();
    } catch (GitAPIException e) {
      LogManager.instance()
          .error(this, "Exception occurred while cloning repo", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return destination;
  }
}
