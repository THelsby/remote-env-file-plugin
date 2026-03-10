// Local Docker test setup fixtures, in order:
// https://env-fixture:8443/base.env
// https://env-fixture:8443/prod.env
node {
  withRemoteEnvFiles(sources: [
    [sourceUrl: 'https://env-fixture:8443/base.env'],
    [sourceUrl: 'https://env-fixture:8443/prod.env']
  ]) {
    if (env.APP_NAME != 'remote-env-file-demo') {
      error('APP_NAME was not loaded from the layered remote env files')
    }
    if (env.APP_MODE != 'prod') {
      error('APP_MODE was not overridden by the higher-precedence source')
    }
    if (env.COMMON_VALUE != 'prod-override') {
      error('COMMON_VALUE was not overridden by the higher-precedence source')
    }
    if (env.RELEASE_CHANNEL != 'stable') {
      error('RELEASE_CHANNEL was not loaded from the higher-precedence source')
    }
    echo "Loaded layered remote variables for ${env.APP_NAME}"
  }
}
