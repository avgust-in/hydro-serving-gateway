node {
  stage('SCM') {
    git 'https://github.com/Hydrospheredata/hydro-serving-gateway.git'
  }
  stage('SonarQube analysis') {
    def scannerHome = tool 'Sonarcloud';
    withSonarQubeEnv('Sonarcloud') { // If you have configured more than one global server connection, you can specify its name
      if (BRANCH_NAME != "master") {
        sh "${scannerHome}/bin/sonar-scanner \
          -Dsonar.projectKey=Hydrospheredata_hydro-serving-gateway \
          -Dsonar.organization=hydrosphere \
          -Dsonar.sources=. \
          -Dsonar.branch.name=${GIT_BRANCH} \
          -Dsonar.pullrequest.key=${CHANGE_ID} \
          -Dsonar.pullrequest.branch=${CHANGE_BRANCH} \
          -Dsonar.pullrequest.base=${CHANGE_TARGET} \
          -Dsonar.host.url=https://sonarcloud.io \
          -Dsonar.login=f4edb54bde6f29b48660b944fda885099b9a2a48"
      } else {
        sh "${scannerHome}/bin/sonar-scanner \
          -Dsonar.projectKey=Hydrospheredata_hydro-serving-gateway \
          -Dsonar.organization=hydrosphere \
          -Dsonar.sources=. \
          -Dsonar.branch.name=${GIT_BRANCH} \
          -Dsonar.host.url=https://sonarcloud.io \
          -Dsonar.login=f4edb54bde6f29b48660b944fda885099b9a2a48"        
      }
    }
  }

//  stage("Quality Gate"){
//    timeout(time: 1, unit: 'HOURS') { // Just in case something goes wrong, pipeline will be killed after a timeout
//      def qg = waitForQualityGate() // Reuse taskId previously collected by withSonarQubeEnv
//      if (qg.status != 'OK') {
//        error "Pipeline aborted due to quality gate failure: ${qg.status}"
//      }
//    }
//  }

  stage("trigger-central") {
    build job: 'provectus.com/hydro-central/master', parameters: [
      [$class: 'StringParameterValue',
      name: 'PROJECT',
      value: 'gateway'
      ],
      [$class: 'StringParameterValue',
      name: 'BRANCH',
      value: env.BRANCH_NAME
      ]
    ]
  }
}
