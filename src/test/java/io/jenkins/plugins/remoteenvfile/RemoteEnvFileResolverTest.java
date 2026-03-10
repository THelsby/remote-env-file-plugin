package io.jenkins.plugins.remoteenvfile;

import hudson.AbortException;
import hudson.EnvVars;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class RemoteEnvFileResolverTest {

    @Test
    public void rejectsBlockedSpecialVariablesCaseInsensitively() {
        assertBlocked("PATH");
        assertBlocked("Path");
        assertBlocked("PATHEXT");
        assertBlocked("LD_PRELOAD");
        assertBlocked("LD_LIBRARY_PATH");
        assertBlocked("DYLD_LIBRARY_PATH");
        assertBlocked("DYLD_INSERT_LIBRARIES");
        assertBlocked("LIBPATH");
        assertBlocked("SHLIB_PATH");
    }

    @Test
    public void detectsExistingEnvironmentCollisionsCaseInsensitively() {
        EnvVars environment = new EnvVars();
        environment.put("App_Mode", "existing");

        AbortException exception = validateFailure(environment, Map.of("APP_MODE", "override"));
        Assert.assertTrue(exception.getMessage().contains("conflicts with an existing build environment variable"));
    }

    @Test
    public void mergesSourcesTopToBottomAndLaterSourcesOverrideEarlierOnes() {
        Map<String, String> merged = RemoteEnvFileResolver.mergeRemoteVariables(List.of(
                Map.of("APP_MODE", "base", "BASE_ONLY", "yes"),
                Map.of("APP_MODE", "prod", "PROD_ONLY", "yes")));

        Assert.assertEquals("prod", merged.get("APP_MODE"));
        Assert.assertEquals("yes", merged.get("BASE_ONLY"));
        Assert.assertEquals("yes", merged.get("PROD_ONLY"));
        Assert.assertEquals(3, merged.size());
    }

    @Test
    public void mergesSourcesCaseInsensitivelyAndPreservesLaterKeyCasing() {
        Map<String, String> merged = RemoteEnvFileResolver.mergeRemoteVariables(List.of(
                Map.of("FOO", "first"),
                Map.of("foo", "second")));

        Assert.assertFalse(merged.containsKey("FOO"));
        Assert.assertEquals("second", merged.get("foo"));
        Assert.assertEquals(1, merged.size());
    }

    private static void assertBlocked(String key) {
        AbortException exception = validateFailure(new EnvVars(), Map.of(key, "value"));
        Assert.assertTrue(exception.getMessage().contains("special OS/process-loading variable"));
    }

    private static AbortException validateFailure(EnvVars environment, Map<String, String> values) {
        try {
            RemoteEnvFileResolver.validateParsedVariables(environment, values);
            Assert.fail("Expected validation to fail");
            return null;
        } catch (AbortException exception) {
            return exception;
        }
    }
}
