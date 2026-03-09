package io.jenkins.plugins.remoteenvfile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import jenkins.MasterToSlaveFileCallable;

final class RemoteEnvFileFetcher {

    static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    static final int READ_TIMEOUT_MILLIS = 15_000;
    static final int MAX_RESPONSE_BYTES = 128 * 1024;

    private RemoteEnvFileFetcher() {
    }

    static String fetch(String sourceUrl, RemoteFetchAuth auth) throws IOException {
        HttpURLConnection connection = openConnection(sourceUrl);
        try {
            auth.apply(connection);
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("remote server returned HTTP " + responseCode);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw new IOException("remote file exceeds the maximum allowed size of " + MAX_RESPONSE_BYTES + " bytes");
            }
            try (InputStream input = connection.getInputStream()) {
                return readResponse(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String sourceUrl) throws IOException {
        URL url = URI.create(sourceUrl).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "text/plain, text/x-envfile, */*");
        connection.setRequestProperty("User-Agent", "jenkins-remote-env-file");
        return connection;
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
        public String invoke(File workspace, hudson.remoting.VirtualChannel channel) throws IOException {
            return fetch(sourceUrl, auth);
        }
    }
}
