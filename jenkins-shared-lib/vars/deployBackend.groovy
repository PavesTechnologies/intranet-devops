/**
 * deployBackend.groovy
 *
 * Build/push/deploy step for the Spring Boot service:
 *   - careers-backend  (github.com/PavesTechnologies/careers-backend)
 *
 * Pipeline:
 *   1. Checkout code
 *   2. Build image               (Maven runs INSIDE the Dockerfile)
 *   3. Verify the image          (layer extraction, before it is published)
 *   4. Push to ECR               (one immutable tag: :main-<sha>)
 *   5. Sync secrets to the VPS   (AWS SSM → /opt/paves/.env.api)
 *   6. Deploy                    (tag bump, pull, recreate, health-gate, rollback)
 *
 * One tag per build, and it is never reused. "What is running in production" is
 * therefore always answerable from /opt/paves/.env, and every previous build
 * stays pullable for rollback. The ECR repositories can be created IMMUTABLE.
 *
 * Two deliberate differences from the frontend step:
 *
 *   - No "Prepare .env" at build time. This image never bakes config.
 *     .dockerignore excludes .env, and application.properties imports it
 *     optionally from the working directory, so the values arrive at runtime
 *     from /opt/paves/.env.api — never from a layer, never from ECR.
 *
 *   - No `mvn package` on the agent. The Dockerfile is multi-stage and already
 *     resolves, compiles and layers the jar. Running Maven here too would just
 *     build it twice. The agent needs Docker only: no JDK, no Maven.
 *
 * The VPS holds NO long-lived AWS credentials. Both the secrets and the ECR
 * token arrive over the SSH connection, and the token is revoked at the end.
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
 * Set deploy: false to build and publish without rolling out.
 */

def call(Map config) {

  def serviceName    = config.serviceName
  def ecrRepo        = config.ecrRepo

  // ── Deploy target ────────────────────────────────────────────────────
  def ssmParameter   = config.ssmParameter   ?: 'paves_careers_backend'
  def composeService = config.composeService ?: 'api'
  def tagKey         = config.tagKey         ?: 'API_TAG'
  def containerName  = config.containerName  ?: 'paves-api'
  def envFileName    = config.envFileName    ?: '.env.api'
  def containerPort  = config.containerPort  ?: '8080'
  // Used only as a fallback when the image carries no HEALTHCHECK.
  def healthPath     = config.healthPath     ?: '/actuator/health'
  def vpsHost        = config.vpsHost        ?: '2.25.234.207'
  def vpsUser        = config.vpsUser        ?: 'deploy'
  def composeDir     = config.composeDir     ?: '/opt/paves'
  def sshCredsId     = config.sshCredentialsId ?: 'vps-deploy-key'
  def awsCredsId     = config.awsCredentialsId ?: 'aws-ecr-credentials'
  def deployEnabled  = (config.deploy == false) ? 'false' : 'true'

  // Keys the service cannot start without. Checked before anything is written,
  // so a truncated or wrong SSM parameter fails loudly instead of producing a
  // container that boots and then dies on its first database call.
  def requiredKeys   = config.requiredKeys ?: ['DB_URL', 'DB_USERNAME', 'DB_PASSWORD']

  def ecrRegistry = config.ecrRegistry ?: '743737183908.dkr.ecr.ap-south-1.amazonaws.com'
  def region      = config.region      ?: 'ap-south-1'
  def branch      = config.branch      ?: 'main'

  pipeline {
    agent { label 'worker' }

    options {
      timestamps()
      disableConcurrentBuilds()
      timeout(time: 40, unit: 'MINUTES')
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
            // Exported for the shell blocks below: they are single-quoted, so
            // they read real environment variables instead of interpolating.
            env.SERVICE_NAME = serviceName
            env.ECR_REGISTRY = ecrRegistry
            env.ECR_REPO     = ecrRepo
            env.AWS_DEFAULT_REGION = region

            // Deploy target, same reason.
            env.DEPLOY_ENABLED  = deployEnabled
            env.SSM_PARAM       = ssmParameter
            env.COMPOSE_SERVICE = composeService
            env.TAG_KEY         = tagKey
            env.CONTAINER_NAME  = containerName
            env.ENV_FILE        = envFileName
            env.CONTAINER_PORT  = containerPort
            env.HEALTH_PATH     = healthPath
            env.VPS_HOST        = vpsHost
            env.VPS_USER        = vpsUser
            env.COMPOSE_DIR     = composeDir
            env.REQUIRED_KEYS   = requiredKeys.join(' ')

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

      // ── STEP 2: Build Docker image ────────────────────────────────────
      // Maven resolves, compiles and layers the jar inside the build. Tests are
      // skipped there by design; run them in a separate CI job if you want them
      // gating this pipeline.
      stage('Build Image') {
        steps {
          sh '''
            set -eu
            test -f Dockerfile || { echo "ERROR: no Dockerfile in repo root"; exit 1; }

            echo "Building Docker image (Maven runs inside the build)..."

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

      // ── STEP 3: Verify image before publishing ────────────────────────
      // Not a boot test: this service needs DB, S3 and UMS credentials to reach
      // "Started", and those live in SSM, not in CI. What is checked here is
      // that the layered jar was extracted correctly and the launcher the
      // ENTRYPOINT names is actually present.
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

      // ── STEP 5: Sync secrets to the VPS ───────────────────────────────
      //
      // Reads the flat JSON from AWS SSM, converts it to KEY=VALUE lines, and
      // pipes it straight into /opt/paves/.env.api over SSH. The values never
      // touch the agent's disk and never appear in the console log.
      //
      // This runs BEFORE the pull so that new config and the image that needs
      // it arrive together.
      stage('Sync Secrets') {
        when {
          environment name: 'DEPLOY_ENABLED', value: 'true'
        }
        steps {
          // sshUserPrivateKey comes from Credentials Binding, so the SSH Agent
          // plugin is not required. It writes the key to a temporary file and
          // deletes it when the block exits.
          withCredentials([
            [
              $class:            'AmazonWebServicesCredentialsBinding',
              credentialsId:     awsCredsId,
              accessKeyVariable: 'AWS_ACCESS_KEY_ID',
              secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
            ],
            sshUserPrivateKey(
              credentialsId:    sshCredsId,
              keyFileVariable:  'SSH_KEY',
              usernameVariable: 'SSH_USER'
            )
          ]) {
            script {
              sh '''
                # Jenkins runs sh with -x. Disable it here: the pipeline below
                # carries live credentials and the console log is stored.
                set +x
                set -eu

                REMOTE_USER="${SSH_USER:-$VPS_USER}"
                SSH="ssh -i $SSH_KEY -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 $REMOTE_USER@$VPS_HOST"

                # Verified before the secret is read, so an SSH problem never
                # leaves a fetched secret sitting in the agent's memory with
                # nowhere to go.
                $SSH 'echo "connected as $(whoami)@$(hostname)"' || {
                  echo "ERROR: cannot reach $REMOTE_USER@$VPS_HOST over SSH."
                  echo "Read the ssh error printed just above this line:"
                  echo "  'error in libcrypto'        malformed private key in the Jenkins"
                  echo "                              credential (usually CRLF line endings)."
                  echo "  'Permission denied'         public key missing from ~/.ssh/authorized_keys"
                  echo "                              for user $REMOTE_USER on the VPS."
                  echo "  'Connection timed out'      this agent's IP is not allowed on port 22."
                  exit 1; }

                echo "Reading $SSM_PARAM from SSM..."
                # Held in a shell variable, not a file. Nothing to leak from the
                # workspace, nothing for cleanWs to miss.
                SECRET_JSON=$(aws ssm get-parameter \
                                --name "$SSM_PARAM" \
                                --with-decryption \
                                --region "$AWS_DEFAULT_REGION" \
                                --query Parameter.Value \
                                --output text) || {
                  echo "ERROR: could not read $SSM_PARAM."
                  echo "       Check ssm:GetParameter on the parameter AND kms:Decrypt"
                  echo "       on the key - a missing kms:Decrypt gives an AccessDenied"
                  echo "       that does not mention KMS."
                  exit 1; }

                echo "$SECRET_JSON" | jq -e 'type == "object"' >/dev/null 2>&1 || {
                  echo "ERROR: $SSM_PARAM is not a flat JSON object."
                  exit 1; }

                # A partial or wrong parameter must stop here. The alternative is
                # a container that starts, then fails on its first DB call.
                for K in $REQUIRED_KEYS; do
                  echo "$SECRET_JSON" | jq -e --arg k "$K" 'has($k) and (.[$k] | tostring | length > 0)' >/dev/null 2>&1 || {
                    echo "ERROR: required key '$K' is missing or empty in $SSM_PARAM"
                    exit 1; }
                done

                KEY_COUNT=$(echo "$SECRET_JSON" | jq -r 'keys | length')
                echo "Writing $KEY_COUNT keys to $COMPOSE_DIR/$ENV_FILE ..."

                # umask sets the mode at creation; the explicit chmod covers the
                # case where the file already exists, since `cat >` truncates but
                # keeps the old permissions.
                echo "$SECRET_JSON" \
                  | jq -r 'to_entries | .[] | "\\(.key)=\\(.value)"' \
                  | $SSH "umask 077 && cat > $COMPOSE_DIR/$ENV_FILE && chmod 600 $COMPOSE_DIR/$ENV_FILE"

                # Confirm from the far end. Key names only — never the values.
                echo "Landed on the VPS:"
                $SSH "ls -l $COMPOSE_DIR/$ENV_FILE && cut -d= -f1 $COMPOSE_DIR/$ENV_FILE | sed 's/^/  - /'"

                REMOTE_COUNT=$($SSH "wc -l < $COMPOSE_DIR/$ENV_FILE" | tr -d ' ')
                if [ "$REMOTE_COUNT" != "$KEY_COUNT" ]; then
                  echo "ERROR: wrote $KEY_COUNT keys but the VPS has $REMOTE_COUNT lines."
                  echo "       A value probably contains a newline. Fix it in SSM."
                  exit 1
                fi

                echo "Secrets synced."
              '''
            }
          }
        }
      }

      // ── STEP 6: Deploy to the VPS ─────────────────────────────────────
      //
      // The tag deployed is the immutable :main-<sha> built by this run, so
      // /opt/paves/.env always names the exact commit that is serving traffic.
      //
      // Only this service's tag line is touched, so a frontend pipeline running
      // at the same time cannot clobber it.
      stage('Deploy to VPS') {
        when {
          environment name: 'DEPLOY_ENABLED', value: 'true'
        }
        steps {
          withCredentials([
            [
              $class:            'AmazonWebServicesCredentialsBinding',
              credentialsId:     awsCredsId,
              accessKeyVariable: 'AWS_ACCESS_KEY_ID',
              secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
            ],
            sshUserPrivateKey(
              credentialsId:    sshCredsId,
              keyFileVariable:  'SSH_KEY',
              usernameVariable: 'SSH_USER'
            )
          ]) {
            script {
              sh '''
                set -eu

                REMOTE_USER="${SSH_USER:-$VPS_USER}"
                # IdentitiesOnly stops ssh offering the agent's other keys first
                # and tripping the server's MaxAuthTries.
                SSH="ssh -i $SSH_KEY -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -o ServerAliveInterval=15 $REMOTE_USER@$VPS_HOST"

                echo "=== 1/6  SSH authentication =========================="
                # Checked on its own, because every later failure would otherwise
                # be reported as whatever that step was looking for.
                $SSH 'echo "connected as $(whoami)@$(hostname)"' || {
                  echo "ERROR: cannot reach $REMOTE_USER@$VPS_HOST over SSH."
                  echo "Read the ssh error printed just above this line:"
                  echo "  'error in libcrypto'        the private key in the Jenkins credential"
                  echo "                              is malformed - usually CRLF line endings or"
                  echo "                              a broken paste. Re-create the credential,"
                  echo "                              generating the key on a Linux host."
                  echo "  'Permission denied'         the matching public key is not in"
                  echo "                              ~/.ssh/authorized_keys for user $REMOTE_USER"
                  echo "                              on the VPS (check it is deploy's file, not root's)."
                  echo "  'Connection timed out'      this agent's IP is not allowed on port 22."
                  echo "                              Add it to the Hostinger VPS firewall."
                  exit 1; }

                echo "=== 2/6  Preflight ==================================="
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
                $SSH "test -s $COMPOSE_DIR/$ENV_FILE" || {
                  echo "ERROR: $COMPOSE_DIR/$ENV_FILE is missing or empty."
                  echo "       The Sync Secrets stage should have written it."
                  exit 1; }

                # Remembered so a failed rollout can be put back.
                PREV_TAG=$($SSH "grep '^$TAG_KEY=' $COMPOSE_DIR/.env | cut -d= -f2-")
                echo "Currently deployed $TAG_KEY = ${PREV_TAG:-<empty>}"
                echo "Rolling out        $TAG_KEY = $IMAGE_TAG"

                echo "=== 3/6  Injecting a short-lived ECR token ============"
                # Piped, so the token is never an argument and never reaches the
                # console log. Valid 12h, but only used in the next 30 seconds.
                aws ecr get-login-password --region "$AWS_DEFAULT_REGION" \
                  | $SSH "docker login --username AWS --password-stdin $ECR_REGISTRY" \
                  || { echo "ERROR: docker login failed on the VPS"; exit 1; }

                echo "=== 4/6  Pulling $IMAGE_TAG ==========================="
                # Pulled by full reference, NOT via compose: .env still names the
                # previous tag at this point, so `compose pull` would fetch the
                # old image. Pulling first also means the tag bump in the next
                # step is followed by an immediate start, with no download in
                # between during which .env names an image that is not present.
                $SSH "docker pull $FULL_IMAGE" || {
                  echo "ERROR: pull failed on the VPS."
                  echo "  'not authorized to perform: ecr:BatchGetImage'"
                  echo "        the IAM identity behind $ECR_REGISTRY can push but not pull."
                  echo "        Add ecr:BatchGetImage, ecr:GetDownloadUrlForLayer and"
                  echo "        ecr:DescribeImages for these repositories."
                  echo "  'manifest unknown'"
                  echo "        the tag is not in ECR - did the push stage really succeed?"
                  echo "  a timeout or DNS error"
                  echo "        the VPS cannot reach ECR; check egress from the VPS."
                  $SSH "docker logout $ECR_REGISTRY" >/dev/null 2>&1 || true
                  exit 1; }

                echo "=== 5/6  Recreating container ========================="
                # The image is already local, so this is a tag bump and a restart
                # with no network in the path.
                $SSH "set -eu
                      cd $COMPOSE_DIR
                      sed -i 's|^$TAG_KEY=.*|$TAG_KEY=$IMAGE_TAG|' .env
                      grep '^$TAG_KEY=' .env
                      docker compose up -d $COMPOSE_SERVICE"

                echo "=== 6/6  Waiting for $CONTAINER_NAME ================="
                # JVM start plus Hikari pool init on 2 shared vCPU, while other
                # containers are also running: 3 minutes is not generous here.
                #
                # Reads the image's HEALTHCHECK when it has one, and falls back
                # to probing the health endpoint directly when it does not.
                # The loop runs on the VPS in one connection rather than one SSH
                # round trip per poll.
                if $SSH "CN=$CONTAINER_NAME; PORT=$CONTAINER_PORT; HP=$HEALTH_PATH; "'
                      HAS_HC=$(docker inspect -f "{{if .State.Health}}yes{{else}}no{{end}}" "$CN" 2>/dev/null || echo no)
                      echo "healthcheck in image: $HAS_HC"
                      for i in $(seq 1 60); do
                        RUNNING=$(docker inspect -f "{{.State.Running}}" "$CN" 2>/dev/null || echo false)
                        if [ "$RUNNING" != "true" ]; then
                          echo "container is not running (exited or never started)"
                          exit 2
                        fi
                        if [ "$HAS_HC" = "yes" ]; then
                          S=$(docker inspect -f "{{.State.Health.Status}}" "$CN" 2>/dev/null || echo missing)
                          case "$S" in
                            healthy)   echo "healthy after $((i*3))s"; exit 0 ;;
                            unhealthy) echo "reported UNHEALTHY";      exit 2 ;;
                          esac
                        else
                          if docker exec "$CN" wget -q -O - "http://127.0.0.1:${PORT}${HP}" >/dev/null 2>&1; then
                            echo "health endpoint responded after $((i*3))s"; exit 0
                          fi
                        fi
                        sleep 3
                      done
                      echo "not healthy after 180s"; exit 1'
                then
                  echo "Deployed: $FULL_IMAGE"
                  $SSH "docker logout $ECR_REGISTRY" >/dev/null 2>&1 || true
                else
                  echo "--------------------------------------------------------"
                  echo "ERROR: $CONTAINER_NAME did not become healthy."
                  echo "Last 80 log lines from the VPS:"
                  $SSH "docker logs --tail 80 $CONTAINER_NAME 2>&1" || true
                  echo "--------------------------------------------------------"
                  echo "Common causes, in rough order of likelihood:"
                  echo "  - Aiven MySQL IP allowlist does not include $VPS_HOST"
                  echo "  - a key in $SSM_PARAM is wrong (the sync only checks presence)"
                  echo "  - health path $HEALTH_PATH is not what this service exposes"
                  echo "  - the JVM needed longer than 180s on 2 vCPU"
                  echo "--------------------------------------------------------"

                  if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$IMAGE_TAG" ] && [ "$PREV_TAG" != "bootstrap" ]; then
                    echo "Rolling back to $PREV_TAG ..."
                    # NOTE: .env.api is NOT rolled back. If this deploy also
                    # changed the secret shape, the previous image gets the new
                    # config. That is usually what you want; if it is not, fix
                    # SSM and redeploy rather than relying on the rollback.
                    $SSH "set -eu
                          cd $COMPOSE_DIR
                          sed -i 's|^$TAG_KEY=.*|$TAG_KEY=$PREV_TAG|' .env
                          docker compose up -d $COMPOSE_SERVICE" || echo "WARNING: rollback command failed"
                    echo "Rolled back to $PREV_TAG."
                  else
                    echo "No usable previous tag ($PREV_TAG) - leaving the new one in place."
                    echo "The API is DOWN. Investigate before retrying."
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

          // Remove the tag this build produced from the local daemon
          sh "docker rmi ${env.FULL_IMAGE ?: ''} 2>/dev/null || true"
          sh "docker image prune -f || true"
          // Prune build cache but keep ~3GB, so the next build still hits the
          // maven-dependency layer this Dockerfile is structured for.
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
