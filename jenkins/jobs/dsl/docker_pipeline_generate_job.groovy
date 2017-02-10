/*
 This 'seed job' is responsible for generating build and test Docker CI pipelines for the respective Dockerfile.
*/

def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

def generateDockerPipeline = freeStyleJob(projectFolderName + "/Generate_Docker_Pipeline");

def logRotatorBuildsToKeep = 5;
def logRotatorNumToKeep = 7;
def logRotatorArtifactsToKeep = 7;
def logRotatorArtifactsNumToKeep = 7;

def referenceDockerFileGitRepo = "adop-cartridge-docker-reference"
def referenceDockerFileGitUrl = 'ssh://jenkins@gerrit:29418/' + projectFolderName + '/' + referenceDockerFileGitRepo
def referenceDockerImageTag = 'tomcat8'
def defaultDockerRegisitryEndpoint = 'registry-1.docker.io'

generateDockerPipeline.with {

 description('This \'seed job \' generates the Jenkins jobs and accosiated pipeline view for the build and tests of Docker images')
 parameters {
  stringParam('SCM_REPO_NAME', referenceDockerFileGitRepo, 'The name of Git repo with the Dockerfile.')
  stringParam('SCM_REPO', referenceDockerFileGitUrl, 'The Git repository URL with the Dockerfile.')
  stringParam("DOCKER_IMAGE_TAG", referenceDockerImageTag, 'A unique string to tag your images. Note: Upper case chararacters are not allowed. This is also used as the prefix to the build pipeline view.')
  stringParam('DOCKER_REGISTRY_ENDPOINT',defaultDockerRegisitryEndpoint, 'The Docker registry address. e.g. registry-1.docker.io.')
  credentialsParam('DOCKER_REGISTRY_CREDENTIALS') {
   type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
   required()
   description('Docker registry username and password. Note: This credential is used to authenticate agaisnt the provided Docker registry endpoint. It is only required if you wish your Docker images to be pushed.')
  }
 }
 logRotator {
  daysToKeep(logRotatorBuildsToKeep)
  numToKeep(logRotatorNumToKeep)
  artifactDaysToKeep(logRotatorArtifactsToKeep)
  artifactNumToKeep(logRotatorArtifactsNumToKeep)
 }
 environmentVariables {
  env('WORKSPACE_NAME', workspaceFolderName)
  env('PROJECT_NAME', projectFolderName)
 }
 wrappers {
  preBuildCleanup()
  maskPasswords()
 }
 steps {
  dsl{
  	text(readFileFromWorkspace('cartridge/jenkins/jobs/dsl/docker_pipeline_jobs.template'))
  }
 }
}

queue(generateDockerPipeline)
