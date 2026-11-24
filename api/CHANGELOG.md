# jpm-api

## 0.15.0

### Minor Changes

- Protocol 1.2: `dependencies/resolve` resolves Maven dependencies in a graph of their own, downloads them and returns their jars, for tools that load a library at run time (`JpmClient.resolve`)
- Protocol 1.2: `dependencies/search` finds Maven coordinates by name, from jpm's download cache and Maven Central's search (`JpmClient.search`), so editors can complete `group:artifact`

## 0.14.0

### Minor Changes

- `JpmClient.versions(dir, keys, refresh)` calls `dependencies/versions` (protocol 1.1); the client speaks protocol 1.1.

## 0.13.0
