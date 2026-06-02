/**
 * notifyTeams.groovy
 * Sends clean Adaptive Card to Microsoft Teams via Power Automate webhook
 */

def call(Map config) {
  def status      = config.status
  def serviceName = config.serviceName.toUpperCase()
  def imageTag    = config.imageTag    ?: 'unknown'
  def branch      = config.branch      ?: 'dev'
  def webhookUrl  = config.webhookUrl
  def buildUrl    = env.BUILD_URL      ?: ''
  def buildNumber = env.BUILD_NUMBER   ?: ''

  def emoji     = status == 'SUCCESS' ? '✅' :
                  status == 'FAILURE' ? '❌' : '🚀'

  def color     = status == 'SUCCESS' ? 'good' :
                  status == 'FAILURE' ? 'attention' : 'accent'

  def title     = status == 'SUCCESS' ? 'Deployment Successful' :
                  status == 'FAILURE' ? 'Deployment Failed' : 'Deployment Started'

  def payload = """
{
  "type": "message",
  "attachments": [
    {
      "contentType": "application/vnd.microsoft.card.adaptive",
      "content": {
        "type": "AdaptiveCard",
        "version": "1.4",
        "body": [
          {
            "type": "Container",
            "style": "${color}",
            "bleed": true,
            "items": [
              {
                "type": "ColumnSet",
                "columns": [
                  {
                    "type": "Column",
                    "width": "auto",
                    "items": [
                      {
                        "type": "TextBlock",
                        "text": "${emoji}",
                        "size": "ExtraLarge"
                      }
                    ]
                  },
                  {
                    "type": "Column",
                    "width": "stretch",
                    "items": [
                      {
                        "type": "TextBlock",
                        "text": "${title}",
                        "weight": "Bolder",
                        "size": "Large",
                        "color": "Light"
                      },
                      {
                        "type": "TextBlock",
                        "text": "Paves Technologies — Intranet Platform",
                        "size": "Small",
                        "color": "Light",
                        "spacing": "None"
                      }
                    ]
                  }
                ]
              }
            ]
          },
          {
            "type": "Container",
            "spacing": "Medium",
            "items": [
              {
                "type": "ColumnSet",
                "columns": [
                  {
                    "type": "Column",
                    "width": "stretch",
                    "items": [
                      {
                        "type": "TextBlock",
                        "text": "SERVICE",
                        "size": "Small",
                        "weight": "Bolder",
                        "color": "Accent",
                        "spacing": "None"
                      },
                      {
                        "type": "TextBlock",
                        "text": "${serviceName}",
                        "size": "Medium",
                        "weight": "Bolder",
                        "spacing": "None"
                      }
                    ]
                  },
                  {
                    "type": "Column",
                    "width": "stretch",
                    "items": [
                      {
                        "type": "TextBlock",
                        "text": "BRANCH",
                        "size": "Small",
                        "weight": "Bolder",
                        "color": "Accent",
                        "spacing": "None"
                      },
                      {
                        "type": "TextBlock",
                        "text": "${branch}",
                        "size": "Medium",
                        "weight": "Bolder",
                        "spacing": "None"
                      }
                    ]
                  },
                  {
                    "type": "Column",
                    "width": "stretch",
                    "items": [
                      {
                        "type": "TextBlock",
                        "text": "BUILD",
                        "size": "Small",
                        "weight": "Bolder",
                        "color": "Accent",
                        "spacing": "None"
                      },
                      {
                        "type": "TextBlock",
                        "text": "#${buildNumber}",
                        "size": "Medium",
                        "weight": "Bolder",
                        "spacing": "None"
                      }
                    ]
                  }
                ]
              },
              {
                "type": "Container",
                "style": "emphasis",
                "spacing": "Medium",
                "items": [
                  {
                    "type": "TextBlock",
                    "text": "IMAGE TAG",
                    "size": "Small",
                    "weight": "Bolder",
                    "color": "Accent",
                    "spacing": "None"
                  },
                  {
                    "type": "TextBlock",
                    "text": "${imageTag}",
                    "fontType": "Monospace",
                    "size": "Small",
                    "spacing": "None",
                    "wrap": true
                  }
                ]
              }
            ]
          }
        ],
        "actions": [
          {
            "type": "Action.OpenUrl",
            "title": "View Build Logs",
            "url": "${buildUrl}",
            "style": "positive"
          }
        ]
      }
    }
  ]
}
"""

  sh """
    curl -s -X POST \\
      -H 'Content-Type: application/json' \\
      -d '${payload.replaceAll("'", "'\\''")}' \\
      '${webhookUrl}' || true
  """
}
