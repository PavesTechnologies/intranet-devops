def call(Map config) {
    // REMOVED 'steps' block - not allowed here
    stage('Load Environment') {
        loadEnv(config.envSecret)
    }

    stage('Checkout') {
        checkout scm
    }

    stage('Print Branch Info') {
        script {
            def branch = env.CHANGE_BRANCH ?: env.BRANCH_NAME
            def target = env.CHANGE_TARGET ?: 'N/A'
            echo "Branch: ${branch}"
            echo "Target Branch: ${target}"
        }
    }
}
