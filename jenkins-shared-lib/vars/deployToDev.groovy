def call(Map config) {

  // Block CD from running on PR builds — CHANGE_ID is set by Jenkins for any PR trigger
  if (env.CHANGE_ID) {
    echo "CD skipped: PR #${env.CHANGE_ID} → '${env.CHANGE_TARGET}'. CD only runs on direct pushes to the branch."
    currentBuild.result = 'ABORTED'
    return
  }

  def serviceName  = config.serviceName
  def serviceType  = config.serviceType  ?: 'java'
  def ecrRepo      = config.ecrRepo
  def ecrRegistry  = '743737183908.dkr.ecr.ap-south-1.amazonaws.com'
  def region       = 'ap-south-1'
  def branch       = config.branch       ?: 'dev'
  def ec2Host      = '13.207.112.154'
  def devopsRepo   = 'PavesTechnologies/intranet-devops'
  def dockerfile   = 'arm.Dockerfile'

  pipeline {
    // agent { label 'worker' }
    agent any
    stages {

      stage('Checkout') {
        steps {
          checkout scm
          script {
            env.SHORT_SHA  = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            env.COMMITTER  = sh(script: 'git log -1 --pretty=format:"%an"', returnStdout: true).trim()
            env.IMAGE_TAG  = "${branch}-${env.SHORT_SHA}"
            env.FULL_IMAGE = "${ecrRegistry}/${ecrRepo}:${env.IMAGE_TAG}"
            echo "Image: ${env.FULL_IMAGE}"
            echo "Triggered by: ${env.COMMITTER}"
            sh """
              if [ ! -f "${dockerfile}" ]; then
                echo "ERROR: ${dockerfile} not found"
                exit 1
              fi
              echo "${dockerfile} found"
            """
          }
        }
      }
      
      stage('Notify Started') {
        steps {
          withCredentials([
            string(
              credentialsId: 'teams-webhook-url',
              variable:      'TEAMS_URL'
            )
          ]) {
            script {
              notifyTeams(
                status:      'STARTED',
                serviceName: serviceName,
                imageTag:    'building...',
                branch:      branch,
                triggeredBy: env.COMMITTER ?: 'Unknown',
                webhookUrl:  env.TEAMS_URL
              )
            }
          }
        }
      }
      stage('Build Image') {
        steps {
          script {
            sh """
              docker buildx inspect paves-builder > /dev/null 2>&1 || \
                docker buildx create --name paves-builder --use
              docker buildx use paves-builder
              docker buildx build \
                --platform linux/arm64 \
                --load \
                --file ${dockerfile} \
                --tag ${env.FULL_IMAGE} \
                .
              echo "Image built: ${env.FULL_IMAGE}"
            """
          }
        }
      }

      stage('Push to ECR') {
        steps {
          withCredentials([
            [
              $class:            'AmazonWebServicesCredentialsBinding',
              credentialsId:     'aws-ecr-credentials',
              accessKeyVariable: 'AWS_ACCESS_KEY_ID',
              secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
            ]
          ]) {
            sh """
              aws ecr get-login-password --region ${region} | \
                docker login --username AWS --password-stdin ${ecrRegistry}
              docker push ${env.FULL_IMAGE}
              echo "Pushed: ${env.FULL_IMAGE}"
            """
          }
        }
      }

      stage('Sync Secrets on EC2') {
        steps {
          withCredentials([
            sshUserPrivateKey(
              credentialsId: 'ec2-ssh-key',
              keyFileVariable: 'SSH_KEY'
            )
          ]) {
            sh """
              ssh -o StrictHostKeyChecking=no \
                  -i \$SSH_KEY \
                  ubuntu@${ec2Host} \
                  "~/k8s/sync-secrets.sh && ~/refresh-ecr-k3s.sh"
              echo "Secrets synced."
            """
          }
        }
      }

      stage('Update GitOps Repo') {
        steps {
          withCredentials([
            usernamePassword(
              credentialsId:     'all-cred',
              usernameVariable:  'GIT_USER',
              passwordVariable:  'GIT_TOKEN'
            )
          ]) {
            sh """
              rm -rf /tmp/gitops
              git clone \
                https://\$GIT_USER:\$GIT_TOKEN@github.com/${devopsRepo}.git \
                /tmp/gitops
              cd /tmp/gitops
              git checkout ${branch}
              DEPLOY="k8s/backend/${serviceName}/deployment.yaml"
              sed -i "s|^          image:.*|          image: ${env.FULL_IMAGE}|g" \$DEPLOY
              git config user.email "jenkins@pavestechnologies.com"
              git config user.name  "Jenkins CD"
              git add \$DEPLOY
              git commit -m "deploy(${serviceName}): ${env.IMAGE_TAG}"
              git push origin ${branch}
              rm -rf /tmp/gitops
              echo "GitOps updated. ArgoCD will deploy."
            """
          }
        }
      }

    }

    post {
      success {
        script {
          echo "SUCCESS: ${serviceName} → ${env.FULL_IMAGE}"
          withCredentials([
            string(
              credentialsId: 'teams-webhook-url',
              variable:      'TEAMS_URL'
            )
          ]) {
            notifyTeams(
              status:      'SUCCESS',
              serviceName: serviceName,
              imageTag:    env.IMAGE_TAG,
              branch:      branch,
              triggeredBy: env.COMMITTER ?: 'Unknown',
              webhookUrl:  env.TEAMS_URL
            )
          }
        }
      }
      failure {
        script {
          echo "FAILED: ${serviceName}"
          withCredentials([
            string(
              credentialsId: 'teams-webhook-url',
              variable:      'TEAMS_URL'
            )
          ]) {
            notifyTeams(
              status:      'FAILURE',
              serviceName: serviceName,
              imageTag:    env.IMAGE_TAG ?: 'unknown',
              branch:      branch,
              triggeredBy: env.COMMITTER ?: 'Unknown',
              webhookUrl:  env.TEAMS_URL
            )
          }
        }
      }
        always {
        script {
          echo "Post-build cleanup..."

          // Remove built image from local daemon
          sh "docker rmi ${env.FULL_IMAGE} 2>/dev/null || true"

          // Remove dangling images and build cache
          sh "docker image prune -f || true"
          sh "docker builder prune -af || true"

          // Clean workspace
          cleanWs()

          sh "df -h"
          echo "Cleanup complete."
        }
      }
    }
  }
}
