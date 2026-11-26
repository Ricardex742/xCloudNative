pluginManagement {
    repositories {
        google()
        mavenCentral {
            url = uri(providers.environmentVariable("XCLOUD_MAVEN_CENTRAL_URL")
                .getOrElse("https://repo.maven.apache.org/maven2"))
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral {
            url = uri(providers.environmentVariable("XCLOUD_MAVEN_CENTRAL_URL")
                .getOrElse("https://repo.maven.apache.org/maven2"))
        }
    }
}

rootProject.name = "xCloudNative"
include(":app")
include(":shortcut")
