def call(String secretName, String region = 'ap-south-1') {
    // 1. Fetch the raw JSON string from AWS
    def secretJson = sh(
        script: "aws secretsmanager get-secret-value --secret-id ${secretName} --region ${region} --query SecretString --output text",
        returnStdout: true
    ).trim()

    // 2. Parse the JSON string into a Groovy Map
    def props = readJSON text: secretJson
    
    return props
}
