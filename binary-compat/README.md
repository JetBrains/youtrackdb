# This module is inteded to test binary compatibility of YouTrackDB releases.


## Usage example:

1. build the image:

```shell
docker build -t bin_compat_yt . -f ./docker/Dockerfile
```

2. run the tests:

```shell
docker run --rm -v path_to_config:/config -v path_to_local_db:/config/test-db bin_compat_yt
```

sample config could be found in `tests/src/test/resources/binary-compatibility-test-config.yaml`
