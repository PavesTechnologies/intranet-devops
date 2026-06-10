def call(Map config) {
    pipeline {
        agent { label 'worker' }
        stages {
            stage('Initialization') {
                steps {
                    script { initStages(config) }
                }
            }
            stage('Setup Java') {
                steps {
                    script {
                        def selectedJdk = config.jdk ?: 'jdk17'
                        echo "Using JDK: ${selectedJdk}"
                        env.JAVA_HOME = tool selectedJdk
                        env.PATH = "${env.JAVA_HOME}/bin:${env.PATH}"
                        sh 'java -version'
                    }
                }
            }
            stage('Compile') {
                steps {
                    sh "mvn clean compile -DskipTests"
                }
            }
            // stage('Parallel Checks') {
            //     parallel {
                    //  stage('Unit Tests') {
                    //     steps { sh "mvn test" }
                    //     post {
                    //         always { junit 'target/surefire-reports/*.xml' }
                    //     }
                    // }
            
                    // stage('SonarQube') {
                    //     steps { sonarScan(config.sonarProjectKey) }
                    // }
                // }
            // // }
            // stage('Quality Gate') {
            //     steps {
            //         script { qualityGateStage() }
            //     }
            // }
        } // End stages
        post {
            success {
                script {
                    echo "CI passed"
                    setGitHubPullRequestStatus(
                        context: 'ci/jenkins',
                        message: 'All quality gates passed',
                        state:   'SUCCESS'
                    )
                }
            }
            failure {
                script {
                    echo "CI failed"
                    setGitHubPullRequestStatus(
                        context: 'ci/jenkins',
                        message: 'CI failed — check build logs',
                        state:   'FAILURE'
                    )
                }
            }
            aborted {
                script {
                    echo "CI aborted"
                    setGitHubPullRequestStatus(
                        context: 'ci/jenkins',
                        message: 'CI aborted',
                        state:   'ERROR'
                    )
                }
            }
            always {
                cleanWs()
            }
        }
    } // End pipeline
} // End call
