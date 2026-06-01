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
    agent any

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
            if (serviceType == 'java') {
              sh '''
                echo "Running Maven build..."
                if [ -f "./mvnw" ]; then
                  ./mvnw clean package -DskipTests -q
                else
                  mvn clean package -DskipTests -q
                fi
                echo "Maven build complete."
              '''
            }
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
                  "~/k8s/update-secret.sh ${serviceName}"
              echo "Secrets synced."
            """
          }
        }
      }

      stage('Update GitOps Repo') {
        steps {
          withCredentials([
            usernamePassword(
              credentialsId:     'github-pat',
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
              sed -i "s|image:.*${ecrRepo}.*|          image: ${env.FULL_IMAGE}|g" \$DEPLOY
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
        echo "SUCCESS: ${serviceName} deployed → ${env.FULL_IMAGE}"
      }
      failure {
        echo "FAILED: ${serviceName} deployment failed"
      }
      always {
        sh "docker rmi ${env.FULL_IMAGE} || true"
      }
    }
  }
}
