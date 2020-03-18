//////////////////////////////////////////////////////////////////////////////////////
/////////// Jenkins Master Seed Job. It builds all Jenkins Jobs for Jenkins //////////
//////////////////////////////////////////////////////////////////////////////////////
import jenkins.model.Jenkins;

// arrays containing information for Jobs to build (see README.md)
def buildJobs = [
        [name: 'api-generator', view: 'blog', repo: 'ivan1405/api-generator', jenkinsfile: 'Jenkinsfile', branch: 'master'],
]

// jobs we want to delete
def jobsToWipeOut = []

// constants:
// name of the secret containing a valid token to use for git operations
def secretName = "github-token"
// the github host
def githubHost = "https://github.com"

// we delete only Jobs we really don't want to keep
deleteJobs(jobsToWipeOut as String[])

// create jobs:
// 1. create "normal" build jobs
buildJobs.findAll { it.multi != true }.each { job ->
    pipelineJob(job.name) {
        logRotator() {
            artifactNumToKeep(5)
            numToKeep(5)
        }
        concurrentBuild(false)
        definition {
            cpsScm {
                scm {
                    git {
                        remote {
                            url("${githubHost}/${job.repo}.git")
                            credentials(secretName)
                            branch(job.branch)
                        }
                    }
                }
                scriptPath(job.jenkinsfile)
            }
        }
        if (job.trigger != false || job.predecessor != null) {
            triggers {
                if (job.trigger != false) {
                    scm('H/2 * * * *')
                }
                if (job.predecessor != null) {
                    upstream(job.predecessor, 'SUCCESS')
                }
            }
        }
    }
}

// 2. create multibranch build jobs
buildJobs.findAll { it.multi == true }.each { job ->
    multibranchPipelineJob(job.name) {
        branchSources {
            git {
                remote("${githubHost}/${job.repo}.git")
                credentialsId(secretName)
                includes(job.branch)
            }
        }
        orphanedItemStrategy {
            discardOldItems {
                numToKeep(0)
            }
        }
        configure {
            it / factory(class: 'org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory') {
                scriptPath(job.jenkinsfile)
            }
        }
        triggers {
            periodic(1)
        }
    }
}

// 3. create release build jobs
buildJobs.findAll { it.release == true }.each { releaseJob ->
    // Do not use whitepace in mavenJob names
    mavenJob("${releaseJob.name}-release") {
        logRotator() {
            artifactNumToKeep(10)
            numToKeep(10)
        }
        mavenInstallation('Maven-LATEST')
        scm {
            git {
                remote {
                    url("${githubHost}/${releaseJob.repo}.git")
                    credentials(secretName)
                    branch(releaseJob.branch)
                }
                extensions {
                    // clean workspace to avoid remaining local tags etc.
                    wipeOutWorkspace()
                    // needed to be able to do local commits during maven release (we must checkout a branch, not a commit)
                    localBranch(releaseJob.branch)
                }
            }
        }
        wrappers {
            credentialsBinding {
                // needed to be able to push during maven release
                usernamePassword('GIT_USERNAME', 'GIT_PASSWORD', secretName)
            }
        }
        preBuildSteps {
            // needed to be able to do local commits during maven release
            shell('git config user.email jenkins@${PROJECT_NAME}')
        }
        goals(
                '-Dpassword=${GIT_PASSWORD} -Dusername=${GIT_USERNAME} -DtagNameFormat=@{project.version} -Darguments="-Dmaven.javadoc.failOnError=false -DskipTests" release:clean release:prepare release:perform')
    }
}

// create views:
// 1. create views defined in Job array
buildJobs.groupBy { it.view }.each { view, jobsPerView ->
    listView(view) {
        jobs {
            jobsPerView.each { job ->
                name("${job.name}")
            }
        }
        columns {
            status()
            weather()
            name()
            lastSuccess()
            lastFailure()
            lastDuration()
            buildButton()
        }
    }
}

// 2. create release view containing the release jobs
listView("releases") {
    jobs {
        buildJobs.findAll { it.release == true }.each { job ->
            name("${job.name}-release")
        }
        columns {
            status()
            weather()
            name()
            lastSuccess()
            lastFailure()
            lastDuration()
            buildButton()
        }
    }
}


// save current Jenkins state to disk
Jenkins.instance.save()


// functions

// deletes an array ob jobs
def deleteJobs(String[] jobsToDelete) {
    Jenkins.instance.items.each { item ->
        if (jobsToDelete.contains(item.fullName)) {
            item.delete()
            println("Deleted job '${item.fullName}'")
        }
    }
}