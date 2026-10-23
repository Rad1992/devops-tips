import groovy.transform.Field

/*
 * =====================================================================================
 *  PIPELINE CI/CD GÉNÉRIQUE — VERSION SIMPLIFIÉE
 *  GitLab (Merge Request) -> Jenkins -> Build/Tests -> SonarQube -> Xray (Frogbot) -> Artifactory
 * =====================================================================================
 *  Utilisation (job Pipeline, "Pipeline script") :
 *
 *      @Library('new-generic-cicd') _
 *      genericCiCdSimple(gitlabUrl: 'https://gitlab.mycompany.com', jfrogUrl: 'https://mycompany.jfrog.io')
 *
 *  Chaque brique s'active séparément (mise en place progressive, voir docs/GUIDE-CICD-SIMPLE.md) :
 *      enableGitlabStatus, enableSonar, enableXray, enableDeploy, enableMrComment, enableMail
 *
 *  Règles (identiques à la version complète) :
 *    open/reopen/update  * -> develop          => VALIDATE : build + Sonar + Xray, pas de livraison
 *    merge               * -> develop          => SNAPSHOT : build + Sonar + livraison snapshots
 *    open/reopen/update  develop -> release*   => RELEASE  : build + Sonar + Xray + livraison releases
 *    open/reopen/update  release* -> master    => PROD     : build + Sonar + Xray + livraison prod
 *    autre                                     => ignoré (NOT_BUILT)
 * =====================================================================================
 */

@Field Map SIMPLE_DEFAULTS = [
    // --- Jenkins ---
    agentLabel         : 'maven',
    jdkTool            : 'jdk-21',       // Administrer Jenkins > Tools
    mavenTool          : 'maven-3.9',    // Maven >= 3.9 (variable MAVEN_ARGS)
    nodeTool           : 'nodejs-20',
    sonarScannerTool   : 'sonar-scanner',

    // --- GitLab ---
    gitlabUrl          : 'https://gitlab.example.com',
    gitCredId          : 'gitlab-git-creds',      // Username/Password : clone
    gitlabTokenCredId  : 'gitlab-api-token',      // Secret text (scope api) : statuts, commentaires, Frogbot
    webhookTokenCredId : 'gitlab-webhook-token',  // Secret text : secret du System Hook
    statusName         : 'jenkins/ci-cd',

    // --- Branches ---
    developBranch      : 'develop',
    releasePrefix      : 'release',               // release, release/1.2, release-1.2 ...
    prodBranch         : 'master',

    // --- Fichiers gérés (Config File Provider). '' = aucun (Maven Central / registry npm public) ---
    mavenSettingsFileId: 'maven-settings-artifactory',
    npmrcFileId        : 'npmrc-artifactory',

    // --- SonarQube ---
    sonarServer        : 'SonarQube',             // Administrer Jenkins > System > SonarQube servers

    // --- JFrog ---
    jfrogUrl           : 'https://mycompany.jfrog.io',   // sans /artifactory ni / final
    jfrogTokenCredId   : 'jfrog-access-token',            // Secret text (Xray)
    xrayMinSeverity    : 'High',
    frogbotDownloadUrl : 'https://releases.jfrog.io/artifactory/frogbot/v2/[RELEASE]/getFrogbot.sh',
    // {group} = 1er segment du chemin GitLab (groupe racine), en minuscules
    repositories       : [
        SNAPSHOT: [maven: 'libs-snapshots-{group}', npm: 'npm-snapshots-{group}'],
        RELEASE : [maven: 'libs-releases-{group}',  npm: 'npm-releases-{group}'],
        PROD    : [maven: 'libs-prod-{group}',      npm: 'npm-prod-{group}'],
    ],

    // --- Mail ---
    mailFallbackTo     : 'devops@example.com',    // si l'e-mail de l'auteur est absent/masqué

    // --- Interrupteurs (mise en place étape par étape) ---
    enableGitlabStatus : true,
    enableSonar        : true,
    enableXray         : true,
    enableDeploy       : true,
    enableMrComment    : true,
    enableMail         : true,
]

def call(Map config = [:]) {
    Map cfg = SIMPLE_DEFAULTS + config

    // Le webhook ne crée un build que pour les flux gérés (texte filtré : "type#action#source#cible")
    String dev = cfg.developBranch, rel = "${cfg.releasePrefix}[^#]*", prod = cfg.prodBranch
    String webhookFilter = '^merge_request#(' +
                           "(open|reopen|update|merge)#[^#]+#${dev}" +
                           "|(open|reopen|update)#${dev}#${rel}" +
                           "|(open|reopen|update)#${rel}#${prod}" +
                           ')$'

    pipeline {
        agent { label "${cfg.agentLabel}" }

        options {
            timestamps()
            timeout(time: 60, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '50'))
            skipDefaultCheckout()
        }

        triggers {
            // Chaque champ JSON du payload GitLab devient une variable d'environnement
            GenericTrigger(
                genericVariables: [
                    [key: 'GL_OBJECT_KIND',   value: '$.object_kind'],
                    [key: 'GL_ACTION',        value: '$.object_attributes.action'],
                    [key: 'GL_SOURCE_BRANCH', value: '$.object_attributes.source_branch'],
                    [key: 'GL_TARGET_BRANCH', value: '$.object_attributes.target_branch'],
                    [key: 'GL_MR_IID',        value: '$.object_attributes.iid'],
                    [key: 'GL_MR_URL',        value: '$.object_attributes.url'],
                    [key: 'GL_OLDREV',        value: '$.object_attributes.oldrev'],
                    [key: 'GL_LAST_COMMIT',   value: '$.object_attributes.last_commit.id'],
                    [key: 'GL_AUTHOR_EMAIL',  value: '$.object_attributes.last_commit.author.email'],
                    [key: 'GL_PROJECT_ID',    value: '$.project.id'],
                    [key: 'GL_PROJECT_PATH',  value: '$.project.path_with_namespace'],
                    [key: 'GL_GIT_URL',       value: '$.project.git_http_url'],
                ],
                tokenCredentialId     : cfg.webhookTokenCredId,
                causeString           : 'GitLab MR !$GL_MR_IID ($GL_ACTION) $GL_SOURCE_BRANCH -> $GL_TARGET_BRANCH [$GL_PROJECT_PATH]',
                printPostContent      : false,
                regexpFilterText      : '$GL_OBJECT_KIND#$GL_ACTION#$GL_SOURCE_BRANCH#$GL_TARGET_BRANCH',
                regexpFilterExpression: webhookFilter
            )
        }

        stages {
            stage('1. Analyse MR & checkout') {
                steps {
                    script {
                        env.CI_MODE = resolveMode(cfg) ?: ''
                        if (!env.CI_MODE) {
                            echo "Événement non concerné (${env.GL_ACTION ?: 'pas de payload'}) -> rien à faire."
                            currentBuild.result = 'NOT_BUILT'
                            return
                        }
                        checkoutMr(cfg)
                        env.CI_TYPE = fileExists('pom.xml') ? 'maven' : (fileExists('package.json') ? 'npm' : '')
                        if (!env.CI_TYPE) {
                            echo 'Ni pom.xml ni package.json -> rien à faire.'
                            currentBuild.result = 'NOT_BUILT'
                            return
                        }
                        env.CI_GROUP = env.GL_PROJECT_PATH.tokenize('/')[0].toLowerCase()
                        currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.GL_PROJECT_PATH} !${env.GL_MR_IID} ${env.CI_MODE}"
                        currentBuild.description = "${env.GL_SOURCE_BRANCH} -> ${env.GL_TARGET_BRANCH} | ${env.CI_TYPE}"
                        if (cfg.enableGitlabStatus) gitlabStatus(cfg, 'running')
                    }
                }
            }

            stage('CI/CD') {
                when { expression { return env.CI_MODE && env.CI_TYPE } }
                stages {
                    stage('2. Build & Tests') {
                        steps {
                            script {
                                withBuildEnv(cfg) {
                                    if (env.CI_TYPE == 'maven') {
                                        sh 'mvn clean verify'
                                    } else {
                                        sh '''
                                            if [ -f package-lock.json ]; then npm ci; else npm install; fi
                                            npm run build --if-present
                                            CI=true npm test --if-present
                                        '''
                                    }
                                }
                            }
                        }
                        post {
                            always { junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml' }
                        }
                    }

                    stage('3. SonarQube') {
                        when { expression { return cfg.enableSonar } }
                        steps { script { sonarAnalysis(cfg) } }
                    }

                    stage('4. Xray (Frogbot)') {
                        // Frogbot commente la MR : inutile après le merge (SNAPSHOT)
                        when { expression { return cfg.enableXray && env.CI_MODE != 'SNAPSHOT' } }
                        steps { script { xrayScan(cfg) } }
                    }

                    stage('5. Livraison Artifactory') {
                        when { expression { return cfg.enableDeploy && env.CI_MODE != 'VALIDATE' } }
                        steps { script { deliver(cfg) } }
                    }
                }
            }
        }

        post {
            success { script { notify(cfg, 'success') } }
            failure { script { notify(cfg, 'failed') } }
            aborted { script { notify(cfg, 'canceled') } }
            cleanup { deleteDir() }
        }
    }
}

// =====================================================================================
//  ÉTAPE 1 : quel traitement pour cet événement ?
// =====================================================================================
def resolveMode(Map cfg) {
    String action = env.GL_ACTION, src = env.GL_SOURCE_BRANCH, tgt = env.GL_TARGET_BRANCH
    if (env.GL_OBJECT_KIND != 'merge_request' || !action) return null

    // "update" sans oldrev = changement de titre/label, pas de nouveau commit
    boolean newCode = action in ['open', 'reopen'] || (action == 'update' && env.GL_OLDREV)
    boolean srcRelease = src?.startsWith(cfg.releasePrefix)
    boolean tgtRelease = tgt?.startsWith(cfg.releasePrefix)

    if (tgt == cfg.developBranch) return action == 'merge' ? 'SNAPSHOT' : (newCode ? 'VALIDATE' : null)
    if (newCode && src == cfg.developBranch && tgtRelease) return 'RELEASE'
    if (newCode && srcRelease && tgt == cfg.prodBranch) return 'PROD'
    return null
}

// SNAPSHOT : branche cible après merge ; sinon : dernier commit de la MR
def checkoutMr(Map cfg) {
    boolean merged = (env.CI_MODE == 'SNAPSHOT')
    String refspec = merged ? "+refs/heads/${env.GL_TARGET_BRANCH}:refs/remotes/origin/${env.GL_TARGET_BRANCH}"
                            : "+refs/merge-requests/${env.GL_MR_IID}/head:refs/remotes/origin/mr-${env.GL_MR_IID}"
    String revision = merged ? "refs/remotes/origin/${env.GL_TARGET_BRANCH}" : env.GL_LAST_COMMIT
    checkout([
        $class           : 'GitSCM',
        branches         : [[name: revision]],
        extensions       : [[$class: 'CloneOption', honorRefspec: true, noTags: true, shallow: false]],
        userRemoteConfigs: [[url: env.GL_GIT_URL, credentialsId: cfg.gitCredId, refspec: refspec]]
    ])
    env.STATUS_SHA = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
}

// =====================================================================================
//  OUTILS : JDK + Maven (+ settings.xml) ou Node (+ .npmrc)
// =====================================================================================
def withBuildEnv(Map cfg, Closure body) {
    String jdk = tool name: cfg.jdkTool, type: 'jdk'
    List vars = ["JAVA_HOME=${jdk}", "PATH+JDK=${jdk}/bin"]

    if (env.CI_TYPE == 'maven') {
        String maven = tool name: cfg.mavenTool, type: 'maven'
        vars << "PATH+MAVEN=${maven}/bin"
        if (!cfg.mavenSettingsFileId) {
            withEnv(vars + ['MAVEN_ARGS=-B -ntp']) { body() }
            return
        }
        configFileProvider([configFile(fileId: cfg.mavenSettingsFileId, variable: 'MAVEN_SETTINGS')]) {
            // MAVEN_ARGS est ajouté à chaque commande mvn (y compris celles lancées par Frogbot)
            withEnv(vars + ["MAVEN_ARGS=-B -ntp -s ${env.MAVEN_SETTINGS}"]) { body() }
        }
    } else {
        String node = tool name: cfg.nodeTool, type: 'nodejs'
        vars << "PATH+NODE=${node}/bin"
        if (!cfg.npmrcFileId) {
            withEnv(vars) { body() }
            return
        }
        configFileProvider([configFile(fileId: cfg.npmrcFileId, variable: 'NPM_CONFIG_USERCONFIG')]) {
            withEnv(vars) { body() }
        }
    }
}

// =====================================================================================
//  ÉTAPE 3 : SONARQUBE (Quality Gate bloquant)
// =====================================================================================
def sonarAnalysis(Map cfg) {
    withEnv(["SONAR_KEY=${env.GL_PROJECT_PATH.replace('/', ':')}"]) {
        withBuildEnv(cfg) {
            withSonarQubeEnv(cfg.sonarServer) {
                if (env.CI_TYPE == 'maven') {
                    sh 'mvn org.sonarsource.scanner.maven:sonar-maven-plugin:sonar -Dsonar.projectKey="$SONAR_KEY" -Dsonar.projectName="$GL_PROJECT_PATH"'
                } else {
                    withEnv(["SCANNER_HOME=${tool cfg.sonarScannerTool}"]) {
                        sh '''"$SCANNER_HOME/bin/sonar-scanner" -Dsonar.projectKey="$SONAR_KEY" -Dsonar.projectName="$GL_PROJECT_PATH" \
                              -Dsonar.sources=. -Dsonar.exclusions="**/node_modules/**,**/dist/**,**/coverage/**"'''
                    }
                }
            }
        }
    }
    // Attend le webhook SonarQube -> https://<jenkins>/sonarqube-webhook/
    timeout(time: 15, unit: 'MINUTES') {
        waitForQualityGate abortPipeline: true
    }
}

// =====================================================================================
//  ÉTAPE 4 : XRAY via FROGBOT (commente la MR avec les vulnérabilités)
// =====================================================================================
def xrayScan(Map cfg) {
    String path = env.GL_PROJECT_PATH
    List fbEnv = [
        "JF_URL=${cfg.jfrogUrl}",
        'JF_GIT_PROVIDER=gitlab',
        "JF_GIT_API_ENDPOINT=${cfg.gitlabUrl}/api/v4",
        "JF_GIT_OWNER=${path.substring(0, path.lastIndexOf('/'))}",
        "JF_GIT_REPO=${path.tokenize('/').last()}",
        "JF_GIT_PULL_REQUEST_ID=${env.GL_MR_IID}",
        "JF_GIT_BASE_BRANCH=${env.GL_TARGET_BRANCH}",
        'JF_FAIL=TRUE',
        "JF_MIN_SEVERITY=${cfg.xrayMinSeverity}",
        "FROGBOT_URL=${cfg.frogbotDownloadUrl}",
        "FROGBOT_DIR=${env.WORKSPACE}@tmp/frogbot",
    ]
    if (env.CI_TYPE == 'npm') fbEnv << 'JF_INSTALL_DEPS_CMD=npm ci'

    withCredentials([string(credentialsId: cfg.jfrogTokenCredId, variable: 'JF_ACCESS_TOKEN'),
                     string(credentialsId: cfg.gitlabTokenCredId, variable: 'JF_GIT_TOKEN')]) {
        withBuildEnv(cfg) {
            withEnv(fbEnv) {
                sh '''
                    set -e
                    mkdir -p "$FROGBOT_DIR"
                    [ -x "$FROGBOT_DIR/frogbot" ] || ( cd "$FROGBOT_DIR" && curl -fLsSg "$FROGBOT_URL" | sh )
                    "$FROGBOT_DIR/frogbot" scan-pull-request
                '''
            }
        }
    }
}

// =====================================================================================
//  ÉTAPE 5 : LIVRAISON ARTIFACTORY
// =====================================================================================
def deliver(Map cfg) {
    String repo = cfg.repositories[env.CI_MODE][env.CI_TYPE].replace('{group}', env.CI_GROUP)
    withBuildEnv(cfg) {
        String version = computeVersion()
        echo "Livraison ${env.CI_TYPE} ${version} -> ${repo}"
        currentBuild.description += " | ${version} -> ${repo}"

        if (env.CI_TYPE == 'maven') {
            // l'id "artifactory" doit avoir ses identifiants dans le settings.xml géré (<server>)
            withEnv(["NEW_VERSION=${version}", "DEPLOY_URL=${cfg.jfrogUrl}/artifactory/${repo}"]) {
                sh '''
                    mvn versions:set -DnewVersion="$NEW_VERSION" -DgenerateBackupPoms=false -DprocessAllModules=true
                    mvn deploy -DskipTests -DaltDeploymentRepository="artifactory::default::$DEPLOY_URL"
                '''
            }
        } else {
            String tag = [SNAPSHOT: 'snapshot', RELEASE: 'release', PROD: 'latest'][env.CI_MODE]
            withEnv(["NEW_VERSION=${version}", "DEPLOY_URL=${cfg.jfrogUrl}/artifactory/api/npm/${repo}/", "NPM_TAG=${tag}"]) {
                sh '''
                    npm version "$NEW_VERSION" --no-git-tag-version --allow-same-version
                    npm publish --registry "$DEPLOY_URL" --tag "$NPM_TAG"
                '''
            }
        }
    }
}

// 1.4.0-SNAPSHOT -> 1.4.0 -> SNAPSHOT: 1.4.0-SNAPSHOT | RELEASE: 1.4.0.RELEASE | PROD: 1.4.0
def computeVersion() {
    String current = (env.CI_TYPE == 'maven')
        ? sh(returnStdout: true, script: 'mvn -q help:evaluate -Dexpression=project.version -DforceStdout').trim()
        : sh(returnStdout: true, script: 'node -p "require(\'./package.json\').version"').trim()
    String base = current.replaceAll(/^(\d+(\.\d+)*).*$/, '$1')
    if (!(base ==~ /\d+(\.\d+)*/)) error("Version '${current}' non exploitable (attendu X.Y.Z)")

    Map formats = [
        SNAPSHOT: [maven: "${base}-SNAPSHOT", npm: "${base}-snapshot.${env.BUILD_NUMBER}"],
        RELEASE : [maven: "${base}.RELEASE",  npm: "${base}-RELEASE"],   // npm exige du semver
        PROD    : [maven: base,               npm: base],
    ]
    return formats[env.CI_MODE][env.CI_TYPE].toString()
}

// =====================================================================================
//  NOTIFICATIONS : statut du commit, commentaire MR, e-mail
// =====================================================================================
def notify(Map cfg, String state) {
    if (!env.CI_TYPE) return   // événement ignoré : rien à notifier
    try {
        if (cfg.enableGitlabStatus) gitlabStatus(cfg, state)

        String icon = [success: ':white_check_mark:', failed: ':x:', canceled: ':no_entry:'][state]
        String text = "${icon} Jenkins ${env.CI_MODE} : **${state.toUpperCase()}** — [build #${env.BUILD_NUMBER}](${env.BUILD_URL})"
        if (cfg.enableMrComment) gitlabComment(cfg, text)

        if (cfg.enableMail) {
            String to = env.GL_AUTHOR_EMAIL?.contains('@') && !env.GL_AUTHOR_EMAIL.contains('REDACTED') ? env.GL_AUTHOR_EMAIL : cfg.mailFallbackTo
            emailext(
                to       : to,
                subject  : "[Jenkins][${state.toUpperCase()}] ${env.GL_PROJECT_PATH} !${env.GL_MR_IID} ${env.GL_SOURCE_BRANCH} -> ${env.GL_TARGET_BRANCH} (${env.CI_MODE})",
                mimeType : 'text/html',
                body     : "<p>Pipeline <b>${env.CI_MODE}</b> : <b>${state}</b></p>" +
                           "<p>Merge request : <a href='${env.GL_MR_URL}'>${env.GL_MR_URL}</a><br>" +
                           "Build Jenkins : <a href='${env.BUILD_URL}'>${env.BUILD_URL}</a></p>",
                attachLog: state != 'success'
            )
        }
    } catch (Exception e) {
        echo "Notification impossible : ${e.message}"
    }
}

// state : running | success | failed | canceled  (bloque la MR avec "Pipelines must succeed")
def gitlabStatus(Map cfg, String state) {
    withCredentials([string(credentialsId: cfg.gitlabTokenCredId, variable: 'GITLAB_TOKEN')]) {
        withEnv(["GITLAB_URL=${cfg.gitlabUrl}", "STATE=${state}", "STATUS_NAME=${cfg.statusName}"]) {
            sh '''
                curl -sS -o /dev/null -w "Statut GitLab $STATE : HTTP %{http_code}\\n" -X POST \
                  -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
                  "$GITLAB_URL/api/v4/projects/$GL_PROJECT_ID/statuses/$STATUS_SHA" \
                  --data-urlencode "state=$STATE" \
                  --data-urlencode "name=$STATUS_NAME" \
                  --data-urlencode "target_url=$BUILD_URL"
            '''
        }
    }
}

def gitlabComment(Map cfg, String text) {
    withCredentials([string(credentialsId: cfg.gitlabTokenCredId, variable: 'GITLAB_TOKEN')]) {
        withEnv(["GITLAB_URL=${cfg.gitlabUrl}", "NOTE=${text}"]) {
            sh '''
                curl -sS -o /dev/null -w "Commentaire MR : HTTP %{http_code}\\n" -X POST \
                  -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
                  "$GITLAB_URL/api/v4/projects/$GL_PROJECT_ID/merge_requests/$GL_MR_IID/notes" \
                  --data-urlencode "body=$NOTE"
            '''
        }
    }
}
