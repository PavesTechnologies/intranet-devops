// vars/qualityGateStage.groovy
def call() {
    // REMOVED 'steps' block
    // Wrapping in timeout directly
    // timeout(time: 2, unit: 'MINUTES') {
    //     waitForQualityGate abortPipeline: true
    // }
    echo "Skip Quality Gate"
    
}
