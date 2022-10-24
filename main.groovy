properties([
    buildDiscarder(logRotator(numToKeepStr: '10')),
    disableConcurrentBuilds(),
    // pipelineTriggers([
    //     cron('H H * * *'),
    // ]),
])

def vars = fileLoader.fromGit(
     'vars.groovy',
     'git@github.com:48763/jenkins-pipeline.git', // repo
     'main', // branch
     'git-bot', // credentialsId
     'master', // node/label
)

def repo = env.JOB_BASE_NAME
def repoMeta = vars.repoMeta(repo)

node {

    env.BRANCH_BASE = repoMeta['branch-base']
    env.BRANCH_PUSH = repoMeta['branch-push']
    // 
    env.MAIN_VERSION = "1.0."

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
                    git -C repo pull --rebase origin "${BRANCH_BASE}"
                """
            }
        }
    }

    def verInfo = [:]

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
                verInfo['BUILD_VERSION']= "${MAIN_VERSION}" + (sh(
                    returnStdout: true,
                    script: 'grep BUILD_VERSION .jenkins/env | sed \'s/BUILD_VERSION=1.0.//g\'',
                ).trim().toInteger() +1)
                // Service to parameterize
                sh """
                    sed -ri -e \"s/^(.*\\/service\\/${repo}:).*/\\1${verInfo['BUILD_VERSION']}/\" docker-compose.yaml
                    sed -ri -e \'s/^(BUILD_VERSION=).*/\\1\'\"${verInfo['BUILD_VERSION']}\"\'/\' .jenkins/env
                    sed -ri -e \'s/^(DIGEST=).*/\\1\'\"$main_digest\"\'/\' .jenkins/env
                """
            } else {
                echo "${repo} not updated"
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
                git commit -m "${JOB_BASE_NAME} update ${repo} to ${verInfo['BUILD_VERSION']}" || true
            """
        }

        stage("log") {
                sh """
                    git log -p "origin/${BRANCH_BASE}...HEAD"
                """
        }

        def numCommits = sh(
                returnStdout: true,
                script: 'git rev-list --count "origin/${BRANCH_BASE}...HEAD"',
        ).trim().toInteger()
        def hasChanges = (numCommits > -1)

        if (hasChanges) {

            def DOMAIN = "harbor.yukifans.com"
            def LIBRARY = "service"

            stage('Build') {
                sh """
                    docker build . -t ${DOMAIN}/${LIBRARY}/${repo}:${verInfo['BUILD_VERSION']}
                """
            }

            stage('Test') {
                sh '''
                    docker-compose up -d
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

            stage('Deploy') {
                sshagent(['ssh']) {
                    // Check remote user permission to www
                    // Remote IP to parameterize
                    sh """

                    """
                }
            }

            stage('Push-Git') {
                sshagent(['git-bot']) {
                    sh 'git push $([ "$BRANCH_BASE" = "$BRANCH_PUSH" ] || echo --force) origin "HEAD:$BRANCH_PUSH"'
                }

                withDockerRegistry([credentialsId: "registry"]) {
                    sh """
                        docker push ${DOMAIN}/${LIBRARY}/${repo}:${verInfo['BUILD_VERSION']}
                    """
                }
            }

        } else {
            echo("No changes in ${repo}!  Skipping.")
        }

    }

}
