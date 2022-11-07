properties([
    buildDiscarder(logRotator(numToKeepStr: '10')),
    disableConcurrentBuilds(),
    // pipelineTriggers([
    //     cron('H H * * *'),
    // ]),
])

def vars = fileLoader.fromGit(
     'vars.groovy',
     'git@github.com:48763/jenkins-pipeline.git', // Repo
     'main', // Branch
     'git-bot', // CredentialsId
     'master', // Jenkins node label
)

def repoMeta = vars.repoMeta(env.JOB_BASE_NAME)

node {

    stage('Checkout') {

        checkout([
            $class: 'GitSCM',
            userRemoteConfigs: [[
                name: 'origin',
                url: repoMeta['url'],
                credentialsId: 'git-bot',
            ]],
            branches: [[name: '*/' + repoMeta['branch-push']]],
            extensions: [
                [
                    $class: 'CleanCheckout',
                ],
                [
                    $class: 'RelativeTargetDirectory',
                    relativeTargetDir: 'repo',
                ],
            ],
            doGenerateSubmoduleConfigurations: false,
            submoduleCfg: [],
        ])

        sh """
            cd repo

            git config user.name  'Jenkins'
            git config user.email 'jenkins@yukifans.com'
        """

        if (repoMeta['branch-base'] != repoMeta['branch-push']) {
            sshagent(['git-bot']) {
                sh """
                    git -C repo pull --rebase origin ${repoMeta['branch-base']}
                """
            }
        }
    }

    def verInfo = [:]
    def imageName

    dir('repo') {

        stage("Update") {

            def main_digest
            def env_digest

            main_digest=sh(
                returnStdout: true,
                script: "git log --pretty='%h' -1 || true" ,
            ).trim()

            env_digest=sh(
                returnStdout: true,
                script: "git log --pretty='%h' -1 .jenkins/env || true" , 
            ).trim()

            if(main_digest != env_digest) {
                verInfo['BUILD_VERSION']= "${repoMeta['env']}" + (sh(
                    returnStdout: true,
                    script: 'grep BUILD_VERSION .jenkins/env | sed \'s/BUILD_VERSION=1.0.//g\'',
                ).trim().toInteger() +1)
                
                imageName = "${repoMeta['registry']}/${JOB_BASE_NAME}:${verInfo['BUILD_VERSION']}"

                sh """
                    sed -ri -e "s|(image: ).*|\\1${imageName}|" docker-compose.yaml
                    sed -ri -e "s/^(BUILD_VERSION=).*/\\1${verInfo['BUILD_VERSION']}/" .jenkins/env
                    sed -ri -e "s/^(DIGEST=).*/\\1${main_digest}/" .jenkins/env
                """
            } else {
                echo "${JOB_BASE_NAME} not updated"
            }

        }

        stage("Diff") {
            sh """
                git status
                git diff
            """
        }

        stage("Commit") {

            sh """
                git add .jenkins/env docker-compose.yaml || true
                git commit -m "${JOB_BASE_NAME} update to ${verInfo['BUILD_VERSION']}" || true
            """
        }

        stage("log") {
                sh """
                    git log -p "origin/${repoMeta['branch-base']}...HEAD"
                """
        }

        def numCommits = sh(
                returnStdout: true,
                script: """git rev-list --count \"origin/${repoMeta['branch-base']}...HEAD\"""",
        ).trim().toInteger()
        def hasChanges = (numCommits > 0)

        if (hasChanges) {

            stage('Build') {
                sh """
                    docker build . -t ${imageName}
                """
            }

            stage('Test') {
                sh '''
                    docker-compose up -d  || true
                    cd tests
                    ./main.sh 3 $(ls -I main.sh) 
                '''   
            }

            stage("Launch") {
                input(
                    message: "Do you want continue pipeline?",
                    ok: "Yes, I want."
                )
            }

            stage('Push-Image') {
                withDockerRegistry([credentialsId: "registry"]) {
                    sh """
                        docker push ${imageName}
                    """
                }
            }

            stage('Deploy') {
                sshagent(['ssh']) {
                    // Check remote user permission to www
                    // Remote IP to parameterize
                    sh """
                        # TO-DO
                    """
                }
            }

            stage('Push-Git') {
                sshagent(['git-bot']) {
                    sh """
                        git push \$([ "${repoMeta['branch-base']}" = "${repoMeta['branch-push']}" ] || echo --force) \
                            origin "HEAD:${repoMeta['branch-push']}"
                    """
                }
            }

        } else {
            echo("No changes in ${JOB_BASE_NAME}!  Skipping.")
        }

    }

}
