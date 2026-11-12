dependencies {
    "api"(project(":formula"))
    "api"(project(":spec"))
    "testImplementation"(project(":testkit"))
}

val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the data source tests against recorded answers."
    group = "verification"
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("dev.afterdrop.data.DataTestsKt")
    workingDir = rootDir
}
tasks.named("check") { dependsOn(selfTest) }
