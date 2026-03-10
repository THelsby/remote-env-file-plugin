# Example Pipelines

These example Jenkinsfiles are designed for the local Docker test setup in this repository.

They assume the local HTTPS fixture service is available at:

```text
https://env-fixture:8443/
```

Seeded jobs created by the Docker Jenkins startup script:

- `example-remote-env-wrapper-success` - expected `SUCCESS`
- `example-remote-env-wrapper-multi-source-success` - expected `SUCCESS`
- `example-remote-env-wrapper-blocked-path` - expected `FAILURE`
- `example-remote-env-wrapper-duplicate-case` - expected `FAILURE`
- `example-remote-env-job-property-success` - expected `SUCCESS`
- `example-remote-env-job-property-blocked-loader` - expected `FAILURE`

If you want to create jobs manually in the Jenkins UI, copy the matching Jenkinsfile from this folder.

For the `job-property-*` examples, open the job `Configure` page, enable:

`Load environment variables from a remote HTTPS dotenv file`

Then add the source URLs shown in the comment at the top of the Jenkinsfile, in the same order they are listed.

Remote values loaded by these examples are plain environment variables. If the Pipeline echoes them, Jenkins does not mask or protect them automatically.
