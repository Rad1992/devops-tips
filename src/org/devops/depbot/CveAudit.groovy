package org.devops.depbot

/**
 * Pure (CPS-free) logic of the bootstrap CVE audit: effective-POM extraction,
 * JFrog Xray / Catalog request building and response merging, version comparison
 * and computation of the recommended patched version.
 */
class CveAudit implements Serializable {

    @NonCPS
    static List extractManaged(model) {
        def seen = [:]
        (model.dependencyManagement?.dependencies ?: []).each { d ->
            if (d.version && !d.version.contains('${')) {
                seen["${d.groupId}:${d.artifactId}:${d.version}".toString()] = [g: d.groupId, a: d.artifactId, v: d.version]
            }
        }
        return seen.values().toList().sort { it.g + ':' + it.a }
    }

    @NonCPS
    static List chunk(List items, int size) {
        def out = []
        for (int i = 0; i < items.size(); i += size) {
            out << items.subList(i, Math.min(i + size, items.size())).collect { it }   // copy: subList is not serializable
        }
        return out
    }

    @NonCPS
    static Map xrayBody(List chunk) {
        return [component_details: chunk.collect { [component_id: "gav://${it.g}:${it.a}:${it.v}".toString()] }]
    }

    @NonCPS
    static void mergeXray(Map acc, Map resp) {
        (resp?.artifacts ?: []).each { art ->
            String id = (art?.general?.component_id ?: '').toString().replaceFirst('^gav://', '')
            if (!id) return
            (art.issues ?: []).each { issue ->
                if (issue.issue_type && issue.issue_type != 'security') return
                List fixed = (issue.components ?: [])
                        .collectMany { (it.fixed_versions ?: []) as List }
                        .collect { cleanVersion(it?.toString()) }
                        .findAll { it }.unique()
                List cves = (issue.cves ?: []).collect { it.cve }.findAll { it }
                if (!cves) cves = [issue.issue_id]
                cves.each { cve ->
                    if (!acc.containsKey(id)) acc[id] = [:]
                    if (!acc[id].containsKey(cve)) acc[id][cve] = [fixed: []]
                    Map e = acc[id][cve]
                    e.xrayId   = issue.issue_id
                    e.severity = issue.severity
                    e.summary  = issue.summary
                    def raw = (issue.cves ?: []).find { it.cve == cve }?.cvss_v3      // e.g. "9.8/CVSS:3.1/AV:N/..."
                    def score = raw ? raw.toString().tokenize('/')[0] : null
                    if (score && score.isNumber()) e.cvss = score.toDouble()
                    e.fixed = (e.fixed + fixed).unique()
                }
            }
        }
    }

    @NonCPS
    static String cleanVersion(String raw) {
        if (!raw) return null
        // Xray returns "[2.17.1]" or ranges like "[2.12.2,2.13.0)": keep the lower bound
        return raw.replaceAll(/[\[\]\(\)\s]/, '').tokenize(',').find { it }
    }

    /**
     * JFrog OneModel GraphQL (POST /onemodel/api/v1/graphql), public catalog namespace.
     * One aliased getPackage per dependency, narrowed to the audited version.
     */
    @NonCPS
    static String catalogQuery(List chunk) {
        def parts = []
        for (int i = 0; i < chunk.size(); i++) {
            def d = chunk[i]
            parts << """    p${i}: getPackage(type: "maven", name: "${d.g}:${d.a}") {
      versionsConnection(first: 1, where: {version: "${d.v}"}) {
        edges { node { version securityInfo {
          vulnerabilitiesConnection(first: 100) {
            edges { node { name severity cvss { preferredBaseScore } knownExploit { addedAt } } }
          }
        } } }
      }
    }"""
        }
        return 'query {\n  publicPackages {\n' + parts.join('\n') + '\n  }\n}'
    }

    @NonCPS
    static String graphqlErrors(Map resp) {
        return (resp?.errors ?: []).collect { it.message }.unique().take(5).join(' | ')
    }

    @NonCPS
    static void mergeCatalog(Map acc, List chunk, Map resp) {
        def data = resp?.data?.publicPackages ?: [:]
        for (int i = 0; i < chunk.size(); i++) {
            def d   = chunk[i]
            def pkg = data["p${i}".toString()]
            if (!pkg) continue
            // keep only the audited version, in case the server ignores the version filter
            def pv = (pkg.versionsConnection?.edges ?: []).collect { it.node }.find { it?.version == d.v }
            if (!pv) continue
            String gav = "${d.g}:${d.a}:${d.v}"
            (pv.securityInfo?.vulnerabilitiesConnection?.edges ?: []).each { edge ->
                def n = edge.node
                if (!n?.name) return
                if (!acc.containsKey(gav)) acc[gav] = [:]
                acc[gav][n.name] = [
                    severity: n.severity,
                    cvss    : n.cvss?.preferredBaseScore != null ? (n.cvss.preferredBaseScore as double) : null,
                    exploit : n.knownExploit != null
                ]
            }
        }
    }

    @NonCPS
    static String normSev(s) {
        String v = (s ?: 'UNKNOWN').toString().toUpperCase()
        return ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].contains(v) ? v : 'UNKNOWN'
    }

    @NonCPS
    static List buildFindings(List managed, Map xray, Map catalog, String minSeverity) {
        def rank = [UNKNOWN: 0, LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4]
        int min = rank[minSeverity.toUpperCase()] ?: 0
        def out = []
        managed.each { d ->
            String gav = "${d.g}:${d.a}:${d.v}"
            Map x = xray[gav] ?: [:]
            Map c = catalog[gav] ?: [:]
            def cves = []
            (x.keySet() + c.keySet()).each { id ->
                Map xi = x[id] ?: [:]
                Map ci = c[id] ?: [:]
                String sev = [normSev(xi.severity), normSev(ci.severity)].max { rank[it] }
                if (rank[sev] < min) return
                List fixed = (xi.fixed ?: []).findAll { compareVersions(it, d.v) > 0 }
                                              .sort { a, b -> compareVersions(a, b) }
                def scores = [xi.cvss, ci.cvss].findAll { it != null }.collect { it as double }
                cves << [
                    id      : id,
                    severity: sev,
                    cvss    : scores ? scores.max() : null,
                    exploit : ci.exploit ?: false,
                    fixedIn : fixed.join(', '),
                    bestFix : pickFix(d.v, fixed),
                    summary : xi.summary ?: '',
                    source  : [xi ? 'Xray' : null, ci ? 'Catalog' : null].findAll { it }.join('+')
                ]
            }
            if (cves) {
                cves.sort { a, b -> (rank[b.severity] <=> rank[a.severity]) ?: ((b.cvss ?: 0d) <=> (a.cvss ?: 0d)) }
                def known = cves.collect { it.bestFix }.findAll { it }
                out << [
                    group      : d.g,
                    artifact   : d.a,
                    version    : d.v,
                    maxSeverity: cves[0].severity,
                    recommended: known ? known.max { a, b -> compareVersions(a, b) } : null,
                    unfixed    : cves.any { !it.bestFix },
                    cves       : cves
                ]
            }
        }
        out.sort { a, b -> (rank[b.maxSeverity] <=> rank[a.maxSeverity]) ?: (b.cves.size() <=> a.cves.size()) }
        return out
    }

    /** Smallest fixed version, preferring the same major.minor line, then the same major. */
    @NonCPS
    static String pickFix(String current, List fixedSortedAsc) {
        if (!fixedSortedAsc) return null
        List cur = current.tokenize('.-')
        def sameMinor = fixedSortedAsc.find { it.tokenize('.-').take(2) == cur.take(2) }
        def sameMajor = fixedSortedAsc.find { it.tokenize('.-')[0] == cur[0] }
        return sameMinor ?: sameMajor ?: fixedSortedAsc[0]
    }

    /** Maven-like version comparison: 1.0-RC1 < 1.0 = 1.0.Final < 1.0-SP1 < 1.0.1 */
    @NonCPS
    static int compareVersions(String a, String b) {
        List ta = versionTokens(a), tb = versionTokens(b)
        int n = Math.max(ta.size(), tb.size())
        for (int i = 0; i < n; i++) {
            List x = i < ta.size() ? ta[i] : [2, 0L]
            List y = i < tb.size() ? tb[i] : [2, 0L]
            int c = (x[0] <=> y[0]) ?: (x[1] <=> y[1])
            if (c != 0) return c
        }
        return 0
    }

    @NonCPS
    static List versionTokens(String v) {
        def preRelease = [alpha: 1L, a: 1L, beta: 2L, b: 2L, milestone: 3L, m: 3L, rc: 4L, cr: 4L, snapshot: 5L]
        def release    = ['ga', 'final', 'release', 'sp']
        return (v ?: '').toLowerCase()
                .split(/[.\-_+]|(?<=\d)(?=\D)|(?<=\D)(?=\d)/)
                .findAll { it }
                .collect { t ->
                    if (t ==~ /\d{1,18}/)     return [2, t.toLong()]
                    if (release.contains(t))  return [2, 0L]
                    return [1, preRelease.containsKey(t) ? preRelease[t] : 6L]
                }
    }

    @NonCPS
    static int cveCount(List findings) {
        int total = 0
        findings.each { total += it.cves.size() }
        return total
    }
}
