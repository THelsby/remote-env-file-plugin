package io.jenkins.plugins.remoteenvfile;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.Map;
import jenkins.tasks.SimpleBuildWrapper;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class RemoteEnvFileBuildWrapper extends SimpleBuildWrapper {

    static final int MAX_RESPONSE_BYTES = RemoteEnvFileFetcher.MAX_RESPONSE_BYTES;

    private final String sourceUrl;
    private String credentialsId;

    @DataBoundConstructor
    public RemoteEnvFileBuildWrapper(String sourceUrl) {
        this.sourceUrl = hudson.Util.fixNull(sourceUrl);
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = hudson.Util.fixEmptyAndTrim(credentialsId);
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
                sourceUrl,
                credentialsId,
                workspace,
                listener);
        for (Map.Entry<String, String> entry : parsed.entrySet()) {
            context.env(entry.getKey(), entry.getValue());
        }
    }

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

        public hudson.util.FormValidation doCheckSourceUrl(@QueryParameter String value) {
            try {
                RemoteEnvFileResolver.validateSourceUrl(value);
                return hudson.util.FormValidation.ok();
            } catch (AbortException exception) {
                return hudson.util.FormValidation.error(exception.getMessage());
            }
        }

        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item,
                @QueryParameter String credentialsId,
                @QueryParameter String sourceUrl) {
            return RemoteEnvFileCredentials.fillCredentialsIdItems(item, credentialsId, sourceUrl);
        }
    }
}
