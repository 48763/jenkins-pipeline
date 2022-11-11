node {
    withCredentials([sshUserPrivateKey(credentialsId: 'git-bot', keyFileVariable: 'secret')]) {
        echo secret
        
        sh '''
            cat $secret
        '''
    }

}