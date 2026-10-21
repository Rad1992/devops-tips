package org.devops.depbot

/** Rendering of the CVE audit findings (CSV attachment + "Security alert digest" HTML mail). */
class CveReport implements Serializable {

    @NonCPS
    static String esc(Object s) {
        return (s == null ? '' : s.toString())
                .replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }

    @NonCPS
    static String toCsv(List findings) {
        def q = { v -> '"' + (v == null ? '' : v.toString().replace('"', '""')) + '"' }
        def lines = ['groupId;artifactId;currentVersion;cve;severity;cvss;xrayCvssV3;xrayCvssV3Vector;knownExploit;fixedIn;recommendedVersion;source']
        findings.each { f ->
            f.cves.each { c ->
                lines << [f.group, f.artifact, f.version, c.id, c.severity, c.cvss, c.cvssV3, c.cvssV3Vector, c.exploit,
                          c.fixedIn, f.recommended, c.source].collect(q).join(';')
            }
        }
        return lines.join('\n') + '\n'
    }

    /**
     * Mail rendered in the style of a "Security alert digest":
     * one card per vulnerable dependency (Dependency / Version / Upgrade to / Vulnerabilities),
     * outlined severity labels and a green call-to-action button.
     * maxDeps = 0 renders every dependency (archived report), > 0 truncates (mail).
     */
    @NonCPS
    static String toHtml(List findings, String bootstrap, String buildUrl, int managedCount, boolean catalogOk, String minSeverity, int maxDeps = 0) {
        final int MAX_CVES_PER_DEP = 5
        String font = "-apple-system,BlinkMacSystemFont,'Segoe UI','Noto Sans',Helvetica,Arial,sans-serif"
        String mono = "ui-monospace,SFMono-Regular,'SF Mono',Menlo,Consolas,monospace"
        String reportUrl = buildUrl ? "${buildUrl}artifact/report/cve-report.html" : '#'
        String bootstrapName = (bootstrap ?: '').tokenize(':').size() > 1 ? bootstrap.tokenize(':')[1] : bootstrap

        def sev = [CRITICAL: ['Critical', '#cf222e'], HIGH: ['High', '#bc4c00'], MEDIUM: ['Moderate', '#9a6700'],
                   LOW: ['Low', '#57606a'], UNKNOWN: ['Unknown', '#57606a']]
        def label = { String s ->
            def st = sev[s] ?: sev.UNKNOWN
            "<span style=\"display:inline-block;padding:0 7px;font-size:12px;font-weight:500;line-height:18px;" +
            "border:1px solid ${st[1]};border-radius:2em;color:${st[1]};white-space:nowrap\">${st[0]}</span>"
        }
        def code = { String v, String bg, String fg ->
            "<code style=\"font-family:${mono};font-size:12px;padding:2px 6px;border-radius:6px;background:${bg};color:${fg}\">${esc(v)}</code>"
        }
        def keyCell = 'style="padding:4px 16px 4px 0;width:120px;color:#57606a;font-size:14px;vertical-align:top;white-space:nowrap"'
        def valCell = 'style="padding:4px 0;font-size:14px;color:#1f2328;vertical-align:top"'

        def counts = [CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, UNKNOWN: 0]
        findings.each { f -> f.cves.each { counts[it.severity] = counts[it.severity] + 1 } }
        int total = counts.values().sum() as int
        List shown = (maxDeps > 0 && findings.size() > maxDeps) ? findings.take(maxDeps) : findings

        def h = []
        // ── page + container ───────────────────────────────────────────────────────
        h << "<html><body style=\"margin:0;padding:0;background:#f6f8fa;font-family:${font};color:#1f2328\">"
        h << '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f6f8fa"><tr><td align="center" style="padding:32px 12px">'
        h << '<table role="presentation" width="640" cellpadding="0" cellspacing="0" style="max-width:640px;width:100%">'

        // ── header ─────────────────────────────────────────────────────────────────
        h << '<tr><td style="padding:0 0 16px;font-size:14px;color:#57606a">&#128737;&nbsp; <b style="color:#1f2328">Platform Security</b></td></tr>'
        h << '<tr><td style="padding:0 0 8px;font-size:24px;font-weight:400;color:#1f2328">Security alert digest</td></tr>'
        h << "<tr><td style=\"padding:0 0 24px;font-size:14px;line-height:1.5;color:#1f2328\">"
        h << (findings ? "Known security vulnerabilities detected in dependencies managed by <b>${esc(bootstrapName)}</b>." :
                         "No known security vulnerability in dependencies managed by <b>${esc(bootstrapName)}</b>.")
        h << '</td></tr>'

        // ── warning when Catalog failed ────────────────────────────────────────────
        if (!catalogOk) {
            h << '<tr><td style="padding:0 0 16px"><div style="padding:12px 16px;background:#fff8c5;border:1px solid #d4a72c;border-radius:6px;font-size:13px">' +
                 '&#9888;&nbsp; JFrog Catalog was unavailable: results are based on JFrog Xray only.</div></td></tr>'
        }

        // ── repository-like card ───────────────────────────────────────────────────
        h << '<tr><td style="background:#ffffff;border:1px solid #d0d7de;border-radius:6px">'
        h << '<table role="presentation" width="100%" cellpadding="0" cellspacing="0">'

        // card header: bootstrap GAV + counters
        h << '<tr><td style="padding:16px 24px;background:#f6f8fa;border-bottom:1px solid #d0d7de;border-radius:6px 6px 0 0">'
        h << "<div style=\"font-size:14px;font-weight:600\"><a href=\"${esc(buildUrl)}\" style=\"color:#0969da;text-decoration:none\">${esc(bootstrap)}</a></div>"
        h << "<div style=\"padding-top:6px;font-size:12px;color:#57606a\">${findings.size()} vulnerable ${findings.size() == 1 ? 'dependency' : 'dependencies'} &middot; ${total} ${total == 1 ? 'vulnerability' : 'vulnerabilities'} &middot; ${managedCount} managed dependencies analysed</div>"
        if (total > 0) {
            h << '<div style="padding-top:10px">' +
                 counts.findAll { k, v -> v > 0 }.collect { k, v -> "${label(k)}&nbsp;<span style=\"font-size:12px;color:#57606a\">${v}</span>" }.join('&nbsp;&nbsp;&nbsp;') +
                 '</div>'
        }
        h << '</td></tr>'

        if (!findings) {
            h << '<tr><td style="padding:24px;font-size:14px;color:#1a7f37">&#10004;&nbsp; All dependencies are free of known vulnerabilities' +
                 (minSeverity && minSeverity != 'Low' ? " (severity &ge; ${esc(minSeverity)})" : '') + '.</td></tr>'
        }

        // one block per vulnerable dependency
        shown.eachWithIndex { f, idx ->
            String border = idx == 0 ? '' : 'border-top:1px solid #d0d7de;'
            h << "<tr><td style=\"padding:16px 24px;${border}\">"
            h << '<table role="presentation" width="100%" cellpadding="0" cellspacing="0">'

            h << "<tr><td ${keyCell}>Dependency</td><td ${valCell}><b>${esc(f.artifact)}</b> " +
                 "<span style=\"color:#57606a;font-size:12px\">${esc(f.group)}</span>&nbsp;&nbsp;${label(f.maxSeverity)}</td></tr>"

            h << "<tr><td ${keyCell}>Version</td><td ${valCell}>${code(f.version, '#ffebe9', '#82071e')}</td></tr>"

            String upgrade
            if (f.recommended) {
                upgrade = code("${f.recommended}", '#dafbe1', '#116329')
                if (f.unfixed) upgrade += '<br><span style="font-size:12px;color:#cf222e">Some vulnerabilities have no patched version yet</span>'
            } else {
                upgrade = '<span style="font-size:13px;color:#cf222e">No patched version available</span>'
            }
            h << "<tr><td ${keyCell}>Upgrade to</td><td ${valCell}>${upgrade}</td></tr>"

            def vulns = f.cves.take(MAX_CVES_PER_DEP).collect { c ->
                String id = c.id.toString()
                String link = id.startsWith('CVE-') ?
                        "<a href=\"https://nvd.nist.gov/vuln/detail/${esc(id)}\" style=\"color:#0969da;text-decoration:none\">${esc(id)}</a>" : esc(id)
                def extras = []
                if (c.cvssV3 != null) {
                    String vector = c.cvssV3Vector ? " title=\"${esc(c.cvssV3Vector)}\"" : ''
                    extras << "<span${vector}>CVSS v3 ${c.cvssV3}</span>"
                } else if (c.cvss != null) {
                    extras << "CVSS ${c.cvss}"
                }
                if (c.fixedIn)      extras << "fixed in ${esc(c.fixedIn)}"
                String line = "${label(c.severity)}&nbsp; ${link}" +
                              (extras ? " <span style=\"font-size:12px;color:#57606a\">&middot; ${extras.join(' &middot; ')}</span>" : '') +
                              (c.exploit ? ' <span style="font-size:12px;font-weight:600;color:#cf222e">&middot; known exploit</span>' : '')
                String summary = c.summary ? c.summary.toString() : ''
                if (summary.size() > 140) summary = summary.take(140) + '&hellip;'
                "<div style=\"padding:3px 0\">${line}" +
                (summary ? "<div style=\"font-size:12px;color:#57606a;padding:2px 0 0\">${esc(summary).replace('&amp;hellip;', '&hellip;')}</div>" : '') +
                '</div>'
            }
            if (f.cves.size() > MAX_CVES_PER_DEP) {
                vulns << "<div style=\"padding:3px 0;font-size:12px;color:#57606a\">+ ${f.cves.size() - MAX_CVES_PER_DEP} more</div>"
            }
            h << "<tr><td ${keyCell}>Vulnerabilities</td><td ${valCell}>${vulns.join('')}</td></tr>"

            h << '</table></td></tr>'
        }

        if (shown.size() < findings.size()) {
            h << "<tr><td style=\"padding:12px 24px;border-top:1px solid #d0d7de;font-size:13px;color:#57606a\">" +
                 "and ${findings.size() - shown.size()} more vulnerable dependencies&hellip;</td></tr>"
        }
        h << '</table></td></tr>'

        // ── call to action ─────────────────────────────────────────────────────────
        if (findings) {
            h << "<tr><td align=\"center\" style=\"padding:24px 0 8px\">" +
                 "<a href=\"${esc(reportUrl)}\" style=\"display:inline-block;padding:8px 16px;background:#1f883d;color:#ffffff;" +
                 "font-size:14px;font-weight:600;text-decoration:none;border-radius:6px;border:1px solid #1a7f37\">Review all vulnerable dependencies</a></td></tr>"
        }

        // ── footer ─────────────────────────────────────────────────────────────────
        h << "<tr><td style=\"padding:24px 0 0;font-size:12px;line-height:1.5;color:#57606a;border-top:1px solid #d0d7de\">" +
             "You are receiving this digest because you are subscribed to the daily security audit of <b>${esc(bootstrapName)}</b>. " +
             "Data from JFrog Xray${catalogOk ? ' and JFrog Catalog' : ''} &middot; minimum severity: ${esc(minSeverity)} &middot; full list attached as CSV.<br>" +
             "<a href=\"${esc(buildUrl)}\" style=\"color:#0969da;text-decoration:none\">View the Jenkins build</a></td></tr>"

        h << '</table></td></tr></table></body></html>'
        return h.join('\n')
    }
}
