package io.jenkins.plugins.remoteenvfile;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

@Extension
public class RemoteEnvFileFlowExecutionListener extends FlowExecutionListener {

    private static final Logger LOGGER = Logger.getLogger(RemoteEnvFileFlowExecutionListener.class.getName());

    @Override
    public void onCreated(FlowExecution execution) {
        FlowExecutionOwner owner = execution.getOwner();
        WorkflowRun run;
        try {
            Object executable = owner.getExecutable();
            if (!(executable instanceof WorkflowRun)) {
                return;
            }
            run = (WorkflowRun) executable;
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to resolve Pipeline run for remote environment preflight", exception);
            return;
        }

        RemoteEnvFileJobProperty property = run.getParent().getProperty(RemoteEnvFileJobProperty.class);
        if (property == null || run.getAction(CachedRemoteEnvVarsAction.class) != null) {
            return;
        }

        TaskListener listener = TaskListener.NULL;
        try {
            listener = owner.getListener();
            EnvVars environment = RemoteEnvFileResolver.buildControllerBaselineEnvironment(run, listener);
            Map<String, String> values = RemoteEnvFileResolver.loadOnController(
                    run,
                    environment,
                    property.getSources(),
                    listener);
            run.addAction(new CachedRemoteEnvVarsAction(values));
            run.save();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(run, listener, exception);
        } catch (IOException exception) {
            fail(run, listener, exception);
        }
    }

    private static void fail(WorkflowRun run, TaskListener listener, Exception exception) {
        String message = failureMessage(exception);
        listener.error(message);
        LOGGER.log(Level.FINE, "Remote environment preflight failed for " + run.getExternalizableId(), exception);
        throw new PipelinePreflightFailure(message);
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Failed to load remote environment file before Pipeline execution";
        }
        return message;
    }

    private static final class PipelinePreflightFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private PipelinePreflightFailure(String message) {
            super(message, null, false, false);
        }
    }
}
