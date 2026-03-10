package io.jenkins.plugins.remoteenvfile;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Action;
import hudson.model.EnvironmentContributingAction;
import hudson.model.EnvironmentContributor;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

final class RemoteEnvFileResolver {

    private static final NavigableSet<String> BLOCKED_VARIABLES = blockedVariables();

    private RemoteEnvFileResolver() {
    }

    static Map<String, String> loadOnAgent(
            Run<?, ?> build,
            EnvVars environment,
            List<RemoteEnvSource> sources,
            FilePath workspace,
            TaskListener listener) throws IOException, InterruptedException {
        return load(build, environment, sources, listener, (expandedUrl, auth) ->
                workspace.act(new RemoteEnvFileFetcher.AgentCallable(expandedUrl, auth)));
    }

    static Map<String, String> loadOnController(
            Run<?, ?> build,
            EnvVars environment,
            List<RemoteEnvSource> sources,
            TaskListener listener) throws IOException, InterruptedException {
        return load(build, environment, sources, listener, RemoteEnvFileFetcher::fetch);
    }

    static EnvVars buildControllerBaselineEnvironment(Run<?, ?> build, TaskListener listener)
            throws IOException, InterruptedException {
        EnvVars environment = build.getParent().getEnvironment(null, listener);
        environment.putAll(build.getCharacteristicEnvVars());

        for (EnvironmentContributor contributor : EnvironmentContributor.all().reverseView()) {
            if (contributor instanceof RemoteEnvFileEnvironmentContributor) {
                continue;
            }
            contributor.buildEnvironmentFor(build, environment, listener);
        }

        for (Action action : build.getAllActions()) {
            if (action instanceof EnvironmentContributingAction) {
                ((EnvironmentContributingAction) action).buildEnvironment(build, environment);
            }
        }
        for (ParametersAction parametersAction : build.getActions(ParametersAction.class)) {
            for (ParameterValue parameterValue : parametersAction.getParameters()) {
                parameterValue.buildEnvironment(build, environment);
            }
        }
        return environment;
    }

    private static Map<String, String> load(
            Run<?, ?> build,
            EnvVars environment,
            List<RemoteEnvSource> sources,
            TaskListener listener,
            Fetcher fetcher) throws IOException, InterruptedException {
        List<RemoteEnvSource> configuredSources = requireSources(sources);
        List<Map<String, String>> loadedSources = new ArrayList<>(configuredSources.size());

        int index = 0;
        for (RemoteEnvSource source : configuredSources) {
            index++;
            String expandedUrl = environment.expand(requireSourceUrl(source.getSourceUrl()));
            URI validatedUri = validateSourceUrl(expandedUrl);
            RemoteFetchAuth auth = resolveAuthentication(build, environment, source.getCredentialsId());
            String logLocation = sanitizeForLogs(validatedUri);

            listener.getLogger().printf("Loading remote environment source %d from %s%n", index, logLocation);

            String dotenvContent;
            try {
                dotenvContent = fetcher.fetch(validatedUri.toString(), auth);
            } catch (IOException exception) {
                throw new AbortException(
                        "Failed to fetch remote environment file from " + logLocation + ": " + exception.getMessage());
            }

            Map<String, String> parsed = DotenvParser.parse(dotenvContent);
            validateParsedVariables(environment, parsed);
            loadedSources.add(parsed);
            listener.getLogger().printf("Loaded %d environment variable(s) from %s%n", parsed.size(), logLocation);
        }

        Map<String, String> merged = mergeRemoteVariables(loadedSources);
        listener.getLogger().printf(
                "Loaded %d merged environment variable(s) from %d remote source(s)%n",
                merged.size(),
                configuredSources.size());
        return merged;
    }

    static URI validateSourceUrl(String value) throws AbortException {
        try {
            URI uri = URI.create(Util.fixNull(value).trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new AbortException("Only HTTPS source URLs are supported");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new AbortException("The source URL must include a host");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new AbortException("The source URL must be a valid HTTPS URL");
        }
    }

    private static String requireSourceUrl(String configuredValue) throws AbortException {
        String trimmed = Util.fixEmptyAndTrim(configuredValue);
        if (trimmed == null) {
            throw new AbortException("A remote source URL is required");
        }
        return trimmed;
    }

    private static List<RemoteEnvSource> requireSources(List<RemoteEnvSource> sources) throws AbortException {
        List<RemoteEnvSource> normalized = RemoteEnvSource.normalize(sources);
        if (normalized.isEmpty()) {
            throw new AbortException("At least one remote source is required");
        }
        return normalized;
    }

    static void validateParsedVariables(EnvVars environment, Map<String, String> parsed) throws AbortException {
        validateNoBlockedVariables(parsed);
        validateNoExistingEnvironmentCollisions(environment, parsed);
    }

    static Map<String, String> mergeRemoteVariables(List<Map<String, String>> parsedSources) {
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> keysByCase = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map<String, String> parsed : parsedSources) {
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                String previousKey = keysByCase.put(entry.getKey(), entry.getKey());
                if (previousKey != null) {
                    merged.remove(previousKey);
                }
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(merged);
    }

    private static void validateNoBlockedVariables(Map<String, String> parsed) throws AbortException {
        for (String key : parsed.keySet()) {
            if (BLOCKED_VARIABLES.contains(key)) {
                throw new AbortException(
                        "Remote environment variable '" + key + "' is not allowed because it is a special OS/process-loading variable");
            }
        }
    }

    private static void validateNoExistingEnvironmentCollisions(EnvVars environment, Map<String, String> parsed)
            throws AbortException {
        for (String key : parsed.keySet()) {
            // Jenkins EnvVars uses a case-insensitive map, so PATH vs Path is treated as the same variable.
            if (environment.containsKey(key)) {
                throw new AbortException(
                        "Remote environment variable '" + key + "' conflicts with an existing build environment variable");
            }
        }
    }

    private static RemoteFetchAuth resolveAuthentication(Run<?, ?> build, EnvVars environment, String credentialsId)
            throws AbortException {
        String configuredCredentialsId = Util.fixEmptyAndTrim(environment.expand(Util.fixNull(credentialsId)));
        if (configuredCredentialsId == null) {
            return RemoteFetchAuth.none();
        }

        IdCredentials credential = CredentialsProvider.findCredentialById(configuredCredentialsId, IdCredentials.class, build);
        if (credential == null) {
            throw new AbortException("Credentials ID '" + configuredCredentialsId + "' could not be found");
        }
        if (credential instanceof StringCredentials) {
            String token = Secret.toString(((StringCredentials) credential).getSecret());
            return RemoteFetchAuth.bearer(token);
        }
        if (credential instanceof UsernamePasswordCredentials) {
            UsernamePasswordCredentials usernamePassword = (UsernamePasswordCredentials) credential;
            return RemoteFetchAuth.basic(
                    usernamePassword.getUsername(),
                    Secret.toString(usernamePassword.getPassword()));
        }
        throw new AbortException(
                "Credentials ID '" + configuredCredentialsId + "' must be Secret Text or Username with password");
    }

    static String sanitizeForLogs(URI uri) {
        StringBuilder sanitized = new StringBuilder();
        sanitized.append(uri.getScheme()).append("://").append(uri.getHost());
        int port = uri.getPort();
        if (port >= 0) {
            sanitized.append(':').append(port);
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            sanitized.append(uri.getRawPath());
        }
        return sanitized.toString();
    }

    @FunctionalInterface
    private interface Fetcher {
        String fetch(String sourceUrl, RemoteFetchAuth auth) throws IOException, InterruptedException;
    }

    private static NavigableSet<String> blockedVariables() {
        TreeSet<String> blocked = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Collections.addAll(
                blocked,
                "PATH",
                "PATHEXT",
                "LD_PRELOAD",
                "LD_LIBRARY_PATH",
                "DYLD_LIBRARY_PATH",
                "DYLD_INSERT_LIBRARIES",
                "LIBPATH",
                "SHLIB_PATH");
        return Collections.unmodifiableNavigableSet(blocked);
    }
}
