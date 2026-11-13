import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.buildFeatures.*
import jetbrains.buildServer.configs.kotlin.triggers.vcs

version = "2026.03"

val token = "tc_token_id:CID_6f70430be90149234d7210e12aa15ea9:-1:6eedf048-c2e9-4675-ba2f-6e73f59c04b0"
// The root exposes only develop, queue branches and the pilot default branch.
// Queue logical names contain only the wildcard suffix, so select them by exclusion.
val queueBranches = "+:*\n-:<default>\n-:refs/heads/develop\n-pr:*"
val unitTrigger = "+:refs/heads/develop\n+pr: sourceRepo=same"
val allBranches = "+:*\n-:<default>\n-pr:*\n+pr: sourceRepo=same\n+:refs/heads/develop"

fun BuildType.configure(branches: String, agent: String? = null, status: String? = null) {
  vcs {
    root(DslContext.settingsRoot)
    branchFilter = branches
    checkoutMode = CheckoutMode.ON_AGENT
    cleanCheckout = true
  }
  if (agent != null) {
    requirements { equals("cloud.amazon.agent-name-prefix", agent) }
    params {
      param("env.TC_BRANCH", "%teamcity.build.branch%")
      param("env.TC_VCS_BRANCH", "%teamcity.build.vcs.branch.YouTrackDB_Settings%")
      param("env.TC_URL", "%teamcity.serverUrl%")
      param("env.TC_USER", "%system.teamcity.auth.userId%")
      password("env.TC_PASSWORD", "%system.teamcity.auth.password%")
      param("env.TC_BUILD_ID", "%teamcity.build.id%")
    }
  }
  failureConditions { nonZeroExitCode = true; testFailure = true }
  features {
    pullRequests {
      vcsRootExtId = "${DslContext.settingsRoot.id}"
      provider = github {
        authType = storedToken { tokenId = token }
        filterTargetBranch = "+:refs/heads/develop"
        filterAuthorRole = PullRequests.GitHubRoleFilter.EVERYBODY
        ignoreDrafts = false
      }
    }
    commitStatusPublisher {
      vcsRootExtId = "${DslContext.settingsRoot.id}"
      publisher = github {
        githubUrl = "https://api.github.com"
        authType = storedToken { tokenId = token }
        if (status != null) { statusCheckName = status }
      }
    }
  }
}

fun Dependencies.require(build: BuildType) {
  snapshot(build) {
    synchronizeRevisions = true
    reuseBuilds = ReuseBuilds.SUCCESSFUL
    onDependencyFailure = FailureAction.FAIL_TO_START
    onDependencyCancel = FailureAction.FAIL_TO_START
  }
}

val DetectChanges = BuildType({
  id("DetectChanges")
  name = "Detect changes"
  configure(allBranches, "Linux-Small")
  params {
    for (key in listOf("should_build", "run_integration_tests", "integration_skip_reason",
        "integration_scope", "integration_modules")) {
      text("detect.$key", "", allowEmpty = true)
    }
    text("teamcity.pullRequest.source.branch", "", allowEmpty = true)
    text("teamcity.pullRequest.target.branch", "", allowEmpty = true)
    param("env.TC_PR_SOURCE", "%teamcity.pullRequest.source.branch%")
    param("env.TC_PR_TARGET", "%teamcity.pullRequest.target.branch%")
  }
  steps {
    script {
      name = "Detect build scope"
      scriptContent = "set -euo pipefail\npython3 .github/scripts/teamcity-build.py detect"
    }
  }
})

val UnitTestsLinuxX86 = BuildType({
  id("UnitTestsLinuxX86")
  name = "Unit tests Linux x86"
  configure(allBranches, "Linux-Large")
  maxRunningBuildsPerBranch = "pull/*:1"
  runningBuildsLimitingMode = BuildsLimitingMode.CANCEL_OLDEST_RUNNING_BUILD
  params {
    param("env.JAVA_HOME", "%env.JDK_21_0%")
    password("env.MAVEN_MIRROR_USERNAME", "credentialsJSON:dfcdf774-ee53-4004-88de-389cfdbb2898")
    password("env.MAVEN_MIRROR_PASSWORD", "credentialsJSON:7d6c27f1-c217-4196-8bc0-8de2c650b7e8")
    param("env.MVNW_REPOURL", "https://maven.youtrackdb.io/maven-central")
    param("env.MVNW_USERNAME", "%env.MAVEN_MIRROR_USERNAME%")
    param("env.MVNW_PASSWORD", "%env.MAVEN_MIRROR_PASSWORD%")
    param("env.TC_SHOULD_BUILD", "%dep.${DetectChanges.id}.detect.should_build%")
  }
  dependencies { require(DetectChanges) }
  steps {
    script {
      name = "Validate Detect changes result"
      scriptContent = "set -euo pipefail\npython3 .github/scripts/teamcity-build.py guard"
    }
    script {
      name = "Run unit tests"
      conditions { equals("dep.${DetectChanges.id}.detect.should_build", "true") }
      scriptContent = """
        set -euo pipefail
        ./mvnw -s .github/workflows/settings.xml clean package -B \
          -Dmaven.test.failure.ignore=true -Dyoutrackdb.test.env=ci \
          -P docker-images,coverage -Ddocker.platforms=linux/amd64
      """.trimIndent()
    }
    script {
      name = "Check reports and test count"
      conditions { equals("dep.${DetectChanges.id}.detect.should_build", "true") }
      scriptContent = "set -euo pipefail\npython3 .github/scripts/teamcity-build.py verify"
      executionMode = BuildStep.ExecutionMode.ALWAYS
    }
  }
  artifactRules = """
    +:**/target/deadlock-report.txt
    +:**/target/surefire-reports/*.dump*
    +:**/target/failsafe-reports/*.dump*
    +:core/target/vmlens-report/** => vmlens-report
  """.trimIndent()
  publishArtifacts = PublishMode.ALWAYS
  features {
    xmlReport {
      reportType = XmlReport.XmlReportType.SUREFIRE
      rules = "+:**/target/surefire-reports/TEST-*.xml"
    }
  }
  triggers { vcs { branchFilter = unitTrigger } }
})

val SummaryMergeQueue = BuildType({
  id("SummaryMergeQueue")
  name = "Summary: merge queue"
  type = BuildTypeSettings.Type.COMPOSITE
  configure(queueBranches, status = "TeamCity CI")
  dependencies { require(UnitTestsLinuxX86) }
  triggers { vcs { branchFilter = queueBranches } }
})

project {
  buildType(DetectChanges)
  buildType(UnitTestsLinuxX86)
  buildType(SummaryMergeQueue)
}
