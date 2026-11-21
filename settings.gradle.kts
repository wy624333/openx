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
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        // 本地内置依赖：Shizuku API/provider（离线构建用，见 app/libs/shizuku-repo）。
        maven { url = uri("app/libs/shizuku-repo") }
    }
}

rootProject.name = "AgentConsoleApp"
include(":app")
include(":quickjs")
