def call(String sonarProjectKey) {
    withSonarQubeEnv('SonarQube') {
        sh "mvn sonar:sonar -Dsonar.projectKey=${sonarProjectKey}"
    }
}
