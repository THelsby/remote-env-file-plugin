package io.jenkins.plugins.remoteenvfile;

import java.io.Serializable;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class RemoteFetchAuth implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final String first;
    private final String second;

    private RemoteFetchAuth(Kind kind, String first, String second) {
        this.kind = kind;
        this.first = first;
        this.second = second;
    }

    static RemoteFetchAuth none() {
        return new RemoteFetchAuth(Kind.NONE, null, null);
    }

    static RemoteFetchAuth bearer(String token) {
        return new RemoteFetchAuth(Kind.BEARER, token, null);
    }

    static RemoteFetchAuth basic(String username, String password) {
        return new RemoteFetchAuth(Kind.BASIC, username, password);
    }

    void apply(HttpRequest.Builder requestBuilder) {
        switch (kind) {
        case NONE:
            return;
        case BEARER:
            requestBuilder.header("Authorization", "Bearer " + first);
            return;
        case BASIC:
            String joined = first + ":" + second;
            String encoded = Base64.getEncoder().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
            requestBuilder.header("Authorization", "Basic " + encoded);
            return;
        default:
            throw new IllegalStateException("Unsupported auth type: " + kind);
        }
    }

    private enum Kind {
        NONE,
        BEARER,
        BASIC
    }
}
