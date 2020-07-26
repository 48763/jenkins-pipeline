properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * *'),
	]),
])

def vars = fileLoader.fromGit(
 	'vars.groovy',
 	'git@github.com:48763/jenkins.git', // repo
 	'master', // branch
 	'git-bot', // credentialsId
 	'master', // node/label
)

def repo = env.JOB_BASE_NAME
def repoMeta = vars.repoMeta(repo)

node {

	env.BRANCH_BASE = repoMeta['branch-base']
	env.BRANCH_PUSH = repoMeta['branch-push']
	env.MAIN_VERSION = "2.0."

    stage('Checkout') {

		// Pull repository and check directory
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

		sh '''
			cd repo

			git config user.name 'yuki_cheng'
			git config user.email 'yuki.cheng@gmail.com'
		'''

		// Check main branch
		if (repoMeta['branch-base'] != repoMeta['branch-push']) {
			sshagent(['git-bot']) {
				sh '''
					git -C repo pull --rebase origin "$BRANCH_BASE"
				'''
			}
		}

	}
	
	def verInfo = [:]

	dir('repo') {

		stage("Update") {

			parallel (
				"appserver": {
					
					def digest

					verInfo['appserver_DIGEST']=sh(
						returnStdout: true,
						script: 'grep appserver_DIGEST ver.info | sed \'s/appserver_DIGEST=//g\'',
					).trim()

					digest=sh(
						returnStdout: true,
						script: 'git log --pretty=%h -1 appserver/',
					).trim()

					if(verInfo['appserver_DIGEST'] != digest) {
						verInfo['appserver_VERSION']= "$MAIN_VERSION" + (sh(
							returnStdout: true,
							script: 'grep appserver_VERSION ver.info | sed \'s/appserver_VERSION=2.0.//g\'',
						).trim().toInteger() +1)

						sh """
						sed -ri -e \'s/^(appserver_DIGEST=).*/\\1\'\"$digest\"\'/\' ver.info
						sed -ri -e \'s/^(appserver_VERSION=).*/\\1\'\"${verInfo['appserver_VERSION']}\"\'/\' ver.info
						sed -ri -e \'s/^(.*\\/appserver:).*/\\1\'\"${verInfo['appserver_VERSION']}\"\'/\' docker-compose.yml
						"""

					} else {
						echo "appserver not updated"
					}
				},
				"web": {

					def digest

					verInfo['web_DIGEST']=sh(
						returnStdout: true,
						script: 'grep web_DIGEST ver.info | sed \'s/web_DIGEST=//g\'',
					).trim()

					digest=sh(
						returnStdout: true,
						script: 'git log --pretty=%h -1 web/',
					).trim()

					if(verInfo['web_DIGEST'] != digest) {
						verInfo['web_VERSION']= "$MAIN_VERSION" + (sh(
							returnStdout: true,
							script: 'grep web_VERSION ver.info | sed \'s/web_VERSION=2.0.//g\'',
						).trim().toInteger() +1)

						sh """
						sed -ri -e \'s/^(web_DIGEST=).*/\\1\'\"$digest\"\'/\' ver.info
						sed -ri -e \'s/^(web_VERSION=).*/\\1\'\"${verInfo['web_VERSION']}\"\'/\' ver.info
						sed -ri -e \'s/^(.*\\/web:).*/\\1\'\"${verInfo['web_VERSION']}\"\'/\' docker-compose.yml
						"""
						// push digest and version
					} else {
						echo "web not updated"
					}
				},
				"database": {

					def digest

					verInfo['database_DIGEST']=sh(
						returnStdout: true,
						script: 'grep database_DIGEST ver.info | sed \'s/database_DIGEST=//g\'',
					).trim()

					digest=sh(
						returnStdout: true,
						script: 'git log --pretty=%h -1 database/',
					).trim()

					if(verInfo['database_DIGEST'] != digest) {
						verInfo['database_VERSION']= MAIN_VERSION + (sh(
							returnStdout: true,
							script: 'grep database_VERSION ver.info | sed \'s/database_VERSION=2.0.//g\'',
						).trim().toInteger() +1)

						sh """
						sed -ri -e \'s/^(database_DIGEST=).*/\\1\'\"$digest\"\'/\' ver.info
						sed -ri -e \'s/^(database_VERSION=).*/\\1\'\"${verInfo['database_VERSION']}\"\'/\' ver.info
						sed -ri -e \'s/^(.*\\/database:).*/\\1\'\"${verInfo['database_VERSION']}\"\'/\' docker-compose.yml
						"""
						// push digest and version
					}  else {
						echo "database not updated"
					}
				}
			)

			if (verInfo['appserver_VERSION'] != null || verInfo['web_VERSION'] != null || verInfo['database_VERSION'] != null) {
				verInfo['service_VERSION'] = MAIN_VERSION + (sh(
					returnStdout: true,
					script: 'grep service-system ver.info | sed \'s/service-system=2.0.//g\'',
				).trim().toInteger() +1)

				sh """
					sed -ri -e \'s/^(service-system=).*/\\1\'\"${verInfo['service_VERSION']}\"\'/\' ver.info
				"""
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
				git add ver.info docker-compose.yml || true
			'''
			
			sh """
				git commit -m "$JOB_BASE_NAME update to ${verInfo['service_VERSION']}" || true
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

			def DOMAIN = "lab.yukifans.com"
			def LIBRARY = "service-system"

			stage('Build') {

				parallel (
					"appserver": {
						if (verInfo['appserver_VERSION'] != null) {
							withDockerRegistry([credentialsId: "harbor-admin", url:"https://$DOMAIN"]) {
								sh """
									docker pull $DOMAIN/$LIBRARY/appserver-env:1.0.1
								"""
							}

							sh """
								cd appserver
								docker run --name app-env -v $WORKSPACE/repo/appserver:/data $DOMAIN/$LIBRARY/appserver-env:1.0.1
								docker rm app-env
								docker build -t $DOMAIN/$LIBRARY/appserver:${verInfo['appserver_VERSION']} .
							"""
						} else {
							echo "appserver is already the newest version."
						}
					},
					"web": {
						if (verInfo['web_VERSION'] != null) {
							sh """
								cd web
								docker build -t $DOMAIN/$LIBRARY/web:${verInfo['web_VERSION']} . 
							"""
						} else {
							echo "web is already the newest version."
						}
					},
					"database": {
						if (verInfo['database_VERSION'] != null) {
							
							sh """
								cd database/docker-ml
								docker build -t $DOMAIN/$LIBRARY/database:${verInfo['database_VERSION']} .
							"""
						} else {
							echo "database is already the newest version."
						}
					}
				)
			}

			stage('Test') {
				retry(3) {
					sh '''
						docker stack deploy -c docker-compose.yml service
						sleep 20
						curl 192.168.0.2:3000 || (docker stack rm service; sleep 20; exit 1)
						# Should need to add databases and appserver test 
						docker stack rm service
					'''
				}
			}

			stage('Push-img') {
				withDockerRegistry([credentialsId: "harbor-admin", url:"https://$DOMAIN"]) {
					
					sh """
						echo docker stack rm service >> update.sh
						echo sleep 20 >> update.sh
					"""

					if (verInfo['appserver_VERSION'] != null) {
						sh """
							docker push $DOMAIN/$LIBRARY/appserver:${verInfo['appserver_VERSION']}
						"""
					}

					if (verInfo['web_VERSION'] != null) {
						sh """
							docker push $DOMAIN/$LIBRARY/web:${verInfo['web_VERSION']}
						"""
					}

					if (verInfo['database_VERSION'] != null) {
						sh """
							docker push $DOMAIN/$LIBRARY/database:${verInfo['database_VERSION']}
						"""
					}

					sh """
						echo docker stack deploy --with-registry-auth -c docker-compose.yml service >> update.sh
					"""

				}
			}

			stage('Run') {
				sshagent(['jenkins-lab']) {
					sh '''
						scp update.sh docker-compose.yml 192.168.0.1:~
						ssh 192.168.0.1 """
							sh update.sh
						"""
					'''
				}
			}

			stage('Push-git') {
				sshagent(['git-bot']) {
					sh 'git push $([ "$BRANCH_BASE" = "$BRANCH_PUSH" ] || echo --force) origin "HEAD:$BRANCH_PUSH"'
				}
			}

		} else {
			echo("No changes in ${repo}!  Skipping.")
		}

	}

}