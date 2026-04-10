import groovy.json.JsonSlurperClassic

def call(String secretName, String region = 'ap-south-1') {
    echo "Fetching secret: ${secretName}"
    
    // Fetch the raw string from AWS
    def rawJson = sh(
        script: "aws secretsmanager get-secret-value --secret-id ${secretName} --region ${region} --query SecretString --output text",
        returnStdout: true
    ).trim()

    if (!rawJson || rawJson == "null") {
        error "Failed to fetch secret or secret is empty: ${secretName}"
    }

    // Use JsonSlurperClassic for Jenkins Pipeline compatibility
    // We use @NonCPS if logic gets complex, but for a simple parse this is fine
    def slurper = new JsonSlurperClassic()
    def configMap = slurper.parseText(rawJson)
    
    return configMap
}
