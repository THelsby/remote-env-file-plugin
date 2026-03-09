import hudson.model.User
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm
import jenkins.install.InstallState
import jenkins.model.Jenkins

def instance = Jenkins.get()
def adminId = System.getenv("JENKINS_ADMIN_ID") ?: "admin"
def adminPassword = System.getenv("JENKINS_ADMIN_PASSWORD") ?: "admin"

def securityRealm = new HudsonPrivateSecurityRealm(false)
if (User.getById(adminId, false) == null) {
    securityRealm.createAccount(adminId, adminPassword)
}

instance.setSecurityRealm(securityRealm)

def authorization = new FullControlOnceLoggedInAuthorizationStrategy()
authorization.setAllowAnonymousRead(false)
instance.setAuthorizationStrategy(authorization)
instance.setNumExecutors(2)
instance.setInstallState(InstallState.INITIAL_SETUP_COMPLETED)
instance.save()
