/**
 * deployBackend.groovy
 *
 * Build/push step for the Spring Boot service:
 *   - careers-backend  (github.com/PavesTechnologies/careers-backend)
 *
 * Pipeline (stops at ECR — no GitOps commit, no EC2 secret sync):
 *   1. Checkout code
 *   2. Build image, linux/amd64   (Maven runs INSIDE the Dockerfile)
 *   3. Verify the image before it is published
 *   4. Push to ECR  (immutable :main-<sha> + moving :main)
 *
 * Deploy is intentionally out of scope: this step only produces and publishes
 * the image. Rolling it out is handled separately.
 *
 * Two deliberate differences from the frontend step:
 *
 *   - No "Prepare .env". This image never bakes config. .dockerignore excludes
 *     .env, and application.properties imports it optionally at runtime, so the
 *     values arrive from the Kubernetes secret — not from the build.
 *
 *   - No `mvn package` on the agent. The Dockerfile is multi-stage and already
 *     resolves, compiles and layers the jar. Running Maven here too would just
 *     build it twice. The agent needs Docker only: no JDK, no Maven.
 *
 * Usage (Jenkinsfile in careers-backend):
 *
 *   @Library('paves-website-scripts') _
 *   deployBackend(
 *     serviceName: 'careers-backend',
 *     ecrRepo:     'careers-backend'
 *   )
 *
 * Builds and tags from `main` by default; override with branch: '<name>'.
 */

def call(Map config) {

  def serviceName = config.serviceName
  def ecrRepo     = config.ecrRepo

  def ecrRegistry = config.ecrRegistry ?: '743737183908.dkr.ecr.ap-south-1.amazonaws.com'
  def region      = config.region      ?: 'ap-south-1'
  def branch      = config.branch      ?: 'main'

  pipeline {
    agent { label 'worker' }

    options {
      timestamps()
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
            if (!serviceName) { error 'deployBackend: serviceName is required' }
            if (!ecrRepo)     { error 'deployBackend: ecrRepo is required' }

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

      // ── STEP 2: Build Docker image (linux/amd64) ──────────────────────
      // Maven resolves, compiles and layers the jar inside the build. Tests are
      // skipped there by design; run them in a separate CI job if you want them
      // gating this pipeline.
      stage('Build Image') {
        steps {
          sh '''
            set -eu
            test -f Dockerfile || { echo "ERROR: no Dockerfile in repo root"; exit 1; }

            echo "Building Docker image (Maven runs inside the build)..."

            docker build -t "$FULL_IMAGE" -t "$MOVING_IMAGE" --label "org.opencontainers.image.revision=$GIT_COMMIT" --label "com.paves.jenkins.build=$BUILD_URL" .

            echo "Image built: $FULL_IMAGE"
            docker image inspect "$FULL_IMAGE" --format 'arch={{.Os}}/{{.Architecture}} size={{.Size}}'
          '''
        }
      }

      // ── STEP 3: Verify image before publishing ────────────────────────
      // Not a boot test: this service needs DB, S3 and UMS credentials to reach
      // "Started", and those live in the Kubernetes secret, not in CI. What is
      // checked here is that the layered jar was extracted correctly and the
      // launcher the ENTRYPOINT names is actually present.
      stage('Verify Image') {
        steps {
          sh '''
            set -eu
            docker run --rm --entrypoint java "$FULL_IMAGE" -version

            docker run --rm --entrypoint sh "$FULL_IMAGE" -c '
              set -e
              test -d /app/BOOT-INF/lib   || { echo "ERROR: dependency layer missing"; exit 1; }
              test -d /app/BOOT-INF/classes || { echo "ERROR: application layer missing"; exit 1; }
              test -d /app/org/springframework/boot/loader || { echo "ERROR: loader layer missing"; exit 1; }
              echo "Layer check passed: $(ls /app/BOOT-INF/lib | wc -l) dependency jars."
            '
          '''
        }
      }

      // ── STEP 4: Push to ECR ───────────────────────────────────────────
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
