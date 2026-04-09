def call(Map config) {

    pipeline {
        agent any

        stages {

            stage('Load Environment') {
                steps {
                    loadEnv(config.envSecret)
                }
            }

            stage('Checkout') {
                steps {
                    checkout scm
                }
            }
            
            stage('Print Branch Info') {
                steps {
                    script {
                        def branch = env.CHANGE_BRANCH ?: env.BRANCH_NAME
                        def target = env.CHANGE_TARGET ?: 'N/A'

                        echo "Branch: ${branch}"
                        echo "Target Branch: ${target}"
                    }
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

            //         stage('Unit Tests') {
            //             steps {
            //                 sh "mvn test"
            //             }
            //             post {
            //                 always {
            //                     junit 'target/surefire-reports/*.xml'
            //                 }
            //             }
            //         }

            //         stage('SonarQube') {
            //             steps {
            //                 sonarScan(config.sonarProjectKey)
            //             }
            //         }
            //     }
            // }

            // stage('Quality Gate') {
            //     steps {
            //         timeout(time: 2, unit: 'MINUTES') {
            //             waitForQualityGate abortPipeline: true
            //         }
            //     }
            // }
        }
    }
}
