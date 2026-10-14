# SDK dependency security review

Reviewed on 2026-10-14 for PR #9. This report covers the resolved Maven runtime dependency graph, including dependencies shared with OSS. It is a package-version advisory scan, not a reachability analysis or a guarantee that no vulnerabilities exist.

## Versions and fixes

Maven Central metadata reported ODPS `0.61.2-public` and SLS `0.6.166` as the latest releases on the review date. Both are used by this plugin; the old issue-example pins are no longer required.

| Dependency | Previous or SDK-default version | Selected version | Scan findings addressed |
| --- | --- | --- | --- |
| odps-sdk-core | 0.52.3-public | 0.61.2-public | Newer SDK and transitive dependencies |
| aliyun-log | 0.6.126 | 0.6.166 | Newer SDK and transitive dependencies |
| commons-lang | 2.6 | Removed by SDK upgrade; commons-lang3 3.20.0 is used | [CVE-2025-48924](https://osv.dev/vulnerability/GHSA-j288-q9x7-2f5v) |
| net.jpountz.lz4:lz4 / org.lz4:lz4-pure-java | 1.3.0 / 1.8.0 | Replaced by at.yawk.lz4:lz4-java 1.12.0 | [CVE-2025-66566](https://osv.dev/vulnerability/GHSA-cmp6-m4wj-q63q), [CVE-2025-12183](https://osv.dev/vulnerability/GHSA-vqf4-7m7x-wgfc) |
| at.yawk.lz4:lz4-java | SDK default 1.11.2 | 1.12.0 | [CVE-2026-106449](https://osv.dev/vulnerability/GHSA-343h-94h5-c4wr), [CVE-2026-106450](https://osv.dev/vulnerability/GHSA-gm45-99xc-r7wv), [CVE-2026-106451](https://osv.dev/vulnerability/GHSA-mcr4-qmvw-px4g) |
| snappy-java | 1.1.10.3 | SDK upgrade selects 1.1.10.7 | [CVE-2023-43642](https://osv.dev/vulnerability/GHSA-55g7-9cwv-5qfv) |
| httpclient5 | BOM-selected 5.5.1 | 5.6.5 | [CVE-2026-64607](https://osv.dev/vulnerability/GHSA-hjcp-jmpx-g3qm) |
| httpcore5 | BOM-selected 5.4.1 | 5.4.4 | [CVE-2026-54399](https://osv.dev/vulnerability/GHSA-hf6x-8p5f-cgmf) |
| httpcore5-h2 | BOM-selected 5.4.1 | 5.4.4 | [CVE-2026-54428](https://osv.dev/vulnerability/GHSA-v3jc-474w-2wm6) |

LZ4 is constrained to the patched version. Explicit resolution overrides are necessary for HttpComponents 5 because the enforced Kestra BOM otherwise wins over ordinary dependency constraints. The plugin relocates these HTTP classes, including the newer `org.apache.hc` package, so it does not replace Kestra's transport classes at runtime. The ODPS credentials API is also relocated.

## Scan method and result

The baseline and upgraded graphs were exported from Gradle's resolved `runtimeClasspath`, including the actual versions selected by the enforced platform. Every resolved Maven package/version was submitted to the [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/). Pagination was followed, full advisory records were fetched, and withdrawn records were omitted.

The final inventory and returned advisory IDs are recorded in [dependency-security-scan.json](dependency-security-scan.json). It contains 65 resolved packages. Of these, 57 are selected for packaging and have zero active OSV advisory matches at the scan time. Eight framework-owned packages are excluded by `shadowJar`.

The complete runtime graph still returns 20 advisory matches across framework-selected Jackson core/databind and OpenTelemetry API. These are explicitly retained in the JSON report rather than suppressed. Jackson, OpenTelemetry and SLF4J classes are excluded from the plugin JAR; Kestra supplies these shared libraries. Kestra's plugin class loader explicitly delegates OpenTelemetry to its parent. This plugin upgrade does not remediate vulnerabilities in the host Kestra installation; operators and maintainers must evaluate the host versions separately.

The query format for reproducing a package check is:

```json
{
  "queries": [
    {"package": {"ecosystem": "Maven", "name": "com.aliyun.odps:odps-sdk-core"}, "version": "0.61.2-public"},
    {"package": {"ecosystem": "Maven", "name": "com.aliyun.openservices:aliyun-log"}, "version": "0.6.166"}
  ]
}
```

Submit the package/version pairs in the JSON inventory to `POST https://api.osv.dev/v1/querybatch`. Fetch each returned ID from `GET https://api.osv.dev/v1/vulns/{id}` and check withdrawal status. To confirm resolved dependency versions after future changes, use `./gradlew dependencies --configuration runtimeClasspath` and `./gradlew dependencyInsight --dependency httpclient5 --configuration runtimeClasspath`. Re-scan after dependency or BOM changes; the recorded result is a dated snapshot.

## Compatibility validation

After the QA fixes and latest OSS merge, the Java 21 / Kestra 1.3.39 build passed all 65 tests and all 25 documentation rules. Its packaged JAR passed 64 tests on Kestra 1.3.39 and 1.3.43, and the same Java 21-built JAR passed all 64 on Java 25 / Kestra 2.0.6. Tests exercise core flow validation/save services, all MaxCompute/SLS examples, SDK-generated STRING/CHAR/VARCHAR/binary fixtures, string watermarks in FETCH/STORE, and the service protocols through WireMock. Packaged-JAR tests check runtime linkage independently of the compiled main class directory.

Kestra 2.0.6 changed the flow-create signature and the upstream OSS test helper's return type. The save test handles both signatures; compatibility validation adapts a generated copy of the upstream OSS fixture to `TriggerState.context()`, preserving its assertions and leaving production code unchanged. No live Alibaba Cloud account or browser UI was used.
