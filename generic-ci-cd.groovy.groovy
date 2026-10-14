#!/usr/bin/env groovy
/*
 * =====================================================================================
 *  PIPELINE CI/CD (Continuous Delivery) : GitLab -> Jenkins -> SonarQube / Xray (Frogbot) -> Artifactory
 * =====================================================================================
 *
 *  Déclenchement : un "System Hook" GitLab (Admin > System Hooks, case "Merge request events")
 *                  -> https://<jenkins>/generic-webhook-trigger/invoke   (Secret token = credential WEBHOOK_TOKEN_CRED)
 *
 *  Règles :
 *  ┌──────────────────────────────────────────────┬──────────────────────────────────────────────────────────────┐
 *  │ Événement MR                                 │ Action                                                       │
 *  ├──────────────────────────────────────────────┼──────────────────────────────────────────────────────────────┤
 *  │ open / reopen / update(nouveau commit)       │ VALIDATE : CI + Sonar + Xray, statut sur le commit           │
 *  │   * -> develop                               │            (bloque la MR si KO), pas de livraison            │
 *  │ merge  * -> develop                          │ SNAPSHOT : CI + Sonar + Xray + livraison                     │
 *  │                                              │            libs-snapshots-<group> / npm-snapshots-<group>    │
 *  │ open / reopen / update  develop -> release*  │ RELEASE  : CI + Sonar + Xray + livraison                     │
 *  │                                              │            libs-releases-<group> / npm-releases-<group>      │
 *  │ open / reopen / update  release* -> master   │ PROD     : CI + Sonar + Xray + livraison                     │
 *  │                                              │            libs-prod-<group> / npm-prod-<group>              │
 *  │ tout le reste                                │ ignoré (NOT_BUILT)                                           │
 *  └──────────────────────────────────────────────┴──────────────────────────────────────────────────────────────┘
 *  - Type de projet : pom.xml => maven, package.json => npm, sinon rien n'est fait.
 *  - Résultat : statut de commit GitLab (bloque la MR avec "Pipelines must succeed"), commentaire sur la MR
 *    et e-mail au committer avec les liens SonarQube + Xray. En cas d'échec, la cause est incluse.
 *  - Xray : uniquement via la CLI Frogbot (pas de commande "jf").
 *
 *  PLUGINS JENKINS REQUIS (jenkins-plugin-cli --plugins ...) :
 *    workflow-aggregator      (Pipeline)
 *    git                      (Git)
 *    generic-webhook-trigger  (Generic Webhook Trigger)
 *    pipeline-utility-steps   (readJSON / writeJSON / readProperties, >= 2.10 pour returnPojo)
 *    http_request             (HTTP Request -> API GitLab)
 *    sonar                    (SonarQube Scanner : withSonarQubeEnv / waitForQualityGate)
 *    email-ext                (Email Extension : emailext)
 *    credentials-binding      (withCredentials)
 *    nodejs                   (installation NodeJS)
 *    junit                    (rapports de tests)
 *    ws-cleanup               (cleanWs)
 *    timestamper              (timestamps)
 *
 *  OUTILS (Manage Jenkins > Tools) : JDK "JDK17", Maven "Maven3", NodeJS "NodeJS20", SonarQube Scanner "SonarScanner"
 *  SERVEUR SONAR (Manage Jenkins > System) : nom "SonarQube" + token ; webhook SonarQube -> https://<jenkins>/sonarqube-webhook/
 *
 *  CREDENTIALS :
 *    gitlab-api-token      Secret text        : token GitLab (scope "api") – statuts, notes MR, Frogbot
 *    gitlab-git-creds      Username/Password  : utilisateur + token (read_repository) pour le clone
 *    gitlab-webhook-token  Secret text        : secret du System Hook GitLab
 *    artifactory-deployer  Username/Password  : utilisateur de déploiement Artifactory (mot de passe ou token)
 *    jfrog-access-token    Secret text        : access token JFrog Platform (Xray) pour Frogbot
 *
 *  IMPORTANT : lancer le job une première fois à la main pour enregistrer le trigger (le build sera NOT_BUILT).
 * =====================================================================================
 */

import groovy.transform.Field
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

// =====================================================================================
//  CONFIGURATION
// =====================================================================================
@Field Map CFG = [
    AGENT_LABEL            : 'linux',

    // --- GitLab ---
    GITLAB_URL             : 'https://gitlab.example.com',
    GITLAB_API_CRED        : 'gitlab-api-token',
    GITLAB_GIT_CRED        : 'gitlab-git-creds',
    WEBHOOK_TOKEN_CRED     : 'gitlab-webhook-token',
    COMMIT_STATUS_NAME     : 'jenkins/ci-cd',

    // --- Branches ---
    DEVELOP_BRANCH         : 'develop',
    RELEASE_BRANCH_REGEX   : '^release.*',
    PROD_BRANCH            : 'master',
    VALIDATE_MR_TO_DEVELOP : true,          // CI + blocage sur les MR ouvertes vers develop (avant merge)

    // --- JFrog / Artifactory ---
    JFROG_URL              : 'https://mycompany.jfrog.io',   // URL plateforme, sans "/artifactory", sans "/" final
    ARTIFACTORY_CRED       : 'artifactory-deployer',
    JFROG_TOKEN_CRED       : 'jfrog-access-token',
    // 'top' = 1er segment du chemin GitLab (groupe racine) ; 'parent' = groupe parent direct
    REPO_GROUP_MODE        : 'top',
    // {group} est remplacé par le groupe GitLab du projet (en minuscules)
    REPOSITORIES           : [
        SNAPSHOT: [maven: 'libs-snapshots-{group}', npm: 'npm-snapshots-{group}'],
        RELEASE : [maven: 'libs-releases-{group}',  npm: 'npm-releases-{group}'],
        PROD    : [maven: 'libs-prod-{group}',      npm: 'npm-prod-{group}'],
    ],
    MAVEN_RESOLVE_REPO     : '',            // ex: 'libs-virtual' -> mirror Maven ; '' = Maven Central direct
    NPM_RESOLVE_REPO       : '',            // ex: 'npm-virtual'  -> registry npm ; '' = registry par défaut

    // --- Xray via Frogbot ---
    FROGBOT_DOWNLOAD_URL   : 'https://releases.jfrog.io/artifactory/frogbot/v2/[RELEASE]/getFrogbot.sh',
    FROGBOT_MIN_SEVERITY   : 'High',        // Low | Medium | High | Critical
    FROGBOT_CMD_ON_MERGE   : 'scan-repository', // commande après merge sur develop ('' = pas de scan). NB: peut ouvrir des MR de fix
    XRAY_WATCHES           : '',            // ex: 'watch-prod,watch-sec'
    JFROG_PROJECT          : '',            // clé de projet JFrog (optionnel)
    FROGBOT_DEPS_REPO      : '',            // repo virtuel de résolution des dépendances pour Frogbot (optionnel)

    // --- SonarQube ---
    SONAR_SERVER           : 'SonarQube',
    SONAR_SCANNER_TOOL     : 'SonarScanner',
    SONAR_BRANCH_SUPPORT   : false,         // true si Developer Edition+ (analyse de branche / PR)
    QUALITY_GATE_TIMEOUT   : 15,            // minutes

    // --- Outils ---
    JDK_TOOL               : 'JDK17',
    MAVEN_TOOL             : 'Maven3',
    NODEJS_TOOL            : 'NodeJS20',
    VERSIONS_PLUGIN        : 'org.codehaus.mojo:versions-maven-plugin:2.16.2',
    HELP_PLUGIN            : 'org.apache.maven.plugins:maven-help-plugin:3.4.0',
    SONAR_MAVEN_PLUGIN     : 'org.sonarsource.scanner.maven:sonar-maven-plugin',

    // --- Mail ---
    MAIL_FROM              : 'jenkins@example.com',
    MAIL_FALLBACK_TO       : 'devops@example.com',  // si l'e-mail du committer est introuvable
    MAIL_CC                : '',
]

// Contexte d'exécution (rempli à partir du payload GitLab)
@Field Map ctx = [
    failures: [],
    results : [:],
    aborted : false,
]

// =====================================================================================
//  TRIGGER : System Hook GitLab (Merge Request events) via Generic Webhook Trigger
// =====================================================================================
properties([
    buildDiscarder(logRotator(numToKeepStr: '100', artifactNumToKeepStr: '20')),
    pipelineTriggers([
        GenericTrigger(
            genericVariables: [
                [key: 'GL_PAYLOAD',       value: '$'],
                [key: 'GL_OBJECT_KIND',   value: '$.object_kind'],
                [key: 'GL_ACTION',        value: '$.object_attributes.action'],
                [key: 'GL_SOURCE_BRANCH', value: '$.object_attributes.source_branch'],
                [key: 'GL_TARGET_BRANCH', value: '$.object_attributes.target_branch'],
                [key: 'GL_PROJECT',       value: '$.project.path_with_namespace'],
                [key: 'GL_MR_IID',        value: '$.object_attributes.iid'],
            ],
            tokenCredentialId        : CFG.WEBHOOK_TOKEN_CRED,   // envoyé par GitLab dans l'en-tête X-Gitlab-Token
            causeString              : 'GitLab MR !$GL_MR_IID ($GL_ACTION) $GL_SOURCE_BRANCH -> $GL_TARGET_BRANCH [$GL_PROJECT]',
            printContributedVariables: false,
            printPostContent         : false,
            silentResponse           : false,
            regexpFilterText         : '$GL_OBJECT_KIND#$GL_ACTION',
            regexpFilterExpression   : '^merge_request#(open|reopen|update|merge)$'
        )
    ])
])

// =====================================================================================
//  PIPELINE
// =====================================================================================
node(CFG.AGENT_LABEL) {
    timestamps {
        try {
            stage('Analyse événement GitLab') {
                parseEvent()
                ctx.mode = resolveMode()
            }
            if (!ctx.mode) {
                echo "Événement non concerné par la CI/CD -> rien à faire."
                currentBuild.result = 'NOT_BUILT'
                currentBuild.description = "Ignoré : ${ctx.projectPath ?: 'pas de payload'} ${ctx.source ?: ''} -> ${ctx.target ?: ''} (${ctx.action ?: '-'})"
                return
            }
            currentBuild.displayName = "#${env.BUILD_NUMBER} ${ctx.projectName} !${ctx.mrIid} ${ctx.mode}"

            stage('Checkout') {
                checkoutSources()
            }

            stage('Détection type projet') {
                ctx.type = detectProjectType()
                echo "Type de projet détecté : ${ctx.type ?: 'aucun (ni pom.xml ni package.json)'}"
            }
            if (!ctx.type) {
                currentBuild.result = 'NOT_BUILT'
                currentBuild.description = "Ignoré : ${ctx.projectPath} n'est ni Maven ni npm"
                return
            }
            currentBuild.description = "${ctx.projectPath} | ${ctx.source} -> ${ctx.target} | ${ctx.mode} | ${ctx.type}"

            // Le statut "running" bloque la MR pendant l'exécution (avec "Pipelines must succeed")
            ctx.statusSha = (ctx.mode == 'SNAPSHOT') ? ctx.buildSha : ctx.headSha
            setCommitStatus('running', "CI/CD ${ctx.mode} en cours")
            resolveCommitter()

            withCiEnv {
                runStep('Build & Tests') { buildAndTest() }

                if (ctx.results['Build & Tests'] == 'OK') {
                    runStep('SonarQube') { sonarAnalysis() }
                    runStep('Xray (Frogbot)') { xrayScan() }
                } else {
                    skipStep('SonarQube', 'build en échec')
                    skipStep('Xray (Frogbot)', 'build en échec')
                }

                if (ctx.mode == 'VALIDATE') {
                    skipStep('Livraison Artifactory', 'validation de MR uniquement (livraison au merge)')
                } else if (!ctx.failures.isEmpty()) {
                    skipStep('Livraison Artifactory', 'contrôles qualité/sécurité en échec')
                } else {
                    runStep('Livraison Artifactory') { deliver() }
                }
            }
        } catch (FlowInterruptedException fie) {
            ctx.aborted = true
            throw fie
        } catch (Exception e) {
            ctx.failures << [stage: 'Pipeline', cause: (e.message ?: e.toString())]
            currentBuild.result = 'FAILURE'
        } finally {
            if (ctx.mode && (ctx.type || !ctx.failures.isEmpty())) {
                stage('Notification') {
                    try {
                        notifyResult()
                    } catch (Exception ne) {
                        echo "Notification impossible : ${ne.message}"
                    }
                }
            }
            if (!ctx.failures.isEmpty() && !ctx.aborted) {
                currentBuild.result = 'FAILURE'
            }
            cleanWs(deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true)
            dir("${env.WORKSPACE}@tmp") { deleteDir() }
        }
    }
}

// =====================================================================================
//  ÉVÉNEMENT GITLAB
// =====================================================================================
def parseEvent() {
    if (!env.GL_PAYLOAD?.trim()) {
        echo 'Aucun payload GitLab (build manuel ?)'
        return
    }
    def p  = readJSON(text: env.GL_PAYLOAD, returnPojo: true)
    def oa = p.object_attributes ?: [:]

    ctx.objectKind  = p.object_kind
    ctx.projectId   = p.project?.id
    ctx.projectPath = p.project?.path_with_namespace
    ctx.projectName = ctx.projectPath?.tokenize('/')?.last()
    ctx.namespace   = ctx.projectPath?.contains('/') ? ctx.projectPath.substring(0, ctx.projectPath.lastIndexOf('/')) : ctx.projectPath
    ctx.gitUrl      = p.project?.git_http_url
    ctx.webUrl      = p.project?.web_url
    ctx.mrIid       = oa.iid
    ctx.mrUrl       = oa.url
    ctx.mrTitle     = oa.title
    ctx.action      = oa.action
    ctx.source      = oa.source_branch
    ctx.target      = oa.target_branch
    ctx.oldrev      = oa.oldrev
    ctx.headSha     = oa.last_commit?.id
    ctx.mergeSha    = oa.merge_commit_sha
    ctx.authorEmail = oa.last_commit?.author?.email
    ctx.authorName  = oa.last_commit?.author?.name
    ctx.triggeredBy = p.user?.username

    def segments = ctx.projectPath?.tokenize('/') ?: []
    if (segments.size() > 0) {
        String g = (CFG.REPO_GROUP_MODE == 'parent' && segments.size() > 1) ? segments[segments.size() - 2] : segments[0]
        ctx.group = g.toLowerCase()
    }

    echo """Événement : ${ctx.objectKind} / ${ctx.action}
Projet    : ${ctx.projectPath} (id=${ctx.projectId}, groupe=${ctx.group})
MR        : !${ctx.mrIid} ${ctx.source} -> ${ctx.target}
Commit    : ${ctx.headSha} (merge=${ctx.mergeSha})"""
}

def resolveMode() {
    if (ctx.objectKind != 'merge_request' || !ctx.projectId) return null

    // "update" n'est pertinent que s'il y a de nouveaux commits (oldrev présent)
    boolean codeChanged = (ctx.action in ['open', 'reopen']) || (ctx.action == 'update' && ctx.oldrev)
    boolean srcRelease  = ctx.source ==~ CFG.RELEASE_BRANCH_REGEX
    boolean tgtRelease  = ctx.target ==~ CFG.RELEASE_BRANCH_REGEX

    if (ctx.target == CFG.DEVELOP_BRANCH) {
        if (ctx.action == 'merge') return 'SNAPSHOT'
        if (codeChanged && CFG.VALIDATE_MR_TO_DEVELOP) return 'VALIDATE'
        return null
    }
    if (codeChanged && ctx.source == CFG.DEVELOP_BRANCH && tgtRelease) return 'RELEASE'
    if (codeChanged && srcRelease && ctx.target == CFG.PROD_BRANCH) return 'PROD'
    return null
}

// =====================================================================================
//  CHECKOUT & DÉTECTION
// =====================================================================================
def checkoutSources() {
    String refspec
    String revision
    if (ctx.mode == 'SNAPSHOT') {
        // Après merge : on construit le commit de merge sur develop
        refspec  = "+refs/heads/${ctx.target}:refs/remotes/origin/${ctx.target}"
        revision = ctx.mergeSha ?: "refs/remotes/origin/${ctx.target}"
    } else {
        // MR ouverte : on construit le HEAD de la MR (fonctionne aussi pour les forks)
        refspec  = "+refs/merge-requests/${ctx.mrIid}/head:refs/remotes/origin/mr-${ctx.mrIid} " +
                   "+refs/heads/${ctx.target}:refs/remotes/origin/${ctx.target}"
        revision = ctx.headSha
    }
    checkout([
        $class           : 'GitSCM',
        branches         : [[name: revision]],
        extensions       : [
            [$class: 'CleanBeforeCheckout'],
            [$class: 'CloneOption', honorRefspec: true, noTags: true, shallow: false, timeout: 20],
        ],
        userRemoteConfigs: [[url: ctx.gitUrl, credentialsId: CFG.GITLAB_GIT_CRED, refspec: refspec]]
    ])
    ctx.buildSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    echo "Commit construit : ${ctx.buildSha}"
}

def detectProjectType() {
    if (fileExists('pom.xml'))      return 'maven'
    if (fileExists('package.json')) return 'npm'
    return null
}

def resolveCommitter() {
    ctx.committerEmail = null
    try {
        def c = gitlabApi('GET', "projects/${ctx.projectId}/repository/commits/${ctx.headSha}")
        ctx.committerEmail = c?.author_email
        ctx.committerName  = c?.author_name
    } catch (Exception e) {
        echo "Lecture du commit via l'API GitLab impossible : ${e.message}"
    }
    if (!ctx.committerEmail || !ctx.committerEmail.contains('@') || ctx.committerEmail.contains('REDACTED')) {
        ctx.committerEmail = ctx.authorEmail
        ctx.committerName  = ctx.authorName
    }
    if (!ctx.committerEmail || !ctx.committerEmail.contains('@') || ctx.committerEmail.contains('REDACTED')) {
        ctx.committerEmail = CFG.MAIL_FALLBACK_TO
    }
    echo "Destinataire des notifications : ${ctx.committerName ?: '?'} <${ctx.committerEmail}>"
}

// =====================================================================================
//  ENVIRONNEMENT DE BUILD (outils + accès Artifactory)
// =====================================================================================
def withCiEnv(Closure body) {
    String tmp = "${env.WORKSPACE}@tmp"
    String jdk = tool(CFG.JDK_TOOL)
    List envList = ["CI_TMP=${tmp}", "JAVA_HOME=${jdk}", "PATH+JDK=${jdk}/bin"]
    if (ctx.type == 'maven') {
        envList << "PATH+MAVEN=${tool(CFG.MAVEN_TOOL)}/bin"
        envList << "MVN_SETTINGS=${tmp}/ci-settings.xml"
    } else {
        envList << "PATH+NODE=${tool(CFG.NODEJS_TOOL)}/bin"
        envList << "NPM_CONFIG_USERCONFIG=${tmp}/ci.npmrc"
        envList << "ART_NPM_NERF=${CFG.JFROG_URL.replaceFirst('^https?://', '')}/artifactory/api/npm/"
        envList << "NPM_RESOLVE_URL=${CFG.NPM_RESOLVE_REPO ? "${CFG.JFROG_URL}/artifactory/api/npm/${CFG.NPM_RESOLVE_REPO}/" : ''}"
    }
    withEnv(envList) {
        withCredentials([usernamePassword(credentialsId: CFG.ARTIFACTORY_CRED,
                                          usernameVariable: 'ART_USER', passwordVariable: 'ART_PASS')]) {
            sh 'mkdir -p "$CI_TMP"'
            try {
                if (ctx.type == 'maven') {
                    writeMavenSettings()
                } else {
                    writeNpmrc()
                }
                body()
            } finally {
                sh 'rm -f "$CI_TMP/ci-settings.xml" "$CI_TMP/ci.npmrc"'
            }
        }
    }
}

def writeMavenSettings() {
    String mirror = CFG.MAVEN_RESOLVE_REPO ? """
  <mirrors>
    <mirror>
      <id>artifactory</id>
      <mirrorOf>*</mirrorOf>
      <url>${CFG.JFROG_URL}/artifactory/${CFG.MAVEN_RESOLVE_REPO}</url>
    </mirror>
  </mirrors>""" : ''
    // Les identifiants restent des variables d'environnement (jamais écrits en clair)
    writeFile file: env.MVN_SETTINGS, text: '''<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <servers>
    <server>
      <id>artifactory</id>
      <username>${env.ART_USER}</username>
      <password>${env.ART_PASS}</password>
    </server>
  </servers>''' + mirror + '''
</settings>
'''
}

def writeNpmrc() {
    sh '''#!/bin/bash
        set -euo pipefail
        AUTH=$(printf '%s:%s' "$ART_USER" "$ART_PASS" | base64 | tr -d '\\n')
        {
          if [ -n "${NPM_RESOLVE_URL:-}" ]; then echo "registry=${NPM_RESOLVE_URL}"; fi
          echo "//${ART_NPM_NERF}:_auth=${AUTH}"
        } > "$NPM_CONFIG_USERCONFIG"
        chmod 600 "$NPM_CONFIG_USERCONFIG"
    '''
}

// =====================================================================================
//  BUILD & TESTS
// =====================================================================================
def buildAndTest() {
    if (ctx.type == 'maven') {
        try {
            runLogged('maven-build', 'mvn -B -ntp -s "$MVN_SETTINGS" clean verify')
        } finally {
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
        }
    } else {
        runLogged('npm-build', '''
            if [ -f package-lock.json ]; then npm ci; else npm install; fi
            npm run lint --if-present
            npm run build --if-present
            CI=true npm run test --if-present
        ''')
    }
}

// =====================================================================================
//  SONARQUBE
// =====================================================================================
def sonarAnalysis() {
    String projectKey = ctx.projectPath.replace('/', ':')
    List envList = [
        "SONAR_PROJECT_KEY=${projectKey}",
        "SONAR_PROJECT_NAME=${ctx.projectPath}",
        "MR_IID=${ctx.mrIid}",
        "MR_SOURCE=${ctx.source}",
        "MR_TARGET=${ctx.target}",
    ]
    String extra = ''
    if (CFG.SONAR_BRANCH_SUPPORT) {
        extra = (ctx.mode == 'SNAPSHOT')
            ? ' -Dsonar.branch.name="$MR_TARGET"'
            : ' -Dsonar.pullrequest.key="$MR_IID" -Dsonar.pullrequest.branch="$MR_SOURCE" -Dsonar.pullrequest.base="$MR_TARGET"'
    }

    String reportTask
    withEnv(envList) {
        withSonarQubeEnv(CFG.SONAR_SERVER) {
            if (ctx.type == 'maven') {
                reportTask = 'target/sonar/report-task.txt'
                runLogged('sonar', 'mvn -B -ntp -s "$MVN_SETTINGS" ' + CFG.SONAR_MAVEN_PLUGIN + ':sonar ' +
                          '-Dsonar.projectKey="$SONAR_PROJECT_KEY" -Dsonar.projectName="$SONAR_PROJECT_NAME"' + extra)
            } else {
                reportTask = '.scannerwork/report-task.txt'
                String scannerHome = tool(CFG.SONAR_SCANNER_TOOL)
                String opts = ''
                if (!fileExists('sonar-project.properties')) {
                    opts += ' -Dsonar.sources=. -Dsonar.exclusions="**/node_modules/**,**/dist/**,**/build/**,**/coverage/**"'
                }
                if (fileExists('coverage/lcov.info')) {
                    opts += ' -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info'
                }
                withEnv(["SONAR_SCANNER_BIN=${scannerHome}/bin/sonar-scanner"]) {
                    runLogged('sonar', '"$SONAR_SCANNER_BIN" -Dsonar.projectKey="$SONAR_PROJECT_KEY" ' +
                              '-Dsonar.projectName="$SONAR_PROJECT_NAME"' + opts + extra)
                }
            }
        }
    }

    if (fileExists(reportTask)) {
        def rt = readProperties(file: reportTask)
        ctx.sonarUrl    = rt.dashboardUrl
        ctx.sonarTaskId = rt.ceTaskId
    }

    timeout(time: CFG.QUALITY_GATE_TIMEOUT, unit: 'MINUTES') {
        def qg = waitForQualityGate(abortPipeline: false)
        ctx.sonarStatus = qg.status
        if (qg.status != 'OK') {
            String details = sonarFailedConditions()
            error("Quality Gate SonarQube : ${qg.status}${details ? '\n' + details : ''}")
        }
    }
}

// Récupère les conditions en échec du Quality Gate (pour l'e-mail)
def sonarFailedConditions() {
    if (!ctx.sonarTaskId) return ''
    try {
        String out = ''
        withEnv(["CE_TASK_ID=${ctx.sonarTaskId}"]) {
            withSonarQubeEnv(CFG.SONAR_SERVER) {
                def task = readJSON(returnPojo: true, text: sh(returnStdout: true,
                    script: 'curl -fsS -u "$SONAR_AUTH_TOKEN:" "$SONAR_HOST_URL/api/ce/task?id=$CE_TASK_ID"'))
                String analysisId = task?.task?.analysisId
                if (analysisId) {
                    withEnv(["ANALYSIS_ID=${analysisId}"]) {
                        def st = readJSON(returnPojo: true, text: sh(returnStdout: true,
                            script: 'curl -fsS -u "$SONAR_AUTH_TOKEN:" "$SONAR_HOST_URL/api/qualitygates/project_status?analysisId=$ANALYSIS_ID"'))
                        def lines = []
                        for (def c : (st?.projectStatus?.conditions ?: [])) {
                            if (c.status == 'ERROR') {
                                lines << "- ${c.metricKey} = ${c.actualValue} (seuil ${c.comparator} ${c.errorThreshold})"
                            }
                        }
                        out = lines.join('\n')
                    }
                }
            }
        }
        return out
    } catch (Exception e) {
        echo "Détail du Quality Gate indisponible : ${e.message}"
        return ''
    }
}

// =====================================================================================
//  XRAY via FROGBOT (pas de commande "jf")
// =====================================================================================
def xrayScan() {
    String fbCmd = (ctx.mode == 'SNAPSHOT') ? CFG.FROGBOT_CMD_ON_MERGE : 'scan-pull-request'
    if (!fbCmd) {
        ctx.xrayStatus = 'SKIPPED'
        echo 'Scan Xray désactivé pour ce mode.'
        return
    }
    ctx.xrayUrl = (fbCmd == 'scan-pull-request') ? ctx.mrUrl : "${env.BUILD_URL}console"

    List fbEnv = [
        "FROGBOT_DIR=${env.CI_TMP}/frogbot",
        "FROGBOT_LOG=${env.CI_TMP}/frogbot.log",
        "FROGBOT_DOWNLOAD_URL=${CFG.FROGBOT_DOWNLOAD_URL}",
        "FROGBOT_CMD=${fbCmd}",
        "JF_URL=${CFG.JFROG_URL}",
        'JF_GIT_PROVIDER=gitlab',
        "JF_GIT_API_ENDPOINT=${CFG.GITLAB_URL}/api/v4",
        "JF_GIT_OWNER=${ctx.namespace}",
        "JF_GIT_REPO=${ctx.projectName}",
        "JF_GIT_BASE_BRANCH=${ctx.target}",
        "JF_GIT_PULL_REQUEST_ID=${ctx.mrIid}",
        'JF_FAIL=TRUE',
        "JF_MIN_SEVERITY=${CFG.FROGBOT_MIN_SEVERITY}",
        'JF_INCLUDE_ALL_VULNERABILITIES=FALSE',
        "JF_WORKING_DIR=.",
    ]
    if (ctx.type == 'npm')        fbEnv << 'JF_INSTALL_DEPS_CMD=npm ci'
    if (CFG.XRAY_WATCHES)         fbEnv << "JF_WATCHES=${CFG.XRAY_WATCHES}"
    if (CFG.JFROG_PROJECT)        fbEnv << "JF_PROJECT=${CFG.JFROG_PROJECT}"
    if (CFG.FROGBOT_DEPS_REPO)    fbEnv << "JF_DEPS_REPO=${CFG.FROGBOT_DEPS_REPO}"

    int rc
    withEnv(fbEnv) {
        withCredentials([string(credentialsId: CFG.JFROG_TOKEN_CRED, variable: 'JF_ACCESS_TOKEN'),
                         string(credentialsId: CFG.GITLAB_API_CRED,  variable: 'JF_GIT_TOKEN')]) {
            sh '''#!/bin/bash
                set -euo pipefail
                mkdir -p "$FROGBOT_DIR"
                if [ ! -x "$FROGBOT_DIR/frogbot" ]; then
                  ( cd "$FROGBOT_DIR" && curl -fLsSg "$FROGBOT_DOWNLOAD_URL" | sh )
                fi
            '''
            rc = sh(returnStatus: true, script: '''#!/bin/bash
                set -o pipefail
                "$FROGBOT_DIR/frogbot" "$FROGBOT_CMD" 2>&1 | tee "$FROGBOT_LOG"
            ''')
        }
        if (rc != 0) {
            ctx.xrayStatus = 'FAILED'
            String tail = sh(returnStdout: true, script: 'tail -n 40 "$FROGBOT_LOG" 2>/dev/null || true').trim()
            error("Xray/Frogbot (${fbCmd}) : problèmes de sécurité >= ${CFG.FROGBOT_MIN_SEVERITY} ou erreur (exit ${rc}).\n${tail}")
        }
    }
    ctx.xrayStatus = 'OK'
}

// =====================================================================================
//  LIVRAISON ARTIFACTORY
// =====================================================================================
def deliver() {
    String repo    = CFG.REPOSITORIES[ctx.mode][ctx.type].replace('{group}', ctx.group)
    String version = computeVersion()
    ctx.targetRepo = repo
    ctx.version    = version
    echo "Livraison ${ctx.type} version ${version} -> ${repo}"

    if (ctx.type == 'maven') {
        withEnv(["NEW_VERSION=${version}", "DEPLOY_URL=${CFG.JFROG_URL}/artifactory/${repo}"]) {
            runLogged('maven-deploy', '''
                mvn -B -ntp -s "$MVN_SETTINGS" ''' + CFG.VERSIONS_PLUGIN + ''':set -DnewVersion="$NEW_VERSION" -DgenerateBackupPoms=false -DprocessAllModules=true
                mvn -B -ntp -s "$MVN_SETTINGS" deploy -DskipTests -DaltDeploymentRepository="artifactory::default::$DEPLOY_URL"
            ''')
        }
    } else {
        String distTag = [SNAPSHOT: 'snapshot', RELEASE: 'rc', PROD: 'latest'][ctx.mode]
        withEnv(["NEW_VERSION=${version}",
                 "DEPLOY_URL=${CFG.JFROG_URL}/artifactory/api/npm/${repo}/",
                 "NPM_DIST_TAG=${distTag}"]) {
            runLogged('npm-publish', '''
                npm version "$NEW_VERSION" --no-git-tag-version --allow-same-version
                npm publish --registry "$DEPLOY_URL" --tag "$NPM_DIST_TAG"
            ''')
        }
    }
    ctx.artifactUrl = "${CFG.JFROG_URL}/ui/repos/tree/General/${repo}"
}

/*
 * Versionnement :
 *   SNAPSHOT : maven X.Y.Z-SNAPSHOT        | npm X.Y.Z-snapshot.<build>
 *   RELEASE  : X.Y.Z-rc.<build>            (release candidate, unique à chaque build)
 *   PROD     : X.Y.Z                       (version finale)
 */
def computeVersion() {
    String current
    if (ctx.type == 'maven') {
        current = sh(returnStdout: true, script: 'mvn -B -ntp -q -s "$MVN_SETTINGS" ' + CFG.HELP_PLUGIN +
                     ':evaluate -Dexpression=project.version -DforceStdout').trim()
    } else {
        current = sh(returnStdout: true, script: 'node -p "require(\'./package.json\').version"').trim()
    }
    String base = current.replaceAll('-.*$', '')
    switch (ctx.mode) {
        case 'SNAPSHOT':
            return (ctx.type == 'maven') ? "${base}-SNAPSHOT".toString() : "${base}-snapshot.${env.BUILD_NUMBER}".toString()
        case 'RELEASE':
            return "${base}-rc.${env.BUILD_NUMBER}".toString()
        default:
            return base
    }
}

// =====================================================================================
//  NOTIFICATIONS : statut GitLab, commentaire MR, e-mail au committer
// =====================================================================================
def notifyResult() {
    boolean ok = !ctx.aborted && ctx.failures.isEmpty()
    String state = ctx.aborted ? 'canceled' : (ok ? 'success' : 'failed')
    String failedStages = ctx.failures.collect { it.stage }.join(', ')

    // 1) Statut de commit -> bloque la MR si "failed" (Settings > Merge requests > Pipelines must succeed)
    setCommitStatus(state, ok ? "CI/CD ${ctx.mode} OK" : "Échec : ${failedStages}")

    // 2) Commentaire sur la MR
    if (ctx.mrIid) {
        gitlabApi('POST', "projects/${ctx.projectId}/merge_requests/${ctx.mrIid}/notes", [body: buildMrNote(ok)])
    }

    // 3) E-mail au committer
    String subject = "[Jenkins][${ok ? 'SUCCÈS' : (ctx.aborted ? 'ANNULÉ' : 'ÉCHEC')}] ${ctx.projectPath} !${ctx.mrIid} " +
                     "${ctx.source} → ${ctx.target} (${modeLabel()})"
    emailext(
        to         : ctx.committerEmail,
        cc         : CFG.MAIL_CC,
        from       : CFG.MAIL_FROM,
        subject    : subject,
        mimeType   : 'text/html',
        body       : buildMailBody(ok),
        attachLog  : !ok,
        compressLog: true
    )
}

def modeLabel() {
    return [VALIDATE: 'CI validation MR', SNAPSHOT: 'CI + livraison SNAPSHOT',
            RELEASE : 'CI + livraison RELEASE', PROD: 'CI + livraison PROD'][ctx.mode] ?: ctx.mode
}

def resultIcon(String r) {
    return [OK: ':white_check_mark:', FAILED: ':x:', SKIPPED: ':fast_forward:', ABORTED: ':no_entry:'][r] ?: ':grey_question:'
}

def buildMrNote(boolean ok) {
    String md = "### ${ok ? ':white_check_mark:' : ':x:'} Jenkins CI/CD — ${ok ? 'SUCCÈS' : 'ÉCHEC'} (${modeLabel()})\n\n"
    md += "Commit : `${ctx.statusSha ?: ctx.headSha}` · Projet : **${ctx.type}**\n\n"
    md += "| Étape | Résultat |\n|---|---|\n"
    ctx.results.each { k, v -> md += "| ${k} | ${resultIcon(v)} ${v} |\n" }
    md += "\n- **SonarQube** : ${ctx.sonarUrl ? "[rapport d'analyse](${ctx.sonarUrl})" : 'n/a'} ${ctx.sonarStatus ? "(Quality Gate : ${ctx.sonarStatus})" : ''}\n"
    md += "- **Xray (Frogbot)** : ${ctx.xrayUrl ? "[rapport](${ctx.xrayUrl})" : 'n/a'} ${ctx.xrayStatus ? "(${ctx.xrayStatus})" : ''}\n"
    if (ctx.targetRepo) {
        md += "- **Artifactory** : version `${ctx.version}` livrée dans [${ctx.targetRepo}](${ctx.artifactUrl})\n"
    }
    md += "- **Jenkins** : [build #${env.BUILD_NUMBER}](${env.BUILD_URL})\n"
    if (!ok && !ctx.failures.isEmpty()) {
        md += "\n#### Cause(s) de l'échec\n"
        ctx.failures.each { f -> md += "\n**${f.stage}**\n```\n${f.cause}\n```\n" }
        if (ctx.mode != 'SNAPSHOT') md += "\n> :no_entry: La merge request est bloquée tant que ce pipeline n'est pas au vert.\n"
    }
    return md
}

def buildMailBody(boolean ok) {
    String color = ok ? '#2e7d32' : '#c62828'
    String rows = ''
    ctx.results.each { k, v ->
        String c = (v == 'OK') ? '#2e7d32' : ((v == 'FAILED') ? '#c62828' : '#757575')
        rows += "<tr><td style='padding:4px 12px'>${esc(k)}</td><td style='padding:4px 12px;color:${c};font-weight:bold'>${esc(v)}</td></tr>"
    }
    String causes = ''
    if (!ok) {
        ctx.failures.each { f ->
            causes += "<h4 style='margin:12px 0 4px'>${esc(f.stage)}</h4>" +
                      "<pre style='background:#f5f5f5;padding:8px;white-space:pre-wrap;font-size:12px'>${esc(f.cause)}</pre>"
        }
        if (ctx.mode != 'SNAPSHOT') {
            causes += "<p><b>La merge request est bloquée</b> tant que le pipeline n'est pas en succès. Corrigez puis poussez un nouveau commit.</p>"
        }
    }
    return """<html><body style="font-family:Arial,sans-serif;font-size:14px">
<h2 style="color:${color}">${ok ? 'Pipeline CI/CD réussi' : (ctx.aborted ? 'Pipeline CI/CD annulé' : 'Pipeline CI/CD en échec')}</h2>
<p>Bonjour ${esc(ctx.committerName ?: '')},</p>
<p>Résultat de l'exécution Jenkins sur votre commit <code>${esc(ctx.statusSha ?: ctx.headSha ?: '')}</code> :</p>
<table style="border-collapse:collapse;border:1px solid #ddd">
  <tr><td style='padding:4px 12px'>Projet</td><td style='padding:4px 12px'>${esc(ctx.projectPath)} (${esc(ctx.type ?: '-')})</td></tr>
  <tr><td style='padding:4px 12px'>Merge request</td><td style='padding:4px 12px'><a href="${ctx.mrUrl}">!${ctx.mrIid} ${esc(ctx.mrTitle ?: '')}</a></td></tr>
  <tr><td style='padding:4px 12px'>Branches</td><td style='padding:4px 12px'>${esc(ctx.source)} → ${esc(ctx.target)}</td></tr>
  <tr><td style='padding:4px 12px'>Mode</td><td style='padding:4px 12px'>${esc(modeLabel())}</td></tr>
  ${rows}
</table>
<ul>
  <li><b>Analyse SonarQube</b> : ${ctx.sonarUrl ? "<a href='${ctx.sonarUrl}'>${ctx.sonarUrl}</a>" : 'non disponible'} ${ctx.sonarStatus ? "(Quality Gate : ${esc(ctx.sonarStatus)})" : ''}</li>
  <li><b>Analyse Xray (Frogbot)</b> : ${ctx.xrayUrl ? "<a href='${ctx.xrayUrl}'>${ctx.xrayUrl}</a>" : 'non disponible'} ${ctx.xrayStatus ? "(${esc(ctx.xrayStatus)})" : ''}</li>
  ${ctx.targetRepo ? "<li><b>Artifactory</b> : version <code>${esc(ctx.version)}</code> livrée dans <a href='${ctx.artifactUrl}'>${esc(ctx.targetRepo)}</a></li>" : ''}
  <li><b>Build Jenkins</b> : <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></li>
</ul>
${causes}
<p style="color:#757575;font-size:12px">Message automatique Jenkins CI/CD</p>
</body></html>"""
}

def esc(def s) {
    if (s == null) return ''
    return s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
}

// =====================================================================================
//  HELPERS
// =====================================================================================
// Exécute une étape ; en cas d'échec, enregistre la cause et marque l'étape en rouge sans stopper le pipeline
def runStep(String name, Closure body) {
    stage(name) {
        try {
            body()
            ctx.results[name] = 'OK'
        } catch (FlowInterruptedException fie) {
            ctx.results[name] = 'ABORTED'
            throw fie
        } catch (Exception e) {
            ctx.results[name] = 'FAILED'
            ctx.failures << [stage: name, cause: (e.message ?: e.toString())]
            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                error("${name} : échec")
            }
        }
    }
}

def skipStep(String name, String reason) {
    stage(name) {
        echo "Étape ignorée : ${reason}"
    }
    ctx.results[name] = 'SKIPPED'
}

// Exécute un script shell, garde la sortie et remonte les 40 dernières lignes dans la cause en cas d'erreur
def runLogged(String label, String script) {
    String logFile = "${env.WORKSPACE}@tmp/${label}.log"
    int rc
    withEnv(["CI_LOG_FILE=${logFile}"]) {
        rc = sh(returnStatus: true, script: "#!/bin/bash\nset -o pipefail\nmkdir -p \"\$(dirname \"\$CI_LOG_FILE\")\"\n" +
                                            "( set -e\n" + script + "\n) 2>&1 | tee \"\$CI_LOG_FILE\"")
        if (rc != 0) {
            String tail = sh(returnStdout: true, script: 'tail -n 40 "$CI_LOG_FILE" 2>/dev/null || true').trim()
            error("${label} a échoué (exit ${rc}) :\n${tail}")
        }
    }
}

def setCommitStatus(String state, String description) {
    if (!ctx.statusSha || !ctx.projectId) return
    String desc = description.length() > 250 ? description.substring(0, 250) : description
    gitlabApi('POST', "projects/${ctx.projectId}/statuses/${ctx.statusSha}", [
        state      : state,
        name       : CFG.COMMIT_STATUS_NAME,
        ref        : (ctx.mode == 'SNAPSHOT') ? ctx.target : ctx.source,
        target_url : env.BUILD_URL,
        description: desc,
    ])
}

def gitlabApi(String method, String path, Map body = null) {
    def resp
    withCredentials([string(credentialsId: CFG.GITLAB_API_CRED, variable: 'GL_API_TOKEN')]) {
        resp = httpRequest(
            url                   : "${CFG.GITLAB_URL}/api/v4/${path}",
            httpMode              : method,
            customHeaders         : [[name: 'PRIVATE-TOKEN', value: env.GL_API_TOKEN, maskValue: true]],
            contentType           : 'APPLICATION_JSON_UTF8',
            requestBody           : body ? writeJSON(json: body, returnText: true) : '',
            validResponseCodes    : '100:599',
            consoleLogResponseBody: false,
            quiet                 : true
        )
    }
    if (resp.status >= 300) {
        echo "API GitLab ${method} ${path} -> HTTP ${resp.status}"
        return null
    }
    return resp.content ? readJSON(text: resp.content, returnPojo: true) : [:]
}
