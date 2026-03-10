// Local Docker test setup fixture:
// https://env-fixture:8443/duplicate-case.env
node {
  withRemoteEnvFiles(sources: [
    [sourceUrl: 'https://env-fixture:8443/duplicate-case.env']
  ]) {
    error('The duplicate-case fixture should fail before this step runs')
  }
}
