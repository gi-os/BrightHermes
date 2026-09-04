pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // light-common lives in GitHub Packages, which has no anonymous read even for public
        // packages. Locally: gpr.user / gpr.key in local.properties (a PAT with read:packages).
        // In CI: GPR_USER / GPR_TOKEN secrets, falling back to the run's own token.
        maven {
            url = uri("https://maven.pkg.github.com/gi-os/light-common")
            credentials {
                username = System.getenv("GPR_USER")?.takeUnless(String::isBlank)
                    ?: System.getenv("GITHUB_ACTOR")?.takeUnless(String::isBlank)
                    ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GPR_TOKEN")?.takeUnless(String::isBlank)
                    ?: System.getenv("GITHUB_TOKEN")?.takeUnless(String::isBlank)
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

rootProject.name = "BrightHermes"
include(":app")
