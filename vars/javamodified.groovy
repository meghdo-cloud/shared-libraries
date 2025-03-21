def call(Map config) {
    setupAndValidateParameters(config)

    pipeline {
        agent {
            kubernetes {
                label 'java17'
                yamlFile "pipeline/pod.yaml"
            }
        }
        environment {
            CHART_PATH = './helm-charts'
            BASE_PATH = '/home/jenkins/agent/workspace'
            TAG = "${GIT_BRANCH}-${GIT_COMMIT[0..5]}"
            REPO_NAME = 'docker-repo'
            TRIVY_GCS = "trivy-${projectId}"
            OWASP_GCS = "owasp-${projectId}"
        }
        stages {
           stage('SAST Code Scanning') {
            steps {
                    snykSecurity(
                      snykInstallation: 'synk-scan',
                      snykTokenId: 'aecc0496-5a75-420c-aafb-3db72478b171',
                      additionalArguments: '--all-projects'
                      )
                }
            }    
        }
        post {
            always {
                script {
                    cleanWs()
                }
            }
        }
    }
}

def setupAndValidateParameters(Map config) {
    projectId = config.projectId
    clusterName = config.clusterName
    clusterRegion = config.clusterRegion
    appName = config.appName
    dockerRegistry = config.dockerRegistry
    namespace = config.namespace
    scanOWASP = config.scanOWASP
}
