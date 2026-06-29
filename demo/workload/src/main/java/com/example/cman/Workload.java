package com.example.cman;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Dumb JDBC client: one plain connection through the CMAN-TDM endpoint — no UCP, no
 * Application Continuity. Each tick it asks the database which RAC node served the call
 * and how long the round trip took, prints it, and ships a sample to InfluxDB. Drain a
 * node while this runs to see what CMAN-TDM does for a client that has no continuity
 * logic of its own.
 */
public final class Workload {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    // SYS_CONTEXT needs no privileges, so the dumb appuser stays minimal. A query against
    // dual does no real DB work, so the measured time is essentially the CMAN round trip.
    private static final String QUERY = """
        select sys_context('USERENV','INSTANCE_NAME'),
               sys_context('USERENV','SERVER_HOST')
        from dual""";

    public static void main(String[] args) throws Exception {
        var host = require("CMAN_HOST");
        var port = env("CMAN_PORT", "1521");
        var service = require("DB_SERVICE");
        var user = env("DB_USER", "appuser");
        var pass = require("APPUSER_PASSWORD");
        var intervalMs = Long.parseLong(env("INTERVAL_MS", "1000"));

        var jdbcUrl = "jdbc:oracle:thin:@//" + host + ":" + port + "/" + service;
        var props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", pass);
        // Bound the legs so a blackholed path fails instead of hanging forever; the TDM proxy
        // handshake is slow, so the overall login budget is generous.
        props.setProperty("oracle.net.CONNECT_TIMEOUT", "20000");
        props.setProperty("oracle.jdbc.ReadTimeout", "30000");
        DriverManager.setLoginTimeout(120);

        var writeUri = URI.create(env("INFLUX_URL", "http://localhost:8086")
                + "/api/v2/write?org=" + env("INFLUX_ORG", "cman")
                + "&bucket=" + env("INFLUX_BUCKET", "workload") + "&precision=ms");
        var token = env("INFLUX_TOKEN", "cman-poc-token");
        var http = HttpClient.newHttpClient();

        System.out.println("Dumb client -> " + jdbcUrl + " as " + user);
        System.out.println("Metrics     -> " + writeUri);
        System.out.println("Ctrl-C to stop.\n");

        System.out.println(LocalTime.now().format(CLOCK) + "  connecting (CMAN-TDM handshake can take ~60s)...");
        long c0 = System.nanoTime();
        Connection conn = DriverManager.getConnection(jdbcUrl, props);
        System.out.printf("%s  connected in %.0fs%n%n", LocalTime.now().format(CLOCK), (System.nanoTime() - c0) / 1e9);
        long downSince = 0; // epoch ms of the first failure in the current outage; 0 = healthy

        while (true) {
            long tsMs = Instant.now().toEpochMilli();
            long t0 = System.nanoTime();
            try {
                String inst, node;
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(QUERY)) {
                    rs.next();
                    inst = rs.getString(1);
                    node = rs.getString(2);
                }
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                if (downSince != 0) {
                    System.out.printf("%s  recovered after %d ms on %s%n",
                            LocalTime.now().format(CLOCK), tsMs - downSince, inst);
                    downSince = 0;
                }
                System.out.printf("%s  %-10s %7.1f ms%n", LocalTime.now().format(CLOCK), inst, ms);
                write(http, writeUri, token, "cman_workload,inst=" + tag(inst) + ",host=" + tag(node)
                        + ",status=ok latency_ms=" + ms + " " + tsMs);
            } catch (SQLException e) {
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                if (downSince == 0) downSince = tsMs;
                System.out.printf("%s  ERROR ORA-%05d %s%n",
                        LocalTime.now().format(CLOCK), e.getErrorCode(), firstLine(e.getMessage()));
                write(http, writeUri, token, "cman_workload,inst=none,host=none,status=error latency_ms="
                        + ms + ",err_code=" + e.getErrorCode() + "i " + tsMs);
                conn = reconnectQuietly(jdbcUrl, props, conn);
            }
            System.out.flush();
            Thread.sleep(intervalMs);
        }
    }

    private static Connection reconnectQuietly(String url, Properties props, Connection old) {
        try { if (old != null) old.close(); } catch (SQLException ignore) { }
        try {
            return DriverManager.getConnection(url, props);
        } catch (SQLException e) {
            return old; // still down; the closed connection makes the next tick throw and retry
        }
    }

    private static void write(HttpClient http, URI uri, String token, String line) {
        var req = HttpRequest.newBuilder(uri)
                .header("Authorization", "Token " + token)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(line))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> { System.err.println("influx write failed: " + ex.getMessage()); return null; });
    }

    private static String tag(String v) {
        return v == null ? "unknown" : v.replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String env(String key, String def) {
        var v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String require(String key) {
        var v = System.getenv(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing env var: " + key);
        return v;
    }
}
