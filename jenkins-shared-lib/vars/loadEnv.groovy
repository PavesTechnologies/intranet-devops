def call(String secretName, String region = 'ap-south-1') {

    sh """
    aws secretsmanager get-secret-value \
        --secret-id ${secretName} \
        --region ${region} \
        --query SecretString \
        --output text > .env
    """

    echo "Loaded environment from AWS Secrets Manager"
}
