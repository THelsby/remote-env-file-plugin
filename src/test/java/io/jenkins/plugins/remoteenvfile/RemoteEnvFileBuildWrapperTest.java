package io.jenkins.plugins.remoteenvfile;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
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
import javax.net.ssl.SSLContext;
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
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.kohsuke.stapler.verb.POST;

public class RemoteEnvFileBuildWrapperTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void loadsRemoteVariablesForFreestyleBuild() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("# comment\nFOO=bar\nQUOTED=\"two words\"\nEMPTY=\n"));

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(wrapper(source(server.url("/env.env"))));
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
            project.getBuildWrappersList().add(wrapper(source(server.url("/secure.env"), "bearer-creds")));
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
            project.getBuildWrappersList().add(wrapper(source(server.url("/basic.env"), "basic-creds")));
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
            project.getBuildWrappersList().add(wrapper(source(server.url("/") + "${ENV_FILE_PATH}")));
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
            project.getBuildWrappersList().add(wrapper(source(server.url("/redirect.env"))));
            project.getBuildersList().add(new AssertEnvBuilder(Map.of("REDIRECT_OK", "yes"), Collections.emptySet()));

            r.buildAndAssertSuccess(project);
            Assert.assertEquals(2, server.server.getRequestCount());
        }
    }

    @Test
    public void mergesMultipleSourcesForFreestyleBuild() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if ("/base.env".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                                .setBody("APP_MODE=base\nBASE_ONLY=yes\n");
                    }
                    if ("/prod.env".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                                .setBody("APP_MODE=prod\nPROD_ONLY=yes\n");
                    }
                    return new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);
                }
            });

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(wrapper(
                    source(server.url("/base.env")),
                    source(server.url("/prod.env"))));
            project.getBuildersList().add(new AssertEnvBuilder(
                    Map.of("APP_MODE", "prod", "BASE_ONLY", "yes", "PROD_ONLY", "yes"),
                    Collections.emptySet()));

            FreeStyleBuild build = r.buildAndAssertSuccess(project);
            Assert.assertEquals(2, server.server.getRequestCount());
            r.assertLogContains("Loaded 3 merged environment variable(s) from 2 remote source(s)", build);
        }
    }

    @Test
    public void failsWhenAnyConfiguredSourceReturnsErrorStatus() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if ("/base.env".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("BASE_OK=yes\n");
                    }
                    if ("/missing.env".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);
                    }
                    return new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);
                }
            });

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(wrapper(
                    source(server.url("/base.env")),
                    source(server.url("/missing.env"))));

            FreeStyleBuild build = project.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            Assert.assertEquals(2, server.server.getRequestCount());
            r.assertLogContains("HTTP 404", build);
        }
    }

    @Test
    public void failsWhenDotenvContentIsInvalid() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("export BAD=value\n"));

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(wrapper(source(server.url("/invalid.env"))));

            FreeStyleBuild build = project.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            r.assertLogContains("Invalid dotenv content at line 1", build);
        }
    }

    @Test
    public void failsWhenRemoteVariablesConflictWithExistingEnvironment() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("APP_MODE=override\n"));

            FreeStyleProject project = r.createFreeStyleProject();
            project.addProperty(new hudson.model.ParametersDefinitionProperty(
                    new hudson.model.StringParameterDefinition("App_Mode", "original")));
            project.getBuildWrappersList().add(wrapper(source(server.url("/collision.env"))));

            FreeStyleBuild build = project.scheduleBuild2(
                    0,
                    new ParametersAction(new StringParameterValue("App_Mode", "original"))).get();
            r.assertBuildStatus(Result.FAILURE, build);

            r.assertLogContains("conflicts with an existing build environment variable", build);
        }
    }

    @Test
    public void failsWhenRemoteVariablesContainBlockedPathVariable() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("Path=override\n"));

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(wrapper(source(server.url("/blocked-path.env"))));

            FreeStyleBuild build = project.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            r.assertLogContains("special OS/process-loading variable", build);
        }
    }

    @Test
    public void failsWhenRemoteVariablesContainBlockedLoaderVariable() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("LD_PRELOAD=/tmp/evil.so\n"));

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(wrapper(source(server.url("/blocked-loader.env"))));

            FreeStyleBuild build = project.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            r.assertLogContains("special OS/process-loading variable", build);
        }
    }

    @Test
    public void failsWhenRemoteResponseExceedsSizeLimit() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            String oversized = "DATA=" + "x".repeat(RemoteEnvFileBuildWrapper.MAX_RESPONSE_BYTES + 1);
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(oversized));

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(wrapper(source(server.url("/large.env"))));

            FreeStyleBuild build = project.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            r.assertLogContains("maximum allowed size", build);
        }
    }

    @Test
    public void makesVariablesVisibleOnlyInsidePipelineWrapperScope() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if ("/base.env".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                                .setBody("PIPELINE_ONLY=inside\nAPP_MODE=base\n");
                    }
                    if ("/override.env".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                                .setBody("app_mode=override\n");
                    }
                    return new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);
                }
            });

            WorkflowJob job = r.createProject(WorkflowJob.class, "pipeline");
            job.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                            + "  withRemoteEnvFiles(sources: [\n"
                            + "    [sourceUrl: '" + escapeGroovy(server.url("/base.env")) + "'],\n"
                            + "    [sourceUrl: '" + escapeGroovy(server.url("/override.env")) + "']\n"
                            + "  ]) {\n"
                            + "    if (env.PIPELINE_ONLY != 'inside') { error('missing inside wrapper') }\n"
                            + "    if (env.app_mode != 'override') { error('missing overridden wrapper value') }\n"
                            + "  }\n"
                            + "  if (env.PIPELINE_ONLY != null) { error('value leaked outside wrapper') }\n"
                            + "  if (env.app_mode != null) { error('override leaked outside wrapper') }\n"
                            + "  echo 'pipeline assertions passed'\n"
                            + "}\n",
                    true));

            WorkflowRun build = r.buildAndAssertSuccess(job);
            Assert.assertEquals(2, server.server.getRequestCount());
            r.assertLogContains("pipeline assertions passed", build);
            r.assertLogNotContains("inside", build);
        }
    }

    @Test
    public void failsWhenPipelineWrapperLoadsBlockedVariable() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("DYLD_LIBRARY_PATH=/tmp/injected\n"));

            WorkflowJob job = r.createProject(WorkflowJob.class, "pipeline-blocked-wrapper");
            job.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                            + "  withRemoteEnvFiles(sources: [[sourceUrl: '"
                            + escapeGroovy(server.url("/blocked-pipeline.env"))
                            + "']]) {\n"
                            + "    error('wrapper should have failed before this point')\n"
                            + "  }\n"
                            + "}\n",
                    true));

            WorkflowRun build = job.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            r.assertLogContains("special OS/process-loading variable", build);
        }
    }

    @Test
    public void makesJobLevelVariablesVisibleAcrossPipelineRun() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if ("/base.env".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                                .setBody("PIPELINE_JOB=configured\nPIPELINE_MODE=base\n");
                    }
                    if ("/override.env".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                                .setBody("pipeline_mode=job-property-override\n");
                    }
                    return new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);
                }
            });

            WorkflowJob job = r.createProject(WorkflowJob.class, "pipeline-job-property");
            job.addProperty(jobProperty(
                    source(server.url("/base.env")),
                    source(server.url("/override.env"))));
            job.setDefinition(new CpsFlowDefinition(
                    "if (env.PIPELINE_JOB != 'configured') { error('missing job property env') }\n"
                            + "if (env.pipeline_mode != 'job-property-override') { error('missing overridden job property env') }\n"
                            + "node {\n"
                            + "  echo 'job property assertions passed'\n"
                            + "}\n",
                    true));

            WorkflowRun build = r.buildAndAssertSuccess(job);
            Assert.assertEquals(2, server.server.getRequestCount());
            r.assertLogContains("job property assertions passed", build);
            r.assertLogNotContains("configured", build);
        }
    }

    @Test
    public void failsWhenJobLevelConfigurationLoadsBlockedVariable() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            server.server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("LD_PRELOAD=/tmp/evil.so\n"));

            WorkflowJob job = r.createProject(WorkflowJob.class, "pipeline-job-property-blocked");
            job.addProperty(jobProperty(source(server.url("/job-property-blocked.env"))));
            job.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                            + "  echo 'this should not run'\n"
                            + "}\n",
                    true));

            WorkflowRun build = job.scheduleBuild2(0).get();
            r.assertBuildStatus(Result.FAILURE, build);
            Assert.assertEquals(1, server.server.getRequestCount());
            r.assertLogContains("special OS/process-loading variable", build);
            r.assertLogNotContains("this should not run", build);
        }
    }

    @Test
    public void showsJobLevelConfigurationOnPipelineConfigurePage() throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "pipeline-job-config");

        HtmlPage page = r.createWebClient().goTo(job.getUrl() + "configure");
        String html = page.asXml();

        Assert.assertEquals(1, countOccurrences(html, "Load environment variables from a remote HTTPS dotenv file"));
        Assert.assertTrue(html.contains("sources"));
        Assert.assertTrue(html.contains("sourceUrl"));
        Assert.assertTrue(html.contains("credentialsId"));
        Assert.assertTrue(html.contains("checkMethod=\"post\""));
        Assert.assertTrue(html.contains("does not mask or protect"));
    }

    @Test
    public void usesPostForSourceValidationAndCredentialDropdownEndpoints() throws Exception {
        Assert.assertTrue(RemoteEnvSource.DescriptorImpl.class
                .getMethod("doCheckSourceUrl", String.class)
                .isAnnotationPresent(POST.class));
        Assert.assertTrue(RemoteEnvSource.DescriptorImpl.class
                .getMethod("doFillCredentialsIdItems", hudson.model.Item.class, String.class, String.class)
                .isAnnotationPresent(POST.class));
    }

    @Test
    public void honorsDifferentCredentialsPerSource() throws Exception {
        try (HttpsTestServer server = new HttpsTestServer()) {
            AtomicReference<String> bearerHeader = new AtomicReference<>();
            AtomicReference<String> basicHeader = new AtomicReference<>();
            server.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if ("/bearer.env".equals(request.getPath())) {
                        bearerHeader.set(request.getHeader("Authorization"));
                        if (!"Bearer bearer-secret".equals(bearerHeader.get())) {
                            return new MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED);
                        }
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("BEARER_OK=yes\n");
                    }
                    if ("/basic.env".equals(request.getPath())) {
                        basicHeader.set(request.getHeader("Authorization"));
                        if (basicHeader.get() == null || !basicHeader.get().startsWith("Basic ")) {
                            return new MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED);
                        }
                        return new MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("BASIC_OK=yes\n");
                    }
                    return new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);
                }
            });

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

            FreeStyleProject project = r.createFreeStyleProject();
            project.getBuildWrappersList().add(wrapper(
                    source(server.url("/bearer.env"), "bearer-creds"),
                    source(server.url("/basic.env"), "basic-creds")));
            project.getBuildersList().add(new AssertEnvBuilder(
                    Map.of("BEARER_OK", "yes", "BASIC_OK", "yes"),
                    Collections.emptySet()));

            FreeStyleBuild build = r.buildAndAssertSuccess(project);
            Assert.assertEquals("Bearer bearer-secret", bearerHeader.get());
            Assert.assertNotNull(basicHeader.get());
            Assert.assertTrue(basicHeader.get().startsWith("Basic "));
            r.assertLogNotContains("bearer-secret", build);
            r.assertLogNotContains("basic-secret", build);
        }
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
        List<String> sourceValues = listBoxValues(r.jenkins
                .getDescriptorByType(RemoteEnvSource.DescriptorImpl.class)
                .doFillCredentialsIdItems(freestyle, null, "https://example.com/env.env"));

        Assert.assertTrue(sourceValues.contains(""));
        Assert.assertTrue(sourceValues.contains("bearer-creds"));
        Assert.assertTrue(sourceValues.contains("basic-creds"));
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

    private static RemoteEnvFileBuildWrapper wrapper(RemoteEnvSource... sources) {
        return new RemoteEnvFileBuildWrapper(List.of(sources));
    }

    private static RemoteEnvFileJobProperty jobProperty(RemoteEnvSource... sources) {
        return new RemoteEnvFileJobProperty(List.of(sources));
    }

    private static RemoteEnvSource source(String sourceUrl) {
        return source(sourceUrl, null);
    }

    private static RemoteEnvSource source(String sourceUrl, String credentialsId) {
        RemoteEnvSource source = new RemoteEnvSource(sourceUrl);
        source.setCredentialsId(credentialsId);
        return source;
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
        private final SSLContext originalContext;

        private HttpsTestServer() throws Exception {
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

            this.originalContext = SSLContext.getDefault();

            this.server = new MockWebServer();
            this.server.useHttps(serverCertificates.sslSocketFactory(), false);
            this.server.start();

            SSLContext.setDefault(clientCertificates.sslContext());
        }

        private String url(String path) {
            return server.url(path).toString();
        }

        @Override
        public void close() throws Exception {
            SSLContext.setDefault(originalContext);
            server.shutdown();
        }
    }
}
