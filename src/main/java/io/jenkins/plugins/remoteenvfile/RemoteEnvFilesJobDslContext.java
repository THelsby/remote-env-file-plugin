package io.jenkins.plugins.remoteenvfile;

import groovy.lang.Closure;
import java.util.ArrayList;
import java.util.List;
import javaposse.jobdsl.dsl.Context;
import javaposse.jobdsl.dsl.ContextHelper;

public class RemoteEnvFilesJobDslContext implements Context {

    private final List<RemoteEnvSource> sources = new ArrayList<>();

    public void source(String sourceUrl) {
        source(sourceUrl, null);
    }

    public void source(String sourceUrl, String credentialsId) {
        sources.add(RemoteEnvSourceJobDslContext.buildSource(sourceUrl, credentialsId));
    }

    public void source(Closure<?> closure) {
        RemoteEnvSourceJobDslContext context = new RemoteEnvSourceJobDslContext();
        ContextHelper.executeInContext(closure, context);
        source(context);
    }

    void source(RemoteEnvSourceJobDslContext context) {
        sources.add(context.build());
    }

    List<RemoteEnvSource> buildSources() {
        List<RemoteEnvSource> configuredSources = RemoteEnvSource.normalize(sources);
        if (configuredSources.isEmpty()) {
            throw new IllegalArgumentException("At least one remote source is required");
        }
        return configuredSources;
    }
}
