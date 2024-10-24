class AppInfoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("appInfo") {
            doLast {
                val userSourceFiles = countUserSourceFiles(project)
                val allSourceFiles = countAllSourceFiles(project)
                val classFiles = countClassFiles(project)
                val latestCommit = getLatestCommit(project)

                println("Number of user source files: $userSourceFiles")
                println("Number of all source files: $allSourceFiles")
                println("Number of class files: $classFiles")
                println("Latest commit: ${latestCommit}")
            }
        }
    }

    private fun countUserSourceFiles(project: Project): Int {
        return project.subprojects.sumOf { subProject ->
            subProject.projectDir.walk().filter { dir -> dir.isDirectory && dir.name == "src" }
                .sumOf { dir -> dir.walk().count { it.isFile && it.extension == "kt" } }
        }
    }

    private fun countAllSourceFiles(project: Project): Int {
        return project.rootDir.walk().count { it.isFile && it.extension in listOf("kt", "java") }
    }

    private fun countClassFiles(project: Project): Int {
         return project.subprojects.sumOf { subProject ->
             subProject.projectDir.walk().filter { dir -> dir.isDirectory && dir.name == "build" }
                 .sumOf { dir -> dir.walk().count { it.isFile && it.extension == "class" } }
         }
    }

    // Get latest commit information
    private fun getLatestCommit(project: Project): String {
        val processBuilder = ProcessBuilder("git", "log", "-1", "--pretty=format:%h by %an")
        processBuilder.directory(project.projectDir)
        val process = processBuilder.start()
        val exitCode = process.waitFor()

        return process.inputStream.bufferedReader().readText().trim()
    }
}

apply<AppInfoPlugin>()
