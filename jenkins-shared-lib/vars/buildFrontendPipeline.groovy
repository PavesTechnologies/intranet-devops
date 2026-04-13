// jenkins-shared-lib/vars/buildFrontendPipeline.groovy
//
// "Build once, deploy everywhere" pattern.
//
// The Vite bundle is built ONCE with no environment-specific values baked in.
// At deploy time, a config.js file is generated from AWS Secrets Manager and
// uploaded alongside the static bundle. The React app reads window.__APP_CONFIG__
// at runtime instead of import.meta.env at build time.
//
// ── One-time migration in your React app ─────────────────────────────────────
//
//   1. Add this as the FIRST <script> tag in index.html (before the Vite entry):
//        <script src="/config.js"></script>
//
//   2. Replace all import.meta.env.VITE_* references:
//        BEFORE:  const url = import.meta.env.VITE_API_BASE_URL
//        AFTER:   const url = window.__APP_CONFIG__.apiBaseUrl
//
//   3. Optional — create src/config.ts for a typed accessor:
//        interface AppConfig { apiBaseUrl: string; featureFlags: boolean }
//        export const appConfig = (window as any).__APP_CONFIG__ as AppConfig
//
// ── Config map keys (pass from your Jenkinsfile) ──────────────────────────────
//   appName         (String, required)  – label used in logs and notifications
//   envSecret       (String, required)  – Secrets Manager secret ID; must be a
//                                         JSON object of runtime config key/values
//   s3Bucket        (String, required)  – bucket name, no s3:// prefix
//   cloudfrontId    (String, required)  – CloudFront distribution ID
//   cloudfrontDomain(String, optional) – used to print PR preview URLs in logs
//   sonarProjectKey (String, optional) – set null/omit to skip the SonarQube stage
//   nodeVersion     (String, optional) – Jenkins Node.js tool name; default 'NodeJS-20'
//   awsRegion       (String, optional) – default 'ap-south-1'
//   buildDir        (String, optional) – Vite output dir; default 'dist'
//
// ── Branch → S3 prefix mapping ────────────────────────────────────────────────
//   main     → prod/
//   staging  → staging/
//   develop  → dev/
//   PR-*     → previews/PR-<CHANGE_ID>/

def call(Map config) {

    // ── Defaults ──────────────────────────────────────────────────────────────
    config.nodeVersion = config.nodeVersion ?: 'NodeJS-20'
    config.awsRegion   = config.awsRegion   ?: 'ap-south-1'
    config.buildDir    = config.buildDir    ?: 'dist'

    // Resolved during 'Load environment' and referenced by later stages
    def s3Prefix   = ''
    def isPR       = false
    def runtimeCfg = [:]   // key/value pairs from Secrets Manager → written to config.js

    pipeline {
        agent any

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

            // ── 1. Load environment ───────────────────────────────────────────
            // Determines the target S3 prefix and loads runtime config values
            // from AWS Secrets Manager. Nothing is passed to the Vite build here —
            // the secrets are held in memory until the 'Generate config.js' stage.
            stage('Load environment') {
                steps {
                    script {
                        isPR = (env.CHANGE_ID != null && env.CHANGE_ID != '')

                        if (isPR) {
                            s3Prefix = "previews/PR-${env.CHANGE_ID}/"
                            echo "Pipeline mode : PR Preview  (PR-${env.CHANGE_ID})"
                        } else {
                            switch (env.BRANCH_NAME) {
                                case 'main':    s3Prefix = 'prod/';    break
                                case 'staging': s3Prefix = 'staging/'; break
                                case 'develop': s3Prefix = 'dev/';     break
                                default:
                                    error("Branch '${env.BRANCH_NAME}' is not mapped to an " +
                                          "environment. Add it to the switch block or restrict " +
                                          "this pipeline to main / staging / develop.")
                            }
                            echo "Pipeline mode : Branch deploy → s3://${config.s3Bucket}/${s3Prefix}"
                        }

                        // loadEnv() (vars/loadEnv.groovy) fetches the secret from
                        // AWS Secrets Manager and returns a Map<String, String>.
                        // Values are NOT forwarded to the Vite build process.
                        if (config.envSecret) {
                            runtimeCfg = loadConfig(config.envSecret)
                            echo "Loaded ${runtimeCfg.size()} runtime config key(s) from '${config.envSecret}'"
                        } else {
                            echo "No envSecret configured – config.js will contain an empty object."
                        }
                    }
                }
            }

            // ── 2. Checkout ───────────────────────────────────────────────────
            stage('Checkout') {
                steps {
                    checkout scm
                    echo "Checked out: ${env.BRANCH_NAME ?: env.CHANGE_BRANCH}"
                }
            }

            // ── 3. Install dependencies ───────────────────────────────────────
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

            

            stage('Build (clean)') {
                steps {
                    nodejs(nodeJSInstallationName: config.nodeVersion) {
                        sh "npm run build"
                        sh """
                            test -d ${config.buildDir} || \
                                (echo 'ERROR: ${config.buildDir}/ not found after build.' && exit 1)
                        """
                    }
                }
            }

            stage('Security scan (SonarQube)') {
                when {
                    expression { config.sonarProjectKey != null }
                }
                steps {
                    script {
                        // This 'tool' command adds the scanner to the PATH for this block
                        def scannerHome = tool 'sonar-scanner' 
                        
                        withSonarQubeEnv('SonarQube') {
                            sh """
                                ${scannerHome}/bin/sonar-scanner \
                                  -Dsonar.projectKey=${config.sonarProjectKey} \
                                  -Dsonar.sources=src \
                                  -Dsonar.exclusions=**/node_modules/**,**/*.test.*,**/__tests__/** \
                                  -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info
                            """
                        }
                    }
                }
            }

                

            // ── 5. Generate config.js ─────────────────────────────────────────
            // This is the ONLY step that differs between environments.
            // It writes a tiny JS file into dist/ that the browser executes before
            // the Vite bundle, exposing window.__APP_CONFIG__ to the React app.
            //
            // The file is intentionally human-readable so ops engineers can curl
            // <cloudfront-domain>/config.js to verify what is deployed.
            stage('Generate config.js') {
                steps {
                    script {
                        // Serialise the Map to JS object literal entries.
                        // groovy.json.JsonOutput.toJson() handles quoting/escaping
                        // of string values so the output is valid JSON/JS.
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
// Deployed to : s3://${config.s3Bucket}/${s3Prefix}config.js
// Build       : ${env.JOB_NAME} #${env.BUILD_NUMBER}
// Branch      : ${env.BRANCH_NAME ?: ('PR-' + env.CHANGE_ID)}
// Timestamp   : ${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}
window.__APP_CONFIG__ = {
${entries}
};
"""
                        writeFile file: "${config.buildDir}/config.js", text: configJs

                        echo "Generated ${config.buildDir}/config.js"
                        // Log keys only — never log values to avoid leaking secrets
                        echo "Config keys injected: ${runtimeCfg.keySet().join(', ')}"
                    }
                }
            }

            // ── 6. Deploy to S3 ───────────────────────────────────────────────
            // Three separate sync passes — each file type needs a different
            // Cache-Control header to balance performance against freshness.
            //
            //   Pass 1 – hashed assets (main.a1b2c3.js, style.4d5e6f.css …)
            //     → immutable, 1 year: filenames change with every build, so
            //       the browser and CDN can cache them forever safely.
            //
            //   Pass 2 – index.html + JSON manifests
            //     → no-cache: must always be re-fetched so the browser learns
            //       the latest hashed filenames.
            //
            //   Pass 3 – config.js
            //     → no-store: environment-specific runtime values; must never
            //       be served from any cache layer, ever.
            stage('Deploy to S3') {
                steps {
                    script {
                        def s3Uri = "s3://${config.s3Bucket}/${s3Prefix}"
                        echo "Deploying '${config.buildDir}/' → '${s3Uri}'"

                        sh """
                            # ── Pass 1: Hashed static assets ──────────────────
                            aws s3 sync ${config.buildDir}/ ${s3Uri} \
                                --delete \
                                --region ${config.awsRegion} \
                                --cache-control "public, max-age=31536000, immutable" \
                                --exclude "index.html" \
                                --exclude "config.js" \
                                --exclude "*.json"

                            # ── Pass 2: index.html + JSON manifests ───────────
                            aws s3 sync ${config.buildDir}/ ${s3Uri} \
                                --region ${config.awsRegion} \
                                --cache-control "no-cache, no-store, must-revalidate" \
                                --exclude "*" \
                                --include "index.html" \
                                --include "*.json" \
                                --no-delete

                            # ── Pass 3: config.js ─────────────────────────────
                            # Explicit cp (not sync) so --cache-control and
                            # --content-type are applied precisely to this one file.
                            aws s3 cp ${config.buildDir}/config.js ${s3Uri}config.js \
                                --region ${config.awsRegion} \
                                --cache-control "no-store, must-revalidate" \
                                --content-type "application/javascript"
                        """
                    }
                }
            }

            // ── 7. CloudFront invalidation ────────────────────────────────────
            // Scoped to the deployed prefix (not a blanket /*) to stay within
            // the AWS free tier of 1,000 invalidation paths per month.
            // config.js is always listed as an explicit separate path to guarantee
            // it is purged immediately, even if no other assets changed.
            stage('CloudFront invalidation') {
                steps {
                    script {
                        def assetPath  = isPR
                            ? "/previews/PR-${env.CHANGE_ID}/*"
                            : "/${s3Prefix}*"
                        def configPath = isPR
                            ? "/previews/PR-${env.CHANGE_ID}/config.js"
                            : "/${s3Prefix}config.js"

                        echo "Invalidating CloudFront: ${assetPath}"

                        sh """
                            aws cloudfront create-invalidation \
                                --distribution-id ${config.cloudfrontId} \
                                --paths "${assetPath}" "${configPath}" \
                                --region ${config.awsRegion}
                        """
                    }
                }
            }

        } // end stages

        // ── Post-build actions ─────────────────────────────────────────────────
        post {

            success {
                script {
                    if (isPR && config.cloudfrontDomain) {
                        def previewUrl = "https://${config.cloudfrontDomain}/previews/PR-${env.CHANGE_ID}/"
                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                        echo " PR PREVIEW READY"
                        echo " ${previewUrl}"
                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                        // Uncomment to post the URL as a PR comment via the
                        // GitHub / Bitbucket Branch Source plugin:
                        // pullRequest.comment("Preview deployed: ${previewUrl}")
                    } else {
                        echo "Deployed → s3://${config.s3Bucket}/${s3Prefix}"
                    }
                }
            }

            failure {
                echo "Pipeline FAILED — ${env.JOB_NAME} #${env.BUILD_NUMBER}"
                // Add Slack / email notification here
            }

            always {
                cleanWs()
            }

        } // end post

    } // end pipeline
} // end call
