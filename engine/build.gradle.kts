dependencies {
    "api"(project(":formula"))
    "api"(project(":spec"))
    "testImplementation"(project(":testkit"))
}

val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the engine tests against the golden corpus."
    group = "verification"
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("dev.afterdrop.engine.EngineTestsKt")
    workingDir = rootDir
}
tasks.named("check") { dependsOn(selfTest) }
