# Remote Env File Plugin

`remote-env-file` loads one or more remote HTTPS `.env` files at build runtime and injects the parsed variables into the Jenkins build environment.

It is designed for cases where configuration lives outside Jenkins, for example in GitHub raw files, object storage, or an internal HTTPS endpoint, and you want Jenkins to fetch the current version at run time instead of copying values into job configuration by hand.

## What it does

- Loads an ordered list of remote dotenv files per invocation.
- Applies sources top to bottom, with later sources overriding earlier remote values.
- Supports per-source auth:
  - no credentials: anonymous request
  - Secret Text credential: `Authorization: Bearer <token>`
  - Username with password credential: HTTP Basic auth
- Exposes a first-class Pipeline step: `withRemoteEnvFiles(...)`
- Adds native Job DSL support via `remoteEnvFiles { ... }` for Freestyle wrappers and Pipeline job properties
- Expands Jenkins environment variables in each `sourceUrl` and `credentialsId` before resolving them.
- Rejects duplicate keys inside a single remote file, including keys that differ only by case such as `FOO` and `foo`.
- Rejects special OS/process-loading variables such as `PATH`, `PATHEXT`, `LD_PRELOAD`, `LD_LIBRARY_PATH`, `DYLD_LIBRARY_PATH`, `DYLD_INSERT_LIBRARIES`, `LIBPATH`, and `SHLIB_PATH`.
- Rejects keys that would overwrite existing build environment variables. Collision checks follow Jenkins environment semantics and are case-insensitive.
- Fails the build on fetch, auth, parse, size, blocked-variable, or collision errors.
- Keeps fetched content in memory only.

## What it does not do

- No non-HTTPS URLs.
- No Git provider specific APIs or custom headers.
- No multiline dotenv support.
- No `export KEY=value` support.
- No variable interpolation such as `${HOME}` or `$HOME`.
- No support for overriding special OS/process-loading variables such as `PATH` or `LD_PRELOAD`.
- No secret masking. Values injected by this plugin are ordinary environment variables.
- No automatic polling or mid-build refresh. Sources are fetched once at wrapper start or once at Pipeline run setup.

## Warning about echoed values

Remote values injected by this plugin are normal environment variables.

If a build step prints, echoes, or otherwise exposes those values, Jenkins does **not** mask or protect them automatically just because they came from this plugin. Treat remote dotenv content accordingly.

## Security behavior

This plugin treats the remote dotenv source as trusted job configuration for normal application settings, but it intentionally rejects environment variables that can change how the operating system resolves binaries or loads shared libraries.

Blocked variables are:

- `PATH`
- `PATHEXT`
- `LD_PRELOAD`
- `LD_LIBRARY_PATH`
- `DYLD_LIBRARY_PATH`
- `DYLD_INSERT_LIBRARIES`
- `LIBPATH`
- `SHLIB_PATH`

These checks are case-insensitive. For example, `PATH` and `Path` are treated as the same variable.

## Choose the right mode

### 1. Freestyle job build environment

Use this when you want the remote variables available for the whole Freestyle build.

In the job configuration:

1. Open `Build Environment`.
2. Enable `Load environment variables from a remote HTTPS dotenv file`.
3. Add one or more rows under `Sources`.
4. Put the lowest-precedence source first and the highest-precedence source last.
5. Optionally choose a credential per source row.

### 2. Pipeline job configuration

Use this when you want the variables available to the Pipeline run without wrapping every stage manually.

In the Pipeline job configuration:

1. Open `Configure`.
2. In the main job configuration section, enable `Load environment variables from a remote HTTPS dotenv file`.
3. Add one or more rows under `Sources`.
4. Put the lowest-precedence source first and the highest-precedence source last.
5. Optionally choose a credential per source row.

This job-level mode applies the fetched values to the Pipeline run after the build starts, fails the run before user steps if loading fails, and caches the merged values for that run.

### 3. Pipeline `withRemoteEnvFiles(...)`

Use this when only part of the Pipeline should see the variables, or when you explicitly want the fetch to happen from the selected agent workspace context.

The plugin exposes `withRemoteEnvFiles(...)` directly in Pipeline and in the Jenkins snippetizer.

```groovy
node {
  withRemoteEnvFiles(sources: [
    [sourceUrl: 'https://raw.githubusercontent.com/your-org/your-repo/main/config/base.env'],
    [sourceUrl: 'https://raw.githubusercontent.com/your-org/your-repo/main/config/prod.env']
  ]) {
    sh 'printenv | sort'
    sh './gradlew test'
  }
}
```

Variables loaded through `withRemoteEnvFiles(...)` exist only inside the wrapped block.

## Precedence rules

Sources are loaded in the order you configure them.

- Earlier source: lower precedence
- Later source: higher precedence
- Same key in multiple remote files: later source wins
- Same key as an existing Jenkins/build environment variable: the build fails

Example:

- `base.env` sets `APP_MODE=base`
- `prod.env` sets `APP_MODE=prod`
- final merged environment uses `APP_MODE=prod`

## Pipeline examples

### Job-level Pipeline configuration

Configure the sources in the job UI, then keep the Jenkinsfile clean:

```groovy
pipeline {
  agent any

  stages {
    stage('Build') {
      steps {
        sh 'test -n "$APP_NAME"'
        sh 'test "$APP_MODE" = "prod"'
        sh 'echo "Loaded merged remote configuration for this run"'
      }
    }
  }
}
```

### Scoped Pipeline wrapper with one source

```groovy
pipeline {
  agent any

  stages {
    stage('Integration Tests') {
      steps {
        withRemoteEnvFiles(sources: [
          [
            sourceUrl: 'https://config.example.com/team/service/test.env',
            credentialsId: 'config-basic-auth'
          ]
        ]) {
          sh './scripts/run-integration-tests.sh'
        }
      }
    }
  }
}
```

### Scoped Pipeline wrapper with `base.env + prod.env`

```groovy
pipeline {
  agent any

  stages {
    stage('Verify Merged Env') {
      steps {
        withRemoteEnvFiles(sources: [
          [sourceUrl: 'https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/base.env'],
          [sourceUrl: 'https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/prod.env']
        ]) {
          sh 'test "$APP_MODE" = "prod"'
          sh 'test "$COMMON_VALUE" = "prod-override"'
          sh 'test "$RELEASE_CHANNEL" = "stable"'
        }
      }
    }
  }
}
```

## Job DSL examples

This plugin also adds native Job DSL methods named `remoteEnvFiles`.

- In Jenkinsfiles, the Pipeline step remains `withRemoteEnvFiles(...)`.
- In Job DSL seed scripts, use `remoteEnvFiles { ... }`.
- In the Job DSL API Viewer, custom closure extensions may only appear as `remoteEnvFiles(Closure closure)`. That is expected for this style of extension. The full supported syntax is documented below and in `examples/jobdsl/`.

### Native Job DSL for Freestyle wrappers

```groovy
freeStyleJob('remote-env-wrapper-job') {
  wrappers {
    remoteEnvFiles {
      source('https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/base.env')
      source {
        sourceUrl('https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/prod.env')
      }
    }
  }

  steps {
    shell('''#!/bin/sh -eu
test "$APP_MODE" = "prod"
echo "Job DSL wrapper example passed"
''')
  }
}
```

### Native Job DSL for Pipeline job properties

```groovy
pipelineJob('remote-env-pipeline-job') {
  properties {
    remoteEnvFiles {
      source('https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/base.env')
      source('https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/prod.env')
    }
  }

  definition {
    cps {
      sandbox(true)
      script('''pipeline {
  agent any
  stages {
    stage('Verify') {
      steps {
        sh 'test "$APP_MODE" = "prod"'
      }
    }
  }
}''')
    }
  }
}
```

Supported source declaration styles inside the Job DSL block:

- Nested:
  `source { sourceUrl('https://...'); credentialsId('cred-id') }`
- Helper-call:
  `source('https://...')`
  `source('https://...', 'cred-id')`

### Dynamic DSL fallback

If the Job DSL plugin exposes generated dynamic methods for your installed plugin version, you can inspect them in the Job DSL API Viewer. The native `remoteEnvFiles` DSL added by this plugin is the recommended, stable option.

### `configure {}` fallback

If you hit a Job DSL/plugin-version mismatch, use `configure {}` as the escape hatch. A working fallback example is included in `examples/jobdsl/pipeline-configure-fallback.groovy`.

### API Viewer limitation

The Job DSL API Viewer currently shows the `remoteEnvFiles` extension method, but it does not render a richer nested example for this custom closure DSL. Improving that viewer experience would require a Job DSL enhancement rather than a plugin-side workaround, so this plugin relies on the README and `examples/jobdsl/` as the canonical reference for full syntax.

## Credentials

The plugin supports the following Jenkins credential types:

- `Secret Text`
- `Username with password`

Each source row has its own `Credentials ID` dropdown, which lists supported credentials visible to that job and applicable to the configured URL.

Use:

- `Secret Text` for bearer-token style endpoints such as private raw file endpoints backed by an access token
- `Username with password` for internal HTTPS services, artifact repositories, or other endpoints protected with HTTP Basic auth

If a source is public or uses a presigned URL, leave `Credentials ID` empty for that row.

## Remote source examples

The plugin expects URLs that return plain text dotenv content over HTTPS. The URL must point to the raw file, not a human-facing HTML page.

### Public GitHub raw file

No credentials required.

```text
https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/public.env
```

### Public GitHub raw files with precedence

Use two sources to layer shared and environment-specific settings.

```text
https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/base.env
https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/prod.env
```

Put `base.env` first and `prod.env` second so `prod.env` wins on overlapping keys.

### Private GitHub raw file

Use a Jenkins `Secret Text` credential containing a GitHub token with read access to the repository.

```text
https://raw.githubusercontent.com/your-org/private-config-repo/main/environments/prod.env
```

Pipeline example:

```groovy
withRemoteEnvFiles(sources: [
  [
    sourceUrl: 'https://raw.githubusercontent.com/your-org/private-config-repo/main/environments/prod.env',
    credentialsId: 'github-pat'
  ]
]) {
  sh './deploy.sh'
}
```

### S3 or object-store presigned URL

No Jenkins credential is needed if the URL already contains a time-limited signature.

```text
https://example-config-bucket.s3.eu-west-2.amazonaws.com/app/prod.env?X-Amz-Algorithm=AWS4-HMAC-SHA256&...
```

### Internal HTTPS config endpoint with basic auth

Use a Jenkins `Username with password` credential.

```text
https://config.example.com/jenkins/my-service/test.env
```

### GitHub Gist raw URL

Useful for simple public demos.

```text
https://gist.githubusercontent.com/your-user/0123456789abcdef/raw/0123456789abcdef/example.env
```

## Test fixtures in this repository

This repository includes ready-to-use fixture files under `examples/`.

Successful fetches:

- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/public.env`
- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/quoted.env`
- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/base.env`
- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/prod.env`

Intentional failure cases:

- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/invalid-export.env`
- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/duplicate-keys.env`
- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/interpolation.env`
- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/duplicate-case.env`
- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/blocked-path.env`
- `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/blocked-loader.env`

Quick test Jenkinsfile:

```groovy
pipeline {
  agent any

  stages {
    stage('Verify Remote Env') {
      steps {
        withRemoteEnvFiles(sources: [
          [sourceUrl: 'https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/base.env'],
          [sourceUrl: 'https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/prod.env']
        ]) {
          sh 'test "$APP_NAME" = "remote-env-file-demo"'
          sh 'test "$APP_MODE" = "prod"'
          sh 'test "$COMMON_VALUE" = "prod-override"'
          sh 'test "$RELEASE_CHANNEL" = "stable"'
        }
      }
    }
  }
}
```

## URL guidance

### GitHub

Use the raw URL:

```text
https://raw.githubusercontent.com/OWNER/REPO/BRANCH/PATH/TO/file.env
```

Do not use the HTML `blob` URL:

```text
https://github.com/OWNER/REPO/blob/main/PATH/TO/file.env
```

### General rule

If opening the URL in a browser shows a webpage with GitHub or GitLab chrome around it, it is probably the wrong URL. The plugin needs the direct raw file response.

## Supported dotenv format

Supported lines:

```dotenv
APP_NAME=my-service
API_BASE_URL=https://api.example.com
QUOTED="two words"
SINGLE='quoted value'
EMPTY=
```

Ignored:

- blank lines
- full-line comments beginning with `#`

Rejected examples:

```dotenv
export BAD=value
MULTILINE="line 1
line 2"
HOME_PATH=${HOME}/bin
1BAD=value
```

## Runtime behavior and limits

- HTTPS only.
- Redirects are followed.
- Connect timeout: 15 seconds.
- Read timeout: 15 seconds.
- Maximum response size: 131072 bytes.
- The plugin logs the source location and success/failure state, but not resolved credential values or dotenv values.

## Common failure modes

### `Only HTTPS source URLs are supported`

Use `https://...`, not `http://...`.

### `At least one remote source is required`

Add at least one row under `Sources`.

### `Credentials ID '...' could not be found`

The selected credential is not visible to the job, or the Pipeline script refers to the wrong credential ID.

### `must be Secret Text or Username with password`

The chosen credential type is unsupported for this plugin.

### `conflicts with an existing build environment variable`

The remote file contains a key that Jenkins already set for the build, such as a parameter or another environment variable. Rename the key in the remote file.

### `Invalid dotenv content at line ...`

The file is not valid under the plugin's strict dotenv parser. Remove `export`, interpolation, malformed keys, or multiline syntax.

## Local Docker development and testing

This repository includes a Docker-based Jenkins setup for local development and plugin testing.

Start Jenkins:

```powershell
.\docker\fixture\generate-certs.ps1
docker compose up --build -d
```

Jenkins will be available at [http://localhost:8080](http://localhost:8080).

Development login:

- username: `admin`
- password: `admin`

Useful commands:

```powershell
docker compose logs -f jenkins
docker compose down
docker compose down -v
docker compose build
docker compose up -d
```

Use `docker compose down -v` when you want to reset Jenkins state completely.

### Docker setup notes

- The plugin is built inside a Maven + JDK 17 container.
- The resulting `.hpi` is baked into a Jenkins `2.528.3-lts-jdk17` image.
- The development image creates a local admin user and disables the setup wizard.
- The Docker image also installs the `job-dsl` plugin and copies `examples/jobdsl/` into `JENKINS_HOME/jobdsl/`.
- The local Docker init scripts disable Job DSL script security for the seeded development examples so they can regenerate without manual approvals.
- The container startup script recopies the plugin into `JENKINS_HOME/plugins` so rebuilt plugin versions are picked up on restart.
- The local HTTPS fixture service uses self-signed certificates generated by `docker/fixture/generate-certs.ps1`.
- Generated cert/key files under `docker/fixture/certs/` are intentionally ignored by Git.

## Quick smoke test

1. Start Jenkins with Docker.
2. Create a Pipeline job.
3. In the job configuration, enable `Load environment variables from a remote HTTPS dotenv file`.
4. Add `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/base.env` as the first source.
5. Add `https://raw.githubusercontent.com/jenkinsci/remote-env-file-plugin/main/examples/prod.env` as the second source.
6. Save the job.
7. Use a Jenkinsfile like this:

```groovy
pipeline {
  agent any

  stages {
    stage('Verify') {
      steps {
        sh 'test "$APP_MODE" = "prod"'
        sh 'echo "Remote env files loaded"'
      }
    }
  }
}
```

If the build fails, the console log will tell you whether the problem is URL validation, authentication, dotenv parsing, blocked special variables, size limits, or variable collisions.

## Local Docker UI smoke tests

The Docker development setup in this repository includes:

- a local Jenkins controller at `http://localhost:8080`
- a local HTTPS fixture service for `.env` files at `https://env-fixture:8443/` inside the Docker network
- seeded example jobs you can open directly in the Jenkins UI after startup
- a Job DSL seed job that generates additional examples from `examples/jobdsl/`

Seeded jobs:

- `example-remote-env-wrapper-success`
- `example-remote-env-wrapper-multi-source-success`
- `example-remote-env-wrapper-blocked-path`
- `example-remote-env-wrapper-duplicate-case`
- `example-remote-env-job-property-success`
- `example-remote-env-job-property-blocked-loader`
- `seed-remote-env-jobdsl-examples`

Job DSL generated jobs:

- `jobdsl-remote-env-wrapper-success`
- `jobdsl-remote-env-pipeline-property-success`
- `jobdsl-remote-env-configure-fallback`

Matching example Jenkinsfiles live under `examples/pipelines/`.
Matching Job DSL scripts live under `examples/jobdsl/`.

## License

MIT. See [LICENSE](LICENSE).
