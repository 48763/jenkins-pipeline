properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * *'),
	]),
])

def vars = fileLoader.fromGit(
 	'pipeline/vars.groovy',
 	'git@github.com:48763/jenkins-pipeline.git', // repo
 	'master', // branch
 	'48763-git', // credentialsId
 	'master', // node/label
)

def repo = env.JOB_BASE_NAME
def repoMeta = vars.repoMeta(repo)

node {

	env.BRANCH_BASE = repoMeta['branch-base']
	env.BRANCH_PUSH = repoMeta['branch-push']
	env.MAIN_VERSION = "1.0."

    stage('Checkout') {

		// Pull repository and check directory
		checkout([
			$class: 'GitSCM',
			userRemoteConfigs: [[
				name: 'origin',
				url: repoMeta['url'],
				credentialsId: '48763-git',
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

		sh '''
			cd repo

			git config user.name '48763'
			git config user.email 'future.starshine@gmail.com'
		'''

		// Check main branch
		if (repoMeta['branch-base'] != repoMeta['branch-push']) {
			sshagent(['48763-git']) {
				sh '''
					git -C repo pull --rebase origin "$BRANCH_BASE"
				'''
			}
		}

	}
	
	dir('repo') {
	
		def verInfo = [:]
		def DOMAIN = "lab.yukifans.com"
		def LIBRARY = "mis-application"

		stage("Update") {

			def digest

			verInfo['DIGEST']=sh(
				returnStdout: true,
				script: 'grep DIGEST ver.info | sed \'s/DIGEST=//g\'',
			).trim()

			digest=sh(
				returnStdout: true,
				script: 'git log --pretty=%h -1 operations/',
			).trim()

			if(verInfo['DIGEST'] != digest) {
				verInfo['VERSION']= MAIN_VERSION + (sh(
					returnStdout: true,
					script: 'grep VERSION ver.info | sed \'s/VERSION=1.0.//g\'',
				).trim().toInteger() +1)

				sh """
					sed -ri -e \'s/^(DIGEST=).*/\\1\'\"$digest\"\'/\' ver.info
					sed -ri -e \'s/^(VERSION=).*/\\1\'\"${verInfo['VERSION']}\"\'/\' ver.info

					sed -ri -e \'s/^(.*$DOMAIN\\/$LIBRARY\\/$JOB_BASE_NAME:).*/\\1${verInfo['VERSION']}/' run.sh
				"""
				// push digest and version
			}  else {
				echo "nginx not updated"
			}

		}

		stage("Diff") {
			sh '''
				git status
				git diff
			'''
		}

		stage("Commit") {

			sh '''
				git add ver.info run.sh || true
			'''
			
			sh """
				git commit -m "$JOB_BASE_NAME update to ${verInfo['VERSION']}" || true
			"""
		}

		stage("log") {
			sh '''
				git log -p "origin/$BRANCH_BASE...HEAD"
			'''
		}

		def numCommits = sh(
				returnStdout: true,
				script: 'git rev-list --count "origin/$BRANCH_BASE...HEAD"',
		).trim().toInteger()
		def hasChanges = (numCommits > 0)

		if (hasChanges) {

			stage('Build') {

				sh """
					cd operations
					docker build -t $DOMAIN/$LIBRARY/$JOB_BASE_NAME:${verInfo['VERSION']} .
				"""
			}

			stage('Test') {
				retry(3) {
					sh """
						docker run --name nginx -p 80:80 -d $DOMAIN/$LIBRARY/$JOB_BASE_NAME:${verInfo['VERSION']}
						sleep 20
						curl localhost:80 || (docker logs nginx; docker stop nginx; docker rm nginx; exit 1)
						docker stop nginx && docker rm nginx
					"""
				}
			}

			stage('Push-img') {
				withDockerRegistry([credentialsId: "harbor-admin", url:"https://$DOMAIN"]) {

					sh """
						docker push $DOMAIN/$LIBRARY/$JOB_BASE_NAME:${verInfo['VERSION']}
					"""
				}
			}

			stage('Run') {
				sshagent(['48763-jenkins']) {
					sh '''
						scp run.sh 192.168.200.2:~
						ssh 192.168.200.2 """
							sh run.sh
						"""
					'''
				}
			}

			stage('Push-git') {
				sshagent(['48763-git']) {
					sh 'git push $([ "$BRANCH_BASE" = "$BRANCH_PUSH" ] || echo --force) origin "HEAD:$BRANCH_PUSH"'
				}
			}

		} else {
			echo("No changes in ${repo}!  Skipping.")
		}

	}

}