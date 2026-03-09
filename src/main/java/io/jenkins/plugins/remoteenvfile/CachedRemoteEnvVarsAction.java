package io.jenkins.plugins.remoteenvfile;

import hudson.model.InvisibleAction;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class CachedRemoteEnvVarsAction extends InvisibleAction {

    private final Map<String, String> values;

    CachedRemoteEnvVarsAction(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    Map<String, String> getValues() {
        return values;
    }
}
