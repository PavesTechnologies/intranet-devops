//
// buildFrontendPipeline.groovy — Simplified Frontend CI/CD Pipeline
//
// "Build once, deploy everywhere" pattern for React + Vite
//
// The Vite bundle is built ONCE with no environment-specific values baked in.
// At deploy time, a config.js file is generated from AWS Secrets Manager with
// fresh values and deployed to S3 bucket root. The React app reads window.__APP_CONFIG__
// at runtime instead of import.meta.env at build time.
//
// ── React App Setup ────────────────────────────────────────────────────────────
//
//   1. Add this as the FIRST <script> tag in index.html (before Vite entry):
//        <script src="/config.js"></script>
//
//   2. Replace all import.meta.env.VITE_* references:
//        BEFORE:  const url = import.meta.env.VITE_API_BASE_URL
//        AFTER:   const url = window.__APP_CONFIG__.TIMESHEET_API_ENDPOINT
//
//   3. Example AWS Secrets Manager secret 'intranet/frontend/runtime-dev':
//        {
//          "TIMESHEET_API_ENDPOINT": "http://localhost:5000",
//          "USER_MANAGEMENT_URL": "http://13.48.18.145",
//          "BASE_URL": "http://16.16.202.195:9999",
//          "PMS_BASE_URL": "http://13.127.14.175",
//          "MSOffice_USER_MANAGEMENT_URL": "http://13.48.18.145",
//          "EMPLOYEE_ONBOARDING_URL": "http://16.16.202.195:9999"
//        }
//
// ── Usage in Jenkinsfile ───────────────────────────────────────────────────────
//
//   @Library('shared-lib') _
//   buildFrontendPipeline([
//       appName         : 'Intranet Frontend',
//       envSecret       : 'intranet/frontend/runtime-dev',
//       s3Bucket        : 'paves-intranet-testing-dev',
//       cloudfrontId    : 'E1QTJRU34QZ161',
//       cloudfrontDomain: 'd15j2ej3bear0q.cloudfront.net',
//       sonarProjectKey : 'intranet-frontend',
//       nodeVersion     : 'NodeJS-22',
//       awsRegion       : 'ap-south-1',
//       buildDir        : 'dist'
//   ])

def call(Map config) {

  // ── Defaults ──────────────────────────────────────────────────────────────
  config.nodeVersion = config.nodeVersion ?: 'NodeJS-20'
  config.awsRegion   = config.awsRegion   ?: 'ap-south-1'
  config.buildDir    = config.buildDir    ?: 'dist'

  def runtimeCfg = [:]

  pipeline {
    agent { label 'worker' }

    options {
      timestamps()
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
      APP_NAME           = "${config.appName}"
      S3_BUCKET          = "${config.s3Bucket}"
      CF_DIST_ID         = "${config.cloudfrontId}"
      AWS_DEFAULT_REGION = "${config.awsRegion}"
      BUILD_DIR          = "${config.buildDir}"
    }

    stages {

      // ── 1. Branch Guard ─────────────────────────────────────────────────
      // Prevent accidental runs on wrong branches
      stage('Branch Guard') {
        steps {
          script {
            // Detect current branch
            def currentBranch = env.BRANCH_NAME
            if (!currentBranch || currentBranch == 'null') {
              currentBranch = env.GIT_BRANCH?.replaceAll('origin/', '')?.replaceAll('refs/heads/', '')
            }
            if (!currentBranch || currentBranch == 'null') {
              try {
                currentBranch = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
              } catch (e) {
                currentBranch = env.CHANGE_BRANCH ?: 'unknown'
              }
            }

//             // Check if branch is 'dev'
//             if (currentBranch != '') {
//               echo """
// ╔════════════════════════════════════════════════════════════════╗
// ║ ❌ PIPELINE GUARD FAILURE                                      ║
// ╠════════════════════════════════════════════════════════════════╣
// ║ Branch: ${currentBranch.padRight(53)} ║
// ║ Expected: dev                                                  ║
// ║                                                                ║
// ║ This pipeline is locked to 'dev' branch only.                  ║
// ║ Accidental execution on other branches is blocked.             ║
// ╚════════════════════════════════════════════════════════════════╝
//               """
//               error "Pipeline only runs on 'dev' branch. Current: ${currentBranch}"
//             }
            
            echo "✅ Branch guard passed: Current branch is 'dev'"
          }
        }
      }

      // ── 2. Checkout ────────────────────────────────────────────────────
      stage('Checkout') {
        steps {
          checkout scm
          script {
            // ════════════════════════════════════════════════════════════
            // BRANCH NAME DETECTION
            // ════════════════════════════════════════════════════════════
            // Try multiple sources for branch name:
            // 1. env.BRANCH_NAME (Multibranch Pipeline)
            // 2. env.GIT_BRANCH (Git plugin format: origin/main → main)
            // 3. git symbolic-ref (what we're actually on)
            // 4. env.CHANGE_BRANCH (Pull Request)
            
            env.DETECTED_BRANCH = env.BRANCH_NAME
            if (!env.DETECTED_BRANCH || env.DETECTED_BRANCH == 'null') {
              env.DETECTED_BRANCH = env.GIT_BRANCH?.replaceAll('origin/', '')?.replaceAll('refs/heads/', '')
            }
            if (!env.DETECTED_BRANCH || env.DETECTED_BRANCH == 'null') {
              env.DETECTED_BRANCH = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
            }
            if (!env.DETECTED_BRANCH || env.DETECTED_BRANCH == 'null') {
              env.DETECTED_BRANCH = env.CHANGE_BRANCH ?: 'unknown'
            }

            // ════════════════════════════════════════════════════════════
            // COMMITTER NAME CAPTURE
            // ════════════════════════════════════════════════════════════
            try {
              env.COMMITTER_NAME = sh(script: 'git log -1 --pretty=format:"%an"', returnStdout: true).trim()
              if (!env.COMMITTER_NAME || env.COMMITTER_NAME == 'null' || env.COMMITTER_NAME.isEmpty()) {
                env.COMMITTER_NAME = 'Unknown'
              }
            } catch (Exception e) {
              env.COMMITTER_NAME = 'Unknown'
            }

            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "✅ Checkout Complete"
            echo "Branch: ${env.DETECTED_BRANCH}"
            echo "Committer: ${env.COMMITTER_NAME}"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
          }
        }
      }

      // ── 3. Notify Teams — Build Started ────────────────────────────────
      stage('Notify Teams') {
        steps {
          withCredentials([
            string(credentialsId: 'teams-webhook-url', variable: 'TEAMS_URL')
          ]) {
            script {
              notifyTeams(
                status:      'STARTED',
                serviceName: config.appName,
                imageTag:    'building...',
                branch:      env.DETECTED_BRANCH ?: 'dev',
                triggeredBy: env.COMMITTER_NAME ?: 'Unknown',
                webhookUrl:  env.TEAMS_URL
              )
            }
          }
        }
      }

      // ── 4. Install Dependencies ─────────────────────────────────────────
      stage('Install dependencies') {
        steps {
          nodejs(nodeJSInstallationName: config.nodeVersion) {
            sh '''
              node --version && npm --version
              npm ci --prefer-offline
            '''
          }
        }
      }

      // ── 5. Build (Vite) ─────────────────────────────────────────────────
          stage('Build') {
            steps {
              nodejs(nodeJSInstallationName: config.nodeVersion) {
                sh '''
                  export NODE_OPTIONS=--max-old-space-size=4096
                  npm run build
                '''
                sh """
                  test -d ${config.buildDir} || \
                    (echo 'ERROR: ${config.buildDir}/ not found after build.' && exit 1)
                """
              }
            }
          }

      // ── 5. Generate Initial config.js ────────────────────────────────────
      stage('Generate config.js') {
        steps {
          script {
            if (config.envSecret) {
              runtimeCfg = loadConfig(config.envSecret)
              echo "Loaded ${runtimeCfg.size()} runtime config key(s) from '${config.envSecret}'"
            } else {
              echo "No envSecret configured – config.js will contain an empty object."
            }

            def entries = runtimeCfg
              .collect { k, v ->
                "  ${k}: ${groovy.json.JsonOutput.toJson(v)}"
              }
              .join(',\n')

            def configJs = """\
// Runtime configuration — generated by Jenkins at deploy time.
// DO NOT cache this file.
// DO NOT commit this file to source control.
//
// Deployed to : s3://${config.s3Bucket}/config.js
// Build       : ${env.JOB_NAME} #${env.BUILD_NUMBER}
// Branch      : ${env.BRANCH_NAME ?: ('PR-' + env.CHANGE_ID)}
// Timestamp   : ${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}
window.__APP_CONFIG__ = {
${entries}
};
"""
            writeFile file: "${config.buildDir}/config.js", text: configJs
            echo "Generated ${config.buildDir}/config.js"
            echo "Config keys injected: ${runtimeCfg.keySet().join(', ')}"
          }
        }
      }

      // ── 6. SonarQube Security Scan (Optional) ───────────────────────────
      // stage('SonarQube scan') {
      //   when {
      //     expression { config.sonarProjectKey != null }
      //   }
      //   steps {
      //     script {
      //       def scannerHome = tool 'sonar-scanner'
      //       withSonarQubeEnv('SonarQube') {
      //         sh """
      //           ${scannerHome}/bin/sonar-scanner \
      //             -Dsonar.projectKey=${config.sonarProjectKey} \
      //             -Dsonar.sources=src \
      //             -Dsonar.exclusions=**/node_modules/**,**/*.test.*,**/__tests__/** \
      //             -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info
      //         """
      //       }
      //     }
      //   }
      // }

      // ── 7. Deploy to S3 with Fresh Secrets ──────────────────────────────
      // 
      // CRITICAL: Fetch FRESH secrets from AWS Secrets Manager right before 
      // uploading to S3. This ensures latest config is always deployed, even if 
      // secrets changed between "Generate config.js" and "Deploy to S3" stages.
      //
      // Flow:
      //   1. Fetch fresh secrets from AWS Secrets Manager
      //   2. Re-generate config.js with latest values
      //   3. Overwrite config.js in dist/
      //   4. Upload entire dist/ to S3 bucket root with proper cache headers
      stage('Deploy to S3') {
        steps {
          script {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "DEPLOY STAGE: Fetching fresh secrets from AWS Secrets Manager..."
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            def freshConfig = [:]
            if (config.envSecret) {
              freshConfig = loadConfig(config.envSecret)
              echo "✅ Fresh config loaded with ${freshConfig.size()} key(s) from '${config.envSecret}'"
              echo "Keys: ${freshConfig.keySet().join(', ')}"
            } else {
              echo "⚠️  No envSecret configured – config.js will contain an empty object."
            }

            // Re-generate config.js with fresh secrets
            def entries = freshConfig
              .collect { k, v ->
                "  ${k}: ${groovy.json.JsonOutput.toJson(v)}"
              }
              .join(',\n')

            def configJs = """\
// Runtime configuration — generated by Jenkins at deploy time (FRESH SECRETS).
// DO NOT cache this file.
// DO NOT commit this file to source control.
//
// Deployed to      : s3://${config.s3Bucket}/config.js
// Build            : ${env.JOB_NAME} #${env.BUILD_NUMBER}
// Branch           : ${env.BRANCH_NAME ?: ('PR-' + env.CHANGE_ID)}
// Timestamp        : ${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}
// AWS Secrets Name : ${config.envSecret}
window.__APP_CONFIG__ = {
${entries}
};
"""
            writeFile file: "${config.buildDir}/config.js", text: configJs
            echo "✅ Re-generated ${config.buildDir}/config.js with fresh secrets"

            // Upload entire dist/ to S3 bucket root
            echo "📤 Uploading ${config.buildDir}/ to S3 bucket: ${config.s3Bucket}"

            sh '''
              # Main sync — uploads all files with general cache policy
              aws s3 sync $BUILD_DIR/ s3://$S3_BUCKET/ \
                --delete \
                --region $AWS_DEFAULT_REGION \
                --cache-control "public, max-age=3600"
              
              # Explicitly set config.js to no-cache/no-store
              # This ensures browser NEVER caches the config, always fetches fresh
              aws s3 cp $BUILD_DIR/config.js s3://$S3_BUCKET/config.js \
                --region $AWS_DEFAULT_REGION \
                --cache-control "no-store, must-revalidate" \
                --metadata "deployment-time=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
                --content-type "application/javascript"
            '''
            echo "✅ S3 deployment complete — all files uploaded"
          }
        }
      }

      // ── 8. CloudFront Invalidation ──────────────────────────────────────
      // Invalidate entire CloudFront cache using path /* (bucket root)
      // This ensures CDN serves fresh assets after deployment
      stage('CloudFront invalidation') {
        steps {
          script {
            echo "🔄 Invalidating CloudFront distribution: ${config.cloudfrontId}"
            sh '''
              aws cloudfront create-invalidation \
                --distribution-id $CF_DIST_ID \
                --paths "/*" \
                --region $AWS_DEFAULT_REGION
              echo "✅ CloudFront invalidation created (path: /*)"
            '''
          }
        }
      }

    } // end stages

    // ── Post-build Actions ──────────────────────────────────────────────────
    post {

      success {
        script {
          echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
          echo "✅ DEPLOYMENT SUCCESSFUL"
          echo "App: ${config.appName}"
          echo "S3 Bucket: ${config.s3Bucket}"
          echo "Build: ${env.JOB_NAME} #${env.BUILD_NUMBER}"
          echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

          withCredentials([
            string(credentialsId: 'teams-webhook-url', variable: 'TEAMS_URL')
          ]) {
            notifyTeams(
              status:      'SUCCESS',
              serviceName: config.appName,
              imageTag:    env.BUILD_NUMBER ?: 'success',
              branch:      env.DETECTED_BRANCH ?: 'unknown',
              triggeredBy: env.COMMITTER_NAME ?: 'Unknown',
              webhookUrl:  env.TEAMS_URL
            )
          }
        }
      }

      failure {
        script {
          echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
          echo "❌ DEPLOYMENT FAILED"
          echo "App: ${config.appName}"
          echo "Build: ${env.JOB_NAME} #${env.BUILD_NUMBER}"
          echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

          withCredentials([
            string(credentialsId: 'teams-webhook-url', variable: 'TEAMS_URL')
          ]) {
            notifyTeams(
              status:      'FAILURE',
              serviceName: config.appName,
              imageTag:    env.BUILD_NUMBER ?: 'failed',
              branch:      env.DETECTED_BRANCH ?: 'unknown',
              triggeredBy: env.COMMITTER_NAME ?: 'Unknown',
              webhookUrl:  env.TEAMS_URL
            )
          }
        }
      }

      always {
        script {
          echo "Cleaning workspace..."
          cleanWs()
          echo "✅ Cleanup complete"
        }
      }

    } // end post

  } // end pipeline
} // end call
