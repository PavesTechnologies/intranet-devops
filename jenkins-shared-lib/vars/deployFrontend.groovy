/**
 * deployFrontend.groovy
 *
 * Shared build/push step for BOTH Next.js frontends:
 *   - Paves_2.0_Website   (github.com/PavesTechnologies/Paves_2.0_Website)
 *   - careers_frontend    (github.com/PavesTechnologies/careers_frontend)
 *
 * They build identically (Next 16, output: "standalone", multi-stage Dockerfile),
 * so one step serves both — only the config map differs.
 *
 * Pipeline (stops at ECR — no GitOps commit, no EC2 secret sync):
 *   1. Checkout code
 *   2. Materialise .env from a Jenkins secret file
 *   3. Build image, linux/amd64  (.env is BAKED IN here)
 *   4. Smoke test the image before it is published
 *   5. Push to ECR  (immutable :main-<sha> + moving :main)
 *
 * Deploy is intentionally out of scope: this step only produces and publishes
 * the image. Rolling it out is handled separately.
 *
 * Usage (Jenkinsfile in each frontend repo):
 *
 *   @Library('paves-website-scripts') _
 *   deployFrontend(
 *     serviceName:      'careers-frontend',
 *     ecrRepo:          'careers-frontend',
 *     envCredentialId:  'careers-frontend-env-dev'
 *   )
 *
 * Builds and tags from `main` by default; override with branch: '<name>'.
 */

def call(Map config) {

  def serviceName = config.serviceName
  def ecrRepo     = config.ecrRepo
  // Jenkins "Secret file" credential holding this service's .env verbatim.
  def envCredId   = config.envCredentialId

  def ecrRegistry = config.ecrRegistry ?: '743737183908.dkr.ecr.ap-south-1.amazonaws.com'
  def region      = config.region      ?: 'ap-south-1'
  def branch      = config.branch      ?: 'main'

  pipeline {
    agent { label 'worker' }

    options {
      timestamps()
      // Two runs of the same job share a workspace, and this one writes .env
      // into it. Serialising avoids one build's secrets landing in another's image.
      disableConcurrentBuilds()
      timeout(time: 40, unit: 'MINUTES')
      buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    stages {

      // ── STEP 1: Checkout code ──────────────────────────────────────────
      stage('Checkout') {
        steps {
          checkout scm
          script {
            // Fail here with a readable message rather than 20 minutes later
            // with a confusing docker or aws error.
            if (!serviceName) { error 'deployFrontend: serviceName is required' }
            if (!ecrRepo)     { error 'deployFrontend: ecrRepo is required' }
            if (!envCredId)   { error 'deployFrontend: envCredentialId is required' }

            env.SHORT_SHA    = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            env.IMAGE_TAG    = "${branch}-${env.SHORT_SHA}"
            env.FULL_IMAGE   = "${ecrRegistry}/${ecrRepo}:${env.IMAGE_TAG}"
            // Moving tag, so "what is currently on main" is always pullable.
            env.MOVING_IMAGE = "${ecrRegistry}/${ecrRepo}:${branch}"
            // Exported for the shell blocks below: they are single-quoted, so
            // they read real environment variables instead of interpolating.
            env.SERVICE_NAME = serviceName
            env.ECR_REGISTRY = ecrRegistry
            env.ECR_REPO     = ecrRepo
            env.AWS_DEFAULT_REGION = region

            // Shown on the Teams card.
            env.COMMITTER = sh(script: 'git log -1 --pretty=format:"%an"', returnStdout: true).trim()

            echo "Image will be: ${env.FULL_IMAGE}"
          }
        }
      }

      // ── Notify: build started ─────────────────────────────────────────
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

      // ── STEP 2: Materialise .env ──────────────────────────────────────
      // .env is gitignored, so `checkout scm` never brings it. The Dockerfile
      // needs it present in the build context: .dockerignore deliberately does
      // NOT exclude .env, and `next build` inlines NEXT_PUBLIC_* from it.
      stage('Prepare .env') {
        steps {
          withCredentials([file(credentialsId: envCredId, variable: 'ENV_FILE')]) {
            sh '''
              set -eu
              test -f Dockerfile || { echo "ERROR: no Dockerfile in repo root"; exit 1; }

              cp "$ENV_FILE" .env
              chmod 600 .env
              echo "Wrote .env for build ($(grep -c . .env) non-empty lines)."

              # NEXT_PUBLIC_* is inlined at build time and cannot be corrected
              # later, so a wrong or empty credential must stop the build now.
              grep -q "NEXT_PUBLIC_" .env || {
                echo "ERROR: .env has no NEXT_PUBLIC_ keys - wrong or empty credential?"; exit 1; }
            '''
          }
        }
      }

      // ── STEP 3: Build Docker image (linux/amd64) ──────────────────────
      stage('Build Image') {
        steps {
          sh '''
            set -eu
            echo "Building Docker image..."

            docker build -t "$FULL_IMAGE" -t "$MOVING_IMAGE" --label "org.opencontainers.image.revision=$GIT_COMMIT" --label "com.paves.jenkins.build=$BUILD_URL" .

            echo "Image built: $FULL_IMAGE"
            docker image inspect "$FULL_IMAGE" --format 'arch={{.Os}}/{{.Architecture}} size={{.Size}}'
          '''
        }
      }

      // ── STEP 4: Smoke test before publishing ──────────────────────────
      // A Next build can succeed and still produce an image that dies on boot
      // (missing standalone output, bad .env, native module for the wrong libc).
      // Catching that here keeps a broken tag out of ECR.
      stage('Smoke Test Image') {
        steps {
          sh '''
            set -eu
            CONTAINER="smoke-$SERVICE_NAME-$BUILD_NUMBER"
            docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
            docker run -d --name "$CONTAINER" "$FULL_IMAGE" >/dev/null

            # Probed from inside the container, so the agent needs no free host
            # port and no curl installed.
            OK=0
            for i in $(seq 1 30); do
              if docker exec "$CONTAINER" sh -c 'wget -q -O - http://127.0.0.1:3000/ >/dev/null 2>&1'; then
                OK=1; break
              fi
              sleep 2
            done

            if [ "$OK" -ne 1 ]; then
              echo "ERROR: container did not serve on :3000 within 60s"
              docker logs "$CONTAINER" 2>&1 | tail -40
              docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
              exit 1
            fi

            echo "Smoke test passed - container serves on :3000."
            docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
          '''
        }
      }

      // ── STEP 5: Push to ECR ───────────────────────────────────────────
      stage('Push to ECR') {
        steps {
          withCredentials([[
            $class:            'AmazonWebServicesCredentialsBinding',
            credentialsId:     'aws-ecr-credentials',
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
          ]]) {
            sh '''
              set -eu
              echo "Logging in to ECR..."
              aws ecr get-login-password --region "$AWS_DEFAULT_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY"

              # A brand-new service otherwise fails on push with an opaque
              # "repository does not exist".
              aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_DEFAULT_REGION" >/dev/null 2>&1 || {
                echo "Creating ECR repository $ECR_REPO..."
                aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_DEFAULT_REGION" --image-scanning-configuration scanOnPush=true >/dev/null
              }

              echo "Pushing image..."
              docker push "$FULL_IMAGE"
              docker push "$MOVING_IMAGE"

              echo "Push complete: $FULL_IMAGE"
            '''
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
          // .env held real secrets — remove it before anything else.
          sh "rm -f .env || true"
          sh "docker rm -f smoke-${env.SERVICE_NAME}-${env.BUILD_NUMBER} 2>/dev/null || true"
          // Remove both tags this build produced from the local daemon
          sh "docker rmi ${env.FULL_IMAGE ?: ''} ${env.MOVING_IMAGE ?: ''} 2>/dev/null || true"
          sh "docker image prune -f || true"
          // Prune build cache but keep ~3GB, so the next build still hits the
          // npm-ci / maven-dependency layers this Dockerfile is structured for.
          // Docker 29 renamed --keep-storage to --reserved-space; try both.
          sh "docker builder prune -f --reserved-space=3GB 2>/dev/null || docker builder prune -f --keep-storage=3GB 2>/dev/null || true"
          cleanWs()
          sh "df -h"
          echo "Cleanup complete."
        }
      }
    }
  }
}
