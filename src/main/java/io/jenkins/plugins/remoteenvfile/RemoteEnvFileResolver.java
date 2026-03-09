package io.jenkins.plugins.remoteenvfile;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

final class RemoteEnvFileResolver {

    private RemoteEnvFileResolver() {
    }

    static Map<String, String> loadOnAgent(
            Run<?, ?> build,
            EnvVars environment,
            String sourceUrl,
            String credentialsId,
            FilePath workspace,
            TaskListener listener) throws IOException, InterruptedException {
        return load(build, environment, sourceUrl, credentialsId, listener, (expandedUrl, auth) ->
                workspace.act(new RemoteEnvFileFetcher.AgentCallable(expandedUrl, auth)));
    }

    static Map<String, String> loadOnController(
            Run<?, ?> build,
            EnvVars environment,
            String sourceUrl,
            String credentialsId,
            TaskListener listener) throws IOException, InterruptedException {
        return load(build, environment, sourceUrl, credentialsId, listener, RemoteEnvFileFetcher::fetch);
    }

    private static Map<String, String> load(
            Run<?, ?> build,
            EnvVars environment,
            String sourceUrl,
            String credentialsId,
            TaskListener listener,
            Fetcher fetcher) throws IOException, InterruptedException {
        String expandedUrl = environment.expand(requireSourceUrl(sourceUrl));
        URI validatedUri = validateSourceUrl(expandedUrl);
        RemoteFetchAuth auth = resolveAuthentication(build, environment, credentialsId);
        String logLocation = sanitizeForLogs(validatedUri);

        listener.getLogger().printf("Loading remote environment from %s%n", logLocation);

        String dotenvContent;
        try {
            dotenvContent = fetcher.fetch(validatedUri.toString(), auth);
        } catch (IOException exception) {
            throw new AbortException("Failed to fetch remote environment file from " + logLocation + ": " + exception.getMessage());
        }

        Map<String, String> parsed = DotenvParser.parse(dotenvContent);
        validateNoCollisions(environment, parsed);
        listener.getLogger().printf("Loaded %d environment variable(s) from %s%n", parsed.size(), logLocation);
        return parsed;
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

    private static void validateNoCollisions(EnvVars environment, Map<String, String> parsed) throws AbortException {
        for (String key : parsed.keySet()) {
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
}
