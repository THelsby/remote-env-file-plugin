package io.jenkins.plugins.remoteenvfile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import jenkins.MasterToSlaveFileCallable;

final class RemoteEnvFileFetcher {

    static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    static final int READ_TIMEOUT_MILLIS = 15_000;
    static final int MAX_RESPONSE_BYTES = 128 * 1024;

    private RemoteEnvFileFetcher() {
    }

    static String fetch(String sourceUrl, RemoteFetchAuth auth) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS))
                .build();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(sourceUrl))
                .GET()
                .timeout(Duration.ofMillis(READ_TIMEOUT_MILLIS))
                .header("Accept", "text/plain, text/x-envfile, */*")
                .header("User-Agent", "jenkins-remote-env-file");
        auth.apply(requestBuilder);

        HttpResponse<InputStream> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int responseCode = response.statusCode();
        if (responseCode < 200 || responseCode >= 300) {
            try (InputStream input = response.body()) {
                // Close the body stream before surfacing the HTTP status error.
            }
            throw new IOException("remote server returned HTTP " + responseCode);
        }

        try (InputStream input = response.body()) {
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw new IOException("remote file exceeds the maximum allowed size of " + MAX_RESPONSE_BYTES + " bytes");
            }
            return readResponse(input);
        }
    }

    private static String readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int totalBytes = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            totalBytes += read;
            if (totalBytes > MAX_RESPONSE_BYTES) {
                throw new IOException("remote file exceeds the maximum allowed size of " + MAX_RESPONSE_BYTES + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    static final class AgentCallable extends MasterToSlaveFileCallable<String> {

        private static final long serialVersionUID = 1L;

        private final String sourceUrl;
        private final RemoteFetchAuth auth;

        AgentCallable(String sourceUrl, RemoteFetchAuth auth) {
            this.sourceUrl = sourceUrl;
            this.auth = auth;
        }

        @Override
        public String invoke(File workspace, hudson.remoting.VirtualChannel channel) throws IOException, InterruptedException {
            return fetch(sourceUrl, auth);
        }
    }
}
