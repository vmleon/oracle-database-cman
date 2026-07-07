package com.example.cman;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.regex.Pattern;

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

    // Escape a line-protocol string field value (only " and \ need escaping inside the quotes).
    static String field(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Oracle/UCP messages start with "PREFIX-NNNNN:" (ORA-03113, UCP-29, TNS-12514). Use that as a
    // low-cardinality tag so errors group by kind; the top-level prefix is the borrow-vs-ORA answer
    // (UCP-* = the pool failed to hand out a connection; ORA-* = the DB/driver raised it).
    private static final Pattern CODE = Pattern.compile("^([A-Za-z]+-\\d+)");

    static String errSlug(int code, String topMessage) {
        if (topMessage != null) {
            var m = CODE.matcher(topMessage);
            if (m.find()) return m.group(1);
        }
        return "err-" + code;
    }

    // Walk to the deepest cause so err_msg carries the real reason, not just the UCP wrapper.
    static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String top = firstLine(t.getMessage());
        String root = firstLine(r.getMessage());
        return root.isEmpty() || root.equals(top) ? top : top + " <- " + root;
    }

    // One error point: err= tag (kind, for grouping) + err_code/err_msg fields (the full story for
    // the dashboard table). Keeps latency_ms and inst/host=none so the existing panels still read it.
    static String errorLine(String client, double ms, SQLException e, long tsMs) {
        String top = firstLine(e.getMessage());
        return "cman_workload,client=" + client + ",inst=none,host=none,status=error,err="
                + tag(errSlug(e.getErrorCode(), top))
                + " latency_ms=" + ms + ",err_code=" + e.getErrorCode() + "i,err_msg=\""
                + field(rootMessage(e)) + "\" " + tsMs;
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
