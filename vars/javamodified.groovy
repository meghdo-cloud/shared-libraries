#!groovy

def call(Map config) {

  setupAndValidateParameters(config)

  pipeline {
      agent { 
           kubernetes { 
              label 'meghdo-java'
              yamlFile "pipeline/pod.yaml"
           }
      }  
      environment {
          CHART_PATH = './helm-charts'   // Set the path to your Helm chart
          BASE_PATH = '/home/jenkins/agent/workspace'
          TAG="${GIT_BRANCH}-${GIT_COMMIT[0..5]}"
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
          stage('Maven Build') {    
              when {
                  expression { return !skipStages }
              }    
              steps {
                  script {
                      // Execute Maven build
                      sh 'pwd'
                      sh 'ls -lrt'
                      sh 'mvn clean package -DskipTests'
                  }
              }
          }
          stage('Kaniko Build & Push') {
              when {
                  expression { return !skipStages }
              }              
              steps  {
                  script {
  
                     container(name: 'kaniko', shell: '/busybox/sh') {
                         
                      // Run Kaniko in a Kubernetes pod
  
                              sh """
                              /kaniko/executor --context "\${BASE_PATH}/${appName}_\${GIT_BRANCH}" \
                              --dockerfile "\${BASE_PATH}/${appName}_\${GIT_BRANCH}/Dockerfile" \
                              --destination ${dockerRegistry}/${projectId}/\${REPO_NAME}/${appName}:\${TAG}
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
                      // Update the Helm chart with the new image tag and deploy
                                         
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
      post {
          always {
              script {
                  container('jnlp') {
                    cleanWs()
                }
              }
          }  
      }            
  }
}

def setupAndValidateParameters(Map config) {
    projectId = config.projectId
    clusterName = config.clusterName
    clusterRegion = config.clusterRegion
    appName =  config.appName
    dockerRegistry = config.dockerRegistry
    namespace = config.namespace
}
