package com.example.cman;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import static com.example.cman.Telemetry.env;
import static com.example.cman.Telemetry.firstLine;
import static com.example.cman.Telemetry.require;
import static com.example.cman.Telemetry.tag;

/**
 * Dumb JDBC client: plain connections through the CMAN-TDM endpoint — no UCP, no Application
 * Continuity. THREADS independent connections (default 1), each tick asking which RAC node served
 * the call and the round-trip time, shipping a sample tagged client=dumb to InfluxDB. Drain a node
 * while this runs to see what CMAN-TDM does for a client with no continuity logic of its own.
 */
public final class Workload {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    // SYS_CONTEXT needs no privileges, so the dumb appuser stays minimal. A query against dual does
    // no real DB work, so the measured time is essentially the CMAN round trip.
    static final String QUERY = """
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
        var threads = Integer.parseInt(env("THREADS", "1"));

        var jdbcUrl = "jdbc:oracle:thin:@//" + host + ":" + port + "/" + service;
        var props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", pass);
        // Bound the TCP connect, but NOT socket reads: a socket ReadTimeout also fires during the
        // (slow) TDM logon and aborts reconnects. The query is bounded per-statement instead, so a
        // dead session is detected without breaking reconnection.
        props.setProperty("oracle.net.CONNECT_TIMEOUT", "20000");
        DriverManager.setLoginTimeout(120);

        var telemetry = new Telemetry();
        System.out.println("Dumb client (" + threads + " thread(s)) -> " + jdbcUrl + " as " + user);
        System.out.println("Ctrl-C to stop.\n");

        for (int i = 1; i <= threads; i++) {
            int id = i;
            new Thread(() -> runWorker(id, jdbcUrl, props, intervalMs, telemetry), "dumb-" + id).start();
        }
    }

    private static void runWorker(int id, String jdbcUrl, Properties props, long intervalMs, Telemetry telemetry) {
        Connection conn = connect(id, jdbcUrl, props);
        if (conn == null) return;
        long downSince = 0; // epoch ms of the first failure in the current outage; 0 = healthy
        while (true) {
            long tsMs = Instant.now().toEpochMilli();
            long t0 = System.nanoTime();
            try {
                String inst, node;
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(15); // detect a dead session fast, above the ~10s first-query warmup
                    try (ResultSet rs = st.executeQuery(QUERY)) {
                        rs.next();
                        inst = rs.getString(1);
                        node = rs.getString(2);
                    }
                }
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                if (downSince != 0) {
                    long gapMs = tsMs - downSince;
                    System.out.printf("%s  [dumb-%d] recovered after %d ms on %s%n",
                            LocalTime.now().format(CLOCK), id, gapMs, inst);
                    telemetry.write("cman_workload,client=dumb,inst=" + tag(inst) + ",host=" + tag(node)
                            + ",status=ok recovery_ms=" + gapMs + " " + tsMs);
                    downSince = 0;
                }
                System.out.printf("%s  [dumb-%d] %-10s %7.1f ms%n", LocalTime.now().format(CLOCK), id, inst, ms);
                telemetry.write("cman_workload,client=dumb,inst=" + tag(inst) + ",host=" + tag(node)
                        + ",status=ok latency_ms=" + ms + " " + tsMs);
            } catch (SQLException e) {
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                if (downSince == 0) downSince = tsMs;
                System.out.printf("%s  [dumb-%d] ERROR ORA-%05d %s%n",
                        LocalTime.now().format(CLOCK), id, e.getErrorCode(), firstLine(e.getMessage()));
                telemetry.write(Telemetry.errorLine("dumb", ms, e, tsMs));
                conn = reconnectQuietly(jdbcUrl, props, conn);
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private static Connection connect(int id, String url, Properties props) {
        while (true) {
            long c0 = System.nanoTime();
            try {
                Connection conn = DriverManager.getConnection(url, props);
                System.out.printf("%s  [dumb-%d] connected in %.1fs%n",
                        LocalTime.now().format(CLOCK), id, (System.nanoTime() - c0) / 1e9);
                return conn;
            } catch (SQLException e) {
                System.out.printf("%s  [dumb-%d] connect failed (%.1fs): ORA-%05d %s — retrying%n",
                        LocalTime.now().format(CLOCK), id, (System.nanoTime() - c0) / 1e9, e.getErrorCode(), firstLine(e.getMessage()));
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return null;
                }
            }
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
}
