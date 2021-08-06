# identify

Library to identify end users using the [OpenID Connect (OIDC)][oidc]
protocol.

Features:
* [Ring][ring] middleware that acts as a [OpenID Connect (OIDC)][oidc] client.

## Latest version

Available from [Clojars][clojars].

[![Clojars Project](https://img.shields.io/clojars/v/com.fbeyer/identify.svg)][clojars]

### Clojure CLI/deps.edn

```clojure
com.fbeyer/identify {:mvn/version "VERSION"}
```

### Leiningen/Boot

```clojure
[com.fbeyer/identify "VERSION"]
```

## Similar projects

* [ring-oauth2]

## License

Distributed under the [MIT License].  
Copyright &copy; 2021 [Ferdinand Beyer]

[clojars]: https://clojars.org/com.fbeyer/identify

[oidc]: https://openid.net/connect/
[ring]: https://github.com/ring-clojure/ring
[ring-oauth2]: https://github.com/weavejester/ring-oauth2

[Ferdinand Beyer]: https://fbeyer.com
[MIT License]: https://opensource.org/licenses/MIT
