def call(String sonarProjectKey) {
    withSonarQubeEnv('SonarQube') {
        sh """
            mvn sonar:sonar \
            -Dsonar.projectKey=${sonarProjectKey} \
            -Dsonar.exclusions=**/target/**,**/test/**,**/resources/** \
            -Dmaven.test.skip=true
        """
    }
}
