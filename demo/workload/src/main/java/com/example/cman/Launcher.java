package com.example.cman;

/** Entry point: runs the dumb client (default) or the smart UCP+AC client, picked by CLIENT. */
public final class Launcher {
    public static void main(String[] args) throws Exception {
        if ("smart".equalsIgnoreCase(Telemetry.env("CLIENT", "dumb"))) {
            SmartWorkload.main(args);
        } else {
            Workload.main(args);
        }
    }
}
