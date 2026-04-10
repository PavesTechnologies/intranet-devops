import groovy.json.JsonSlurperClassic

def call(String secretName, String region = 'ap-south-1') {
    echo "Fetching secret: ${secretName}"
    
    // We use -r (raw) to ensure we get the clean string content
    // We pipe to jq '.' to validate it's actual JSON before returning to Groovy
    def rawJson = sh(
        script: """
            aws secretsmanager get-secret-value \
                --secret-id ${secretName} \
                --region ${region} \
                --query SecretString \
                --output text | jq -c '.'
        """,
        returnStdout: true
    ).trim()

    // Debug: This will show in Jenkins logs if it's still failing
    // echo "Raw string received: ${rawJson}"

    if (!rawJson || rawJson == "null" || rawJson.startsWith("Error")) {
        error "Failed to fetch secret or invalid format: ${secretName}"
    }

    try {
        def slurper = new JsonSlurperClassic()
        return slurper.parseText(rawJson)
    } catch (Exception e) {
        echo "Failed to parse JSON. Content was: ${rawJson}"
        throw e
    }
}
