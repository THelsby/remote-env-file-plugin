package io.jenkins.plugins.remoteenvfile;

import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Job;
import hudson.util.ListBoxModel;
import hudson.util.FormValidation;
import jenkins.model.OptionalJobProperty;
import jenkins.model.OptionalJobProperty.OptionalJobPropertyDescriptor;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class RemoteEnvFileJobProperty extends OptionalJobProperty<Job<?, ?>> {

    private final String sourceUrl;
    private String credentialsId;

    @DataBoundConstructor
    public RemoteEnvFileJobProperty(String sourceUrl) {
        this.sourceUrl = Util.fixNull(sourceUrl);
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    @Extension
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return "org.jenkinsci.plugins.workflow.job.WorkflowJob".equals(jobType.getName());
        }

        @Override
        public String getDisplayName() {
            return "Load environment variables from a remote HTTPS dotenv file";
        }

        public FormValidation doCheckSourceUrl(@QueryParameter String value) {
            try {
                RemoteEnvFileResolver.validateSourceUrl(value);
                return FormValidation.ok();
            } catch (hudson.AbortException exception) {
                return FormValidation.error(exception.getMessage());
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
