dependencies {
    "api"(project(":formula"))
    "testImplementation"(project(":testkit"))
}

val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the spec tests against the golden corpus."
    group = "verification"
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("dev.afterdrop.spec.SpecTestsKt")
    workingDir = rootDir
}
tasks.named("check") { dependsOn(selfTest) }
