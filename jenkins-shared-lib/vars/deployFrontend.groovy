/**
 * deployFrontend.groovy
 *
 * Shared build/push/deploy step for BOTH Next.js frontends:
 *   - Paves_2.0_Website   (github.com/PavesTechnologies/Paves_2.0_Website)
 *   - careers_frontend    (github.com/PavesTechnologies/careers_frontend)
 *
 * They build identically (Next 16, output: "standalone", multi-stage Dockerfile),
 * so one step serves both — only the config map differs.
 *
 * Pipeline:
 *   1. Checkout code + resolve tags
 *   2. Materialise .env from AWS Secrets Manager
 *   3. Build image           (.env is BAKED IN here)
 *   4. Smoke test the image  (before it is published)
 *   5. Push to ECR           (one immutable tag: :main-<sha>)
 *   6. Deploy to the VPS     (tag bump, pull, recreate, health-gate, rollback)
 *
 * One tag per build, and it is never reused. "What is running in production" is
 * therefore always answerable from /opt/paves/.env, and every previous build
 * stays pullable for rollback. The ECR repositories can be created IMMUTABLE.
 *
 * The VPS holds NO long-lived AWS credentials. This step hands it a fresh ECR
 * token over SSH, valid for the few seconds the pull takes, then logs out.
 *
 * Usage (Jenkinsfile in each frontend repo):
 *
 *   @Library('paves-website-scripts') _
 *   deployFrontend(
 *     serviceName:    'careers-frontend',
 *     ecrRepo:        'careers-frontend',
 *     secretName:     'careers-frontend-env',   // AWS Secrets Manager secret id
 *     composeService: 'careers',                // service key in docker-compose.yml
 *     tagKey:         'CAREERS_TAG'             // variable in /opt/paves/.env
 *   )
 *
 * Builds and tags from `main` by default; override with branch: '<name>'.
 * Set deploy: false to build and publish without rolling out.
 */

def call(Map config) {

  def serviceName    = config.serviceName
  def ecrRepo        = config.ecrRepo
  // AWS Secrets Manager secret holding this service's .env as flat JSON.
  def secretName     = config.secretName

  // ── Deploy target ────────────────────────────────────────────────────
  def composeService = config.composeService                 // 'web' | 'careers'
  def tagKey         = config.tagKey                         // 'WEB_TAG' | 'CAREERS_TAG'
  def containerName  = config.containerName  ?: "paves-${composeService}"
  def vpsHost        = config.vpsHost        ?: '2.25.234.207'
  def vpsUser        = config.vpsUser        ?: 'deploy'
  def composeDir     = config.composeDir     ?: '/opt/paves'
  def sshCredsId     = config.sshCredentialsId ?: 'vps-deploy-key'
  def awsCredsId     = config.awsCredentialsId ?: 'aws-ecr-credentials'
  def deployEnabled  = (config.deploy == false) ? 'false' : 'true'

  def ecrRegistry = config.ecrRegistry ?: '743737183908.dkr.ecr.ap-south-1.amazonaws.com'
  def region      = config.region      ?: 'ap-south-1'
  def branch      = config.branch      ?: 'main'

  pipeline {
    agent { label 'worker' }

    stages {

      // ── STEP 1: Checkout code ──────────────────────────────────────────
      stage('Checkout') {
        steps {
          checkout scm
          script {
            // Fail here with a readable message rather than 20 minutes later
            // with a confusing docker or aws error.
            if (!serviceName)    { error 'deployFrontend: serviceName is required' }
            if (!ecrRepo)        { error 'deployFrontend: ecrRepo is required' }
            if (!secretName)     { error 'deployFrontend: secretName is required' }
            if (deployEnabled == 'true') {
              if (!composeService) { error 'deployFrontend: composeService is required when deploying' }
              if (!tagKey)         { error 'deployFrontend: tagKey is required when deploying' }
            }

            env.SHORT_SHA    = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            env.IMAGE_TAG    = "${branch}-${env.SHORT_SHA}"
            env.FULL_IMAGE   = "${ecrRegistry}/${ecrRepo}:${env.IMAGE_TAG}"
            // Exported for the shell blocks below: they are single-quoted, so
            // they read real environment variables instead of interpolating.
            env.SERVICE_NAME = serviceName
            env.ECR_REGISTRY = ecrRegistry
            env.ECR_REPO     = ecrRepo
            env.AWS_DEFAULT_REGION = region

            // Deploy target, same reason.
            env.DEPLOY_ENABLED   = deployEnabled
            env.COMPOSE_SERVICE  = composeService ?: ''
            env.TAG_KEY          = tagKey ?: ''
            env.CONTAINER_NAME   = containerName
            env.VPS_HOST         = vpsHost
            env.VPS_USER         = vpsUser
            env.COMPOSE_DIR      = composeDir

            // Shown on the Teams card.
            env.COMMITTER = sh(script: 'git log -1 --pretty=format:"%an"', returnStdout: true).trim()

            echo "Image will be: ${env.FULL_IMAGE}"
            echo deployEnabled == 'true' \
              ? "Deploy target: ${vpsUser}@${vpsHost} → ${composeDir} (${composeService}, ${tagKey})" \
              : "Deploy: DISABLED (build and publish only)"
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

      // ── STEP 2: Materialise .env from AWS Secrets Manager ─────────────
      // .env is gitignored, so `checkout scm` never brings it. The Dockerfile
      // needs it present in the build context: .dockerignore deliberately does
      // NOT exclude .env, and `next build` inlines NEXT_PUBLIC_* from it.
      stage('Prepare .env') {
        steps {
          withCredentials([[
            $class:            'AmazonWebServicesCredentialsBinding',
            credentialsId:     awsCredsId,
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
          ]]) {
            script {
              createEnvFile(secretName, region)
            }
            sh '''
              set -eu
              test -f Dockerfile || { echo "ERROR: no Dockerfile in repo root"; exit 1; }

              # NEXT_PUBLIC_* is inlined at build time and cannot be corrected
              # later, so a wrong or partial secret must stop the build now.
              grep -q "NEXT_PUBLIC_" .env || {
                echo "ERROR: .env has no NEXT_PUBLIC_ keys - wrong secret?"; exit 1; }

              # Key names only. Never print values: the console log is stored.
              echo "Keys baked into this image:"
              cut -d= -f1 .env | sed 's/^/  - /'
            '''
          }
        }
      }

      // ── STEP 3: Build Docker image ────────────────────────────────────
      stage('Build Image') {
        steps {
          sh '''
            set -eu
            echo "Building Docker image..."

            docker build -t "$FULL_IMAGE" --label "org.opencontainers.image.revision=$GIT_COMMIT" --label "com.paves.jenkins.build=$BUILD_URL" .

            echo "Image built: $FULL_IMAGE"
            docker image inspect "$FULL_IMAGE" --format 'arch={{.Os}}/{{.Architecture}} size={{.Size}}'

            # The VPS is linux/amd64. An arm64 agent would produce an image that
            # builds, pushes, and then refuses to start on the target — a failure
            # that is very confusing to diagnose from the VPS end.
            ARCH=$(docker image inspect "$FULL_IMAGE" --format '{{.Architecture}}')
            if [ "$ARCH" != "amd64" ]; then
              echo "ERROR: image architecture is '$ARCH', the VPS needs amd64."
              echo "       Use an amd64 build agent, or add buildx + qemu and"
              echo "       build with --platform linux/amd64."
              exit 1
            fi
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
            credentialsId:     awsCredsId,
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
                aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_DEFAULT_REGION" --image-tag-mutability IMMUTABLE --image-scanning-configuration scanOnPush=true >/dev/null
              }

              # Tags are immutable and derived from the commit SHA, so a tag that
              # already exists means this exact commit was built before. Rebuilding
              # it would be rejected by ECR anyway; say why, clearly.
              if aws ecr describe-images --repository-name "$ECR_REPO" --image-ids imageTag="$IMAGE_TAG" --region "$AWS_DEFAULT_REGION" >/dev/null 2>&1; then
                echo "ERROR: $IMAGE_TAG already exists in $ECR_REPO."
                echo "       This commit has already been built and pushed."
                echo "       To redeploy it, run the deploy by hand or push a new commit."
                exit 1
              fi

              echo "Pushing image..."
              docker push "$FULL_IMAGE"

              echo "Push complete: $FULL_IMAGE"
            '''
          }
        }
      }

      // ── STEP 6: Deploy to the VPS ─────────────────────────────────────
      //
      // The VPS stores no AWS credentials. Everything it needs arrives over the
      // SSH connection and is revoked at the end of the stage.
      //
      // Only this service's tag line in /opt/paves/.env is touched, so two
      // pipelines running at once cannot clobber each other's version.
      //
      // The tag deployed is the immutable :main-<sha> built by this run, so
      // /opt/paves/.env always names the exact commit that is serving traffic.
      stage('Deploy to VPS') {
        when {
          environment name: 'DEPLOY_ENABLED', value: 'true'
        }
        steps {
          withCredentials([[
            $class:            'AmazonWebServicesCredentialsBinding',
            credentialsId:     awsCredsId,
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
          ]]) {
            sshagent(credentials: [sshCredsId]) {
              sh '''
                set -eu

                SSH="ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -o ServerAliveInterval=15 $VPS_USER@$VPS_HOST"

                echo "=== 1/5  Connectivity and preflight ==================="
                $SSH "test -f $COMPOSE_DIR/docker-compose.yml" || {
                  echo "ERROR: $COMPOSE_DIR/docker-compose.yml not found on $VPS_HOST"
                  echo "       Has the server been bootstrapped?"
                  exit 1; }
                $SSH "test -f $COMPOSE_DIR/.env" || {
                  echo "ERROR: $COMPOSE_DIR/.env not found. Compose cannot resolve image tags."
                  exit 1; }
                $SSH "grep -q '^$TAG_KEY=' $COMPOSE_DIR/.env" || {
                  echo "ERROR: $TAG_KEY is not present in $COMPOSE_DIR/.env"
                  exit 1; }

                # Remembered so a failed rollout can be put back.
                PREV_TAG=$($SSH "grep '^$TAG_KEY=' $COMPOSE_DIR/.env | cut -d= -f2-")
                echo "Currently deployed $TAG_KEY = ${PREV_TAG:-<empty>}"
                echo "Rolling out        $TAG_KEY = $IMAGE_TAG"

                echo "=== 2/5  Injecting a short-lived ECR token ============"
                # Piped, so the token is never an argument and never reaches the
                # console log. Valid 12h, but only used in the next 30 seconds.
                aws ecr get-login-password --region "$AWS_DEFAULT_REGION" \
                  | $SSH "docker login --username AWS --password-stdin $ECR_REGISTRY" \
                  || { echo "ERROR: docker login failed on the VPS"; exit 1; }

                echo "=== 3/5  Pulling $IMAGE_TAG ==========================="
                $SSH "set -eu; cd $COMPOSE_DIR; docker compose pull $COMPOSE_SERVICE" || {
                  echo "ERROR: pull failed. The image exists in ECR (it was just pushed),"
                  echo "       so this is usually the ECR token or a network egress issue."
                  $SSH "docker logout $ECR_REGISTRY" >/dev/null 2>&1 || true
                  exit 1; }

                echo "=== 4/5  Recreating container ========================="
                # Tag bump and recreate are one SSH call: the window where .env
                # points at an image that is not yet running stays minimal.
                $SSH "set -eu
                      cd $COMPOSE_DIR
                      sed -i 's|^$TAG_KEY=.*|$TAG_KEY=$IMAGE_TAG|' .env
                      grep '^$TAG_KEY=' .env
                      docker compose up -d $COMPOSE_SERVICE"

                echo "=== 5/5  Waiting for $CONTAINER_NAME to report healthy ="
                # The loop runs on the VPS in one connection rather than one SSH
                # round trip per poll. The Dockerfile's HEALTHCHECK is what is
                # being read here.
                if $SSH "CN=$CONTAINER_NAME; "'
                      for i in $(seq 1 40); do
                        S=$(docker inspect -f "{{.State.Health.Status}}" "$CN" 2>/dev/null || echo missing)
                        case "$S" in
                          healthy)   echo "healthy after ${i} polls"; exit 0 ;;
                          unhealthy) echo "reported UNHEALTHY";       exit 2 ;;
                        esac
                        sleep 3
                      done
                      echo "still $S after 120s"; exit 1'
                then
                  echo "Deployed: $FULL_IMAGE"
                  $SSH "docker logout $ECR_REGISTRY" >/dev/null 2>&1 || true
                else
                  echo "--------------------------------------------------------"
                  echo "ERROR: $CONTAINER_NAME did not become healthy."
                  echo "Last 60 log lines from the VPS:"
                  $SSH "docker logs --tail 60 $CONTAINER_NAME 2>&1" || true
                  echo "--------------------------------------------------------"

                  if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$IMAGE_TAG" ] && [ "$PREV_TAG" != "bootstrap" ]; then
                    echo "Rolling back to $PREV_TAG ..."
                    $SSH "set -eu
                          cd $COMPOSE_DIR
                          sed -i 's|^$TAG_KEY=.*|$TAG_KEY=$PREV_TAG|' .env
                          docker compose up -d $COMPOSE_SERVICE" || echo "WARNING: rollback command failed"
                    echo "Rolled back to $PREV_TAG. The site should be serving the previous build."
                  else
                    echo "No usable previous tag ($PREV_TAG) - leaving the new one in place."
                    echo "The service is DOWN. Investigate before retrying."
                  fi

                  $SSH "docker logout $ECR_REGISTRY" >/dev/null 2>&1 || true
                  exit 1
                fi
              '''
            }
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
          // Remove the tag this build produced from the local daemon
          sh "docker rmi ${env.FULL_IMAGE ?: ''} 2>/dev/null || true"
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
