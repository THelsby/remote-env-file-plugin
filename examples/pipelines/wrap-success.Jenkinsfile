// Local Docker test setup fixture:
// https://env-fixture:8443/public.env
node {
  wrap([
    $class: 'RemoteEnvFileBuildWrapper',
    sources: [
      [sourceUrl: 'https://env-fixture:8443/public.env']
    ]
  ]) {
    if (env.APP_NAME != 'remote-env-file-demo') {
      error('APP_NAME was not loaded from the remote env file')
    }
    if (env.APP_MODE != 'demo') {
      error('APP_MODE was not loaded from the remote env file')
    }
    if (env.QUOTED_MESSAGE != 'loaded from GitHub raw') {
      error('QUOTED_MESSAGE did not match the expected fixture value')
    }
    echo "Loaded remote variables for ${env.APP_NAME}"
  }
}
