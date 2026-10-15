import groovy.transform.Field
import org.devops.depbot.CveAudit
import org.devops.depbot.CveReport

/*
 * Daily CVE audit of the company bootstrap parent (child of spring-boot-starter-parent).
 *
 *  1. Checks out the bootstrap from GitLab and computes its effective POM
 *     -> every dependency it manages: its own <dependencyManagement> + everything
 *        inherited from spring-boot-dependencies (imported BOMs are inlined).
 *  2. JFrog Xray  (POST /xray/api/v1/summary/component) -> security issues + fixed versions per GAV.
 *  3. JFrog Catalog (GraphQL)                            -> CVEs, severity, CVSS, known exploit per GAV.
 *  4. Merges both, computes a recommended patched version per dependency,
 *     mails an HTML report (+ CSV attachment) and archives everything.
 *
 * Usage (Jenkins job, "Pipeline script"):
 *
 *     @Library('new-generic-cicd') _
 *     dependancy_bot_alert()
 *
 *  or with overrides (every key of DEFAULTS below can be overridden):
 *
 *     dependancy_bot_alert(
 *         bootstrapRepo: 'git@gitlab.mycompany.com:platform/company-bootstrap.git',
 *         mailTo       : 'architects@mycompany.com',
 *         minSeverity  : 'High'
 *     )
 *
 * Plugins: Pipeline, Git, Pipeline Utility Steps, Email Extension, Config File Provider,
 *          Credentials Binding, Timestamper.
 * Credentials: 'gitlab-ssh-key' (SSH key), 'jfrog-access-token' (secret text, Xray read).
 * Managed file: 'maven-settings-artifactory' (settings.xml pointing to Artifactory).
 */

// Default configuration, every key can be overridden from the Jenkinsfile: dependancy_bot_alert(key: value, ...)
@Field Map DEFAULTS = [
    agentLabel             : 'maven',
    mavenTool              : 'maven-3.9',                  // Manage Jenkins > Tools > Maven
    jdkTool                : 'jdk-21',                     // Manage Jenkins > Tools > JDK
    cron                   : 'TZ=Europe/Paris\nH 6 * * *', // every day around 06:00 Paris time
    timeoutMinutes         : 60,
    buildsToKeep           : 30,

    // default values of the build parameters
    bootstrapRepo          : 'git@gitlab.mycompany.com:platform/company-bootstrap.git',
    bootstrapBranch        : 'main',
    mailTo                 : 'architects@mycompany.com',
    minSeverity            : 'Low',                        // Low | Medium | High | Critical
    mailWhenClean          : true,
    mailMaxDependencies    : 20,                           // dependencies shown in the mail body (full list in the archived report)

    // JFrog
    jfrogUrl               : 'https://mycompany.jfrog.io', // platform URL, no trailing slash
    catalogPath            : '/catalog/api/v1/custom/graphql',
    xrayChunk              : 100,
    catalogChunk           : 25,

    // credentials / managed files
    gitCredentialsId       : 'gitlab-ssh-key',
    jfrogTokenCredentialsId: 'jfrog-access-token',
    mavenSettingsFileId    : 'maven-settings-artifactory',
]

def call(Map config = [:]) {
    Map cfg = DEFAULTS + config

    // the first choice is the default one -> put the configured severity first
    List severities = ['Low', 'Medium', 'High', 'Critical']
    List severityChoices = [cfg.minSeverity] + (severities - cfg.minSeverity)
    String buildsToKeep  = cfg.buildsToKeep.toString()
    int timeoutMinutes   = cfg.timeoutMinutes as int
    boolean mailWhenClean = cfg.mailWhenClean as boolean

    pipeline {
        agent { label "${cfg.agentLabel}" }

        options {
            timestamps()
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: buildsToKeep, artifactNumToKeepStr: buildsToKeep))
            timeout(time: timeoutMinutes, unit: 'MINUTES')
        }

        triggers {
            cron(cfg.cron)
        }

        parameters {
            string(name: 'BOOTSTRAP_REPO',   defaultValue: cfg.bootstrapRepo, description: 'GitLab repository of the bootstrap parent')
            string(name: 'BOOTSTRAP_BRANCH', defaultValue: cfg.bootstrapBranch, description: 'Branch to analyse')
            string(name: 'MAIL_TO',          defaultValue: cfg.mailTo, description: 'Comma-separated recipients')
            choice(name: 'MIN_SEVERITY',     choices: severityChoices, description: 'Ignore CVEs below this severity')
            booleanParam(name: 'MAIL_WHEN_CLEAN', defaultValue: mailWhenClean, description: 'Send the mail even when no CVE is found')
        }

        environment {
            JFROG_URL     = "${cfg.jfrogUrl}"                       // platform URL, no trailing slash
            CATALOG_PATH  = "${cfg.catalogPath}"
            XRAY_CHUNK    = "${cfg.xrayChunk}"                      // components per Xray call
            CATALOG_CHUNK = "${cfg.catalogChunk}"                   // packageVersion aliases per GraphQL call
        }

        stages {

            stage('Prepare') {
                steps {
                    sh 'rm -rf report tmp && mkdir -p report tmp'
                }
            }

            stage('Checkout bootstrap') {
                steps {
                    dir('bootstrap') {
                        git url: params.BOOTSTRAP_REPO, branch: params.BOOTSTRAP_BRANCH, credentialsId: cfg.gitCredentialsId
                    }
                }
            }

            stage('Resolve managed dependencies') {
                steps {
                    script {
                        String javaHome  = tool name: cfg.jdkTool, type: 'jdk'
                        String mavenHome = tool name: cfg.mavenTool, type: 'maven'
                        withEnv(["JAVA_HOME=${javaHome}", "PATH+JDK=${javaHome}/bin", "PATH+MAVEN=${mavenHome}/bin"]) {
                            dir('bootstrap') {
                                configFileProvider([configFile(fileId: cfg.mavenSettingsFileId, variable: 'MVN_SETTINGS')]) {
                                    // -N = root POM only (works even if the bootstrap is multi-module)
                                    sh 'mvn -B -N -U -s "$MVN_SETTINGS" help:effective-pom -Doutput=target/effective-pom.xml'
                                }
                            }
                        }
                        def model   = readMavenPom file: 'bootstrap/target/effective-pom.xml'
                        def managed = CveAudit.extractManaged(model)
                        env.BOOTSTRAP_GAV = "${model.groupId}:${model.artifactId}:${model.version}"
                        writeJSON file: 'report/managed-dependencies.json', json: managed, pretty: 2
                        echo "${env.BOOTSTRAP_GAV} manages ${managed.size()} dependencies"
                    }
                }
            }

            stage('Xray: issues & fixed versions') {
                steps {
                    script {
                        def managed = readJSON file: 'report/managed-dependencies.json', returnPojo: true
                        def chunks  = CveAudit.chunk(managed, env.XRAY_CHUNK as int)
                        def xray    = [:]
                        withCredentials([string(credentialsId: cfg.jfrogTokenCredentialsId, variable: 'JF_TOKEN')]) {
                            for (int i = 0; i < chunks.size(); i++) {
                                writeJSON file: "tmp/xray-req-${i}.json", json: CveAudit.xrayBody(chunks[i])
                                // single-quoted shell vars: the token is never interpolated by Groovy
                                sh """
                                    curl -sS --fail-with-body -X POST \\
                                      -H "Authorization: Bearer \$JF_TOKEN" \\
                                      -H 'Content-Type: application/json' \\
                                      --data @tmp/xray-req-${i}.json \\
                                      -o tmp/xray-resp-${i}.json \\
                                      "\$JFROG_URL/xray/api/v1/summary/component"
                                """
                                CveAudit.mergeXray(xray, readJSON(file: "tmp/xray-resp-${i}.json", returnPojo: true))
                            }
                        }
                        writeJSON file: 'report/xray.json', json: xray, pretty: 2
                        echo "Xray: ${xray.size()} vulnerable components"
                    }
                }
            }

            stage('Catalog: CVE details') {
                steps {
                    // Catalog is an enrichment: if it fails the report is still sent with Xray data only
                    catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                        script {
                            def managed = readJSON file: 'report/managed-dependencies.json', returnPojo: true
                            def chunks  = CveAudit.chunk(managed, env.CATALOG_CHUNK as int)
                            def catalog = [:]
                            withCredentials([string(credentialsId: cfg.jfrogTokenCredentialsId, variable: 'JF_TOKEN')]) {
                                for (int i = 0; i < chunks.size(); i++) {
                                    writeJSON file: "tmp/catalog-req-${i}.json", json: [query: CveAudit.catalogQuery(chunks[i])]
                                    sh """
                                        curl -sS --fail-with-body -X POST \\
                                          -H "Authorization: Bearer \$JF_TOKEN" \\
                                          -H 'Content-Type: application/json' \\
                                          --data @tmp/catalog-req-${i}.json \\
                                          -o tmp/catalog-resp-${i}.json \\
                                          "\$JFROG_URL\$CATALOG_PATH"
                                    """
                                    def resp = readJSON file: "tmp/catalog-resp-${i}.json", returnPojo: true
                                    def warn = CveAudit.graphqlErrors(resp)
                                    if (warn) echo "Catalog warnings (chunk ${i}): ${warn}"
                                    CveAudit.mergeCatalog(catalog, chunks[i], resp)
                                }
                            }
                            writeJSON file: 'report/catalog.json', json: catalog, pretty: 2
                            echo "Catalog: ${catalog.size()} vulnerable components"
                        }
                    }
                }
            }

            stage('Build report') {
                steps {
                    script {
                        def managed   = readJSON file: 'report/managed-dependencies.json', returnPojo: true
                        def xray      = readJSON file: 'report/xray.json', returnPojo: true
                        def catalogOk = fileExists('report/catalog.json')
                        def catalog   = catalogOk ? readJSON(file: 'report/catalog.json', returnPojo: true) : [:]

                        def findings = CveAudit.buildFindings(managed, xray, catalog, params.MIN_SEVERITY)
                        writeJSON file: 'report/findings.json', json: findings, pretty: 2
                        writeFile file: 'report/cve-report.csv',  text: CveReport.toCsv(findings)
                        // full report (archived, linked from the mail button) + truncated digest for the mail body
                        writeFile file: 'report/cve-report.html',
                                  text: CveReport.toHtml(findings, env.BOOTSTRAP_GAV, env.BUILD_URL, managed.size(), catalogOk, params.MIN_SEVERITY, 0)
                        writeFile file: 'report/mail.html',
                                  text: CveReport.toHtml(findings, env.BOOTSTRAP_GAV, env.BUILD_URL, managed.size(), catalogOk, params.MIN_SEVERITY, cfg.mailMaxDependencies as int)

                        env.VULN_DEPS = "${findings.size()}"
                        env.CVE_COUNT = "${CveAudit.cveCount(findings)}"
                        currentBuild.description = "${env.VULN_DEPS} vulnerable deps / ${env.CVE_COUNT} CVEs"
                    }
                }
            }

            stage('Send mail') {
                when { expression { (env.VULN_DEPS as int) > 0 || params.MAIL_WHEN_CLEAN } }
                steps {
                    emailext(
                        to: params.MAIL_TO,
                        subject: "[${env.BOOTSTRAP_GAV}] Security alert digest - ${env.VULN_DEPS} vulnerable dependencies",
                        mimeType: 'text/html',
                        body: readFile('report/mail.html'),
                        attachmentsPattern: 'report/cve-report.csv'
                    )
                }
            }
        }

        post {
            always {
                archiveArtifacts artifacts: 'report/**', allowEmptyArchive: true
            }
            failure {
                emailext(
                    to: params.MAIL_TO,
                    subject: "[Bootstrap CVE audit] FAILED - build #${env.BUILD_NUMBER}",
                    mimeType: 'text/html',
                    body: "The daily bootstrap CVE audit failed. Console: <a href=\"${env.BUILD_URL}console\">${env.BUILD_URL}console</a>"
                )
            }
        }
    }
}
