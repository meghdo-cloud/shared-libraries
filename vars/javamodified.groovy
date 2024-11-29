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
                script {
                    container('semgrep') {
                        def semgrepFile = "semgrep-${appName}-${TAG}.json"
                        sh """                        
                        semgrep scan --config=/etc/semgrep-rules/rules.yaml --include=src/** --json --output=${semgrepFile} --metrics=off
                        """
                        env.SEMGREP_FILE = semgrepFile
                        }
                    container('infra-tools') {
                        sh """                        
                        gsutil cp ${env.SEMGREP_FILE} gs://${TRIVY_GCS}/${appName}/${TAG}/${env.SEMGREP_FILE}
                        """
                        }
                    }
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
