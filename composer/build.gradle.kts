dependencies {
    "api"(project(":formula"))
    "api"(project(":spec"))
    "testImplementation"(project(":testkit"))
}

val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the composer and describe-rule tests."
    group = "verification"
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("dev.afterdrop.composer.ComposerTestsKt")
    workingDir = rootDir
}
tasks.named("check") { dependsOn(selfTest) }
