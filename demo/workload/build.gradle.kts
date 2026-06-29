plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Oracle JDBC thin driver. No UCP, no Application Continuity — a deliberately dumb client.
    implementation("com.oracle.database.jdbc:ojdbc11:23.6.0.24.10")
}

application {
    mainClass = "com.example.cman.Workload"
}
