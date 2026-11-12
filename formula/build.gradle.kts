dependencies {
    "testImplementation"(project(":testkit"))
}

val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the formula tests."
    group = "verification"
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("dev.afterdrop.formula.FormulaTestsKt")
    workingDir = rootDir
}
tasks.named("check") { dependsOn(selfTest) }
