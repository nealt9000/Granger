kotlin {
    sourceSets.getByName("commonMain").dependencies { implementation("com.fleeksoft.ksoup:ksoup:0.2.6") }
    sourceSets.getByName("jvmTest").dependencies { implementation("org.jsoup:jsoup:1.21.2") }
}
