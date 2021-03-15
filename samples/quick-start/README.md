# KotlinGitHubAppSample

This is a Kotlin implementation of [the official GitHubApp sample](https://github.com/github-developer/using-the-github-api-in-your-app.git) implemented in Ruby.

## Run the server

The explanation is based on the assumption that IntelliJ is used.

1. Open the source code for this app.
2. Set the environment variable to the following information issued when you register the GitHubApp.
   - GITHUB_WEBHOOK_SECRET
   - GITHUB_APP_IDENTIFIER
   - GITHUB_PRIVATE_KEY
     - This is the private key in RSA PEM format that was issued when you registered the GitHubApp.
     - Replace the line breaks to "\n".
3. Run this app from IntelliJ.

For more information on setting environment variables, please see [the official environment building page](https://docs.github.com/en/developers/apps/setting-up-your-development-environment-to-create-a-github-app#step-4-prepare-the-runtime-environment).

## Note

KotlinGitHubAppSample is powered by [Ktor](http://ktor.io) framework.

<img src="https://repository-images.githubusercontent.com/40136600/f3f5fd00-c59e-11e9-8284-cb297d193133" alt="Ktor" width="100" style="max-width:20%;">

Ktor Version: 1.5.2 Kotlin Version: 1.4.10
BuildSystem: [Gradle with Kotlin DSL](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
