/**
 * deployToProd.groovy
 * Promotes the latest dev image to production — no rebuild.
 *
 * Steps:
 *   1. Fetch the latest image tag from paves/{service}-dev in ECR
 *   2. Pull dev image → re-tag as paves/{service}-prod
 *   3. Push prod image to ECR
 *   4. Update k8s/backend/{service}/deployment.yaml in GitOps repo (main branch)
 *   5. ArgoCD auto-deploys from main
 *
 * Usage in Jenkinsfile.prod:
 *
 *   @Library('paves-shared-lib') _
 *
 *   deployToProd(
 *       serviceName: 'ums',            // ums | tms | eos | pms | rms | lms
 *       ecrRepo:     'paves/ums-prod'  // prod ECR repo
 *   )
 */
def call(Map config) {

  def serviceName = config.serviceName               // e.g. 'ums'
  def ecrRegistry = '743737183908.dkr.ecr.ap-south-1.amazonaws.com'
  def region      = 'ap-south-1'
  def prodRepo    = config.ecrRepo                   // e.g. 'paves/ums-prod'
  def devRepo     = prodRepo.replace('-prod', '-dev') // e.g. 'paves/ums-dev'
  def prodBranch  = config.prodBranch ?: 'main'
  def devopsRepo  = 'PavesTechnologies/intranet-devops'

  pipeline {
    agent { label 'worker' }

    stages {

      // ── STEP 1: Find the latest image in paves/{service}-dev ─────────────
      stage('Get Latest Dev Image') {
        steps {
          withCredentials([[
            $class:            'AmazonWebServicesCredentialsBinding',
            credentialsId:     'aws-ecr-credentials',
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
          ]]) {
            script {
              env.DEV_TAG = sh(
                script: """
                  aws ecr describe-images \
                    --repository-name ${devRepo} \
                    --region ${region} \
                    --query 'sort_by(imageDetails, &imagePushedAt)[-1].imageTags[0]' \
                    --output text
                """,
                returnStdout: true
              ).trim()

              // dev-09f4c4c → prod-09f4c4c
              def sha         = env.DEV_TAG.replace('dev-', '')
              env.PROD_TAG    = "prod-${sha}"
              env.DEV_IMAGE   = "${ecrRegistry}/${devRepo}:${env.DEV_TAG}"
              env.PROD_IMAGE  = "${ecrRegistry}/${prodRepo}:${env.PROD_TAG}"

              echo "Promoting: ${env.DEV_IMAGE}"
              echo "To:        ${env.PROD_IMAGE}"
            }
          }
        }
      }

      // ── STEP 2: Notify Teams ──────────────────────────────────────────────
      stage('Notify Started') {
        steps {
          withCredentials([string(credentialsId: 'teams-webhook-url', variable: 'TEAMS_URL')]) {
            script {
              notifyTeams(
                status:      'STARTED',
                serviceName: "${serviceName}-prod",
                imageTag:    env.PROD_TAG,
                branch:      prodBranch,
                triggeredBy: env.BUILD_USER ?: 'Jenkins',
                webhookUrl:  env.TEAMS_URL
              )
            }
          }
        }
      }

      // ── STEP 3: Pull dev image → re-tag → push as prod ───────────────────
      stage('Promote Image') {
        steps {
          withCredentials([[
            $class:            'AmazonWebServicesCredentialsBinding',
            credentialsId:     'aws-ecr-credentials',
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
          ]]) {
            sh """
              aws ecr get-login-password --region ${region} | \
                docker login --username AWS --password-stdin ${ecrRegistry}

              docker pull ${env.DEV_IMAGE}
              docker tag  ${env.DEV_IMAGE} ${env.PROD_IMAGE}
              docker push ${env.PROD_IMAGE}

              echo "Promoted: ${env.PROD_IMAGE}"
            """
          }
        }
      }

      // ── STEP 4: Update GitOps → ArgoCD deploys ───────────────────────────
      stage('Update GitOps Repo') {
        steps {
          withCredentials([
            usernamePassword(
              credentialsId:    'github-pat',
              usernameVariable: 'GIT_USER',
              passwordVariable: 'GIT_TOKEN'
            )
          ]) {
            sh """
              rm -rf /tmp/gitops-prod
              git clone \
                https://\$GIT_USER:\$GIT_TOKEN@github.com/${devopsRepo}.git \
                /tmp/gitops-prod

              cd /tmp/gitops-prod
              git checkout ${prodBranch}

              DEPLOY="k8s/backend/${serviceName}/deployment.yaml"

              sed -i "s|image:.*${ecrRegistry}.*|          image: ${env.PROD_IMAGE}|" \$DEPLOY

              git config user.email "jenkins@pavestechnologies.com"
              git config user.name  "Jenkins CD"
              git add \$DEPLOY
              git commit -m "deploy(${serviceName}): ${env.PROD_TAG}"
              git push origin ${prodBranch}

              rm -rf /tmp/gitops-prod
              echo "GitOps updated on ${prodBranch}. ArgoCD will deploy to prod."
            """
          }
        }
      }

    }

    post {
      success {
        script {
          echo "SUCCESS: ${serviceName} → prod (${env.PROD_IMAGE})"
          withCredentials([string(credentialsId: 'teams-webhook-url', variable: 'TEAMS_URL')]) {
            notifyTeams(
              status:      'SUCCESS',
              serviceName: "${serviceName}-prod",
              imageTag:    env.PROD_TAG,
              branch:      prodBranch,
              triggeredBy: env.BUILD_USER ?: 'Jenkins',
              webhookUrl:  env.TEAMS_URL
            )
          }
        }
      }
      failure {
        script {
          echo "FAILED: ${serviceName} prod promotion failed"
          withCredentials([string(credentialsId: 'teams-webhook-url', variable: 'TEAMS_URL')]) {
            notifyTeams(
              status:      'FAILURE',
              serviceName: "${serviceName}-prod",
              imageTag:    env.PROD_TAG ?: 'unknown',
              branch:      prodBranch,
              triggeredBy: env.BUILD_USER ?: 'Jenkins',
              webhookUrl:  env.TEAMS_URL
            )
          }
        }
      }
      always {
        sh """
          docker rmi ${env.DEV_IMAGE}  || true
          docker rmi ${env.PROD_IMAGE} || true
        """
        cleanWs()
      }
    }
  }
}