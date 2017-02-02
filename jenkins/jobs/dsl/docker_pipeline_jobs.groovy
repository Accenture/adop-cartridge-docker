
// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def dockerfileGitRepo = "adop-cartridge-docker-reference"
def dockerfileGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + dockerfileGitRepo

// Jobs
def getDockerfile = freeStyleJob(projectFolderName + "/Get_Dockerfile")
def staticCodeAnalysis = freeStyleJob(projectFolderName + "/Static_Code_Analysis")
def dockerBuild = freeStyleJob(projectFolderName + "/Image_Build")
def containerTest = freeStyleJob(projectFolderName + "/Container_Test")
def vulnerabilityScan = freeStyleJob(projectFolderName + "/Vulnerability_Scan")
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
  description("This job clones the specified local repository which contains the Dockerfile (and local resources).  It is the start of a pipeline that performs various testing stages.  The pipeline leads to a job called 'Image Push' that attempts to push to DockerHub.com.  For this to succeeed by default the job looks for a Jenkins credential called 'dockerhub-credentials'.  Only if that parameter is set and valid will the push to Dockehub.com succeed.  The subsequent 'Container Deploy' job also requires the same credentials.")
  parameters{
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
  triggers {
    gerrit {
      events {
        refUpdated()
      }
      project(projectFolderName + '/' + dockerfileGitRepo, 'plain:master')
      configure { node ->
        node / serverName("ADOP Gerrit")
      }
    }
  }
  steps {
    shell('''set -e
            |set +x
            |echo "Pull the Dockerfile out of Git, ready for us to test and if successful, release via the pipeline."
            |
            |# Convert tag name to lowercase letters if any uppercase letters are present since they are not allowed by Docker
            |
            |'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Static_Code_Analysis"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
          predefinedProp("CLAIR_DB",'${CLAIR_DB}')
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
    shell('''set -e
            |set +x
            |echo "Mount the Dockerfile into a container that will run Dockerlint: https://github.com/RedCoolBeans/dockerlint"
            |docker run --rm -v jenkins_slave_home:/jenkins_slave_home/ --entrypoint="dockerlint" redcoolbeans/dockerlint -f /jenkins_slave_home/$JOB_NAME/Dockerfile > ${WORKSPACE}/${JOB_NAME##*/}.out
            |
            |if ! grep "Dockerfile is OK" ${WORKSPACE}/${JOB_NAME##*/}.out ; then
            | echo "Dockerfile does not satisfy Dockerlint static code analysis"
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            | exit 1
            |else
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            |fi
            |
            |'''.stripMargin())
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
        }
      }
    }
  }
}

dockerBuild.with{
    description("This job builds the Dockerfile analysed in the previous step")
    parameters{
        stringParam("B",'',"Parent build number")
        stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
        stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
        stringParam("CLAIR_DB",'',"URI for the Clair PostgreSQL database in the format postgresql://postgres:password@postgres:5432?sslmode=disable")
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
    }
    steps {
        copyArtifacts('Get_Dockerfile') {
            buildSelector {
                buildNumber('${B}')
            }
        }
        shell('''set -xe
                |echo "Building the docker image locally..."
                |docker build -t ${IMAGE_TAG}:${B} ${WORKSPACE}/.
                |docker inspect ${IMAGE_TAG}:${B}
                |'''.stripMargin())
	}
	publishers{
		downstreamParameterized{
		  trigger(projectFolderName + "/Container_Test"){
  		    condition("UNSTABLE_OR_BETTER")
  		    parameters{
  		      predefinedProp("B",'${B}')
  		      predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
  		      predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
  		      predefinedProp("CLAIR_DB",'${CLAIR_DB}')
		    }
		  }
		}
	}
}

containerTest.with{
  description("This job uses a docker-security-test a reusable container security testing tool that is designed to look for common security errors which have been defined using a BDD approach.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
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
    copyArtifacts("Get_Dockerfile") {
      buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -xe
            |echo "Use the darrenajackson/image-inspector container to inspect the image"
            |# Set path workspace is available from inside docker machine
            |export docker_workspace_dir=$(echo ${WORKSPACE} | sed 's#/workspace#/var/lib/docker/volumes/jenkins_slave_home/_data#')
            |docker run --rm \\
            |-v ${docker_workspace_dir}/Dockerfile:/dockerdir/Dockerfile \\
            |-v ${docker_workspace_dir}/:/dockerdir/output \\
            |-v /var/run/docker.sock:/var/run/docker.sock \\
            |-w /dockerdir \\
            |luismsousa/docker-security-test rake \\
            |CUCUMBER_OPTS='features --format json --guess -o /dockerdir/output/cubumber.json'
            |docker rm --force $(docker ps -a -q --filter 'name=container-to-delete')
            |'''.stripMargin())
  }
  configure { myProject ->
      myProject / 'publishers' << 'net.masterthought.jenkins.CucumberReportPublisher'(plugin: 'cucumber-reports@0.1.0') {
          jsonReportDirectory("")
          pluginUrlPath("")
          fileIncludePattern("")
          fileExcludePattern("")
          skippedFails("false")
          pendingFails("false")
          undefinedFails("false")
          missingFails("false")
          noFlashCharts("false")
          ignoreFailedTests("false")
          parallelTesting("false")
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
        }
      }
    }
  }
}

vulnerabilityScan.with{
  description("This job tests the image against a database of known vulnerabilities using Clair, an open source static analysis tool https://github.com/coreos/clair. It assumes that Clair has access to the image being tested.  If CLAIR_DB is undefined the step does nothing.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    stringParam("CLAIR_DB",'',"URI for the Clair PostgreSQL database in the format postgresql://postgres:password@postgres:5432?sslmode=disable")
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
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -e
            |set +x
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
            |'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Image_Push"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
        }
      }
    }
  }
}

dockerPush.with{
  description("This job pushes the fully tested Docker image to Dockerhub.com.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Docker_Build","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        defaultValue('dockerhub-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "dockerhub-credentials"')
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
    shell('''set -e
            |set +x
            |IMAGE_TAG=$(echo "$IMAGE_TAG" | awk '{print tolower($0)}')
            |docker tag ${IMAGE_TAG}:${B} ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${B}
            |docker login -u ${DOCKERHUB_USERNAME} -p ${DOCKERHUB_PASSWORD} -e devops@adop.com
            |docker push ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${B}
            |
            |'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Container_Deploy"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD",'${PARENT_BUILD}')
          predefinedProp("IMAGE_TAG",'${IMAGE_TAG}')
        }
      }
    }
  }
}

dockerDeploy.with{
  description("This job pulls the Image back from Dockerhub.com and attempts to run it.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Docker_Build","Parent build name")
    stringParam("IMAGE_TAG",'',"Enter a unique string to tag your images e.g. your enterprise ID (Note: Upper case chararacters are not allowed)")
    credentialsParam("DOCKER_LOGIN"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        defaultValue('dockerhub-credentials')
        description('Dockerhub username and password. Please make sure the credentials are added with ID "dockerhub-credentials"')
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
    shell('''set -e
            |set +x
            |IMAGE_TAG=$(echo "$IMAGE_TAG" | awk '{print tolower($0)}')           
            |docker login -u ${DOCKERHUB_USERNAME} -p ${DOCKERHUB_PASSWORD} -e devops@adop.com
            |docker run -d --name jenkins_${IMAGE_TAG}_${B} ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${B}
            |
            |'''.stripMargin())
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
    shell('''set -e
            |set +x
            |IMAGE_TAG=$(echo "$IMAGE_TAG" | awk '{print tolower($0)}')
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
            |'''.stripMargin())
  }
}
