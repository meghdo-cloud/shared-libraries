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
            stage('SCM Skip') {
                steps {
                    script {
                        skipStages = false
                        scmSkip = sh(script: 'git log -1 --pretty=%B ${GIT_COMMIT}', returnStdout: true).trim()
                        if (scmSkip.contains("[ci skip]")) {
                            skipStages = true
                            currentBuild.description = "SCM Skip - Build skipped as no new commits in branch"
                        }
                        sh "echo ${skipStages}"
                    }
                }
            }
            stage('Code N Dependency Scans') {
                when {
                    expression { return !skipStages }
                }
                steps {
                    script {
                        container('owasp') {
                            def owaspReport = "${appName}-${TAG}.json"
                            sh """
                            pwd
                            /opt/dependency-check/bin/dependency-check.sh --project "${appName}" \
                            --scan "." --format "HTML,JSON" \
                            --out $owaspReport
                            """
                            env.OWASP_FILE = reportFileName
                          }
                          container('infra-tools') {
                            sh """                        
                            gsutil cp ${env.OWASP_FILE} gs://${OWASP_GCS}/${appName}/${env.OWASP_FILE}
                            """
                          } 
                      }
                  }
            }    
            stage('Maven Build') {
                when {
                    expression { return !skipStages }
                }
                steps {
                    script {
                        container('maven') {
                            sh 'mvn clean package -DskipTests'
                        }
                    }
                }
            }
            stage('Kaniko Image Build & Push') {
                when {
                    expression { return !skipStages }
                }
                steps {
                    script {
                        container(name: 'kaniko', shell: '/busybox/sh') {
                            sh """
                            /kaniko/executor --context "${BASE_PATH}/${appName}_${GIT_BRANCH}" \
                            --dockerfile "${BASE_PATH}/${appName}_${GIT_BRANCH}/Dockerfile" \
                            --destination ${dockerRegistry}/${projectId}/${REPO_NAME}/${appName}:${TAG}
                            """
                        }
                    }
                }
            }
            stage('Image scanning') {
            steps {
                script {
                    container('trivy') {
                        def reportFileName = "${appName}-${TAG}.json"
                        sh """                        
                        trivy image --cache-dir /tmp --severity HIGH,CRITICAL  --format json --output ${reportFileName} ${dockerRegistry}/${projectId}/${REPO_NAME}/${appName}:${TAG}
                        """
                        env.TRIVY_FILE = reportFileName
                        }
                    container('infra-tools') {
                        sh """                        
                        gsutil cp ${env.TRIVY_FILE} gs://${TRIVY_GCS}/${appName}/${env.TRIVY_FILE}
                        """
                        }
                    }
                }
            }    
            stage('Deploy with Helm') {
                when {
                    expression { return !skipStages }
                }
                steps {
                    script {
                        container('infra-tools') {
                            sh """
                            gcloud config set project ${projectId}
                            gcloud container clusters get-credentials ${clusterName} --zone ${clusterRegion}
                            helm upgrade --install ${appName} ${CHART_PATH} \
                            --namespace ${namespace} \
                            --set image.repository=${dockerRegistry}/${projectId}/${REPO_NAME}/${appName} \
                            --set image.tag=${TAG}
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
}
