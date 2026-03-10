// Local Docker test setup fixture:
// https://env-fixture:8443/blocked-path.env
node {
  withRemoteEnvFiles(sources: [
    [sourceUrl: 'https://env-fixture:8443/blocked-path.env']
  ]) {
    error('The blocked PATH fixture should fail before this step runs')
  }
}
