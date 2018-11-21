import pluggable.scm.*;

SCMProvider scmProvider = SCMProviderHandler.getScmProvider("${SCM_PROVIDER_ID}", binding.variables)

// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"
def projectScmNamespace = "${SCM_NAMESPACE}"

// Variables
def scmUrl = scmProvider.getScmUrl()
def dockerfileGitRepo = "adop-cartridge-docker-reference";
def dockerfileGitUrl = scmUrl + "${PROJECT_NAME}/" + dockerfileGitRepo


// Jobs
def getDockerfile = freeStyleJob(projectFolderName + "/Get_Dockerfile")
def staticCodeAnalysis = freeStyleJob(projectFolderName + "/Static_Code_Analysis")
def dockerBuild = freeStyleJob(projectFolderName + "/Image_Build")
def vulnerabilityScan = freeStyleJob(projectFolderName + "/Vulnerability_Scan")
def imageTest = freeStyleJob(projectFolderName + "/Image_Test")
def containerTest = freeStyleJob(projectFolderName + "/Container_Test")
def dockerPush = freeStyleJob(projectFolderName + "/Image_Push")
def dockerDeploy = freeStyleJob(projectFolderName + "/Container_Deploy")
def dockerCleanup = freeStyleJob(projectFolderName + "/Container_Cleanup")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Sample_Docker_CI")

pipelineView.with{
    title('Sample Docker Pipeline')
    displayedBuilds(4)
    selectedJob(projectFolderName + "/Get_Dockerfile")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
    alwaysAllowManualTrigger()
    startsWithParameters()
}

// All jobs are tied to build on the Jenkins slave
// A default set of wrappers have been used for each job

getDockerfile.with{
  description("This job clones the specified local repository which contains the Dockerfile (and local resources).")
  parameters{
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        required()
        defaultValue('docker-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "docker-credentials"')
    }
    stringParam("IMAGE_REPO",dockerfileGitUrl,"Repository location of your Dockerfile")
    stringParam("IMAGE_TAG",'tomcat8',"Enter a unique string to tag your images (Note: Upper case chararacters are not allowed)")
    stringParam("CLAIR_DB",'',"URI for the Clair PostgreSQL database in the format postgresql://postgres:password@postgres:5432?sslmode=disable (ignore parameter as it is currently unsupported)")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  scm{
    git{
      remote{
        url('${IMAGE_REPO}')
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  triggers scmProvider.trigger(projectFolderName, dockerfileGitRepo, "master")
  steps {
    shell('''set +x
            |echo "Pull the Dockerfile out of Git, ready for us to test and if successful, release via the pipeline."
            |
            |# Convert tag name to lowercase letters if any uppercase letters are present since they are not allowed by Docker
            |echo TAG=$(echo "$IMAGE_TAG" | awk '{print tolower($0)}') > build.properties
            |
            |# Export out credential ID to a properties file
            |echo LOGIN=$(echo ${DOCKER_LOGIN}) >> build.properties
            |set -x'''.stripMargin())
    environmentVariables {
      propertiesFile('build.properties')
    }
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Static_Code_Analysis"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
          predefinedProp("IMAGE_TAG",'${TAG}')
          predefinedProp("CLAIR_DB",'${CLAIR_DB}')
          predefinedProp("DOCKER_LOGIN",'${LOGIN}')
        }
      }
    }
  }
}

staticCodeAnalysis.with{
  description("This job performs static code analysis on the Dockerfile using the Redcoolbeans Dockerlint image. It assumes that the Dockerfile exists in the root of the directory structure.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    stringParam("CLAIR_DB",'',"URI for the Clair PostgreSQL database in the format postgresql://postgres:password@postgres:5432?sslmode=disable")
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        required()
        defaultValue('docker-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "docker-credentials"')
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  label("docker")
  steps {
    copyArtifacts('Get_Dockerfile') {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set +x
            |echo "Mount the Dockerfile into a container that will run Dockerlint: https://github.com/RedCoolBeans/dockerlint"
            |docker run --rm -v jenkins_slave_home:/jenkins_slave_home/ --entrypoint="dockerlint" redcoolbeans/dockerlint:0.2.0 -f /jenkins_slave_home/$JOB_NAME/Dockerfile > ${WORKSPACE}/${JOB_NAME##*/}.out
            |
            |if ! grep "Dockerfile is OK" ${WORKSPACE}/${JOB_NAME##*/}.out ; then
            | echo "Dockerfile does not satisfy Dockerlint static code analysis"
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            | exit 1
            |else
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            |fi
            |
            |echo "LOGIN=${DOCKER_LOGIN}" > credential.properties
            |set -x'''.stripMargin())
    environmentVariables {
      propertiesFile('credential.properties')
    }
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Image_Build"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
          predefinedProp("CLAIR_DB",'${CLAIR_DB}')
          predefinedProp("DOCKER_LOGIN",'${LOGIN}')
        }
      }
    }
  }
}

dockerBuild.with{
	description("This job builds our dockerfile analysed in the previous step")
	parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    stringParam("CLAIR_DB",'',"URI for the Clair PostgreSQL database in the format postgresql://postgres:password@postgres:5432?sslmode=disable")
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        defaultValue('docker-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "docker-credentials"')
    }
  }
	environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
	label("docker")
	wrappers {
		preBuildCleanup()
		injectPasswords()
		maskPasswords()
		sshAgent("adop-jenkins-master")
    credentialsBinding {
        usernamePassword("DOCKERHUB_USERNAME", "DOCKERHUB_PASSWORD", '${DOCKER_LOGIN}')
    }
	}
	steps {
    copyArtifacts('Get_Dockerfile') {
        buildSelector {
          buildNumber('${B}')
      }
    }
		shell('''set -x
      |echo "Building the docker image locally..."
      |docker build -t ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${B} ${WORKSPACE}/.
      |
      |echo LOGIN=$(echo ${DOCKER_LOGIN}) > credential.properties
      |set +x'''.stripMargin())
    environmentVariables {
      propertiesFile('credential.properties')
    }
	}
	publishers{
		downstreamParameterized{
		  trigger(projectFolderName + "/Vulnerability_Scan"){
  			condition("UNSTABLE_OR_BETTER")
  			parameters{
  			  predefinedProp("B",'${B}')
  			  predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
          predefinedProp("CLAIR_DB",'${CLAIR_DB}')
          predefinedProp("DOCKER_LOGIN",'${LOGIN}')
			  }
		  }
		}
	}
}

vulnerabilityScan.with{
  description("This job tests the image against a database of known vulnerabilities using Clair, an open source static analysis tool https://github.com/coreos/clair. It assumes that Clair has access to the image being tested.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    stringParam("CLAIR_DB",'',"URI for the Clair PostgreSQL database in the format postgresql://postgres:password@postgres:5432?sslmode=disable")
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        defaultValue('docker-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "docker-credentials"')
    }
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
    credentialsBinding {
        usernamePassword("DOCKERHUB_USERNAME", "DOCKERHUB_PASSWORD", '${DOCKER_LOGIN}')
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set +x
            |echo "THIS STEP NEEDS TO BE UPDATED ONCE ACCESS TO A PRODUCTION CLAIR DATABASE IS AVAILABLE"
            |
            |if [ -z ${CLAIR_DB} ]; then
            | echo "WARNING: You have not provided the endpoints for a Clair database, moving on for now..."
            |else
            | # Set up Clair as a docker container
            | echo "Clair database endpoint: ${CLAIR_DB}"
            | mkdir /tmp/clair_config
            | curl -L https://raw.githubusercontent.com/coreos/clair/master/config.example.yaml -o /tmp/clair_config/config.yaml
            | # Add the URI for your postgres database
            | sed -i'' -e "s|options: |options: ${CLAIR_DB}|g" /tmp/clair_config/config.yaml
            | docker run -d -p 6060-6061:6060-6061 -v /tmp/clair_config:/config quay.io/coreos/clair -config=/config/config.yaml
            | # INSERT STEPS HERE TO RUN VULNERABILITY ANALYSIS ON IMAGE USING CLAIR API
            |fi
            |
            |echo LOGIN=$(echo ${DOCKER_LOGIN}) > credential.properties
            |set -x'''.stripMargin())
    environmentVariables {
      propertiesFile('credential.properties')
    }
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Image_Test"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
          predefinedProp("DOCKER_LOGIN",'${LOGIN}')
        }
      }
    }
  }
}

imageTest.with{
  description("This job uses a python script to analyse the output from docker inspect against a configuration file that details required parameters. It also looks for any unexpected additions to the new image being tested. The configuration file must live under tests/image-test inside the images repository.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        defaultValue('docker-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "docker-credentials"')
    }
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
    credentialsBinding {
        usernamePassword("DOCKERHUB_USERNAME", "DOCKERHUB_PASSWORD", '${DOCKER_LOGIN}')
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
      buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Use the darrenajackson/image-inspector container to inspect the image"
            |# Set test file directory
            |export TESTS_PATH="tests/image-test"
            |
            |# Set directory where $TESTS_PATH will be mounted inside container
            |export TEST_DIR="/tmp"
            |
            |# Set path workspace is available from inside docker machine
            |export docker_workspace_dir=$(echo ${WORKSPACE} | sed 's#/workspace#/var/lib/docker/volumes/jenkins_slave_home/_data#')
            |
            |docker run --net=host --rm -v ${docker_workspace_dir}/${TESTS_PATH}/:${TEST_DIR} -v /var/run/docker.sock:/var/run/docker.sock darrenajackson/image-inspector -i  ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${B} -f ${TEST_DIR}/image.cfg > ${WORKSPACE}/${JOB_NAME##*/}.out
            |
            |if grep "ERROR" ${WORKSPACE}/${JOB_NAME##*/}.out; then
            | echo "Your built image has failed testing..."
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            | exit 1
            |else
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            |fi
            |
            |echo LOGIN=$(echo ${DOCKER_LOGIN}) > credential.properties
            |set +x'''.stripMargin())
    environmentVariables {
      propertiesFile('credential.properties')
    }
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Container_Test"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
          predefinedProp("DOCKER_LOGIN",'${LOGIN}')
        }
      }
    }
  }
}

containerTest.with{
  description("This job creates a new testing image from the image being tested that also contains all the tools necessary for internal testing of the image. A series of tests are then run from inside the new container. The tests can be written in any fashion you wish, however they must be initiated from a file called container_tests.sh that lives inside tests/container-test in the images repository.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        defaultValue('docker-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "docker-credentials"')
    }
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
    credentialsBinding {
        usernamePassword("DOCKERHUB_USERNAME", "DOCKERHUB_PASSWORD", '${DOCKER_LOGIN}')
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
      buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Building a new test image installing required applications, running test suite and destroying the new image and container at the end of the tests..."
            |# Set test file directory
            |export TESTS_PATH="tests/container-test"
            |
            |# Set testing docker file info
            |export TEST_DF_PATH="${WORKSPACE}/${TESTS_PATH}/dockerfile"
            |export TEST_DF_NAME="Dockerfile.test"
            |export TEST_DF="${TEST_DF_PATH}/${TEST_DF_NAME}"
            |
            |# Set file containing list of environment variables
            |export TEST_ENVS="${WORKSPACE}/${TESTS_PATH}/envs/envs.cfg"
            |
            |# Test image name extension
            |export IMG_EXT="test"
            |
            |# Test image tag
            |export IMG_TAG=${B}
            |
            |# Set directory where $TESTS_PATH will be mounted inside container
            |export TEST_DIR="/var/tmp"
            |
            |# Set path workspace is available from inside docker machine
            |export docker_workspace_dir=$(echo ${WORKSPACE} | sed 's#/workspace#/var/lib/docker/volumes/jenkins_slave_home/_data#')
            |
            |# Source environment variables needed for test dockerfile generation
            |source ${WORKSPACE}/${TESTS_PATH}/dockerfile/dockerfile_envs.sh
            |
            |# Create test Dockerfile
            |if ! [[ -f ${TEST_DF} ]]; then
            |  cat << EOF > ${TEST_DF}
            |FROM  ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${B}
            |
            |RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y ${PACK_LIST} && apt-get clean && rm -rf /var/lib/apt/lists/*
            |
            |EOF
            |fi
            |
            |# If the environment variable file exists add them to the dockerfile
            |if [[ -f ${TEST_ENVS} ]]; then
            |  cat ${TEST_ENVS} >> ${TEST_DF}
            |fi
            |
            |# Build the test images
            |docker build -t ${IMAGE_TAG}-${IMG_EXT}:${IMG_TAG} -f ${TEST_DF} ${TEST_DF_PATH}
            |
            |# Run the test image
            |docker run -d --name ${IMAGE_TAG}-${IMG_EXT} -v ${docker_workspace_dir}/${TESTS_PATH}/:${TEST_DIR} ${IMAGE_TAG}-${IMG_EXT}:${IMG_TAG}
            |
            |# Allow the container time to start
            |sleep 60
            |
            |# Execute the testing scripts
            |docker exec ${IMAGE_TAG}-${IMG_EXT} chmod -R +x ${TEST_DIR}
            |docker exec ${IMAGE_TAG}-${IMG_EXT} ${TEST_DIR}/container_tests.sh ${TEST_DIR} > ${WORKSPACE}/${JOB_NAME##*/}.out
            |
            |# Stop and clean up the testing container and testing image
            |docker stop ${IMAGE_TAG}-${IMG_EXT} && docker rm -v ${IMAGE_TAG}-${IMG_EXT} && docker rmi ${IMAGE_TAG}-${IMG_EXT}:${IMG_TAG}
            |
            |if grep "^-" ${WORKSPACE}/${JOB_NAME##*/}.out; then
            | echo "Note: some warnings/errors found..."
            | if grep -E "expected port closed|expected process incorrect owner|expected process incorrect owner" ${WORKSPACE}/${JOB_NAME##*/}.out; then
            |   echo "Your container has failed testing..."
            |   exit 1
            | fi
            | echo "Some unexpected ports/processes found, moving on for now..."
            |else
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            | echo "Container has successfully passed testing..."
            |fi
            |
            |echo LOGIN=$(echo ${DOCKER_LOGIN}) > credential.properties
            |set -x'''.stripMargin())
    environmentVariables {
      propertiesFile('credential.properties')
    }
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Image_Push"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
          predefinedProp("DOCKER_LOGIN",'${LOGIN}')
        }
      }
    }
  }
}

dockerPush.with{
  description("This job pushed the fully tested Docker image to Dockerhub.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Docker_Build","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        defaultValue('docker-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "docker-credentials"')
    }
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
    credentialsBinding {
        usernamePassword("DOCKERHUB_USERNAME", "DOCKERHUB_PASSWORD", '${DOCKER_LOGIN}')
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    shell('''set +x
      |docker login -u ${DOCKERHUB_USERNAME} -p ${DOCKERHUB_PASSWORD} -e devops@adop.com
      |docker push ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${B}
      |
      |echo LOGIN=$(echo ${DOCKER_LOGIN}) > credential.properties
      |set -x'''.stripMargin())
    environmentVariables {
      propertiesFile('credential.properties')
    }
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Container_Deploy"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD",'${PARENT_BUILD}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
          predefinedProp("DOCKER_LOGIN",'${LOGIN}')
        }
      }
    }
  }
}

dockerDeploy.with{
  description("This job deploys the Docker image pushed in the previous job in a container.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Docker_Build","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        defaultValue('docker-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "docker-credentials"')
    }
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
    credentialsBinding {
        usernamePassword("DOCKERHUB_USERNAME", "DOCKERHUB_PASSWORD", '${DOCKER_LOGIN}')
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    shell('''set +x
      |docker login -u ${DOCKERHUB_USERNAME} -p ${DOCKERHUB_PASSWORD} -e devops@adop.com
      |docker run -d --name jenkins_${IMAGE_TAG}_${B} ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${B}
      |
      |set -x'''.stripMargin())
  }
  publishers{
    buildPipelineTrigger(projectFolderName + "/Container_Cleanup") {
      parameters {
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD",'${PARENT_BUILD}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
      }
    }
  }
}

dockerCleanup.with{
  description("This job cleans up any existing deployed containers (has to be run manually).")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Docker_Build","Parent build name")
    stringParam("IMAGE_TAG",'tomcat8',"Enter the string value which you entered to tag your images (Note: Upper case chararacters are not allowed)")
    choiceParam('CONTAINER_DELETION', ['SINGLE', 'ALL'], 'Choose whether to delete the container created by this run of the pipeline or all the containers created by each run of the pipeline.')
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    shell('''set +x
      |# Checking to see whether to delete all containers or just one
      |if [ ${CONTAINER_DELETION} = "SINGLE" ]; then
      |  echo "Deleting single container..."
      |  docker rm -f jenkins_${IMAGE_TAG}_${B}
      |elif [ ${CONTAINER_DELETION} = "ALL" ]; then
      |   echo "Deleting all containers..."
      |   for i in `seq 1 ${B}`;
      |     do
      |      if docker ps -a | grep "jenkins_${IMAGE_TAG}_${i}"; then
      |          docker rm -f jenkins_${IMAGE_TAG}_${i}
      |        fi
      |     done
      |fi
      |set -x'''.stripMargin())
  }
}
