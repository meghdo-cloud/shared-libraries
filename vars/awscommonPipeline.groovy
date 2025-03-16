def call(Map config, Closure buildStage) {
    setupAndValidateParameters(config)

    pipeline {
        agent {
            kubernetes {
                label "${label}"
                yamlFile "pipeline/pod.yaml"
            }
        }
        environment {
            CHART_PATH = './helm-charts'
            BASE_PATH = '/home/jenkins/agent/workspace'
            TAG = "${GIT_BRANCH}-${GIT_COMMIT[0..5]}"
            REPO_NAME = 'docker-repo'
            AWS_BUCKET = "scans-${accountName}"
            DOCKER_REGISTRY="${config.accountId}.dkr.ecr.${region}.amazonaws.com"
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
            stage('OWASP Scans') {
                when {
                    expression { return scanOWASP == "true" }
                }
                steps {
                    dependencyCheck odcInstallation: 'dep-check', additionalArguments: '--scan src/main --exclude helm-charts --exclude pipeline --disableRetireJS --project ${appName}' 
                    
                    script {
                        container('aws') {
                            sh """                        
                            aws s3 cp ./dependency-check-report.xml s3://${AWS_BUCKET}/${appName}/owasp-${appName}-${TAG}.xml
                            """
                          } 
                      }
                  }
            }    
            stage('Dynamic Lang Build') {
                when {
                    expression { return !skipStages }
                }
                steps {
                    script {
                        buildStage()
                    }
                }
            }
            stage('Kaniko Image Build & Push') {
                when {
                    expression { return !skipStages }
                }
                steps {
                    script {
                        container('aws') {
                            sh """
                            # Check if repository exists
                            aws ecr describe-repositories --repository-names ${REPO_NAME}/${appName} --region ${region} || {
                                # Create repository if it doesn't exist
                                echo "Creating ECR repository ${REPO_NAME}/${appName}"
                                aws ecr create-repository --repository-name ${REPO_NAME}/${appName} --region ${region}
                            }
                            """
                        }
                        container(name: 'kaniko', shell: '/busybox/sh') {
                            sh """
                            echo ${DOCKER_REGISTRY}
                            /kaniko/executor --context "${BASE_PATH}/${appName}_${GIT_BRANCH}" \
                            --dockerfile "${BASE_PATH}/${appName}_${GIT_BRANCH}/Dockerfile" \
                            --destination "${DOCKER_REGISTRY}/${REPO_NAME}/${appName}:${TAG}"
                            """
                        }
                    }
                }
            }
            stage('Image scanning') {
            steps {
                script {
                    container('trivy') {
                        def reportFileName = "trivy-${appName}-${TAG}.json"
                        sh """                        
                        trivy image --cache-dir /tmp --severity HIGH,CRITICAL  --format json --output ${reportFileName} ${DOCKER_REGISTRY}/${REPO_NAME}/${appName}:${TAG}
                        """
                        env.TRIVY_FILE = reportFileName
                        }
                    container('aws') {
                        sh """                        
                        aws s3 cp ./${env.TRIVY_FILE} s3://${AWS_BUCKET}/${appName}/${TAG}/${env.TRIVY_FILE}
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
                        container('aws') {
                        sh """                        
                        aws eks update-kubeconfig --name ${config.clusterName} --region ${config.region} 
                        """
                        }
                        container('infra-tools') {
                            sh """                                                
                            helm upgrade --install ${appName} ${CHART_PATH} \
                            --namespace ${namespace} \
                            --set image.repository=${DOCKER_REGISTRY}/${REPO_NAME}/${appName} \
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
    accountName = config.accountName
    accountId = config.accountId
    clusterName = config.clusterName
    region = config.region
    appName = config.appName
    namespace = config.namespace
    scanOWASP = config.scanOWASP
    label = config.label ?: 'default'
}
