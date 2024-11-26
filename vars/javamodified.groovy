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
            stage('Static Code Analysis') {
                steps {
                    container('semgrep') {
                        script {
                            // Semgrep Code Scanning
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                sh '''
                                    semgrep \
                                    --config=auto \
                                    --json --output semgrep-results.json \
                                    --metrics=off
                                '''

                                // Parse and evaluate Semgrep results
                                def semgrepResults = readJSON file: 'semgrep-results.json'
                                evaluateCodeQualityGate(semgrepResults)
                             }
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
                            sh 'pwd'
                            sh 'ls -lrt'
                            sh 'mvn clean package -DskipTests'
                        }
                    }
                }
            }
            stage('Kaniko Build & Push') {
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
                    // Security Scan Reporting
                    archiveArtifacts artifacts: '*-results.json,*-report.json', allowEmptyArchive: true

                    // Generate Security Reports
                    publishSecurityReports()
                    cleanWs()
                }
            }
          failure {
                // Security Failure Notifications
                script {
                    sendSecurityFailureNotification()
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
