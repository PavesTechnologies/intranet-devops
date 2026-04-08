def call(Map config) {

    pipeline {
        agent any

        stages {

            stage('Load Environment') {
                steps {
                    loadEnv(config.envCredential)
                }
            }

            stage('Checkout') {
                steps {
                    script {
                        def branchToBuild = env.CHANGE_BRANCH ?: env.BRANCH_NAME
            
                        git branch: branchToBuild,
                            url: config.repoUrl
            
                        echo "Checking out branch: ${branchToBuild}"
                    }
                }
            }

            stage('Compile') {
                steps {
                    sh "mvn clean compile -DskipTests"
                }
            }

            stage('Parallel Checks') {
                parallel {

                    stage('Unit Tests') {
                        steps {
                            sh "mvn test"
                        }
                        post {
                            always {
                                junit 'target/surefire-reports/*.xml'
                            }
                        }
                    }

                    stage('SonarQube') {
                        steps {
                            sonarScan(config.sonarProjectKey)
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    timeout(time: 2, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }
        }
    }
}
