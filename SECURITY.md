# Security Policy

## Supported versions

kuri is pre-1.0 and moves fast. Security fixes land only on the latest release; there are no
long-term-support branches for the `0.x` series. Please reproduce any issue against the most recent
version before reporting it.

| Version | Supported          |
|---------|--------------------|
| Latest  | :white_check_mark: |
| Older   | :x:                |

## Reporting a vulnerability

Please report suspected vulnerabilities privately through GitHub's private vulnerability reporting:
open the repository's **Security** tab and choose **Report a vulnerability**. This keeps the report
confidential until a fix is ready, so a patch can be prepared before public disclosure. Do not open a
public issue for a security problem.

When you report, please include the affected version, a minimal reproduction (the exact input string
is ideal), and what you expected versus what happened.

kuri parses and canonicalizes untrusted URLs, so its attack surface includes SSRF via host confusion,
authority-parsing ambiguities, and IDNA homograph or spoofing cases. Reports in these areas are taken
seriously and triaged accordingly.
