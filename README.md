# cicd-jenkins-ocp
How to create CI/CD pipelines for applications on OpenShift

oc policy add-role-to-user system:image-puller system:serviceaccount:appuat:default -n cicd-test

update rolebinding of admin for appuat namespace by adding jenkins service account of cicd-test
