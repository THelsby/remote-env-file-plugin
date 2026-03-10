// Configure the job property in the Jenkins UI with these sources, in order:
// https://env-fixture:8443/base.env
// https://env-fixture:8443/prod.env
node {
  if (env.APP_NAME != 'remote-env-file-demo') {
    error('APP_NAME was not loaded from the job property fixture')
  }
  if (env.APP_MODE != 'prod') {
    error('APP_MODE was not overridden by the later job property source')
  }
  if (env.COMMON_VALUE != 'prod-override') {
    error('COMMON_VALUE was not overridden by the later job property source')
  }
  echo "Verified layered job property variables for ${env.APP_NAME}"
}
