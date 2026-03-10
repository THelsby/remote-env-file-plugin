package io.jenkins.plugins.remoteenvfile;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.EnvironmentContributor;
import java.io.IOException;

@Extension
public class RemoteEnvFileEnvironmentContributor extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(Run run, EnvVars env, TaskListener listener) throws IOException, InterruptedException {
        @SuppressWarnings("unchecked")
        Run<?, ?> typedRun = (Run<?, ?>) run;
        CachedRemoteEnvVarsAction cached = typedRun.getAction(CachedRemoteEnvVarsAction.class);
        if (cached == null) {
            return;
        }
        env.overrideAll(cached.getValues());
    }
}
