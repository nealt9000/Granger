kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
            export(project(":vectors"))
            export(project(":text-research"))
        }
    }
    sourceSets.getByName("commonMain").dependencies {
        api(project(":vectors"))
        api(project(":text-research"))
    }
}
