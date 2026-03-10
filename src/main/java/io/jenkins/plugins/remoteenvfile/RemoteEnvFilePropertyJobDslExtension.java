package io.jenkins.plugins.remoteenvfile;

import groovy.lang.Closure;
import hudson.Extension;
import javaposse.jobdsl.dsl.helpers.properties.PropertiesContext;
import javaposse.jobdsl.plugin.ContextExtensionPoint;
import javaposse.jobdsl.plugin.DslExtensionMethod;

@Extension(optional = true)
public class RemoteEnvFilePropertyJobDslExtension extends ContextExtensionPoint {

    @DslExtensionMethod(context = PropertiesContext.class)
    public RemoteEnvFileJobProperty remoteEnvFiles(Closure<?> closure) {
        RemoteEnvFilesJobDslContext context = new RemoteEnvFilesJobDslContext();
        executeInContext(closure, context);
        return buildProperty(context);
    }

    static RemoteEnvFileJobProperty buildProperty(RemoteEnvFilesJobDslContext context) {
        return new RemoteEnvFileJobProperty(context.buildSources());
    }
}
