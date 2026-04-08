def call(String secretName, String region = 'ap-south-1') {

    sh """
    aws secretsmanager get-secret-value \
        --secret-id ${secretName} \
        --region ${region} \
        --query SecretString \
        --output text | jq -r 'to_entries|map("\\(.key)=\\(.value)")|.[]' > .env
    """

    echo "Converted AWS secret JSON → .env file"
}
