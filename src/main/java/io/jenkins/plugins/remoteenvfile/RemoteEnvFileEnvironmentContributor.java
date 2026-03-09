package io.jenkins.plugins.remoteenvfile;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.EnvironmentContributingAction;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.EnvironmentContributor;
import java.io.IOException;
import java.util.Map;

@Extension
public class RemoteEnvFileEnvironmentContributor extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(Run run, EnvVars env, TaskListener listener) throws IOException, InterruptedException {
        @SuppressWarnings("unchecked")
        Run<?, ?> typedRun = (Run<?, ?>) run;
        RemoteEnvFileJobProperty property =
                (RemoteEnvFileJobProperty) typedRun.getParent().getProperty(RemoteEnvFileJobProperty.class);
        if (property == null) {
            return;
        }

        CachedRemoteEnvVarsAction cached = typedRun.getAction(CachedRemoteEnvVarsAction.class);
        Map<String, String> values;
        if (cached != null) {
            values = cached.getValues();
        } else {
            EnvVars effectiveEnvironment = new EnvVars(env);
            for (Action action : typedRun.getAllActions()) {
                if (action instanceof EnvironmentContributingAction) {
                    ((EnvironmentContributingAction) action).buildEnvironment(typedRun, effectiveEnvironment);
                }
            }
            for (ParametersAction parametersAction : typedRun.getActions(ParametersAction.class)) {
                for (ParameterValue parameterValue : parametersAction.getParameters()) {
                    parameterValue.buildEnvironment(typedRun, effectiveEnvironment);
                }
            }
            values = RemoteEnvFileResolver.loadOnController(
                    typedRun,
                    effectiveEnvironment,
                    property.getSourceUrl(),
                    property.getCredentialsId(),
                    listener);
            typedRun.addAction(new CachedRemoteEnvVarsAction(values));
        }

        env.overrideAll(values);
    }
}
