def defaultRepoMeta = [
    ['url', 'git@github.com:48763/%%REPO%%.git'],
    ['env', '.+_VERSION'], // gawk regex, anchored
    ['otherEnvs', []],
    ['branch-base', 'main'], // branch to check out from
    ['branch-push', 'main'], // branch to push to
]

def rawReposData = [
    // TODO busybox (BUSYBOX_VERSION) -- looong builds
    ['jenkins-test-project', [
        'env': 'TEST_VERSION',
    ]],
    ['nodejs-sample', [
        'env': 'TEST_VERSION'
    ]]
]

// list of repos: ["celery", "wordpress", ...]
repos = []

// map of repo metadata: ["celery": ["url": "...", ...], ...]
reposMeta = [:]

def repoMeta(repo) {
    return reposMeta[repo]
}

for (int i = 0; i < rawReposData.size(); ++i) {
    def repo = rawReposData[i][0]
    def repoMeta = rawReposData[i][1]

    // apply "defaultRepoMeta" for missing bits
    //   wouldn't it be grand if we could just use "map1 + map2" here??
    //   dat Jenkins sandbox...
    for (int j = 0; j < defaultRepoMeta.size(); ++j) {
        def key = defaultRepoMeta[j][0]
        def val = defaultRepoMeta[j][1]
        if (repoMeta[key] == null) {
            repoMeta[key] = val
        }
    }

    repoMeta['url'] = repoMeta['url'].replaceAll('%%REPO%%', repo)

    repos << repo
    reposMeta[repo] = repoMeta
}

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
