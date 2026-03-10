# Job DSL Examples

This directory is the canonical copy/paste reference for Job DSL usage with this plugin.

The scripts here are copied into the local Docker Jenkins image at `/var/jenkins_home/jobdsl/` and used by the seeded job `seed-remote-env-jobdsl-examples`.

Important note about the API Viewer:

- The Job DSL API Viewer may only show `remoteEnvFiles(Closure closure)` for this plugin.
- That is expected for a custom closure-based extension method.
- The full supported syntax is documented here instead.

Generated jobs:

- `jobdsl-remote-env-wrapper-success`
- `jobdsl-remote-env-pipeline-property-success`
- `jobdsl-remote-env-configure-fallback`

## Supported DSL shape

Native Freestyle wrapper support:

```groovy
freeStyleJob('remote-env-wrapper-job') {
  wrappers {
    remoteEnvFiles {
      source('https://env-fixture:8443/base.env')
      source('https://env-fixture:8443/prod.env', 'fixture-basic')
    }
  }
}
```

Native Pipeline job-property support:

```groovy
pipelineJob('remote-env-pipeline-job') {
  properties {
    remoteEnvFiles {
      source {
        sourceUrl('https://env-fixture:8443/base.env')
      }
      source('https://env-fixture:8443/prod.env')
    }
  }
}
```

Supported source declaration styles inside `remoteEnvFiles`:

- Helper-call style:
  - `source('https://...')`
  - `source('https://...', 'cred-id')`
- Nested style:
  - `source { sourceUrl('https://...'); credentialsId('cred-id') }`

## Files in this folder

- `freestyle-native-wrapper.groovy`
  Native `freeStyleJob { wrappers { remoteEnvFiles { ... } } }`
  Uses both helper-call and nested source styles plus per-source credentials.
- `pipeline-native-job-property.groovy`
  Native `pipelineJob { properties { remoteEnvFiles { ... } } }`
- `pipeline-configure-fallback.groovy`
  `configure {}` fallback for users who want direct XML control or need a compatibility escape hatch.

## Upstream limitation

If we want richer inline examples directly inside the Job DSL API Viewer for custom closure extensions like this one, that would need a Job DSL enhancement. This plugin intentionally does not add a non-standard workaround for that.

## Local Docker flow

The Docker init script reads these files on startup and refreshes the `seed-remote-env-jobdsl-examples` definition from them.

After editing these scripts:

1. Rebuild/restart the local Docker Jenkins setup.
2. Run `seed-remote-env-jobdsl-examples`.
3. Open the generated jobs listed above.
