pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "spunto"
include(":app", ":core:model", ":core:design", ":core:plugins", ":data:local", ":data:sync", ":plugins:dictation", ":plugins:quick-capture", ":benchmark")
