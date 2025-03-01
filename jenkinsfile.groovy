properties([
  parameters([
    choice(choices: ['patch','minor','major'], name: 'patchVersion', description: 'What needs to be bump?'),
    string(defaultValue:'', description: 'Force set newVersion or leave empty', name: 'newVersion', trim: false),
    string(defaultValue:'', description: 'Set grpcVersion or leave empty', name: 'grpcVersion', trim: false),
    choice(choices: ['local', 'global'], name: 'releaseType', description: 'It\'s local release or global?'),
   ])
])
 
SERVICENAME = 'hydro-serving-gateway'
SEARCHPATH = './project/Dependencies.scala'
SEARCHGRPC = 'val servingGrpcScala'
REGISTRYURL = 'hydrosphere'
SERVICEIMAGENAME = 'serving-gateway'
HELMCHARTNAME = 'gateway'
GITHUBREPO  = "github.com/Hydrospheredata/hydro-serving-gateway.git"

def checkoutRepo(String repo){
  if (env.CHANGE_FORK != null){
    git changelog: false, credentialsId: 'HydroRobot_AccessToken', poll: false, url: repo, branch: 'master'
    sh script: "git fetch origin pull/$CHANGE_ID/head:$BRANCH_NAME"
    sh script: "git checkout $BRANCH_NAME"
  }else if (env.CHANGE_ID != null ){
    git changelog: false, credentialsId: 'HydroRobot_AccessToken', poll: false, url: repo, branch: env.CHANGE_BRANCH
  } else{
    git changelog: false, credentialsId: 'HydroRobot_AccessToken', poll: false, url: repo, branch: env.BRANCH_NAME
  }
}

def getVersion(){
    try{
      if (params.releaseType == 'global'){
        //remove only quotes
        withCredentials([usernamePassword(credentialsId: 'HydroRobot_AccessToken', passwordVariable: 'Githubpassword', usernameVariable: 'Githubusername')]) {
          version = sh(script: "git ls-remote --sort='v:refname' --tags --refs 'https://$Githubusername:$Githubpassword@${GITHUBREPO}' | sed \"s/.*\\///\" | grep -v \"[a-z]\" | tail -n1", returnStdout: true, label: "get ${SERVICENAME} version").trim()
        }
      } else {
        //Set version as commit SHA
        version = sh(script: "git rev-parse HEAD", returnStdout: true ,label: "get version").trim()
      }
        return version
    }catch(err){
        return "$err" 
    }
}

def slackMessage(){
    withCredentials([string(credentialsId: 'slack_message_url', variable: 'slack_url')]) {
    //beautiful block
      def json = """
{
	"blocks": [
		{
			"type": "header",
			"text": {
				"type": "plain_text",
				"text": "$SERVICENAME: release - ${currentBuild.currentResult}!",
				"emoji": true
			}
		},
		{
			"type": "section",
			"block_id": "section567",
			"text": {
				"type": "mrkdwn",
				"text": "Build info:\n    Project: $JOB_NAME\n    Author: $AUTHOR\n    SHA: $newVersion"
			},
			"accessory": {
				"type": "image",
				"image_url": "https://res-5.cloudinary.com/crunchbase-production/image/upload/c_lpad,h_170,w_170,f_auto,b_white,q_auto:eco/oxpejnx8k2ixo0bhfsbo",
				"alt_text": "Hydrospere loves you!"
			}
		},
		{
			"type": "section",
			"text": {
				"type": "mrkdwn",
				"text": "You can see the assembly details by clicking on the button"
			},
			"accessory": {
				"type": "button",
				"text": {
					"type": "plain_text",
					"text": "Details",
					"emoji": true
				},
				"value": "Details",
				"url": "${env.BUILD_URL}",
				"action_id": "button-action"
			}
		}
	]
}
"""
    //Send message
        sh label:"send slack message",script:"curl -X POST \"$slack_url\" -H \"Content-type: application/json\" --data '${json}'"
    }
}

def bumpVersion(String currentVersion,String newVersion, String patch, String path){
    sh script: """cat <<EOF> ${WORKSPACE}/bumpversion.cfg
[bumpversion]
current_version = 0.0.0
commit = False
tag = False
parse = (?P<major>\\d+)\\.(?P<minor>\\d+)\\.(?P<patch>\\d+)
serialize =
    {major}.{minor}.{patch}

EOF""", label: "Set bumpversion configfile"
    if (newVersion != null && newVersion != ''){ //TODO: needs verify valid semver
        sh("echo $newVersion > version") 
    }else{
        sh("bumpversion $patch $path --config-file '${WORKSPACE}/bumpversion.cfg' --allow-dirty --verbose --current-version '$currentVersion'")   
    }
}

//Bump lib in dependency file
def bumpGrpc(String newVersion, String search, String patch, String path){
    sh script: "cat $path | grep '$search' > tmp", label: "Store search value in tmp file"
    currentVersion = sh(script: "cat tmp | cut -d'=' -f2 | sed 's/\"//g' | sed 's/,//g'", returnStdout: true, label: "Get current version").trim()
    sh script: "sed -i -E \"s/$currentVersion/$newVersion/\" tmp", label: "Bump temp version"
    sh script: "sed -i 's/\\\"/\\\\\"/g' tmp", label: "remove quote and space from version"
    sh script: "sed -i \"s/.*$search.*/\$(cat tmp)/g\" $path", label: "Change version"
    sh script: "rm -rf tmp", label: "Remove temp file"
}

def runTest(){
    sh script: "sbt --batch test", label: "Run test task"
}

//run build command and store build tag
def buildDocker(){
    tagVersion = getVersion()
    sh script: "sbt --batch -DappVersion=$tagVersion docker", label: "Run build docker task";
    sh script: "sbt --batch -DappVersion=latest docker", label: "Run build docker task";
}


def pushDocker(String registryUrl, String dockerImage){
    //push docker image to registryUrl
    withCredentials([usernamePassword(credentialsId: 'hydrorobot_docker_creds', passwordVariable: 'password', usernameVariable: 'username')]) {
      sh script: "docker login --username ${username} --password ${password}"
      sh script: "docker push $registryUrl/$dockerImage",label: "push docker image to registry"
    }
}

def updateDockerCompose(String newVersion){
  dir('docker-compose'){
    //Change template
    sh script: "sed -i \"s/.*image:.*/    image: ${REGISTRYURL}\\/${SERVICEIMAGENAME}:$newVersion/g\" ${SERVICENAME}.service.template", label: "sed ${SERVICEIMAGENAME} version"
    //Merge compose into 1 file
    composeMerge = "docker-compose"
    composeService = sh label: "Get all template", returnStdout: true, script: "ls *.template"
    list = composeService.split( "\\r?\\n" )
    for(l in list){
        composeMerge = composeMerge + " -f $l"
    }
    composeMerge = composeMerge + " config > ../docker-compose.yaml"
    sh script: "$composeMerge", label:"Merge compose file"
  }
}

def updateHelmChart(String newVersion){
  dir('helm'){
    //Change template
    sh script: "sed -i \"s/.*full:.*/  full: ${REGISTRYURL}\\/${SERVICEIMAGENAME}:$newVersion/g\" ${HELMCHARTNAME}/values.yaml", label: "sed ${HELMCHARTNAME} version"
    sh script: "sed -i \"s/.*${SERVICEIMAGENAME}.*/    full: ${REGISTRYURL}\\/${SERVICEIMAGENAME}:$newVersion/g\" dev.yaml", label: "sed ${HELMCHARTNAME} dev stage version"

    //Refresh readme for chart
    sh script: "frigate gen ${HELMCHARTNAME} --no-credits > ${HELMCHARTNAME}/README.md"

    //Lint manager
    dir(HELMCHARTNAME){
        sh script: "helm dep up", label: "Dependency update"
        sh script: "helm lint .", label: "Lint ${HELMCHARTNAME} chart"
        sh script: "helm template -n serving --namespace hydrosphere . > test.yaml", label: "save template to file"
        sh script: "polaris audit --audit-path test.yaml -f yaml", label: "lint template by polaris"
        sh script: "polaris audit --audit-path test.yaml -f score", label: "get polaris score"
        sh script: "rm -rf test.yaml", label: "remove test.yaml"
    }
    //Lint serving and bump version for dev stage
    dir('serving'){
        sh script: "helm dep up", label: "Dependency update"
        sh script: "helm lint .", label: "Lint all charts"
        sh script: "helm template -n serving --namespace hydrosphere . > test.yaml", label: "save template to file"
        sh script: "polaris audit --audit-path test.yaml -f yaml", label: "lint template by polaris"
        sh script: "polaris audit --audit-path test.yaml -f score", label: "get polaris score"
        sh script: "rm -rf test.yaml", label: "remove test.yaml"
    }
  }
}

//Create github release
def releaseService(String xVersion, String yVersion){
  withCredentials([usernamePassword(credentialsId: 'HydroRobot_AccessToken', passwordVariable: 'password', usernameVariable: 'username')]) {
      //Set global git
      sh script: "git diff", label: "show diff"
      sh script: "git commit -a -m 'Bump to $yVersion'", label: "commit to git"
      sh script: "git push --set-upstream origin master", label: "push all file to git"
      sh script: "git tag -a v${yVersion} -m 'Bump ${xVersion} to ${yVersion} version'",label: "set git tag"
      sh script: "git push --set-upstream origin master --tags",label: "push tag and create release"
      //Create release from tag
      sh script: "curl -X POST -H \"Accept: application/vnd.github.v3+json\" -H \"Authorization: token ${password}\" https://api.github.com/repos/Hydrospheredata/${SERVICENAME}/releases -d '{\"tag_name\":\"v${yVersion}\",\"name\": \"${yVersion}\",\"body\": \"Bump to ${yVersion}\",\"draft\": false,\"prerelease\": false}'"
  }
}


node('hydrocentral') {
    try {
        stage('SCM'){
            //Set commit author
            sh script: "git config --global user.name \"HydroRobot\"", label: "Set username"
            sh script: "git config --global user.email \"robot@hydrosphere.io\"", label: "Set user email"
            // git changelog: false, credentialsId: 'HydroRobot_AccessToken', poll: false, url: 'https://github.com/Hydrospheredata/hydro-serving-manager.git' 
            checkoutRepo("https://github.com/Hydrospheredata/${SERVICENAME}" + '.git')
            AUTHOR = sh(script:"git log -1 --pretty=format:'%an'", returnStdout: true, label: "get last commit author").trim()
            if (params.grpcVersion == ''){
                //Set grpcVersion
                grpcVersion = sh(script: "curl -Ls https://pypi.org/pypi/hydro-serving-grpc/json | jq -r .info.version", returnStdout: true, label: "get grpc version").trim()
            }
        }

        stage('Test'){
            if (env.CHANGE_ID != null || env.CHANGE_FORK != null ){
                runTest()
            }
        }

        stage('Release'){
            if (BRANCH_NAME == 'master' || BRANCH_NAME == 'main'){
                if (params.releaseType == 'global'){
                    oldVersion = getVersion()
                    bumpVersion(getVersion(),params.newVersion,params.patchVersion,'version')
                    newVersion = getVersion()
                    bumpGrpc(grpcVersion, SEARCHGRPC, params.patchVersion, SEARCHPATH)
                } else {
                    newVersion = getVersion()
                }
                buildDocker()
                pushDocker(REGISTRYURL, SERVICEIMAGENAME+":$newVersion")
                pushDocker(REGISTRYURL, SERVICEIMAGENAME+":latest")
                //Update helm and docker-compose if release 
                if (params.releaseType == 'global'){
                    sh script: "echo Start ${params.releaseType} release"
                    releaseService(oldVersion, newVersion)
                } else {
                    dir('release'){
                        sh script: "echo Start ${params.releaseType} release"
                        //bump only image tag
                        withCredentials([usernamePassword(credentialsId: 'HydroRobot_AccessToken', passwordVariable: 'Githubpassword', usernameVariable: 'Githubusername')]) {
                            git changelog: false, credentialsId: 'HydroRobot_AccessToken', url: "https://$Githubusername:$Githubpassword@github.com/Hydrospheredata/hydro-serving.git"
                            sh script: "echo Update Helm and Compose"      
                            updateHelmChart("$newVersion")
                            updateDockerCompose("$newVersion")
                            sh script: "echo Commit changes to hydro-serving repo"
                            sh script: "git commit --allow-empty -a -m 'Releasing ${SERVICENAME}:$newVersion'",label: "commit to git chart repo"
                            sh script: "git push https://$Githubusername:$Githubpassword@github.com/Hydrospheredata/hydro-serving.git --set-upstream master",label: "push to git"
                        }
                    }
                }
            }
        }
    //post if success
    if (params.releaseType == 'local' && BRANCH_NAME == 'master'){
        slackMessage()
    }
    } catch (e) {
    //post if failure
        currentBuild.result = 'FAILURE'
    if (params.releaseType == 'local' && BRANCH_NAME == 'master'){
        slackMessage()
    }
        throw e
    }
}