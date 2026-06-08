/**
 * buildPythonCIPipeline.groovy
 * CI pipeline for Python-based microservices.
 *
 * Usage in Jenkinsfile.ci:
 *
 *   @Library('shared-lib') _
 *
 *   buildPythonCIPipeline(
 *       serviceName:       'ums-service',
 *       requirementsPath:  'Backend/requirements.txt',
 *       qualityScriptPath: 'scripts/quality_check.sh',
 *       envSecret:         'dev-ums',
 *       envOutputPath:     'Backend/.env',
 *       webhookUrl:        env.TEAMS_WEBHOOK_URL,
 *       awsRegion:         'ap-south-1'
 *   )
 */
def call(Map config) {

    def requirementsPath = config.requirementsPath ?: 'Backend/requirements.txt'
    def qualityScript    = config.qualityScriptPath ?: 'scripts/quality_check.sh'
    def envOutputPath    = config.envOutputPath    ?: 'Backend/.env'
    def awsRegion        = config.awsRegion        ?: 'ap-south-1'
    def serviceName      = config.serviceName      ?: 'python-service'
    def webhookUrl       = config.webhookUrl       ?: ''
    def venvDir          = '.ci-venv'

    pipeline {
        agent { label 'worker' }

        stages {

            stage('Checkout') {
                steps {
                    script {
                        checkout scm
                        env.GIT_BRANCH   = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'unknown'
                        env.TRIGGERED_BY = sh(
                            script: "git log -1 --pretty=format:'%an'",
                            returnStdout: true
                        ).trim()
                        echo "Branch: ${env.GIT_BRANCH} | Author: ${env.TRIGGERED_BY}"
                    }
                }
            }

            stage('Notify Teams — Started') {
                when { expression { return webhookUrl?.trim() } }
                steps {
                    script {
                        notifyTeams([
                            status:      'STARTED',
                            serviceName: serviceName,
                            branch:      env.GIT_BRANCH,
                            triggeredBy: env.TRIGGERED_BY,
                            imageTag:    "PR-CI #${env.BUILD_NUMBER}",
                            webhookUrl:  webhookUrl
                        ])
                    }
                }
            }

            stage('Python Setup') {
                steps {
                    sh '''
                        if ! command -v python3 &>/dev/null; then
                            echo "Python3 not found — installing..."
                            sudo apt-get update -q
                            sudo apt-get install -y python3 python3-venv python3-pip
                        fi
                        python3 --version
                        pip3 --version
                    '''
                }
            }

            stage('Install Dependencies') {
                steps {
                    sh """
                        python3 -m venv ${venvDir}
                        . ${venvDir}/bin/activate
                        pip install --upgrade pip --quiet
                        pip install -r ${requirementsPath}
                    """
                }
            }

            stage('Load Environment') {
                when { expression { return config.envSecret?.trim() } }
                steps {
                    sh """
                        aws secretsmanager get-secret-value \\
                            --secret-id ${config.envSecret} \\
                            --region ${awsRegion} \\
                            --query SecretString \\
                            --output text | jq -r 'to_entries|map("\\(.key)=\\(.value)")|.[]' > ${envOutputPath}
                    """
                    echo "Converted AWS secret '${config.envSecret}' → ${envOutputPath}"
                }
            }

            stage('Quality & Tests') {
                steps {
                    sh """
                        . ${venvDir}/bin/activate
                        chmod +x ${qualityScript}
                        ./${qualityScript}
                    """
                }
            }

        } // end stages

        post {
            success {
                script {
                    echo "CI passed"
                    if (webhookUrl?.trim()) {
                        notifyTeams([
                            status:      'SUCCESS',
                            serviceName: serviceName,
                            branch:      env.GIT_BRANCH ?: 'unknown',
                            triggeredBy: env.TRIGGERED_BY ?: 'unknown',
                            imageTag:    "PR-CI #${env.BUILD_NUMBER}",
                            webhookUrl:  webhookUrl
                        ])
                    }
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
                    if (webhookUrl?.trim()) {
                        notifyTeams([
                            status:      'FAILURE',
                            serviceName: serviceName,
                            branch:      env.GIT_BRANCH ?: 'unknown',
                            triggeredBy: env.TRIGGERED_BY ?: 'unknown',
                            imageTag:    "PR-CI #${env.BUILD_NUMBER}",
                            webhookUrl:  webhookUrl
                        ])
                    }
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
    }
}
