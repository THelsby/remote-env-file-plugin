package io.jenkins.plugins.remoteenvfile;

import groovy.lang.Closure;
import hudson.Extension;
import javaposse.jobdsl.dsl.helpers.wrapper.WrapperContext;
import javaposse.jobdsl.plugin.ContextExtensionPoint;
import javaposse.jobdsl.plugin.DslExtensionMethod;

@Extension(optional = true)
public class RemoteEnvFileWrapperJobDslExtension extends ContextExtensionPoint {

    @DslExtensionMethod(context = WrapperContext.class)
    public RemoteEnvFileBuildWrapper remoteEnvFiles(Closure<?> closure) {
        RemoteEnvFilesJobDslContext context = new RemoteEnvFilesJobDslContext();
        executeInContext(closure, context);
        return buildWrapper(context);
    }

    static RemoteEnvFileBuildWrapper buildWrapper(RemoteEnvFilesJobDslContext context) {
        return new RemoteEnvFileBuildWrapper(context.buildSources());
    }
}
