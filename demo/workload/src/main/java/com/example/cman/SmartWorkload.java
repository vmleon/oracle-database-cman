package com.example.cman;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

import static com.example.cman.Telemetry.env;
import static com.example.cman.Telemetry.firstLine;
import static com.example.cman.Telemetry.require;
import static com.example.cman.Telemetry.tag;

/**
 * Smart JDBC client: a UCP pool of THREADS connections (default 8) through the same CMAN-TDM
 * endpoint, with Application Continuity replay (the AC-aware connection factory) and Fast
 * Connection Failover enabled so it reacts to FAN. Each worker borrows a pooled connection per
 * tick, runs the same SYS_CONTEXT query, returns it, and ships a sample tagged client=smart.
 * Against the dumb client this shows the pool draining gracefully and rebalancing across nodes.
 */
public final class SmartWorkload {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Retire a pooled connection once it has lived this long, so the pool re-spreads onto a
    // restored node: UCP only rebalances lazily on FAN up events, so without a ceiling the whole
    // pool can stay pinned to one node after a drain+restore. Gentle default; lower it (30-60s)
    // for faster re-spread at the cost of more reconnects. 0 disables. See REFERENCE.md "Tuning".
    private static final long MAX_CONN_REUSE_SECONDS = 180;

    public static void main(String[] args) throws Exception {
        if (Boolean.parseBoolean(env("FAN_DEBUG", "false"))) enableFanLogging();
        var host = require("CMAN_HOST");
        var port = env("CMAN_PORT", "1521");
        var service = require("DB_SERVICE");
        var user = env("DB_USER", "appuser");
        var pass = require("APPUSER_PASSWORD");
        var intervalMs = Long.parseLong(env("INTERVAL_MS", "1000"));
        var threads = Integer.parseInt(env("THREADS", "8"));
        // SERVER=POOLED routes this pooling-aware client to CMAN's PRCP pool of warm backend gateway
        // sessions, so a drained/restored node is never cold. PRCP requires every client on the
        // service to be POOLED (the dumb client requests it too), but only this one adds UCP+AC+FAN.
        var jdbcUrl = "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=" + host
                + ")(PORT=" + port + "))(CONNECT_DATA=(SERVICE_NAME=" + service + ")(SERVER=POOLED)))";

        PoolDataSource pds = buildPool(jdbcUrl, user, pass, threads, true);
        try {
            pds.getConnection().close(); // probe: forces pool init, surfaces an unreachable-FAN failure
        } catch (SQLException e) {
            System.out.println("FAN/FCF unavailable (" + firstLine(e.getMessage())
                    + "); continuing with Application Continuity only");
            pds = buildPool(jdbcUrl, user, pass, threads, false);
        }

        var telemetry = new Telemetry();
        System.out.println("Smart client (UCP+AC, " + threads + " thread(s)) -> " + jdbcUrl + " as " + user);
        System.out.println("Ctrl-C to stop.\n");

        final PoolDataSource pool = pds;
        for (int i = 1; i <= threads; i++) {
            int id = i;
            new Thread(() -> runWorker(id, pool, intervalMs, telemetry), "smart-" + id).start();
        }
    }

    // FAN_DEBUG=true streams the FAN/ONS event stack (oracle.simplefan) and pool activity
    // (oracle.ucp) to stdout. During a drain this is the decisive test: if these loggers stay
    // silent while a node drains, the pool never received the FAN event — CMAN-TDM isn't relaying
    // it to the client, which is why connections are discovered dead lazily (UCP-29 on borrow)
    // instead of draining ahead of the stop. See REFERENCE.md "FAN".
    private static void enableFanLogging() {
        var handler = new java.util.logging.ConsoleHandler();
        handler.setLevel(java.util.logging.Level.ALL);
        for (String name : new String[]{"oracle.simplefan", "oracle.ucp"}) {
            var log = java.util.logging.Logger.getLogger(name);
            log.setLevel(java.util.logging.Level.ALL);
            log.addHandler(handler);
            log.setUseParentHandlers(false);
        }
        System.out.println("FAN_DEBUG on: streaming oracle.simplefan + oracle.ucp at ALL");
    }

    private static PoolDataSource buildPool(String url, String user, String pass, int threads, boolean fcf) {
        try {
            PoolDataSource pds = PoolDataSourceFactory.getPoolDataSource();
            // AC replay: the replay-aware factory makes in-flight work transparently replay on failover.
            pds.setConnectionFactoryClassName("oracle.jdbc.replay.OracleDataSourceImpl");
            pds.setURL(url);
            pds.setUser(user);
            pds.setPassword(pass);
            // min below max (half the worker count) so a drain that kills the connections on one
            // node only forces the pool to rebuild down to min, not the full set — fewer cold
            // gateway builds on the survivor, so less chance of a borrow (UCP-29) storm while it warms.
            int min = Math.max(1, threads / 2);
            pds.setInitialPoolSize(min);
            pds.setMinPoolSize(min);
            pds.setMaxPoolSize(threads);
            // Wait through a cold-pool warmup instead of failing the borrow: if the PRCP pool is
            // still filling warm sessions right after a drain, a borrow waits up to this long rather
            // than throwing UCP-29. Turns a residual storm into brief elevated latency, not errors.
            pds.setConnectionWaitDuration(java.time.Duration.ofSeconds(30));
            pds.setConnectionProperty("oracle.net.CONNECT_TIMEOUT", "20000");
            // FAN/Fast Connection Failover: react to drain/up events (in-band via CMAN-TDM).
            pds.setFastConnectionFailoverEnabled(fcf);
            // Retire aged connections so the pool re-spreads onto a restored node (paired with the
            // service's RLB goal, which tells the pool where the load is).
            pds.setMaxConnectionReuseTime(MAX_CONN_REUSE_SECONDS);
            return pds;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to build UCP pool", e);
        }
    }

    private static void runWorker(int id, PoolDataSource pds, long intervalMs, Telemetry telemetry) {
        long downSince = 0; // epoch ms of the first failure in the current outage; 0 = healthy
        while (true) {
            long tsMs = Instant.now().toEpochMilli();
            long t0 = System.nanoTime();
            try (Connection conn = pds.getConnection();
                 Statement st = conn.createStatement()) {
                st.setQueryTimeout(30); // AC replay can take a beat; allow more than the dumb client
                String inst, node;
                try (ResultSet rs = st.executeQuery(Workload.QUERY)) {
                    rs.next();
                    inst = rs.getString(1);
                    node = rs.getString(2);
                }
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                if (downSince != 0) {
                    long gapMs = tsMs - downSince;
                    System.out.printf("%s  [smart-%d] recovered after %d ms on %s%n",
                            LocalTime.now().format(CLOCK), id, gapMs, inst);
                    telemetry.write("cman_workload,client=smart,inst=" + tag(inst) + ",host=" + tag(node)
                            + ",status=ok recovery_ms=" + gapMs + " " + tsMs);
                    downSince = 0;
                }
                System.out.printf("%s  [smart-%d] %-10s %7.1f ms%n", LocalTime.now().format(CLOCK), id, inst, ms);
                telemetry.write("cman_workload,client=smart,inst=" + tag(inst) + ",host=" + tag(node)
                        + ",status=ok latency_ms=" + ms + " " + tsMs);
            } catch (SQLException e) {
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                if (downSince == 0) downSince = tsMs;
                System.out.printf("%s  [smart-%d] ERROR ORA-%05d %s%n",
                        LocalTime.now().format(CLOCK), id, e.getErrorCode(), firstLine(e.getMessage()));
                telemetry.write(Telemetry.errorLine("smart", ms, e, tsMs));
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }
}
