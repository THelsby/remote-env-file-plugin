import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.common.IdCredentials
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import hudson.util.Secret
import jenkins.model.Jenkins
import javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def jenkins = Jenkins.get()
def jobDslSecurity = jenkins.getExtensionList(GlobalJobDslSecurityConfiguration.class).first()
def jobDslDir = new File(jenkins.rootDir, 'jobdsl')
def generatedJobNames = [
        'jobdsl-remote-env-wrapper-success',
        'jobdsl-remote-env-pipeline-property-success',
        'jobdsl-remote-env-configure-fallback'
]

def ensureCredential = { IdCredentials credential ->
    def provider = SystemCredentialsProvider.getInstance()
    if (provider.getCredentials().any { it.id == credential.id }) {
        return
    }
    provider.getCredentials().add(credential)
    provider.save()
}

ensureCredential(new StringCredentialsImpl(
        CredentialsScope.GLOBAL,
        'fixture-bearer',
        'Development bearer credential for Job DSL examples',
        Secret.fromString('fixture-bearer-token')))

ensureCredential(new UsernamePasswordCredentialsImpl(
        CredentialsScope.GLOBAL,
        'fixture-basic',
        'Development basic-auth credential for Job DSL examples',
        'fixture-user',
        'fixture-password'))

jobDslSecurity.setUseScriptSecurity(false)
jobDslSecurity.save()

def seedJob = jenkins.getItem('seed-remote-env-jobdsl-examples') as WorkflowJob
if (seedJob == null) {
    seedJob = jenkins.createProject(WorkflowJob, 'seed-remote-env-jobdsl-examples')
}

def exampleFiles = [
        'freestyle-native-wrapper.groovy',
        'pipeline-native-job-property.groovy',
        'pipeline-configure-fallback.groovy'
]
def jobDslScript = exampleFiles.collect { fileName ->
    new File(jobDslDir, fileName).getText('UTF-8')
}.join('\n\n')

seedJob.setDescription('Generates Job DSL example jobs for the remote-env-file plugin from the example scripts synced into /var/jenkins_home/jobdsl/. See examples/jobdsl/README.md in the repo for the copy/paste syntax reference and API Viewer limitation note.')
seedJob.setDefinition(new CpsFlowDefinition("""
pipeline {
  agent any

  stages {
    stage('Generate Job DSL Examples') {
      steps {
        jobDsl(
          lookupStrategy: 'JENKINS_ROOT',
          removedJobAction: 'IGNORE',
          removedViewAction: 'IGNORE',
          sandbox: true,
          scriptText: ${jobDslScript.inspect()}
        )
      }
    }
  }
}
""".stripIndent(), true))
seedJob.save()

if (generatedJobNames.any { jenkins.getItem(it) == null }) {
    seedJob.scheduleBuild2(0)
}

jenkins.save()
