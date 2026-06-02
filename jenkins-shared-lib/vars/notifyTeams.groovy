/**
 * notifyTeams.groovy
 * Sends build notification to Microsoft Teams
 * Uses Office 365 Connector plugin
 */

def call(Map config) {
  def status      = config.status
  def serviceName = config.serviceName
  def imageTag    = config.imageTag    ?: 'unknown'
  def branch      = config.branch      ?: 'dev'
  def webhookUrl  = config.webhookUrl

  def color = status == 'SUCCESS' ? '00C853' :
              status == 'FAILURE' ? 'D50000' : '0288D1'

  def emoji = status == 'SUCCESS' ? '✅' :
              status == 'FAILURE' ? '❌' : '🚀'

  office365ConnectorSend(
    webhookUrl: webhookUrl,
    color:      color,
    status:     "${emoji} ${status}",
    message:    "${emoji} **${serviceName}** CD Pipeline — **${status}**",
    factDefinitions: [
      [name: 'Service',      template: serviceName],
      [name: 'Image Tag',    template: imageTag],
      [name: 'Branch',       template: branch],
      [name: 'Build Number', template: "#${env.BUILD_NUMBER}"],
      [name: 'Build URL',    template: "${env.BUILD_URL}"]
    ]
  )
}
