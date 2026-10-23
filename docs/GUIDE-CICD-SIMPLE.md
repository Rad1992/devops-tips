# Pipeline CI/CD générique — analyse et mise en place pas à pas

Ce guide explique le pipeline générique (GitLab → Jenkins → SonarQube → Xray → Artifactory). Il le met en place
**étape par étape**, avec la version simplifiée [`vars/genericCiCdSimple.groovy`](../vars/genericCiCdSimple.groovy).

---

## Partie 1 — Analyse du pipeline existant

### Ce que fait le pipeline

GitLab envoie un événement de **Merge Request** à Jenkins (System Hook). Jenkins décide quoi faire selon
l'action et les branches :

| Événement MR                              | Mode         | Build + tests | Sonar | Xray | Livraison Artifactory          |
|-------------------------------------------|--------------|:-------------:|:-----:|:----:|--------------------------------|
| open / reopen / update `*` → `develop`    | **VALIDATE** | ✅ | ✅ | ✅ | ❌ (bloque la MR si KO)            |
| merge `*` → `develop`                     | **SNAPSHOT** | ✅ | ✅ | ❌* | `libs-snapshots-<groupe>`     |
| open / reopen / update `develop` → `release*` | **RELEASE** | ✅ | ✅ | ✅ | `libs-releases-<groupe>`      |
| open / reopen / update `release*` → `master`  | **PROD**    | ✅ | ✅ | ✅ | `libs-prod-<groupe>`          |
| tout le reste                             | ignoré (NOT_BUILT) | | | | |

\* La version complète peut lancer `frogbot scan-repository` après le merge. La version simple ne le fait pas.

- Projet **Maven** si `pom.xml`, **npm** si `package.json`, sinon ignoré.
- Versions livrées : SNAPSHOT `X.Y.Z-SNAPSHOT`, RELEASE `X.Y.Z.RELEASE`, PROD `X.Y.Z`.
  En npm, ça donne `X.Y.Z-snapshot.<build>` et `X.Y.Z-RELEASE`.
- Résultat publié en **statut de commit GitLab**. Avec « Pipelines must succeed », la MR est bloquée si c'est KO.
  S'y ajoutent un **commentaire sur la MR** et un **e-mail** à l'auteur du commit.

### Le déroulé, en 6 blocs

```
Webhook GitLab ──► 1. Analyse MR (mode ?) ──► checkout ──► détection maven/npm
                                                              │
        ┌─────────────────────────────────────────────────────┘
        ▼
   2. Build & Tests ──► 3. SonarQube (Quality Gate) ──► 4. Xray (Frogbot) ──► 5. Livraison
        │                                                                          │
        └──────────────── 6. Notification : statut commit + commentaire MR + e-mail ◄┘
```

### Les 5 versions du dépôt

| Fichier                                 | Style       | Particularité                                                     | Lignes |
|-----------------------------------------|-------------|-------------------------------------------------------------------|-------:|
| `genericCiCd.groovy`                    | scripté     | SonarQube Developer Edition (analyse de branches/MR)              | ~830 |
| `genericCiCdSonarCommunity.groovy`      | scripté     | SonarQube Community : une clé Sonar par flux                      | ~845 |
| `genericCiCdWithFilters.groovy`         | déclaratif  | filtre des branches dans le webhook                               | ~780 |
| `genericCiCdTest.groovy`                | déclaratif  | + test manuel sur un seul projet / une MR, DRY_RUN                | ~860 |
| **`genericCiCdSimple.groovy`**          | déclaratif  | **version simplifiée, activable brique par brique** | **~420** |

### Ce qui rend la version complète difficile à mettre en place

1. **Beaucoup de code « maison »** : `gate()`, `runLogged()`, le contexte `ctx`, le parsing JSON complet du payload,
   le détail du Quality Gate via l'API Sonar, un e-mail HTML de 40 lignes…
2. **Tout est nécessaire d'un coup** : GitLab, Sonar, Xray, Artifactory et SMTP doivent marcher dès le premier build.
3. **Fichiers de config générés à la volée** (settings.xml, .npmrc) avec injection de mots de passe.
4. **12 plugins**, dont `http_request`, `pipeline-utility-steps` et `ws-cleanup`.

### Ce que change la version simplifiée

| Version complète                                     | Version simple                                                         |
|------------------------------------------------------|------------------------------------------------------------------------|
| Payload JSON parsé avec `readJSON`                   | Variables extraites directement par le webhook (`GL_ACTION`, `GL_MR_IID`…) |
| Contexte `ctx` + `@Field`                            | Variables d'environnement (`CI_MODE`, `CI_TYPE`, `CI_GROUP`)           |
| settings.xml / .npmrc générés par le code            | **Fichiers gérés** Jenkins (Config File Provider), créés dans l'UI     |
| API GitLab via `httpRequest`                         | `curl` (pas de plugin)                                                 |
| `gate()` / `catchError` / liste d'échecs             | Échec natif du stage : les suivants sont sautés                        |
| Tout obligatoire                                     | Interrupteurs `enableSonar`, `enableXray`, `enableDeploy`, `enableGitlabStatus`, `enableMrComment`, `enableMail` |
| E-mail HTML détaillé + cause de l'échec              | E-mail court + log du build en pièce jointe                            |

Les règles métier (modes, branches, versions, dépôts Artifactory) **restent les mêmes**.

---

## Partie 2 — Mise en place manuelle, étape par étape

> Principe : on démarre avec **toutes les briques désactivées** et on en active **une par étape**,
> en vérifiant à chaque fois que ça fonctionne avant de passer à la suivante.

### Étape 0 — Déclarer la shared library (une seule fois)

*Administrer Jenkins → System → Global Trusted Pipeline Libraries → Ajouter*

- Name : `new-generic-cicd`
- Default version : `main` (ou la branche à tester)
- Retrieval method : Modern SCM → Git → URL de ce dépôt (+ credentials si privé)

### Étape 1 — Plugins Jenkins

*Administrer Jenkins → Plugins → Available plugins*, installer :

| Plugin                    | Sert à                                   | Requis dès  |
|---------------------------|------------------------------------------|-------------|
| Pipeline                  | pipelines                                | étape 4     |
| Git                       | checkout                                 | étape 4     |
| Generic Webhook Trigger   | recevoir le System Hook GitLab           | étape 4     |
| Credentials Binding       | `withCredentials`                        | étape 4     |
| Timestamper               | horodatage des logs                      | étape 4     |
| JUnit                     | rapports de tests                        | étape 6     |
| Config File Provider      | settings.xml / .npmrc gérés              | étape 6     |
| NodeJS                    | projets npm uniquement                   | étape 6     |
| SonarQube Scanner         | `withSonarQubeEnv`, `waitForQualityGate` | étape 8     |
| Email Extension           | `emailext`                               | étape 11    |

### Étape 2 — Outils

*Administrer Jenkins → Tools* :

| Type                  | Nom (valeur par défaut) | Paramètre à surcharger si le nom diffère |
|-----------------------|-------------------------|------------------------------------------|
| JDK                   | `jdk-21`                | `jdkTool`                                |
| Maven (**≥ 3.9**)     | `maven-3.9`             | `mavenTool`                              |
| NodeJS                | `nodejs-20`             | `nodeTool`                               |
| SonarQube Scanner     | `sonar-scanner`         | `sonarScannerTool`                       |

Il faut aussi un agent Jenkins avec le label `maven` (paramètre `agentLabel`), et `git` + `curl` installés dessus.

### Étape 3 — Credentials

*Administrer Jenkins → Credentials → System → Global → Add Credentials* :

| ID                     | Type              | Contenu                                                    | Requis dès |
|------------------------|-------------------|------------------------------------------------------------|------------|
| `gitlab-webhook-token` | Secret text       | une chaîne aléatoire (même valeur dans le System Hook)     | étape 4    |
| `gitlab-git-creds`     | Username/Password | compte GitLab + token `read_repository`                    | étape 4    |
| `gitlab-api-token`     | Secret text       | token GitLab scope `api` (compte technique)                | étape 7    |
| `jfrog-access-token`   | Secret text       | access token JFrog (lecture Xray)                          | étape 9    |
| `artifactory-deployer` | Username/Password | compte de déploiement Artifactory                          | étape 10   |

Astuce pour générer le secret du webhook : `openssl rand -hex 32`

### Étape 4 — Créer le job (toutes briques désactivées)

*Nouvel item → Pipeline* → nom `generic-cicd` → *Pipeline script* :

```groovy
@Library('new-generic-cicd') _
genericCiCdSimple(
    gitlabUrl          : 'https://gitlab.mycompany.com',
    mavenSettingsFileId: '',          // Maven Central pour commencer
    npmrcFileId        : '',
    enableGitlabStatus : false,
    enableSonar        : false,
    enableXray         : false,
    enableDeploy       : false,
    enableMrComment    : false,
    enableMail         : false,
)
```

**Enregistrer**, puis cliquer sur **Lancer un build** une fois. Ce premier build est NOT_BUILT, c'est normal :
il sert à enregistrer le trigger webhook.

✅ **Vérifier** : le log affiche `Événement non concerné (pas de payload) -> rien à faire.`

### Étape 5 — Brancher le webhook GitLab

**Test sans GitLab d'abord**, depuis votre poste (remplacez les valeurs) :

```bash
curl -X POST "https://<jenkins>/generic-webhook-trigger/invoke" \
  -H "Content-Type: application/json" \
  -H "X-Gitlab-Token: <valeur de gitlab-webhook-token>" \
  -d '{
    "object_kind": "merge_request",
    "project": { "id": 123, "path_with_namespace": "team/mon-app",
                 "git_http_url": "https://gitlab.mycompany.com/team/mon-app.git" },
    "object_attributes": { "iid": 1, "action": "open",
                 "source_branch": "feature/test", "target_branch": "develop",
                 "url": "https://gitlab.mycompany.com/team/mon-app/-/merge_requests/1",
                 "last_commit": { "id": "<sha d un commit de la MR>", "author": { "email": "moi@mycompany.com" } } }
  }'
```

✅ **Vérifier** : la réponse JSON contient `"triggered": true`, et un build `team/mon-app !1 VALIDATE` démarre.
`"triggered": false` veut dire que le filtre de branches a rejeté l'événement. C'est normal pour `feature → master`, par exemple.

**Puis côté GitLab** (compte admin) : *Admin Area → System Hooks → Add new webhook*

- URL : `https://<jenkins>/generic-webhook-trigger/invoke`
- Secret token : la valeur de `gitlab-webhook-token`
- Trigger : cocher **uniquement** « Merge request events »
- SSL verification : selon votre certificat Jenkins

> Pour un seul projet : utilisez à la place *Projet → Settings → Webhooks* avec les mêmes valeurs.

✅ **Vérifier** : ouvrez une MR `feature/x → develop` sur un projet de test. Un build doit démarrer dans Jenkins.

### Étape 6 — Build & tests (+ dépôt Artifactory de résolution, optionnel)

Le build tourne déjà depuis l'étape 4 (`mvn clean verify` ou `npm ci / build / test`).

Si les dépendances doivent venir d'Artifactory, créez le fichier géré :
*Administrer Jenkins → Managed files → Add a new Config → Global Maven settings.xml* (ou *Maven settings.xml*)

- ID : `maven-settings-artifactory`
- Server Credentials : ServerId `artifactory` → credential `artifactory-deployer`
- Contenu :

```xml
<settings>
  <mirrors>
    <mirror>
      <id>artifactory</id>
      <mirrorOf>*</mirrorOf>
      <url>https://mycompany.jfrog.io/artifactory/libs-virtual</url>
    </mirror>
  </mirrors>
</settings>
```

Pour npm : *Add a new Config → Npm config file*, ID `npmrc-artifactory`, avec un *registry*
`https://mycompany.jfrog.io/artifactory/api/npm/npm-virtual/` et le credential `artifactory-deployer`.

Retirez ensuite `mavenSettingsFileId: ''` / `npmrcFileId: ''` du job : les valeurs par défaut prennent le relais.

✅ **Vérifier** : push d'un commit sur la MR → stage *2. Build & Tests* vert, onglet *Test Result* rempli.

### Étape 7 — Statut GitLab (bloquer la MR)

Dans le job : `enableGitlabStatus: true`

Dans GitLab, sur chaque projet : *Settings → Merge requests → Merge checks →* cocher **Pipelines must succeed**.

✅ **Vérifier** : la MR affiche un pipeline externe `jenkins/ci-cd` (running, puis passed ou failed).
Si le build échoue, le bouton Merge est bloqué. Le log Jenkins affiche `Statut GitLab running : HTTP 201`.

| Code HTTP | Cause probable                                                 |
|-----------|----------------------------------------------------------------|
| 401       | token invalide (`gitlab-api-token`)                            |
| 403       | le compte du token n'est pas au moins *Developer* du projet    |
| 400       | transition de statut refusée (même statut déjà posé) : sans gravité |

### Étape 8 — SonarQube

1. *Administrer Jenkins → System → SonarQube servers* : nom `SonarQube`, URL, et token (credential Secret text).
2. Dans SonarQube : *Administration → Configuration → Webhooks → Create* → `https://<jenkins>/sonarqube-webhook/`
3. Dans le job : `enableSonar: true`

✅ **Vérifier** : le projet `team:mon-app` apparaît dans SonarQube. Le stage *3. SonarQube* attend
le Quality Gate, puis passe au vert (ou échoue si le Quality Gate est KO).
Si le stage reste bloqué 15 minutes, c'est que le webhook SonarQube → Jenkins de l'étape 2 manque.

### Étape 9 — Xray (Frogbot)

1. Credential `jfrog-access-token` (étape 3) : un token avec des droits de lecture Xray.
2. L'agent doit pouvoir joindre `releases.jfrog.io`, ou adaptez `frogbotDownloadUrl` vers votre Artifactory.
3. Dans le job : `enableXray: true, jfrogUrl: 'https://mycompany.jfrog.io'`

✅ **Vérifier** : Frogbot poste un commentaire sur la MR. Le stage échoue si une vulnérabilité ≥ `xrayMinSeverity`
(High par défaut) est trouvée.

### Étape 10 — Livraison Artifactory

1. Créer dans Artifactory les dépôts locaux, par groupe GitLab racine (ex. groupe `team`) :
   `libs-snapshots-team`, `libs-releases-team`, `libs-prod-team` (+ `npm-*-team` pour npm).
   Le schéma de nommage se change avec le paramètre `repositories`.
2. Le fichier géré `maven-settings-artifactory` doit contenir le **server `artifactory`** (fait à l'étape 6).
3. Dans le job : `enableDeploy: true`

✅ **Vérifier** : mergez une MR vers `develop`. Le build SNAPSHOT livre `X.Y.Z-SNAPSHOT` dans `libs-snapshots-team`.
Ensuite, ouvrez une MR `develop → release/1.0` : elle livre `X.Y.Z.RELEASE` dans `libs-releases-team`.

### Étape 11 — Commentaire MR et e-mail

1. *Administrer Jenkins → System → Extended E-mail Notification* : serveur SMTP, puis *Test configuration*.
2. Dans le job : `enableMrComment: true, enableMail: true, mailFallbackTo: 'devops@mycompany.com'`

✅ **Vérifier** : la MR reçoit un commentaire « ✅ Jenkins VALIDATE : SUCCESS — build #N ». L'auteur du commit reçoit
l'e-mail. Si GitLab masque l'e-mail (`REDACTED`), c'est `mailFallbackTo` qui le reçoit.

### Étape 12 — Configuration finale

Une fois tout validé, le job se réduit à :

```groovy
@Library('new-generic-cicd') _
genericCiCdSimple(
    gitlabUrl: 'https://gitlab.mycompany.com',
    jfrogUrl : 'https://mycompany.jfrog.io',
)
```

---

## Dépannage rapide

| Symptôme                                              | Piste                                                                 |
|-------------------------------------------------------|-----------------------------------------------------------------------|
| Aucun build quand une MR est ouverte                  | Lancer le job une fois à la main ; vérifier le secret du System Hook (*Admin → System Hooks → Edit → Recent events*) |
| Webhook → `"triggered": false`                        | Flux non géré (ex. `feature → master`) ; noms de branches ≠ `developBranch` / `releasePrefix` / `prodBranch` |
| Build NOT_BUILT                                       | `update` sans nouveau commit (titre, label…), ou projet ni Maven ni npm |
| `ERROR: No tool named …`                              | nom d'outil ≠ étape 2 → surcharger `jdkTool`, `mavenTool`…          |
| Checkout `couldn't find remote ref refs/merge-requests/…` | `gitlab-git-creds` n'a pas accès au projet                        |
| Stage SonarQube bloqué 15 min                         | webhook SonarQube → `https://<jenkins>/sonarqube-webhook/` manquant  |
| `mvn deploy` → 401                                    | server `artifactory` absent du settings.xml géré, ou mauvais credential |
| `mvn deploy` → 409                                    | version RELEASE/PROD déjà présente : le dépôt refuse le redéploiement |

## Paramètres disponibles

Tous sont dans la map `SIMPLE_DEFAULTS` en haut de [`vars/genericCiCdSimple.groovy`](../vars/genericCiCdSimple.groovy).
Ils se surchargent tous de la même façon : `genericCiCdSimple(cle: valeur, ...)`.
