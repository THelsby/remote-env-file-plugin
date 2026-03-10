package io.jenkins.plugins.remoteenvfile;

import hudson.Extension;
import hudson.model.Job;
import java.util.List;
import jenkins.model.OptionalJobProperty;
import jenkins.model.OptionalJobProperty.OptionalJobPropertyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class RemoteEnvFileJobProperty extends OptionalJobProperty<Job<?, ?>> {

    private final List<RemoteEnvSource> sources;

    @DataBoundConstructor
    public RemoteEnvFileJobProperty(List<RemoteEnvSource> sources) {
        this.sources = RemoteEnvSource.normalize(sources);
    }

    public List<RemoteEnvSource> getSources() {
        return sources;
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
    }
}
