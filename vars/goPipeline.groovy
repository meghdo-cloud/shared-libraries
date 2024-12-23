def call(Map config) {
    setupAndValidateParameters(config)

    pipeline {
        agent {
            kubernetes {
                label 'python'
                yamlFile "pipeline/pod.yaml"
            }
        }
        environment {
            CHART_PATH = './helm-charts'
            BASE_PATH = '/home/jenkins/agent/workspace'
            TAG = "${GIT_BRANCH}-${GIT_COMMIT[0..5]}"
            REPO_NAME = 'docker-repo'
            GCS_BUCKET = "scans-${projectId}"
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
                        container('infra-tools') {
                            sh """                        
                            gsutil cp ./dependency-check-report.xml gs://${GCS_BUCKET}/${appName}/${TAG}/owasp-${appName}-${TAG}.xml
                            """
                          } 
                      }
                  }
            }    
            stage('Go Build') {
                when {
                    expression { return !skipStages }
                }
                steps {
                    script {
                        container('golang') {
                            sh """
                            # Set up Go workspace
                            chmod -R 777 /opt/go
                            export PATH="/usr/local/go/bin:$PATH"
                            export GOPATH=/opt/go/
                            
                            # Change to source directory
                            cd src                            
                            # Download dependencies
                            go mod download
                            
                            # Run tests
                            go test ./...
                            
                            # Build the application
                            CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o ${BASE_PATH}/${appName}_${GIT_BRANCH}/main .
                            """
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
                        def reportFileName = "trivy-${appName}-${TAG}.json"
                        sh """                        
                        trivy image --cache-dir /tmp --severity HIGH,CRITICAL  --format json --output ${reportFileName} ${dockerRegistry}/${projectId}/${REPO_NAME}/${appName}:${TAG}
                        """
                        env.TRIVY_FILE = reportFileName
                        }
                    container('infra-tools') {
                        sh """                        
                        gsutil cp ${env.TRIVY_FILE} gs://${GCS_BUCKET}/${appName}/${TAG}/${env.TRIVY_FILE}
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
                            gcloud iam service-accounts add-iam-policy-binding svc-gke@${projectId}.iam.gserviceaccount.com --member="serviceAccount:${projectId}.svc.id.goog[${namespace}/${appName}]" --role="roles/iam.workloadIdentityUser" 
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
    scanOWASP = config.scanOWASP
}
