@NonCPS
def createOcParams(def params) {
    String paramsStr = ''
    params.each { key, val ->
        paramsStr += "--param=${key}=\"${val}\" "
    }
    return paramsStr
}

def activeApp
def activeAppVersion
def deploymentName = 'blue'

pipeline {
    agent {
        label 'maven'
    }

    stages {
        stage('Setting Up') {
            steps {
                script {
                    WORKSPACE = "/home/jenkins/workspace/${JOB_NAME}/${BUILD_NUMBER}"
                    sh "mkdir -p ${WORKSPACE}/configurations"
                    sh "mkdir -p ${WORKSPACE}/deploy"

                    dir ("${WORKSPACE}/configurations") {
                        checkout scm: [$class: 'GitSCM',
                            userRemoteConfigs: [[url: "https://github.com/athamsiramas/cicd-jenkins-ocp.git", credentialsId: 'git-login']],
                            branches: [[name: "main"]],
                            extensions: [
                                [$class: 'CloneOption', noTags: true, shallow: true]
                            ]
                        ]
                    }
                    
                    activeAppAndVersion = sh(returnStdout: true, script: "oc get svc -l app=${serviceName} -n appuat -o jsonpath='{range .items[*].metadata}{.name}:{.labels.version}{end}'").trim()
                    if (activeAppAndVersion) {
                        def active = activeAppAndVersion.split(":")
                        activeAppVersion = active[1]
                        if (deploymentStrategy != 'rollout' && active[0] == "${serviceName}-blue-service") {
                            deploymentName = 'green'
                        }
                    }
                }
            }
        }

        stage('Deploy Config') {
            when { expression { return fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/${environment}/configure.yaml") || fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/configure.yaml") }}
            steps {
                dir("${WORKSPACE}/deploy") {
                    script {
                        def binding = [:]
                        binding.SERVICE_NAME = serviceName
                        binding.SERVICE_VERSION = serviceVersion
                        binding.SERVICE_VERSION_DASH = serviceVersion.replaceAll("\\.", "-")
                        def paramsStr = createOcParams(binding)
                        if (fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/${environment}/configure.yaml")) {
                            sh "cp ${WORKSPACE}/configurations/openshift/${serviceName}/${environment}/configure.yaml configure.yaml"
                        } else if (fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/configure.yaml")) {
                            sh "cp ${WORKSPACE}/configurations/openshift/${serviceName}/configure.yaml configure.yaml"
                        }
                        
                        sh "oc process -f configure.yaml ${paramsStr} -n appuat | oc apply -n appuat -f -"
                    }
                }
            }
        }

        stage('Deploy Application') {
            steps {
                dir("${WORKSPACE}/deploy") {
                    script {
                        if (fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/${environment}/deployment.yaml")) {
                            sh "cp ${WORKSPACE}/configurations/openshift/${serviceName}/${environment}/deployment.yaml deployment.yaml"
                        } else if (fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/deployment.yaml")) {
                            sh "cp ${WORKSPACE}/configurations/openshift/${serviceName}/deployment.yaml deployment.yaml"
                        } else {
                            echo 'No deployment file found!'
                            sh 'exit 1'
                        }

                        def binding = [:]
                        binding.SERVICE_NAME = serviceName
                        binding.SERVICE_VERSION = serviceVersion
                        binding.SERVICE_VERSION_DASH = serviceVersion.replaceAll("\\.", "-")
                        binding.DEPLOYMENT_NAME = deploymentName
                        def paramsStr = createOcParams(binding)
                        
                        echo "Creating new deployment for ${serviceName}"
                        sh "oc process -f deployment.yaml ${paramsStr} -n appuat | oc apply -n appuat -f -"
                    }
                }
            }
        }

        stage('Configure Route') {
            steps {
                dir("${WORKSPACE}/deploy") {
                    script {
                        ROUTE_QUERY_RESULT = sh (
                            script: "oc get route -n appuat -l app=${serviceName} -o jsonpath='{range .items[*].metadata}{.name}{end}'",
                            returnStdout: true
                        ).trim()
                        if (!ROUTE_QUERY_RESULT) {
                            echo "Route doesn't exist. Creating new route for ${serviceName}"
                            sh "oc expose svc/${serviceName}-${deploymentName}-service -n appuat --name=${serviceName}-route -l app=${serviceName}"
                        }

                        if (deploymentStrategy == 'rollout') {
                            SERVICE_VERSION_QUERY_RESULT = sh (
                                script: "oc get cm -n appuat -l app=${serviceName} -o jsonpath='{range .items[*].metadata}{.labels.version}{end}'",
                                returnStdout: true
                            ).trim()

                            def removingVersion = SERVICE_VERSION_QUERY_RESULT.split("\n").find { it != (serviceVersion) }
                            if (removingVersion) {
                                sh "oc delete all -n appuat -l app=${serviceName} -l version=${removingVersion}"
                            }
                        }
                    }
                }
            }
        }

        stage('Verify Pod') {
            steps {
                dir("${WORKSPACE}/deploy") {
                    script {
                        sh "oc wait --for=condition=Ready pod -l app=${serviceName} -l version=${serviceVersion} -n appuat --timeout=120s"
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
