# CI/CD - Jenkins on OpenShift
## Prerequisite
1. Deploy Jenkins using template provided by OpenShift in `cicd` namespace.
2. Deploy Nexus OSS using 'sonatype/nexus3' image in `cicd` namespace.
3. Build customized Jenkins agent image from /jenkins-agent and push the customized Jenkins agent image to OCP in `cicd` namespace.
4. Configure Jenkins in 'Manage Nodes and Clouds' -> 'Configure Clouds' and add new pod template with following details:-
```
Name: <name> <-- this is used as label name in pipeline script.
Labels: <name>
Containers:
  Name: jnlp
  Docker image: image-registry.openshift-image-registry.svc:5000/cicd/jenkins-agent-custom:1.0.0 <-- image name and version is from what you upload into OpenShift.
  Working directory: /home/jenkins
  Arguments to pass to the command: ${computer.jnlpmac} ${computer.name}
Service Account: jenkins
```
5. Install `Generic Webhook Trigger` plugin on Jenkins.
6. All built images are store under `cicd` namespace. So `system:serviceaccount:<namespace>:default` need to be added `system:image-puller` role.
```
oc policy add-role-to-user system:image-puller system:serviceaccount:<namespace>:default -n cicd
```
7. Update RoleBinding of `admin` role for targeting namespace by adding `jenkins` service account of `cicd` namespace.
NOTE: For code scanning, this repo is using SonarCloud for experiment.

## CI/CD using OC client and templates
### Build Pipeline
1. Create new pipeline on Jenkins.
2. Select `Do not allow concurrent builds`
3. Select `This project is parameterized` and add two String parameters named `ref` and `repo`.
4. Select `Generic Webhook Trigger` and add two Post content parameters.
```
Post content parameters:
- Variable: ref
  Expression: $.ref
  JSONPath: true
- Variable: repo
  Expression: $.repository.clone_url <-- for Github webhook.
  JSONPath: true
Token: helloworld
Optional filter:
  Expression: .*main <-- this will build only branches ended with 'main'.
  Text: $ref
```
5. On the pipeline script section, configure the code to use /jenkins-jobs-openshift-template/build-pipeline.groovy.
6. Go the Git repository you want to build. Then update webhook with this URL https://jenkins-cicd.apps.<openshift-domain>/generic-webhook-trigger/invoke?token=helloworld
NOTE: 
#1 Please add secret for the webhook and update the Optional filter of Generic Webhook Trigger accordingly.
#2 Example code can be found - https://github.com/athamsiramas/hello-world

This pipeline will build, unittest, code scaning and archive artifact in Nexus on OpenShift. After that, it will build application image and deploy on OpenShift.

### Deploy Pipeline
This pipeline is meant to use to deploy on other environment. It has blue-green deployment strategy feature which will use with rollout pipeline to rollout new application version.
1. Create new pipeline on Jenkins.
2. Select `This project is parameterized` and add three String parameters named `serviceName`, `serviceVersion` and `environment` (Set this param matches with environment you are setting). Then add Choice parameter named `deploymentStrategy` and Choices are `rollout` and `bluegreen`.
3. On the pipeline script section, configure the code to use /jenkins-jobs-openshift-template/deploy-pipeline.groovy.
  
### Rollout Pipeline
To rollout the application version and remove non-used application version.
1. Create new pipeline on Jenkins.
2. Select `This project is parameterized` and add three String parameters named `serviceName`, `serviceVersion` and `environment` (Set this param matches with environment you are setting).
3. On the pipeline script section, configure the code to use /jenkins-jobs-openshift-template/rollout-pipeline.groovy.
