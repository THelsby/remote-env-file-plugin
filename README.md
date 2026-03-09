# Remote Env File Plugin

`remote-env-file` loads a remote HTTPS `.env` file at build runtime and injects the parsed variables into the Jenkins build environment.

It is designed for cases where the environment file lives outside Jenkins, for example in GitHub, an object store, or an internal HTTPS endpoint, and you want Jenkins to fetch the current version at run time instead of copying values into job configuration by hand.

## What it does

- Fetches one remote dotenv file per invocation.
- Supports three auth modes:
  - no credentials: anonymous request
  - Secret Text credential: `Authorization: Bearer <token>`
  - Username with password credential: HTTP Basic auth
- Expands Jenkins environment variables in `sourceUrl` and `credentialsId` before resolving them.
- Rejects duplicate keys inside the remote file.
- Rejects keys that would overwrite existing build environment variables.
- Fails the build on fetch, auth, parse, size, or collision errors.
- Keeps fetched content in memory only.

## What it does not do

- No non-HTTPS URLs.
- No Git provider specific APIs or custom headers.
- No multiline dotenv support.
- No `export KEY=value` support.
- No variable interpolation such as `${HOME}` or `$HOME`.
- No automatic polling or mid-build refresh. The file is fetched once at wrapper start or once at Pipeline run setup.

## Choose the right mode

### 1. Freestyle job build environment

Use this when you want the remote variables available for the whole Freestyle build.

In the job configuration:

1. Open `Build Environment`.
2. Enable `Load environment variables from a remote HTTPS dotenv file`.
3. Enter `Source URL`.
4. Optionally select a credential from the `Credentials ID` dropdown.

### 2. Pipeline job configuration

Use this when you want the variables available to the Pipeline run without wrapping every stage manually.

In the Pipeline job configuration:

1. Open `Configure`.
2. In the main job configuration section, enable `Load environment variables from a remote HTTPS dotenv file`.
3. Enter `Source URL`.
4. Optionally select a credential from the `Credentials ID` dropdown.

This job-level mode applies the fetched values to the Pipeline run after the build starts and caches them for that run.

### 3. Pipeline `wrap(...)`

Use this when only part of the Pipeline should see the variables, or when you explicitly want the fetch to happen from the selected agent workspace context.

```groovy
node {
  wrap([
    $class: 'RemoteEnvFileBuildWrapper',
    sourceUrl: 'https://raw.githubusercontent.com/your-org/your-repo/main/config/app.env',
    credentialsId: 'github-pat'
  ]) {
    sh 'printenv | sort'
    sh './gradlew test'
  }
}
```

Variables loaded through `wrap(...)` exist only inside the wrapped block.

## Pipeline examples

### Job-level Pipeline configuration

Configure the remote source in the job UI, then keep the Jenkinsfile clean:

```groovy
pipeline {
  agent any

  stages {
    stage('Build') {
      steps {
        sh 'test -n "$APP_NAME"'
        sh 'echo "Loaded APP_NAME for this run"'
      }
    }
  }
}
```

### Scoped Pipeline wrapper

```groovy
pipeline {
  agent any

  stages {
    stage('Integration Tests') {
      steps {
        wrap([
          $class: 'RemoteEnvFileBuildWrapper',
          sourceUrl: 'https://config.example.com/team/service/test.env',
          credentialsId: 'config-basic-auth'
        ]) {
          sh './scripts/run-integration-tests.sh'
        }
      }
    }
  }
}
```

## Credentials

The plugin supports the following Jenkins credential types:

- `Secret Text`
- `Username with password`

In Jenkins UI, the `Credentials ID` field is a dropdown that lists supported credentials visible to that job and applicable to the configured URL.

Use:

- `Secret Text` for bearer-token style endpoints such as private raw file endpoints backed by an access token.
- `Username with password` for internal HTTPS services, artifact repositories, or other endpoints protected with HTTP Basic auth.

If the file is public or uses a presigned URL, leave `Credentials ID` empty.

## Remote source examples

The plugin expects a URL that returns plain text dotenv content over HTTPS. The URL must point to the raw file, not a human-facing HTML page.

### Public GitHub raw file

No credentials required.

```text
https://raw.githubusercontent.com/THelsby/remote-env-file/main/examples/public.env
```

Pipeline example:

```groovy
wrap([
  $class: 'RemoteEnvFileBuildWrapper',
  sourceUrl: 'https://raw.githubusercontent.com/THelsby/remote-env-file/main/examples/public.env'
]) {
  sh 'echo "$APP_NAME"'
}
```

### Private GitHub raw file

Use a Jenkins `Secret Text` credential containing a GitHub token with read access to the repository.

```text
https://raw.githubusercontent.com/your-org/private-config-repo/main/environments/prod.env
```

Pipeline example:

```groovy
wrap([
  $class: 'RemoteEnvFileBuildWrapper',
  sourceUrl: 'https://raw.githubusercontent.com/your-org/private-config-repo/main/environments/prod.env',
  credentialsId: 'github-pat'
]) {
  sh './deploy.sh'
}
```

### S3 or object-store presigned URL

No Jenkins credential is needed if the URL already contains a time-limited signature.

```text
https://example-config-bucket.s3.eu-west-2.amazonaws.com/app/prod.env?X-Amz-Algorithm=AWS4-HMAC-SHA256&...
```

Job-level configuration works well here because the presigned URL can be supplied as a parameter-expanded `sourceUrl`.

### Internal HTTPS config endpoint with basic auth

Use a Jenkins `Username with password` credential.

```text
https://config.example.com/jenkins/my-service/test.env
```

Pipeline example:

```groovy
wrap([
  $class: 'RemoteEnvFileBuildWrapper',
  sourceUrl: 'https://config.example.com/jenkins/my-service/test.env',
  credentialsId: 'config-basic-auth'
]) {
  sh './smoke-test.sh'
}
```

### GitHub Gist raw URL

Useful for simple public demos.

```text
https://gist.githubusercontent.com/your-user/0123456789abcdef/raw/0123456789abcdef/example.env
```

## Test fixtures in this repository

This repository now includes ready-to-use fixture files under `examples/`.

Successful fetches:

- `https://raw.githubusercontent.com/THelsby/remote-env-file/main/examples/public.env`
- `https://raw.githubusercontent.com/THelsby/remote-env-file/main/examples/quoted.env`

Intentional failure cases:

- `https://raw.githubusercontent.com/THelsby/remote-env-file/main/examples/invalid-export.env`
- `https://raw.githubusercontent.com/THelsby/remote-env-file/main/examples/duplicate-keys.env`
- `https://raw.githubusercontent.com/THelsby/remote-env-file/main/examples/interpolation.env`

Quick test Jenkinsfile:

```groovy
pipeline {
  agent any

  stages {
    stage('Verify Remote Env') {
      steps {
        wrap([
          $class: 'RemoteEnvFileBuildWrapper',
          sourceUrl: 'https://raw.githubusercontent.com/THelsby/remote-env-file/main/examples/public.env'
        ]) {
          sh 'test "$APP_NAME" = "remote-env-file-demo"'
          sh 'test "$QUOTED_MESSAGE" = "loaded from GitHub raw"'
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
- The container startup script recopies the plugin into `JENKINS_HOME/plugins` so rebuilt plugin versions are picked up on restart.

## Quick smoke test

1. Start Jenkins with Docker.
2. Create a Pipeline job.
3. In the job configuration, enable `Load environment variables from a remote HTTPS dotenv file`.
4. Point `Source URL` at a public raw `.env` file.
5. Save the job.
6. Use a Jenkinsfile like this:

```groovy
pipeline {
  agent any

  stages {
    stage('Verify') {
      steps {
        sh 'test -n "$APP_NAME"'
        sh 'echo "Remote env file loaded"'
      }
    }
  }
}
```

If the build fails, the console log will tell you whether the problem is URL validation, authentication, dotenv parsing, size limits, or variable collisions.

## License

MIT. See [LICENSE](LICENSE).
