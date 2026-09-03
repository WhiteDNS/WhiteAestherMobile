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
        // Psiphon's own binary distribution. It is a maven layout served out of
        // a git repository rather than a repository manager, which is how
        // Psiphon publish it -- see MobileLibrary/Android in psiphon-tunnel-core.
        //
        // Scoped to their group alone. A repository this far outside the usual
        // supply chain has no business being asked for anything else, and an
        // unscoped one would be consulted for every dependency we resolve.
        maven {
            name = "psiphon"
            url = uri("https://raw.githubusercontent.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master")
            content { includeGroup("ca.psiphon") }
        }
    }
}

rootProject.name = "WhiteAestherMobile"
include(":app")
