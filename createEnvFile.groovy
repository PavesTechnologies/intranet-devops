/**
 * createEnvFile.groovy
 *
 * Pulls a JSON secret out of AWS Secrets Manager and writes it to .env in the
 * current workspace, one KEY=value per line.
 *
 * The caller must already be inside an AWS credentials binding — this step
 * shells out to `aws` and does not bind credentials itself.
 *
 * Requires `jq` on the agent.
 *
 * Usage:
 *   createEnvFile('careers-frontend-env')
 *   createEnvFile('careers-frontend-env', 'ap-south-1')
 */

def call(String secretName, String region = 'ap-south-1') {

  if (!secretName) { error 'createEnvFile: secretName is required' }

  withEnv(["SECRET_NAME=${secretName}", "SECRET_REGION=${region}"]) {
    sh '''
      set -eu

      command -v jq >/dev/null 2>&1 || {
        echo "ERROR: jq is not installed on this agent"; exit 1; }

      # Fetched into a variable first rather than piped straight into jq.
      # In `aws ... | jq ...` the pipeline reports jq's exit code, so a failed
      # or unauthorised fetch would look successful and leave an empty .env —
      # which for a frontend means an image built with blank NEXT_PUBLIC_*
      # values baked in, undetectable until someone loads the page.
      SECRET_JSON=$(aws secretsmanager get-secret-value \
        --secret-id "$SECRET_NAME" \
        --region "$SECRET_REGION" \
        --query SecretString \
        --output text)

      # -e makes jq fail on null/false output, e.g. a secret that is not an object.
      printf '%s' "$SECRET_JSON" | jq -e -r 'to_entries|map("\\(.key)=\\(.value)")|.[]' > .env
      chmod 600 .env

      test -s .env || { echo "ERROR: secret $SECRET_NAME produced an empty .env"; exit 1; }

      echo "Converted AWS secret JSON -> .env ($(grep -c . .env) keys)."
    '''
  }
}
