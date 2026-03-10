package io.jenkins.plugins.remoteenvfile;

import hudson.AbortException;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class RemoteEnvSource extends AbstractDescribableImpl<RemoteEnvSource> {

    private final String sourceUrl;
    private String credentialsId;

    @DataBoundConstructor
    public RemoteEnvSource(String sourceUrl) {
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

    static List<RemoteEnvSource> normalize(List<RemoteEnvSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }

        List<RemoteEnvSource> normalized = new ArrayList<>(sources.size());
        for (RemoteEnvSource source : sources) {
            if (source != null) {
                normalized.add(source);
            }
        }
        return Collections.unmodifiableList(normalized);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<RemoteEnvSource> {

        @Override
        public String getDisplayName() {
            return "Remote source";
        }

        @POST
        public FormValidation doCheckSourceUrl(@QueryParameter String value) {
            try {
                RemoteEnvFileResolver.validateSourceUrl(value);
                return FormValidation.ok();
            } catch (AbortException exception) {
                return FormValidation.error(exception.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item,
                @QueryParameter String credentialsId,
                @QueryParameter String sourceUrl) {
            return RemoteEnvFileCredentials.fillCredentialsIdItems(item, credentialsId, sourceUrl);
        }
    }
}
