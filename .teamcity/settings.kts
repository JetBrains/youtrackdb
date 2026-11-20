import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.buildFeatures.*
import jetbrains.buildServer.configs.kotlin.triggers.vcs

version = "2026.03"

val token = "tc_token_id:CID_6f70430be90149234d7210e12aa15ea9:-1:6eedf048-c2e9-4675-ba2f-6e73f59c04b0"
// The root preserves develop and the full queue path as logical branch names.
// The Linux x86 unit trigger handles develop and same-repository draft PR pushes.
// The ready summary handles ready PR pushes. The REST helper handles ready flips.
val queueBranches = "+:gh-readonly-queue/develop/*"
val unitTrigger = "+:develop\n+pr: draft=true sourceRepo=same"
val allBranches = "+:develop\n+:gh-readonly-queue/develop/*\n+:pull/*"
// Test builds need this tag for the Spotless ratchet.
val spotlessBaselineFetch = """
  GIT_TERMINAL_PROMPT=0 git -c credential.helper= -c http.extraHeader= \
    -c http.https://github.com/.extraheader= fetch --no-tags \
    https://github.com/JetBrains/youtrackdb.git \
    refs/tags/spotless-baseline:refs/tags/spotless-baseline
""".trimIndent()

fun BuildType.configure(
    branches: String, agent: String? = null, status: String? = null, readyOnly: Boolean = false
) {
  vcs {
    root(DslContext.settingsRoot)
    branchFilter = branches
    checkoutMode = CheckoutMode.ON_AGENT
    cleanCheckout = true
  }
  if (agent != null) {
    requirements { equals("cloud.amazon.agent-name-prefix", agent) }
  }
  if (agent != null && !readyOnly) {
    params {
      param("env.TC_VCS_BRANCH", "%teamcity.build.vcs.branch.${DslContext.settingsRoot.id}%")
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
        // Discover draft PR branches too. The start paths enforce the draft rule.
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

val UnitTestsLinuxX86 = BuildType({
  id("UnitTestsLinuxX86")
  name = "Unit tests Linux x86"
  configure(allBranches, "Linux-Large")
  failureConditions { executionTimeoutMin = 360 }
  maxRunningBuildsPerBranch = "pull/*:1"
  runningBuildsLimitingMode = BuildsLimitingMode.CANCEL_OLDEST_RUNNING_BUILD
  mavenMirrorParameters()
  params {
    param("env.JAVA_HOME", "%env.JDK_21_0%")
    param("env.TC_SHOULD_BUILD", "%dep.${DetectChanges.id}.detect.should_build%")
  }
  dependencies { require(DetectChanges) }
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
        ./mvnw -s .github/workflows/settings.xml clean package -B \
          -Dmaven.test.failure.ignore=true -Dyoutrackdb.test.env=ci \
          -P docker-images,coverage -Ddocker.platforms=linux/amd64
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

val SummaryMergeQueue = BuildType({
  id("SummaryMergeQueue")
  name = "Summary: merge queue"
  type = BuildTypeSettings.Type.COMPOSITE
  configure(queueBranches, status = "TeamCity CI")
  dependencies { require(UnitTestsLinuxX86) }
  triggers { vcs { branchFilter = queueBranches } }
})

val readyBranches = "+:pull/*"

fun readyTests(
    buildId: String, label: String, agent: String, integration: Boolean = false,
    windows: Boolean = false
) = BuildType({
  id(buildId)
  name = label
  configure(readyBranches, agent, readyOnly = true)
  failureConditions { executionTimeoutMin = 360 }
  maxRunningBuildsPerBranch = "pull/*:1"
  runningBuildsLimitingMode = BuildsLimitingMode.CANCEL_OLDEST_RUNNING_BUILD
  dependencies { require(DetectChanges) }
  mavenMirrorParameters()
  params {
    param("env.JAVA_HOME", "%env.JDK_21_0%")
    for (key in listOf("should_build", "run_integration_tests", "integration_skip_reason",
        "integration_scope", "integration_modules")) {
      param("env.TC_${key.uppercase()}", "%dep.${DetectChanges.id}.detect.$key%")
    }
  }
  val kind = if (integration) "failsafe" else "surefire"
  val python = if (windows) "python" else "python3"
  val adapter = "$python .github/scripts/teamcity-build.py ready $kind"
  val bash = "#!/bin/bash\nset -euo pipefail\n"
  steps {
    script {
      name = "Validate Detect changes result"
      scriptContent = "$adapter guard"
    }
    script {
      name = "Run tests"
      conditions {
        equals("env.TC_SHOULD_BUILD", "true")
        if (integration) { equals("env.TC_RUN_INTEGRATION_TESTS", "true") }
      }
      val unit = "./mvnw -s .github/workflows/settings.xml clean package -B " +
          "-Dmaven.test.failure.ignore=true"
      scriptContent = when {
        integration -> bash + spotlessBaselineFetch + "\n$adapter maven"
        windows -> {
          // Windows runs the step under cmd.exe. Only the fixed command goes to Git Bash.
          val fetch = spotlessBaselineFetch.replace("\\\n", " ")
          """"C:\Program Files\Git\bin\bash.exe" -ec "$fetch && $unit""""
        }
        else -> bash + spotlessBaselineFetch + "\n$unit " +
            "-P docker-images -Ddocker.platforms=linux/arm64/v8"
      }
    }
    script {
      name = "Check test reports"
      conditions {
        equals("env.TC_SHOULD_BUILD", "true")
        if (integration) { equals("env.TC_RUN_INTEGRATION_TESTS", "true") }
      }
      scriptContent = "$adapter verify"
      executionMode = BuildStep.ExecutionMode.RUN_ON_FAILURE
    }
  }
  keepTestArtifacts()
  features {
    xmlReport {
      reportType = XmlReport.XmlReportType.SUREFIRE
      rules = "+:**/target/surefire-reports/TEST-*.xml" +
          if (integration) "\n+:**/target/failsafe-reports/TEST-*.xml" else ""
    }
  }
})

val UnitTestsWindowsX64 = readyTests(
    "UnitTestsWindowsX64", "Unit tests Windows x64", "Windows-Large", windows = true)
val UnitTestsLinuxArm64 = readyTests(
    "UnitTestsLinuxArm64", "Unit tests Linux ARM64", "Ubuntu-24.04-Large-Arm64")
val IntegrationTestsLinuxX86 = readyTests(
    "IntegrationTestsLinuxX86", "Integration tests Linux x86", "Linux-Large", integration = true)
val IntegrationTestsLinuxArm64 = readyTests(
    "IntegrationTestsLinuxArm64", "Integration tests Linux ARM64", "Ubuntu-24.04-Large-Arm64",
    integration = true)

val SummaryReadyForReview = BuildType({
  id("SummaryReadyForReview")
  name = "Summary: ready for review"
  type = BuildTypeSettings.Type.COMPOSITE
  configure(readyBranches, status = "TeamCity CI", readyOnly = true)
  dependencies {
    require(UnitTestsLinuxX86)
    require(UnitTestsWindowsX64)
    require(UnitTestsLinuxArm64)
    require(IntegrationTestsLinuxX86)
    require(IntegrationTestsLinuxArm64)
  }
  triggers { vcs { branchFilter = "+pr: draft=false sourceRepo=same" } }
})

project {
  buildType(UnitTestsWindowsX64)
  buildType(UnitTestsLinuxArm64)
  buildType(IntegrationTestsLinuxX86)
  buildType(IntegrationTestsLinuxArm64)
  buildType(SummaryReadyForReview)
  buildType(DetectChanges)
  buildType(UnitTestsLinuxX86)
  buildType(SummaryMergeQueue)
}
