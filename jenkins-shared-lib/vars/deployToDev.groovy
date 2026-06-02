def call(Map config) {

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
    agent { label 'worker' }

    stages {

      stage('Checkout') {
        steps {
          checkout scm
          script {
            env.SHORT_SHA  = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            env.IMAGE_TAG  = "${branch}-${env.SHORT_SHA}"
            env.FULL_IMAGE = "${ecrRegistry}/${ecrRepo}:${env.IMAGE_TAG}"
            echo "Image: ${env.FULL_IMAGE}"
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

      stage('Build Image') {
        steps {
          script {
            // if (serviceType == 'java') {
            //   sh '''
            //     echo "Running Maven build..."
            //     if [ -f "./mvnw" ]; then
            //       ./mvnw clean package -DskipTests -q
            //     else
            //       mvn clean package -DskipTests -q
            //     fi
            //     echo "Maven build complete."
            //   '''
            // }
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
                  "~/k8s/sync-secrets.sh"
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
      always {
        script {
          echo "Post-build cleanup..."
          sh "docker rmi ${env.FULL_IMAGE} 2>/dev/null || true"
          sh "docker image prune -f || true"
          sh "docker builder prune -af || true"
          cleanWs()
          sh "df -h"
          echo "Cleanup complete."
        }
      }

      success {
        script {
          def committerEmail  = sh(script: 'git log -1 --format="%ae"', returnStdout: true).trim()
          def committerName   = sh(script: 'git log -1 --format="%an"', returnStdout: true).trim()
          def commitMessage   = sh(script: 'git log -1 --format="%s"',  returnStdout: true).trim()

          echo "Sending success email to: ${committerEmail}"

          emailext(
            to:       "${committerEmail}",
            subject:  "✅ CD Pipeline Success — ${serviceName} [${env.IMAGE_TAG}]",
            mimeType: 'text/html',
            body:     """
<html>
<body style="font-family: Arial, sans-serif; color: #2C3E50;">

  <div style="background:#1E8449; color:white; padding:16px; border-radius:6px;">
    <h2 style="margin:0;">✅ Deployment Successful</h2>
    <p style="margin:4px 0 0 0;">Service: <strong>${serviceName}</strong></p>
  </div>

  <br/>

  <table style="border-collapse:collapse; width:100%;">
    <tr style="background:#D5F5E3;">
      <td style="padding:10px; border:1px solid #ccc; width:30%;"><strong>Service</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${serviceName}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ccc;"><strong>Branch</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${branch}</td>
    </tr>
    <tr style="background:#D5F5E3;">
      <td style="padding:10px; border:1px solid #ccc;"><strong>Image</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${env.FULL_IMAGE}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ccc;"><strong>Commit</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${env.SHORT_SHA}</td>
    </tr>
    <tr style="background:#D5F5E3;">
      <td style="padding:10px; border:1px solid #ccc;"><strong>Commit Message</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${commitMessage}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ccc;"><strong>Deployed By</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${committerName}</td>
    </tr>
    <tr style="background:#D5F5E3;">
      <td style="padding:10px; border:1px solid #ccc;"><strong>Build URL</strong></td>
      <td style="padding:10px; border:1px solid #ccc;"><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td>
    </tr>
  </table>

  <br/>
  <div style="background:#D5F5E3; padding:12px; border-radius:6px; border-left:4px solid #1E8449;">
    <p style="margin:0;">
      ✅ ArgoCD has been notified and will deploy the new image automatically.<br/>
      Monitor at: <a href="https://13.207.112.154:30452">ArgoCD Dashboard</a>
    </p>
  </div>

  <br/>
  <p style="color:#888; font-size:12px;">Paves Technologies — Jenkins CD Pipeline</p>
</body>
</html>
            """
          )
        }
      }

      failure {
        script {
          def committerEmail  = sh(script: 'git log -1 --format="%ae"', returnStdout: true).trim()
          def committerName   = sh(script: 'git log -1 --format="%an"', returnStdout: true).trim()
          def commitMessage   = sh(script: 'git log -1 --format="%s"',  returnStdout: true).trim()

          echo "Sending failure email to: ${committerEmail}"

          emailext(
            to:       "${committerEmail}",
            subject:  "❌ CD Pipeline Failed — ${serviceName} [${env.IMAGE_TAG}]",
            mimeType: 'text/html',
            body:     """
<html>
<body style="font-family: Arial, sans-serif; color: #2C3E50;">

  <div style="background:#922B21; color:white; padding:16px; border-radius:6px;">
    <h2 style="margin:0;">❌ Deployment Failed</h2>
    <p style="margin:4px 0 0 0;">Service: <strong>${serviceName}</strong></p>
  </div>

  <br/>

  <table style="border-collapse:collapse; width:100%;">
    <tr style="background:#FADBD8;">
      <td style="padding:10px; border:1px solid #ccc; width:30%;"><strong>Service</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${serviceName}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ccc;"><strong>Branch</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${branch}</td>
    </tr>
    <tr style="background:#FADBD8;">
      <td style="padding:10px; border:1px solid #ccc;"><strong>Image Tag</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${env.IMAGE_TAG}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ccc;"><strong>Commit</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${env.SHORT_SHA}</td>
    </tr>
    <tr style="background:#FADBD8;">
      <td style="padding:10px; border:1px solid #ccc;"><strong>Commit Message</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${commitMessage}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ccc;"><strong>Committed By</strong></td>
      <td style="padding:10px; border:1px solid #ccc;">${committerName}</td>
    </tr>
    <tr style="background:#FADBD8;">
      <td style="padding:10px; border:1px solid #ccc;"><strong>Build URL</strong></td>
      <td style="padding:10px; border:1px solid #ccc;"><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td>
    </tr>
  </table>

  <br/>
  <div style="background:#FADBD8; padding:12px; border-radius:6px; border-left:4px solid #922B21;">
    <p style="margin:0;">
      ❌ The deployment did NOT go through.<br/>
      Please check the build logs and fix the issue before merging again.<br/>
      <a href="${env.BUILD_URL}console">View Console Output →</a>
    </p>
  </div>

  <br/>
  <p style="color:#888; font-size:12px;">Paves Technologies — Jenkins CD Pipeline</p>
</body>
</html>
            """
          )
        }
      }
    }
  }
}
