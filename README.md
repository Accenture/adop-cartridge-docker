# What is a Cartridge?

A Cartridge is a set of resources that are loaded into ADOP for a particular project. They may contain anything from a simple reference implementation for a technology to a set of best practice examples for building, deploying, and managing a technology stack that can be used by a project.

This cartridge consists of source code repositories and jenkins jobs for a sample Docker CI pipeline.

## Source code repositories

Cartridge loads the source code repositories

* [Tomcat8 Dockerfile](https://github.com/Accenture/adop-cartridge-docker-reference/)

## Jenkins Jobs

This cartridge generates the jenkins jobs to -

* Performs static code analysis using Dockerlint on the Dockerfile in the Git repository.
* Builds the Dockerfile in the repository.
* Performs a vulnerability scan on the built image using Anchore Container Scanner plugin.
* Pushes the built and scanned image to a registry.
  * Supports ECR, DTR and public docker hub.

**Note** : The credential parameter used in the jenkins job expects the docker hub credentials to be added to jenkins with ID **docker-credentials**. For DTR and public docker-credentials is supposed to be username and password whereas if you are using ECR then username is expected to be AWS\_ACCESS\_KEY\_ID and password is expected to be AWS\_SECRET\_ACCESS\_KEY.

# License
Please view [license information](LICENSE.md) for the software contained on this image.

## Documentation
Documentation will be captured within this README.md and this repository's Wiki.

## Issues
If you have any problems with or questions about this repository, please contact us through a GitHub issue.

## Contribute
You are invited to contribute new features, fixes, or updates, large or small; we are always thrilled to receive pull requests, and do our best to process them as fast as we can.

Before you start to code, we recommend discussing your plans through a GitHub issue, especially for more ambitious contributions. This gives other contributors a chance to point you in the right direction, give you feedback on your design, and help you find out if someone else is working on the same thing.
