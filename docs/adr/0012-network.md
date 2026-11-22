# 0012. Network: certificates, proxies, retries

Status: accepted

## Decision

- Besides the JDK's CA certificates, jpm trusts the system bundle where there is one:
  `/etc/ssl/certs/ca-certificates.crt` (Debian, Ubuntu, Alpine, Arch), `/etc/pki/tls/certs/ca-bundle.crt`
  (Fedora, RHEL), `/etc/ssl/ca-bundle.pem` (openSUSE) and `/etc/ssl/cert.pem` (macOS, BSDs) (`Net`).
- Proxies come from the environment (`https_proxy`, `http_proxy`, `no_proxy`) unless the JVM's proxy
  properties are set.
- Downloads from Maven repositories are one request with no retries; see
  [0005](0005-maven-repositories.md).
- A Gradle distribution found in the cache without a recorded checksum (placed there by hand, or left over
  from an interrupted install) is hashed before use (`GradleDist`).
