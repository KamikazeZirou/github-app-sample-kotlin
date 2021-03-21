# Authorize Users Sample

This sample github app that perform creating an issue on behalf of a user.

## Usage
### 1. Create GitHub App
1. [Open profile's settings.](https://github.com/settings/profile)
1. Open "Developer Settings".
1. Open "New GitHub App".
1. Create GitHub App.
   - Enter "GitHub App Name".
   - Deactivate Webhook.
   - Set "Repository permissions - Issues" to "Read & write".
1. Write down the value of "Client ID".
1. Generate a new client secret and write down the value of it.

### 2. Install GitHub App to Your Repositories
1. Open "Settings" -> "Developer Settings" -> "GitHub Apps" -> "Created App".
1. Open "Install App" and click "install" button.
1. Choose "All repositories" or "Only select repositories" and install app.

### 3. Run the server

The explanation is based on the assumption that IntelliJ is used.

1. Open this directory as project from IntelliJ
2. Set the environment variable to the following information issued when you register the GitHubApp.
   - CLIENT_ID
   - CLIENT_SECRET
3. Run this app from IntelliJ.
4. Open "http://0.0.0.0:8080/" in web browser.

## Note

KotlinGitHubAppSample is powered by [Ktor](http://ktor.io) framework.

<img src="https://repository-images.githubusercontent.com/40136600/f3f5fd00-c59e-11e9-8284-cb297d193133" alt="Ktor" width="100" style="max-width:20%;">

Ktor Version: 1.5.2 Kotlin Version: 1.4.10
BuildSystem: [Gradle with Kotlin DSL](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
