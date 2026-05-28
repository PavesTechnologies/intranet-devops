/**
 * deployToDev.groovy
 * Simple CD Pipeline:
 * 1. Checkout code
 * 2. Build ARM64 image
 * 3. Push to ECR
 * 4. SSH to EC2 → sync secrets
 * 5. Update deployment.yaml in GitHub → ArgoCD auto-deploys
 */

def call(Map config) {

  def serviceName = config.serviceName
  def serviceType = config.serviceType ?: 'java'
  def ecrRepo     = config.ecrRepo
  def ecrRegistry = '743737183908.dkr.ecr.ap-south-1.amazonaws.com'
  def region      = 'ap-south-1'
  def branch      = 'dev'
  def ec2Host     = '13.207.112.154'
  def devopsRepo  = 'PavesTechnologies/intranet-devops'

  pipeline {
    agent any

    stages {

      // ── STEP 1: Checkout code ──────────────────────────────────────────
      stage('Checkout') {
        steps {
          checkout scm
          script {
            env.SHORT_SHA   = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            env.IMAGE_TAG   = "${branch}-${env.SHORT_SHA}"
            env.FULL_IMAGE  = "${ecrRegistry}/${ecrRepo}:${env.IMAGE_TAG}"
            echo "Image will be: ${env.FULL_IMAGE}"
          }
        }
      }

      // ── STEP 2: Build ARM64 Docker image ──────────────────────────────
      stage('Build Image') {
        steps {
          script {
            if (serviceType == 'java') {
              // Java — build JAR first then Docker image
              sh """
                echo "Running Maven build..."
                if [ -f "./mvnw" ]; then
                  ./mvnw clean package -DskipTests -q
                else
                  mvn clean package -DskipTests -q
                fi
                echo "Maven build complete."
              """
            }

            sh """
              echo "Building ARM64 Docker image..."

              # Create buildx builder if not exists
              docker buildx inspect paves-builder > /dev/null 2>&1 || \\
                docker buildx create --name paves-builder --use

              docker buildx use paves-builder

              # Build using existing Dockerfile in repo
              docker buildx build \\
                --platform linux/arm64 \\
                --load \\
                --tag ${env.FULL_IMAGE} \\
                .

              echo "Image built: ${env.FULL_IMAGE}"
            """
          }
        }
      }

      // ── STEP 3: Push to ECR ───────────────────────────────────────────
      stage('Push to ECR') {
        steps {
          withCredentials([[
            \$class:            'AmazonWebServicesCredentialsBinding',
            credentialsId:     'aws-ecr-credentials',
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
          ]]) {
            sh """
              echo "Logging in to ECR..."
              aws ecr get-login-password --region ${region} | \\
                docker login --username AWS --password-stdin ${ecrRegistry}

              echo "Pushing image..."
              docker push ${env.FULL_IMAGE}

              echo "Push complete: ${env.FULL_IMAGE}"
            """
          }
        }
      }

      // ── STEP 4: SSH to EC2 → sync secrets ────────────────────────────
      stage('Sync Secrets on EC2') {
        steps {
          withCredentials([
            sshUserPrivateKey(
              credentialsId: 'ec2-ssh-key',
              keyFileVariable: 'SSH_KEY'
            )
          ]) {
            sh """
              echo "Connecting to EC2 and syncing secrets for ${serviceName}..."

              ssh -o StrictHostKeyChecking=no \\
                  -i \$SSH_KEY \\
                  ubuntu@${ec2Host} \\
                  "~/k8s/update-secret.sh ${serviceName}"

              echo "Secrets synced."
            """
          }
        }
      }

      // ── STEP 5: Update GitHub → ArgoCD auto-deploys ───────────────────
      stage('Update GitOps Repo') {
        steps {
          withCredentials([
            usernamePassword(
              credentialsId: 'github-pat',
              usernameVariable: 'GIT_USER',
              passwordVariable: 'GIT_TOKEN'
            )
          ]) {
            sh """
              echo "Updating deployment.yaml in ${branch} branch..."

              # Clone devops repo
              rm -rf /tmp/gitops
              git clone \\
                https://\$GIT_USER:\$GIT_TOKEN@github.com/${devopsRepo}.git \\
                /tmp/gitops

              cd /tmp/gitops
              git checkout ${branch}

              # Update image tag
              DEPLOY="k8s/backend/${serviceName}/deployment.yaml"
              sed -i "s|image:.*${ecrRepo}.*|          image: ${env.FULL_IMAGE}|g" \$DEPLOY

              echo "Updated image to: ${env.FULL_IMAGE}"

              # Push
              git config user.email "jenkins@pavestechnologies.com"
              git config user.name  "Jenkins CD"
              git add \$DEPLOY
              git commit -m "deploy(${serviceName}): ${env.IMAGE_TAG}"
              git push origin ${branch}

              rm -rf /tmp/gitops
              echo "GitOps updated. ArgoCD will deploy automatically."
            """
          }
        }
      }

    }

    post {
      success {
        echo "✅ ${serviceName} deployed successfully → ${env.FULL_IMAGE}"
      }
      failure {
        echo "❌ ${serviceName} deployment failed"
      }
      always {
        sh "docker rmi ${env.FULL_IMAGE} || true"
      }
    }
  }
}
