package com.example.cman;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** InfluxDB line-protocol writer plus the small env/tag helpers both clients share. */
final class Telemetry {

    private final HttpClient http = HttpClient.newHttpClient();
    private final URI uri;
    private final String token;

    Telemetry() {
        this.uri = URI.create(env("INFLUX_URL", "http://localhost:8086")
                + "/api/v2/write?org=" + env("INFLUX_ORG", "cman")
                + "&bucket=" + env("INFLUX_BUCKET", "workload") + "&precision=ms");
        this.token = env("INFLUX_TOKEN", "cman-poc-token");
    }

    void write(String line) {
        var req = HttpRequest.newBuilder(uri)
                .header("Authorization", "Token " + token)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(line))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> { System.err.println("influx write failed: " + ex.getMessage()); return null; });
    }

    static String tag(String v) {
        return v == null ? "unknown" : v.replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    static String env(String key, String def) {
        var v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    static String require(String key) {
        var v = System.getenv(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing env var: " + key);
        return v;
    }
}
