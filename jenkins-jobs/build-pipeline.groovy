@NonCPS
def createOcParams(def params) {
    String paramsStr = ''
    params.each { key, val ->
        paramsStr += "--param=${key}=\"${val}\" "
    }
    return paramsStr
}

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
                    sh "mkdir -p ${WORKSPACE}/source"

                    if (ref.contains('refs/heads/')) {
                        ref = ref.replace('refs/heads/', '')
                    }

                    dir ("${WORKSPACE}/source") {
                        checkout scm: [$class: 'GitSCM',
                            userRemoteConfigs: [[url: "${repo}", credentialsId: 'git-login']],
                            branches: [[name: "${ref}"]],
                            extensions: [
                                [$class: 'CloneOption', noTags: true, shallow: true]
                            ]
                        ]
                    }

                    dir ("${WORKSPACE}/configurations") {
                        checkout scm: [$class: 'GitSCM',
                            userRemoteConfigs: [[url: "https://github.com/athamsiramas/cicd-jenkins-ocp.git", credentialsId: 'git-login']],
                            branches: [[name: "main"]],
                            extensions: [
                                [$class: 'CloneOption', noTags: true, shallow: true]
                            ]
                        ]
                    }
                }
            }
        }

        stage('Unittest') {
            steps {
                script {
                    dir("${WORKSPACE}/source") {
                        serviceName = sh(
                            script: 'mvn org.apache.maven.plugins:maven-help-plugin:3.2.0:evaluate -Dexpression=project.artifactId -q -DforceStdout',
                            returnStdout: true
                        )
                        serviceVersion = sh(
                            script: 'mvn org.apache.maven.plugins:maven-help-plugin:3.2.0:evaluate -Dexpression=project.version -q -DforceStdout',
                            returnStdout: true
                        )
                        serviceVersion = "${serviceVersion}-${BUILD_NUMBER}"
                        sh "mvn versions:set -DnewVersion=${serviceVersion}"
                        sh 'mvn clean install -DskipTests'
                    }
                }
            }
        }

        stage('Code Coverage') {
            steps {
                script {
                    dir("${WORKSPACE}/source") {
                        withCredentials([string(credentialsId: 'sonar-login', variable: 'SONAR_LOGIN')]) {
                            sh "mvn sonar:sonar -Dsonar.host.url=https://sonarcloud.io -Dsonar.login=${SONAR_LOGIN} -Dsonar.organization=athamsiramas"
                        }
                        echo "SKIP!"
                    }
                }
            }
        }

        stage('Deploy Artifact') {
            steps {
                script {
                    dir("${WORKSPACE}/source") {
                        def nexusUrl
                        if (serviceVersion.contains("SNAPSHOT")) {
                            nexusUrl = "nexus3.cicd-test.svc.cluster.local:8081/repository/maven-snapshots/"
                        } else {
                            nexusUrl = "nexus3.cicd-test.svc.cluster.local:8081/repository/maven-releases/"
                        }
                        withCredentials([usernamePassword(credentialsId: 'nexus-login', passwordVariable: 'N_PASS', usernameVariable: 'N_USER')]) {
                            sh "mvn deploy -DskipTests=true -DskipTests -DaltDeploymentRepository=nexus::default::http://${N_USER}:${N_PASS}@${nexusUrl}"
                        }
                        echo "SKIP!"
                    }
                }
            }
        }

        stage('Build Image') {
            steps {
                dir("${WORKSPACE}/source") {
                    script {
                        sh "oc delete bc ${serviceName} -n cicd-test || true"
                        sh "oc new-build --name=${serviceName} --binary --strategy docker --to-docker=true --to=image-registry.openshift-image-registry.svc:5000/cicd-test/${serviceName}:${serviceVersion} --build-arg SERVICE_VERSION=${serviceVersion} -n cicd-test"
                        sh "oc start-build ${serviceName} --follow --from-dir=${WORKSPACE}/source -n cicd-test"
                    }
                }
            }
        }

        stage('Deploy Config') {
            when { expression { return fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/dev/configure.yaml") || fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/configure.yaml") }}
            steps {
                dir("${WORKSPACE}/deploy") {
                    script {
                        def binding = [:]
                        binding.SERVICE_NAME = serviceName
                        binding.SERVICE_VERSION = serviceVersion
                        binding.SERVICE_VERSION_DASH = serviceVersion.replaceAll(".", "-")
                        def paramsStr = createOcParams(binding)
                        if (fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/dev/configure.yaml")) {
                            sh "cp ${WORKSPACE}/configurations/openshift/${serviceName}/dev/configure.yaml configure.yaml"
                        } else if (fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/configure.yaml")) {
                            sh "cp ${WORKSPACE}/configurations/openshift/${serviceName}/configure.yaml configure.yaml"
                        }
                        
                        sh "oc process -f configure.yaml ${paramsStr} | oc apply -n cicd-test -f -"
                    }
                }
            }
        }

        stage('Deploy Application') {
            steps {
                dir("${WORKSPACE}/deploy") {
                    script {
                        if (fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/dev/deployment.yaml")) {
                            sh "cp ${WORKSPACE}/configurations/openshift/${serviceName}/dev/deployment.yaml deployment.yaml"
                        } else if (fileExists("${WORKSPACE}/configurations/openshift/${serviceName}/deployment.yaml")) {
                            sh "cp ${WORKSPACE}/configurations/openshift/${serviceName}/deployment.yaml deployment.yaml"
                        } else {
                            echo 'No deployment file found!'
                            sh 'exit 1'
                        }
                        def binding = [:]
                        binding.SERVICE_NAME = serviceName
                        binding.SERVICE_VERSION = serviceVersion
                        binding.SERVICE_VERSION_DASH = serviceVersion.replaceAll(".", "-")
                        binding.DEPLOYMENT_NAME = 'main'
                        def paramsStr = createOcParams(binding)
                        
                        echo "Creating new deployment for ${serviceName}"
                        sh "oc process -f deployment.yaml ${paramsStr} | oc apply -n cicd-test -f -"
                    }
                }
            }
        }

        stage('Configure Route') {
            steps {
                dir("${WORKSPACE}/deploy") {
                    script {
                        ROUTE_QUERY_RESULT = sh (
                            script: "oc get route -n cicd-test -l app=${serviceName} -o jsonpath='{range .items[*].metadata}{.name}{end}'",
                            returnStdout: true
                        ).trim()
                        if (!ROUTE_QUERY_RESULT) {
                            echo "Route doesn't exist. Creating new route for ${serviceName}"
                            sh "oc expose svc/${serviceName}-main-service -n cicd-test --name=${serviceName}-main-route -l app=${serviceName}"
                        }
                    }
                }
            }
        }

        stage('Verify Pod') {
            steps {
                dir("${WORKSPACE}/deploy") {
                    script {
                        sh "oc wait --for=condition=Ready pod -l app=${serviceName} -l version=${serviceVersion} --timeout=120s"
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
