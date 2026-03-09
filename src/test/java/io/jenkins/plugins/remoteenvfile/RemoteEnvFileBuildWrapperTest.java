package io.jenkins.plugins.remoteenvfile;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.StringParameterValue;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

public class RemoteEnvFileBuildWrapperTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void loadsRemoteVariablesForFreestyleBuild() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("# comment\nFOO=bar\nQUOTED=\"two words\"\nEMPTY=\n"));

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(new RemoteEnvFileBuildWrapper(server.url("/env.env")));
            project.getBuildersList().add(new AssertEnvBuilder(
                    Map.of("FOO", "bar", "QUOTED", "two words"),
                    Collections.emptySet()));

            FreeStyleBuild build = r.buildAndAssertSuccess(project);
            Assert.assertEquals(1, server.server.getRequestCount());
            r.assertLogContains("Loaded 3 environment variable(s)", build);
            r.assertLogNotContains("two words", build);
        }
    }

    @Test
    public void sendsBearerAuthWhenUsingSecretTextCredentials() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            AtomicReference<String> authHeader = new AtomicReference<>();
            server.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    authHeader.set(request.getHeader("Authorization"));
                    if (!"Bearer bearer-secret".equals(authHeader.get())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED);
                    }
                    return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("TOKEN_OK=yes\n");
                }
            });

            addCredential(new StringCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    "bearer-creds",
                    "Bearer test credential",
                    Secret.fromString("bearer-secret")));

            FreeStyleProject project = r.createFreeStyleProject();
            RemoteEnvFileBuildWrapper wrapper = new RemoteEnvFileBuildWrapper(server.url("/secure.env"));
            wrapper.setCredentialsId("bearer-creds");
            project.getBuildWrappersList().add(wrapper);
            project.getBuildersList().add(new AssertEnvBuilder(Map.of("TOKEN_OK", "yes"), Collections.emptySet()));

            FreeStyleBuild build = r.buildAndAssertSuccess(project);
            Assert.assertEquals("Bearer bearer-secret", authHeader.get());
            r.assertLogNotContains("bearer-secret", build);
        }
    }

    @Test
    public void sendsBasicAuthWhenUsingUsernamePasswordCredentials() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            AtomicReference<String> authHeader = new AtomicReference<>();
            server.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    authHeader.set(request.getHeader("Authorization"));
                    if (authHeader.get() == null || !authHeader.get().startsWith("Basic ")) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED);
                    }
                    return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("BASIC_OK=yes\n");
                }
            });

            addCredential(new UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    "basic-creds",
                    "Basic auth credential",
                    "jenkins",
                    "basic-secret"));

            FreeStyleProject project = r.createFreeStyleProject();
            RemoteEnvFileBuildWrapper wrapper = new RemoteEnvFileBuildWrapper(server.url("/basic.env"));
            wrapper.setCredentialsId("basic-creds");
            project.getBuildWrappersList().add(wrapper);
            project.getBuildersList().add(new AssertEnvBuilder(Map.of("BASIC_OK", "yes"), Collections.emptySet()));

            FreeStyleBuild build = r.buildAndAssertSuccess(project);
            Assert.assertNotNull(authHeader.get());
            Assert.assertTrue(authHeader.get().startsWith("Basic "));
            r.assertLogNotContains("basic-secret", build);
        }
    }

    @Test
    public void expandsSourceUrlFromBuildParameters() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            AtomicReference<String> path = new AtomicReference<>();
            server.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    path.set(request.getPath());
                    return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("EXPANDED=yes\n");
                }
            });

            FreeStyleProject project = r.createFreeStyleProject();
            project.addProperty(new hudson.model.ParametersDefinitionProperty(
                    new hudson.model.StringParameterDefinition("ENV_FILE_PATH", "expanded.env")));
            project.getBuildWrappersList().add(new RemoteEnvFileBuildWrapper(server.url("/") + "${ENV_FILE_PATH}"));
            project.getBuildersList().add(new AssertEnvBuilder(Map.of("EXPANDED", "yes"), Collections.emptySet()));

            FreeStyleBuild build = project.scheduleBuild2(
                    0,
                    new ParametersAction(new StringParameterValue("ENV_FILE_PATH", "expanded.env"))).get();
            r.assertBuildStatusSuccess(build);

            Assert.assertEquals("/expanded.env", path.get());
            r.assertLogContains("Loaded 1 environment variable(s)", build);
        }
    }

    @Test
    public void followsRedirects() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if ("/redirect.env".equals(request.getPath())) {
                        return new MockResponse()
                                .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
                                .addHeader("Location", server.url("/final.env"));
                    }
                    if ("/final.env".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("REDIRECT_OK=yes\n");
                    }
                    return new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);
                }
            });

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(new RemoteEnvFileBuildWrapper(server.url("/redirect.env")));
            project.getBuildersList().add(new AssertEnvBuilder(Map.of("REDIRECT_OK", "yes"), Collections.emptySet()));

            r.buildAndAssertSuccess(project);
            Assert.assertEquals(2, server.server.getRequestCount());
        }
    }

    @Test
    public void failsWhenRemoteFetchReturnsErrorStatus() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND));

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(new RemoteEnvFileBuildWrapper(server.url("/missing.env")));

            FreeStyleBuild build = project.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            r.assertLogContains("HTTP 404", build);
        }
    }

    @Test
    public void failsWhenDotenvContentIsInvalid() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("export BAD=value\n"));

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(new RemoteEnvFileBuildWrapper(server.url("/invalid.env")));

            FreeStyleBuild build = project.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            r.assertLogContains("Invalid dotenv content at line 1", build);
        }
    }

    @Test
    public void failsWhenRemoteVariablesConflictWithExistingEnvironment() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("COLLIDE=override\n"));

            FreeStyleProject project = r.createFreeStyleProject();
            project.addProperty(new hudson.model.ParametersDefinitionProperty(
                    new hudson.model.StringParameterDefinition("COLLIDE", "original")));
            project.getBuildWrappersList().add(new RemoteEnvFileBuildWrapper(server.url("/collision.env")));

            FreeStyleBuild build = project.scheduleBuild2(
                    0,
                    new ParametersAction(new StringParameterValue("COLLIDE", "original"))).get();
            r.assertBuildStatus(Result.FAILURE, build);

            r.assertLogContains("conflicts with an existing build environment variable", build);
        }
    }

    @Test
    public void failsWhenRemoteResponseExceedsSizeLimit() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            String oversized = "DATA=" + "x".repeat(RemoteEnvFileBuildWrapper.MAX_RESPONSE_BYTES + 1);
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(oversized));

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(new RemoteEnvFileBuildWrapper(server.url("/large.env")));

            FreeStyleBuild build = project.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            r.assertLogContains("maximum allowed size", build);
        }
    }

    @Test
    public void makesVariablesVisibleOnlyInsidePipelineWrapperScope() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("PIPELINE_ONLY=inside\n"));

            WorkflowJob job = r.createProject(WorkflowJob.class, "pipeline");
            job.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                            + "  wrap([$class: 'RemoteEnvFileBuildWrapper', sourceUrl: '" + escapeGroovy(server.url("/pipeline.env")) + "']) {\n"
                            + "    if (env.PIPELINE_ONLY != 'inside') { error('missing inside wrapper') }\n"
                            + "  }\n"
                            + "  if (env.PIPELINE_ONLY != null) { error('value leaked outside wrapper') }\n"
                            + "  echo 'pipeline assertions passed'\n"
                            + "}\n",
                    true));

            WorkflowRun build = r.buildAndAssertSuccess(job);
            Assert.assertEquals(1, server.server.getRequestCount());
            r.assertLogContains("pipeline assertions passed", build);
            r.assertLogNotContains("inside", build);
        }
    }

    @Test
    public void makesJobLevelVariablesVisibleAcrossPipelineRun() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("PIPELINE_JOB=configured\n"));

            WorkflowJob job = r.createProject(WorkflowJob.class, "pipeline-job-property");
            job.addProperty(new RemoteEnvFileJobProperty(server.url("/job-property.env")));
            job.setDefinition(new CpsFlowDefinition(
                    "if (env.PIPELINE_JOB != 'configured') { error('missing job property env') }\n"
                            + "node {\n"
                            + "  echo 'job property assertions passed'\n"
                            + "}\n",
                    true));

            WorkflowRun build = r.buildAndAssertSuccess(job);
            Assert.assertEquals(1, server.server.getRequestCount());
            r.assertLogContains("job property assertions passed", build);
            r.assertLogNotContains("configured", build);
        }
    }

    @Test
    public void showsJobLevelConfigurationOnPipelineConfigurePage() throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "pipeline-job-config");

        HtmlPage page = r.createWebClient().goTo(job.getUrl() + "configure");
        String html = page.asXml();

        Assert.assertEquals(1, countOccurrences(html, "Load environment variables from a remote HTTPS dotenv file"));
        Assert.assertTrue(html.contains("sourceUrl"));
        Assert.assertTrue(html.contains("credentialsId"));
    }

    @Test
    public void listsSupportedCredentialsInDropdowns() throws Exception {
        addCredential(new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "bearer-creds",
                "Bearer test credential",
                Secret.fromString("bearer-secret")));
        addCredential(new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL,
                "basic-creds",
                "Basic auth credential",
                "jenkins",
                "basic-secret"));

        FreeStyleProject freestyle = r.createFreeStyleProject();
        WorkflowJob pipeline = r.createProject(WorkflowJob.class, "pipeline-job-dropdown");

        List<String> buildWrapperValues = listBoxValues(r.jenkins
                .getDescriptorByType(RemoteEnvFileBuildWrapper.DescriptorImpl.class)
                .doFillCredentialsIdItems(freestyle, null, "https://example.com/env.env"));
        List<String> jobPropertyValues = listBoxValues(r.jenkins
                .getDescriptorByType(RemoteEnvFileJobProperty.DescriptorImpl.class)
                .doFillCredentialsIdItems(pipeline, null, "https://example.com/env.env"));

        Assert.assertTrue(buildWrapperValues.contains(""));
        Assert.assertTrue(buildWrapperValues.contains("bearer-creds"));
        Assert.assertTrue(buildWrapperValues.contains("basic-creds"));
        Assert.assertTrue(jobPropertyValues.contains(""));
        Assert.assertTrue(jobPropertyValues.contains("bearer-creds"));
        Assert.assertTrue(jobPropertyValues.contains("basic-creds"));
    }

    private void addCredential(com.cloudbees.plugins.credentials.Credentials credential) throws IOException {
        SystemCredentialsProvider provider = SystemCredentialsProvider.getInstance();
        provider.getCredentials().add(credential);
        provider.save();
    }

    private static String escapeGroovy(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static List<String> listBoxValues(ListBoxModel model) {
        return model.stream().map(option -> option.value).toList();
    }

    private static final class AssertEnvBuilder extends TestBuilder {

        private static final long serialVersionUID = 1L;

        private final Map<String, String> expected;
        private final Set<String> absent;

        private AssertEnvBuilder(Map<String, String> expected, Set<String> absent) {
            this.expected = expected;
            this.absent = absent;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            EnvVars environment = build.getEnvironment(listener);
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                Assert.assertEquals("Unexpected value for " + entry.getKey(), entry.getValue(), environment.get(entry.getKey()));
            }
            for (String key : absent) {
                Assert.assertNull("Expected no value for " + key, environment.get(key));
            }
            listener.getLogger().println("Environment assertions completed");
            return true;
        }
    }

    private static final class HttpsTestServer implements AutoCloseable {

        private final MockWebServer server;
        private final SSLSocketFactory originalFactory;
        private final HostnameVerifier originalVerifier;

        private HttpsTestServer() throws IOException {
            HeldCertificate certificate = new HeldCertificate.Builder()
                    .commonName("localhost")
                    .addSubjectAlternativeName("localhost")
                    .build();
            HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                    .heldCertificate(certificate)
                    .build();
            HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
                    .addTrustedCertificate(certificate.certificate())
                    .build();

            this.originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
            this.originalVerifier = HttpsURLConnection.getDefaultHostnameVerifier();

            this.server = new MockWebServer();
            this.server.useHttps(serverCertificates.sslSocketFactory(), false);
            this.server.start();

            HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> "localhost".equals(hostname));
        }

        private String url(String path) {
            return server.url(path).toString();
        }

        @Override
        public void close() throws IOException {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory);
            HttpsURLConnection.setDefaultHostnameVerifier(originalVerifier);
            server.shutdown();
        }
    }
}
