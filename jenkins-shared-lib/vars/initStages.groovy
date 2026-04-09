def call(Map config) {

    stage('Load Environment') {
        steps {
            loadEnv(config.envSecret)
        }
    }

    stage('Checkout') {
        steps {
            checkout scm
        }
    }

    stage('Print Branch Info') {
        steps {
            script {
                def branch = env.CHANGE_BRANCH ?: env.BRANCH_NAME
                def target = env.CHANGE_TARGET ?: 'N/A'

                echo "Branch: ${branch}"
                echo "Target Branch: ${target}"
            }
        }
    }
}
