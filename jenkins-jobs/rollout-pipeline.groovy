def activeApp
def activeAppVersion
def deploymentName = 'blue'

pipeline {
    agent {
        label 'maven'
    }

    stages {
        stage('Rollout Service') {
            steps {
                dir() {
                    script {
                        SERVICE_VERSION_QUERY_RESULT = sh (
                            script: "oc get svc -n appuat -l app=${serviceName} -o jsonpath='{range .items[*].metadata}{.name}:{.labels.version}{end}'",
                            returnStdout: true
                        ).trim()

                        def removingAppAndVersion = SERVICE_VERSION_QUERY_RESULT.split("\n").find { !it.contains(serviceVersion) }
                        def removing = removingAppAndVersion.split(":")
                        def removingVersion = removing[1]
                        if (removing[0] == "${serviceName}-blue-service") {
                            deploymentName = 'green'
                        }

                        sh "oc set route-backends ${serviceName}-route ${serviceName}-${deploymentName}-service=100"
                        sh "oc delete all -n appuat -l app=${serviceName} -l version=${removingVersion}"
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                echo "FINISHED!!!"
            }
        }
    }
}