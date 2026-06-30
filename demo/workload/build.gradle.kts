plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Oracle JDBC thin driver (used directly by the dumb client).
    implementation("com.oracle.database.jdbc:ojdbc11:23.6.0.24.10")
    // UCP + FAN libraries for the smart client (pool, Application Continuity replay, FAN/FCF).
    implementation("com.oracle.database.jdbc:ucp11:23.6.0.24.10")
    implementation("com.oracle.database.ha:ons:23.6.0.24.10")
    implementation("com.oracle.database.ha:simplefan:23.6.0.24.10")
}

application {
    // Launcher dispatches to the dumb or smart client based on the CLIENT env var.
    mainClass = "com.example.cman.Launcher"
}
