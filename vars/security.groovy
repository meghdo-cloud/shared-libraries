// Quality Gate Evaluation Methods
def evaluateCodeQualityGate(semgrepResults) {
    def qualityGates = [
            critical: 0,
            high: 3,
            medium: 5,
            low: 10
    ]

    def severityCounts = [
            critical: 0,
            high: 0,
            medium: 0,
            low: 0
    ]

    semgrepResults.results.each { result ->
        switch(result.extra.severity.toLowerCase()) {
            case 'critical':
                severityCounts.critical++
                break
            case 'high':
                severityCounts.high++
                break
            case 'medium':
                severityCounts.medium++
                break
            case 'low':
                severityCounts.low++
                break
        }
    }

    if (severityCounts.critical > qualityGates.critical) {
        error "Code Quality Gate Failed: ${severityCounts.critical} critical vulnerabilities found"
    }
    if (severityCounts.high > qualityGates.high) {
        error "Code Quality Gate Failed: ${severityCounts.high} high severity vulnerabilities found"
    }
}

def evaluateDependencyQualityGate(snykResults) {
    def qualityGates = [
            critical: 0,
            high: 2,
            medium: 5
    ]

    def vulnerabilitySeverities = [
            critical: [],
            high: [],
            medium: []
    ]

    snykResults.vulnerabilities.each { vuln ->
        switch(vuln.severity.toLowerCase()) {
            case 'critical':
                vulnerabilitySeverities.critical << vuln
                break
            case 'high':
                vulnerabilitySeverities.high << vuln
                break
            case 'medium':
                vulnerabilitySeverities.medium << vuln
                break
        }
    }

    if (vulnerabilitySeverities.critical.size() > qualityGates.critical) {
        error "Dependency Scan Failed: ${vulnerabilitySeverities.critical.size()} critical vulnerabilities found"
    }

    if (vulnerabilitySeverities.high.size() > qualityGates.high) {
        error "Dependency Scan Failed: ${vulnerabilitySeverities.high.size()} high severity vulnerabilities found"
    }
}

def evaluateContainerImageQualityGate(trivyResults) {
    def qualityGates = [
            critical: 0,
            high: 3,
            medium: 5
    ]

    def vulnerabilitySummary = [
            critical: [],
            high: [],
            medium: []
    ]

    trivyResults.Results.each { result ->
        result.Vulnerabilities?.each { vuln ->
            switch(vuln.Severity.toLowerCase()) {
                case 'critical':
                    vulnerabilitySummary.critical << vuln
                    break
                case 'high':
                    vulnerabilitySummary.high << vuln
                    break
                case 'medium':
                    vulnerabilitySummary.medium << vuln
                    break
            }
        }
    }

    if (vulnerabilitySummary.critical.size() > qualityGates.critical) {
        error """
        Container Image Scan Failed:
        - ${vulnerabilitySummary.critical.size()} critical vulnerabilities found
        - Vulnerable Packages: ${vulnerabilitySummary.critical.collect { it.PkgName }.unique().join(', ')}
        """
    }

    if (vulnerabilitySummary.high.size() > qualityGates.high) {
        error "Container Image Scan Failed: ${vulnerabilitySummary.high.size()} high severity vulnerabilities found"
    }
}

// Reporting and Notification Methods
def publishSecurityReports() {
    // Publish HTML reports for various scans
    publishHTML([
            allowMissing: false,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: '.',
            reportFiles: 'semgrep-results.json',
            reportName: 'Security Scan Reports'
    ])
}

def sendSecurityFailureNotification() {
    // Customize notification based on your communication tools
    mail to: 'raman.aparna@gmail.com',
            subject: "Security Scan Failed: ${currentBuild.fullDisplayName}",
            body: """
         Security quality gates were not met.
         
         Build: ${currentBuild.fullDisplayName}
         Status: Failed
         
         Please review the detailed scan results in the build artifacts.
         """
}
