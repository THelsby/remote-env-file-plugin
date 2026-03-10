import io.jenkins.plugins.remoteenvfile.RemoteEnvFileJobProperty
import io.jenkins.plugins.remoteenvfile.RemoteEnvSource
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def fixtureBase = "https://env-fixture:8443"
def jenkins = Jenkins.get()

def source = { String url, String credentialsId = null ->
    def configured = new RemoteEnvSource(url)
    configured.setCredentialsId(credentialsId)
    configured
}

def createOrUpdateJob = { String name, String description, String script, List propertySources ->
    WorkflowJob job = jenkins.getItem(name) as WorkflowJob
    if (job == null) {
        job = jenkins.createProject(WorkflowJob, name)
    }

    job.setDescription(description)
    job.setDefinition(new CpsFlowDefinition(script, true))

    def existing = job.getProperty(RemoteEnvFileJobProperty.class)
    if (existing != null) {
        job.removeProperty(existing)
    }
    if (propertySources != null) {
        job.addProperty(new RemoteEnvFileJobProperty(propertySources))
    }

    job.save()
}

createOrUpdateJob(
        "example-remote-env-wrapper-success",
        "Expected SUCCESS. Wrap mode using the local HTTPS fixture service and public.env.",
        """
node {
  withRemoteEnvFiles(sources: [
    [sourceUrl: '${fixtureBase}/public.env']
  ]) {
    if (env.APP_NAME != 'remote-env-file-demo') {
      error('APP_NAME was not loaded from the remote env file')
    }
    if (env.APP_MODE != 'demo') {
      error('APP_MODE was not loaded from the remote env file')
    }
    echo("Loaded remote variables for " + env.APP_NAME)
  }
}
""".stripIndent(),
        null)

createOrUpdateJob(
        "example-remote-env-wrapper-multi-source-success",
        "Expected SUCCESS. Wrap mode using base.env then prod.env to verify later sources override earlier remote values.",
        """
node {
  withRemoteEnvFiles(sources: [
    [sourceUrl: '${fixtureBase}/base.env'],
    [sourceUrl: '${fixtureBase}/prod.env']
  ]) {
    if (env.APP_NAME != 'remote-env-file-demo') {
      error('APP_NAME was not loaded from the layered remote env files')
    }
    if (env.APP_MODE != 'prod') {
      error('APP_MODE was not overridden by the later remote source')
    }
    if (env.COMMON_VALUE != 'prod-override') {
      error('COMMON_VALUE was not overridden by the later remote source')
    }
    if (env.RELEASE_CHANNEL != 'stable') {
      error('RELEASE_CHANNEL was not loaded from the later remote source')
    }
    echo("Loaded layered remote variables for " + env.APP_NAME)
  }
}
""".stripIndent(),
        null)

createOrUpdateJob(
        "example-remote-env-wrapper-blocked-path",
        "Expected FAILURE. Wrap mode using blocked-path.env to verify PATH rejection.",
        """
node {
  withRemoteEnvFiles(sources: [
    [sourceUrl: '${fixtureBase}/blocked-path.env']
  ]) {
    error('The blocked PATH fixture should fail before this step runs')
  }
}
""".stripIndent(),
        null)

createOrUpdateJob(
        "example-remote-env-wrapper-duplicate-case",
        "Expected FAILURE. Wrap mode using duplicate-case.env to verify case-insensitive duplicate rejection.",
        """
node {
  withRemoteEnvFiles(sources: [
    [sourceUrl: '${fixtureBase}/duplicate-case.env']
  ]) {
    error('The duplicate-case fixture should fail before this step runs')
  }
}
""".stripIndent(),
        null)

createOrUpdateJob(
        "example-remote-env-job-property-success",
        "Expected SUCCESS. Job property mode using base.env then prod.env to verify later sources override earlier remote values.",
        """
node {
  if (env.APP_NAME != 'remote-env-file-demo') {
    error('APP_NAME was not loaded from the job property fixture')
  }
  if (env.APP_MODE != 'prod') {
    error('APP_MODE was not overridden by the later job property source')
  }
  if (env.COMMON_VALUE != 'prod-override') {
    error('COMMON_VALUE was not overridden by the later job property source')
  }
  echo("Verified layered job property variables for " + env.APP_NAME)
}
""".stripIndent(),
        [
                source(fixtureBase + "/base.env"),
                source(fixtureBase + "/prod.env")
        ])

createOrUpdateJob(
        "example-remote-env-job-property-blocked-loader",
        "Expected FAILURE. Job property mode using blocked-loader.env to verify Pipeline preflight rejects blocked loader variables.",
        """
node {
  echo('This line should never run because the blocked loader fixture must fail first')
}
""".stripIndent(),
        [
                source(fixtureBase + "/blocked-loader.env")
        ])

jenkins.save()
