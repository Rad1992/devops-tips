# new-generic-cicd — Jenkins Shared Library

Reusable Jenkins pipelines (GitLab → Jenkins → SonarQube / JFrog Xray → Artifactory, and a daily CVE audit),
packaged as a [Jenkins Shared Library](https://www.jenkins.io/doc/book/pipeline/shared-libraries/).

```
vars/                                  # global steps, callable from any Jenkinsfile / Pipeline script
├── dependancy_bot_alert.groovy        # daily CVE audit of the bootstrap parent (Xray + Catalog OneModel) -> HTML mail
├── genericCiCd.groovy                 # CI/CD on GitLab MR events (scripted, SonarQube Developer Edition+)
├── genericCiCdSonarCommunity.groovy   # same, SonarQube Community Edition (one Sonar key per flow)
├── genericCiCdWithFilters.groovy      # declarative variant, branch filtering done in the webhook trigger
└── genericCiCdTest.groovy             # declarative variant + manual test on a single project / MR (DRY_RUN)
src/org/devops/depbot/
├── CveAudit.groovy                    # Xray / Catalog parsing, version comparison, findings
└── CveReport.groovy                   # CSV + "Security alert digest" HTML rendering
```

## 1. Register the library in Jenkins

*Manage Jenkins → System → Global Trusted Pipeline Libraries* (or *Global Pipeline Libraries*) → **Add**:

| Field                   | Value                                                   |
|-------------------------|---------------------------------------------------------|
| Name                    | `new-generic-cicd`                                      |
| Default version         | `main`                                                  |
| Load implicitly         | unchecked                                               |
| Allow default version to be overridden | checked                                  |
| Retrieval method        | Modern SCM → Git → URL of this repository (+ credentials if private) |

Libraries configured globally are trusted (not sandboxed), so the `src/` classes run without script approvals,
even when the job itself has **Use Groovy Sandbox** checked.

## 2. Call it from a job

Job type **Pipeline**, *Definition* = **Pipeline script** (or a `Jenkinsfile` in a repository):

```groovy
@Library('new-generic-cicd') _
dependancy_bot_alert()
```

Every default can be overridden with named arguments:

```groovy
@Library('new-generic-cicd') _
dependancy_bot_alert(
    bootstrapRepo  : 'git@gitlab.mycompany.com:platform/company-bootstrap.git',
    bootstrapBranch: 'main',
    mailTo         : 'architects@mycompany.com',
    minSeverity    : 'High',
    jfrogUrl       : 'https://mycompany.jfrog.io',
    agentLabel     : 'maven',
)
```

See the `DEFAULTS` map at the top of [`vars/dependancy_bot_alert.groovy`](vars/dependancy_bot_alert.groovy) for all keys
(tools, cron, credentials IDs, managed settings file, chunk sizes…).

The CI/CD pipelines work the same way; their keys are the ones of the `CFG` map at the top of each file:

```groovy
@Library('new-generic-cicd') _
genericCiCdSonarCommunity(
    GITLAB_URL : 'https://gitlab.mycompany.com',
    JFROG_URL  : 'https://mycompany.jfrog.io',
    AGENT_LABEL: 'linux',
)
```

Nested maps (`REPOSITORIES`, `VERSION_FORMATS`, …) are replaced as a whole when overridden.

To test a branch of the library before merging it: `@Library('new-generic-cicd@my-branch') _`.

> **First run**: triggers, parameters and cron are registered by the pipeline itself, so run the job once
> manually after creating it (or after changing these settings).

## Notes

- Declarative variants keep the Jenkins tool names (`maven-3.9`, `jdk-21`, `nodejs-20`) in their `tools {}` block;
  rename them there if your *Manage Jenkins → Tools* names differ.
- In `genericCiCdWithFilters` / `genericCiCdTest` the branch names in the webhook filter regex are still literal
  (`develop`, `release*`, `master`): keep them consistent with `DEVELOP_BRANCH`, `RELEASE_BRANCH_REGEX`, `PROD_BRANCH`.
