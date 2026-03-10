package io.jenkins.plugins.remoteenvfile;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildWrapperDescriptor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import jenkins.tasks.SimpleBuildWrapper;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

public class RemoteEnvFileBuildWrapper extends SimpleBuildWrapper {

    static final int MAX_RESPONSE_BYTES = RemoteEnvFileFetcher.MAX_RESPONSE_BYTES;

    private final List<RemoteEnvSource> sources;

    @DataBoundConstructor
    public RemoteEnvFileBuildWrapper(List<RemoteEnvSource> sources) {
        this.sources = RemoteEnvSource.normalize(sources);
    }

    public List<RemoteEnvSource> getSources() {
        return sources;
    }

    @Override
    public boolean requiresWorkspace() {
        return true;
    }

    @Override
    public void setUp(
            Context context,
            Run<?, ?> build,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            EnvVars initialEnvironment) throws IOException, InterruptedException {
        Map<String, String> parsed = RemoteEnvFileResolver.loadOnAgent(
                build,
                initialEnvironment,
                sources,
                workspace,
                listener);
        for (Map.Entry<String, String> entry : parsed.entrySet()) {
            context.env(entry.getKey(), entry.getValue());
        }
    }

    @Symbol("withRemoteEnvFiles")
    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Load environment variables from a remote HTTPS dotenv file";
        }
    }
}
