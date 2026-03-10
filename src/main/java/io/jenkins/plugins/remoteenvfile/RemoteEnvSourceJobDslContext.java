package io.jenkins.plugins.remoteenvfile;

import hudson.Util;
import javaposse.jobdsl.dsl.Context;

public class RemoteEnvSourceJobDslContext implements Context {

    private String sourceUrl;
    private String credentialsId;

    public void sourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public void credentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    RemoteEnvSource build() {
        return buildSource(sourceUrl, credentialsId);
    }

    static RemoteEnvSource buildSource(String sourceUrl, String credentialsId) {
        String configuredSourceUrl = Util.fixEmptyAndTrim(sourceUrl);
        if (configuredSourceUrl == null) {
            throw new IllegalArgumentException("A remote source URL is required");
        }
        RemoteEnvSource source = new RemoteEnvSource(configuredSourceUrl);
        source.setCredentialsId(credentialsId);
        return source;
    }
}
