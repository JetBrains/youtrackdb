import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.buildFeatures.*
import jetbrains.buildServer.configs.kotlin.triggers.vcs

version = "2026.03"

val token = "tc_token_id:CID_6f70430be90149234d7210e12aa15ea9:-1:6eedf048-c2e9-4675-ba2f-6e73f59c04b0"
// The root preserves develop and the full queue path as logical branch names.
val unitTrigger = "+:develop\n+:gh-readonly-queue/develop/*\n+pr: sourceRepo=same"
val allBranches = "+:develop\n+:gh-readonly-queue/develop/*\n+:pull/*"
// Test builds need this tag for the Spotless ratchet.
val spotlessBaselineFetch = """
  GIT_TERMINAL_PROMPT=0 git -c credential.helper= -c http.extraHeader= \
    -c http.https://github.com/.extraheader= fetch --no-tags \
    https://github.com/JetBrains/youtrackdb.git \
    refs/tags/spotless-baseline:refs/tags/spotless-baseline
""".trimIndent()

fun BuildType.configure(agent: String) {
  vcs {
    root(DslContext.settingsRoot)
    branchFilter = allBranches
    checkoutMode = CheckoutMode.ON_AGENT
    cleanCheckout = true
  }
  requirements { equals("cloud.amazon.agent-name-prefix", agent) }
  params {
    param("env.TC_VCS_BRANCH", "%teamcity.build.vcs.branch.${DslContext.settingsRoot.id}%")
    param("env.TC_URL", "%teamcity.serverUrl%")
    param("env.TC_USER", "%system.teamcity.auth.userId%")
    password("env.TC_PASSWORD", "%system.teamcity.auth.password%")
    param("env.TC_BUILD_ID", "%teamcity.build.id%")
  }
  failureConditions {
    nonZeroExitCode = true
    testFailure = true
  }
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
      }
    }
  }
}

val DetectChanges = BuildType({
  id("DetectChanges")
  name = "Detect changes"
  configure("Linux-Small")
  maxRunningBuildsPerBranch = "pull/*:1"
  runningBuildsLimitingMode = BuildsLimitingMode.CANCEL_OLDEST_RUNNING_BUILD
  params {
    for (key in listOf("should_build", "run_integration_tests", "integration_skip_reason",
        "integration_scope", "integration_modules")) {
      text("detect.$key", "", allowEmpty = true)
    }
  }
  steps {
    script {
      name = "Detect build scope"
      scriptContent = "#!/bin/bash\nset -euo pipefail\npython3 .github/scripts/teamcity-build.py detect"
    }
  }
})

fun BuildType.mavenMirrorParameters() {
  params {
    password("env.MAVEN_MIRROR_USERNAME", "credentialsJSON:dfcdf774-ee53-4004-88de-389cfdbb2898")
    password("env.MAVEN_MIRROR_PASSWORD", "credentialsJSON:7d6c27f1-c217-4196-8bc0-8de2c650b7e8")
    param("env.MVNW_REPOURL", "https://maven.youtrackdb.io/maven-central")
    param("env.MVNW_USERNAME", "%env.MAVEN_MIRROR_USERNAME%")
    param("env.MVNW_PASSWORD", "%env.MAVEN_MIRROR_PASSWORD%")
  }
}

fun BuildType.keepTestArtifacts() {
  artifactRules = """
    +:**/target/deadlock-report.txt
    +:**/target/surefire-reports/*.dump*
    +:**/target/failsafe-reports/*.dump*
    +:core/target/vmlens-report/** => vmlens-report
  """.trimIndent()
  publishArtifacts = PublishMode.ALWAYS
}

val UnitTestsLinuxArm64 = BuildType({
  id("UnitTestsLinuxArm64")
  name = "Unit tests Linux ARM64"
  configure("Ubuntu-24.04-Large-Arm64")
  failureConditions { executionTimeoutMin = 360 }
  maxRunningBuildsPerBranch = "pull/*:1"
  runningBuildsLimitingMode = BuildsLimitingMode.CANCEL_OLDEST_RUNNING_BUILD
  mavenMirrorParameters()
  params {
    param("env.JAVA_HOME", "%env.JDK_21_0%")
    param("env.TC_SHOULD_BUILD", "%dep.${DetectChanges.id}.detect.should_build%")
  }
  dependencies {
    snapshot(DetectChanges) {
      synchronizeRevisions = true
      // The develop change base can change between builds of the same revision.
      reuseBuilds = ReuseBuilds.NO
      onDependencyFailure = FailureAction.FAIL_TO_START
      onDependencyCancel = FailureAction.FAIL_TO_START
    }
  }
  steps {
    script {
      name = "Validate Detect changes result"
      scriptContent = "#!/bin/bash\nset -euo pipefail\npython3 .github/scripts/teamcity-build.py guard"
    }
    script {
      name = "Run unit tests"
      conditions { equals("dep.${DetectChanges.id}.detect.should_build", "true") }
      scriptContent = """
        #!/bin/bash
        set -euo pipefail
        ${spotlessBaselineFetch.prependIndent("        ").trimStart()}
        python3 .github/scripts/teamcity-build.py maven
      """.trimIndent()
    }
    script {
      name = "Check reports and test count"
      conditions { equals("dep.${DetectChanges.id}.detect.should_build", "true") }
      scriptContent = "#!/bin/bash\nset -euo pipefail\npython3 .github/scripts/teamcity-build.py verify"
      executionMode = BuildStep.ExecutionMode.RUN_ON_FAILURE
    }
  }
  keepTestArtifacts()
  features {
    xmlReport {
      reportType = XmlReport.XmlReportType.SUREFIRE
      rules = "+:**/target/surefire-reports/TEST-*.xml"
    }
  }
  triggers { vcs { branchFilter = unitTrigger } }
})

project {
  buildType(DetectChanges)
  buildType(UnitTestsLinuxArm64)
}
