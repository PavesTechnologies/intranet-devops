def call(String credentialId) {
    withCredentials([file(credentialsId: credentialId, variable: 'ENV_FILE')]) {
        sh '''
        cat $ENV_FILE > .env
        '''
    }
}
