dependencies {
    "api"(project(":composer"))
    "testImplementation"(project(":testkit"))
}

val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the generator and Gemini client tests against a scripted API."
    group = "verification"
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("dev.afterdrop.ai.AiTestsKt")
    workingDir = rootDir
}
tasks.named("check") { dependsOn(selfTest) }
