(ns oidc.client.ring
  "OpenID Connect 1.0 Client

   Implements the Code Flow (OAuth2 Authorization Code Grant) for OpenID
   Relying Parties (RP).  Provides a pluggable API using Ring handlers
   and Ring middleware.
   
   Flow:
   - User Agent requests the authentication endpoint
   - Client resolves the OpenID Provider (OP) to use and validates the
     request
   - Client runs the :authentication-hook
   - Client redirects User Agent to Provider
   - Provider authenticates user and redirects the User Agent to the
     callback endpoint
   - Client validates the request and prepares to fetch tokens
   - Client runs the :authorization-code-hook
   - Client requests tokens at the Provider
   - Provider responds with tokens
   - Client validates the response and the ID token
   - Client runs the :token-hook
   - Client requests user info at the Provider
   - Provider responds with user info
   - Client validates the response and the user info
   - Clients runs the :userinfo-hook
   - Client redirects the User Agent to the completion URI
   
   Hooks:
     :authentication-hook
     :authorization-code-hook
     :token-hook
     :userinfo-hook

   Handlers:
     :authentication-response-handler
     :callback-response-handler
     :error-handler
   
   See: https://openid.net/specs/openid-connect-basic-1_0.html
   "
  {:clj-kondo/config
   '{:linters {:unresolved-var {:exclude [org.httpkit.client]}}}}
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [crypto.random :as random]
            [org.httpkit.client :as http]
            [org.httpkit.sni-client :as sni-client]
            [ring.util.codec :as codec]
            [ring.util.request :as req]
            [ring.util.response :as resp]))

;; TODO: Add tests
;; TODO: Add specs
;; TODO: Implement Discovery
;; TODO: Support OAuth2-only (no ID Token)

;;;; Provider info

(defn- provider? [x]
  (::valid x))

(defn- prop
  "Returns a potentially dynamic provider property: if the map entry is
   a function, call it in the context of the current request."
  [{::keys [provider] :as request} key]
  (let [v (get provider key)]
    (if (fn? v) (v request) v)))

(defn- resolve-uri
  "Resolves an URI relative to the request URL, returning an absolute URL."
  [request uri]
  (-> (req/request-url request)
      (java.net.URI/create)
      (.resolve uri)
      str))

(defn- completion-uri [request]
  (resolve-uri request (prop request :completion-uri)))

(defn- callback-uri [request]
  (resolve-uri request (prop request :callback-uri)))

;;;; JSON

(defn- json? [headers]
  (some-> (:content-type headers)
          (str/includes? "json")))

(defn- read-json [input]
  (with-open [reader (io/reader input)]
    (json/read reader :key-fn keyword)))

(defn- parse-json-response [{:keys [headers body]}]
  (when (json? headers)
    (read-json body)))

(defn- parse-jwt
  "Parses the payload of a JWT, ignoring header and signature."
  [jwt]
  (-> (str/split jwt #"\." 3)
      second
      codec/base64-decode
      read-json))

;;;; Error Handling

;; TODO: Normalize errors: Codes, exceptions, ...

(defn- handle-error
  "Special action executed when an error is encountered in the flow."
  [_context error]
  ;; TODO: Dispatch on known error types; use anomaly?
  (->
   (resp/response (pr-str error))
   (resp/content-type "text/plain")))

;;;; Authorization Code Flow

(defn- random-state []
  ;; Number of bytes should be divisable by 3, to not waste space
  ;; in a Base64-encoded string.
  (random/url-part 12))

(defn- scopes [provider]
  (->> (:scopes provider)
       (map name)
       (str/join " ")))

;; TODO: Support additional parameters, such as login_hint.  These could be
;; provided by the :authentication-hook
;; See: https://openid.net/specs/openid-connect-basic-1_0.html#RequestParameters
(defn- authenticate-uri
  [{::keys [provider] :as request} state]
  (let [endpoint (:authorization-endpoint provider)]
    (str endpoint
         (if (.contains ^String endpoint "?") "&" "?")
         (codec/form-encode {:response_type "code"
                             :client_id     (:client-id provider)
                             :redirect_uri  (callback-uri request)
                             :scope         (scopes provider)
                             :state         state}))))

(defn authentication-response-handler
  "Redirects the user agent to the provider's authorization endpoint to
   begin the Code Flow."
  [{:keys [session] :or {session {}} :as request} respond _raise]
  (let [state (random-state)
        uri (authenticate-uri request state)]
    (-> (resp/redirect uri)
        (assoc :session (assoc session ::state state))
        respond)))

(defn- get-authorization-code [request]
  (get-in request [:query-params "code"]))

(defn- state-matches? [request]
  (= (get-in request [:session ::state])
     (get-in request [:query-params "state"])))

(defn- validate-callback-request [request]
  (cond
    ;; State is also required for error responses.
    (not (state-matches? request))
    ::state-mismatch

    ;; XXX: Match error value against specified enum?
    ;; Can also have error_description and error_uri
    ;; https://www.rfc-editor.org/rfc/rfc6749.html#section-4.1.2
    (some? (get-in request [:query-params "error"]))
    ::provider-error

    (nil? (get-authorization-code request))
    ::no-auth-code))

(defn- wrap-validate-callback
  [handler]
  (fn [request respond raise]
    (if-let [error (validate-callback-request request)]
      ;; TODO: Unify error handling!
      (raise (ex-info "Invalid request" {:error error}))
      (handler (assoc request ::code (get-authorization-code request))
               respond raise))))

(defn- add-token-auth
  [request {:keys [client-id client-secret token-auth]}]
  (case token-auth
    :basic (assoc request :basic-auth [client-id client-secret])
    :post (update request :form-params assoc
                  :client_id     client-id
                  :client_secret client-secret)))

(defn- fetch-tokens
  [{::keys [provider code] :as request} callback]
  (-> {:client (force sni-client/default-client)
       :as :stream
       :headers {"Accept" "application/json"}
       :form-params {:grant_type   "authorization_code"
                     :code         code
                     :redirect_uri (callback-uri request)}}
      (add-token-auth provider)
      (as-> opts (http/post (:token-endpoint provider) opts callback))))

(defn- receive-tokens
  [request {:keys [error status] :as response}]
  (cond
    (some? error)
    (throw (ex-info "network error" response))

    ;; XXX - body will not be available any more
    (>= status 300)
    (throw (ex-info "http error"
                    (assoc response :error ::invalid-status)))
    :else
    (if-let [tokens (parse-json-response response)]
      ;; TODO: Validate structure
      ;; TODO: Determine absolute expiry time
      ;; TODO: Add provider-key?
      (assoc request ::tokens tokens)
      (throw (ex-info "invalid response"
                      (assoc response :error ::invalid-json))))))

(defn- wrap-fetch-tokens
  [handler]
  (fn [request respond raise]
    (letfn [(respond* [response]
              (respond (update-in response [:session] dissoc :state)))
            (callback [response]
              (try (-> (receive-tokens request response)
                       (handler respond* raise))
                   (catch Exception e
                     (raise e))))]
      (fetch-tokens request callback))))

;;;; ID Token

(defn- expired?
  "Tells if a token is expired, given its expiration time as seconds
   since the epoch, and a tolerable clock skew."
  [provider epoch-secs]
  (let [now (System/currentTimeMillis)
        skew (:max-clock-skew-secs provider)]
    (< (* (+ epoch-secs skew) 1000) now)))

(defn- validate-id-token-claims
  [{:keys [issuer client-id] :as provider} {:keys [iss aud exp azp]}]
  (cond
    (not= issuer iss)
    ::issuer-mismatch

    (not (or (= client-id aud)
             (and (coll? aud) (contains? aud client-id))))
    ::audience-mismatch

    (and (coll? aud) (nil? azp))
    ::no-authorized-party

    (and (some? azp) (not= client-id azp))
    ::authorized-party-mismatch

    (expired? provider exp)
    ::token-expired))

(defn- validate-id-token
  "Parses and validates the ID token received from the OP and adds it
   to the request as :id-token."
  [{::keys [provider tokens] :as request}]
  (let [{:keys [id_token]} tokens
        id-token (parse-jwt id_token)]
    (if-let [error (validate-id-token-claims provider id-token)]
      (throw (ex-info "invalid ID token"
                      {:error error
                       :id-token id-token}))
      ;; TODO: Use [::tokens provider-key :parsed-id-token]?
      (assoc request ::id-token id-token))))

(defn- wrap-validate-id-token
  [handler]
  (fn [request respond raise]
    (try (-> (validate-id-token request)
             (handler respond raise))
         (catch Exception e
           (raise e)))))

(defn completion-handler
  "Redirects the user agent to the configured completion URI.  Used by the
   callback handler to complete the flow, but can also be used by hooks
   to skip authentication."
  [request respond _raise]
  (respond (resp/redirect (completion-uri request))))

;;;; User Info

;; TODO

;;;; Ring Handlers

(defn- await-handler
  "Calls an asynchronous ring handler and awaits its completion.  Useful
   for implementing synchronous ring handlers from asynchronous ones."
  ([handler request] (await-handler handler request 60000))
  ([handler request timeout-ms]
   (let [p (promise)
         respond (partial deliver p)
         raise (fn [e] (deliver p {::error e}))
         _ (handler request respond raise)
         response (deref p timeout-ms {::error :handler-timeout})]
     (if-some [err (::error response)]
       (throw (if (instance? Throwable err)
                err
                (ex-info "error in asynchronous handler"
                         (if (map? err) err {:error err}))))
       response))))

(defn provider-request
  [request provider]
  (assoc request ::provider provider))

;; TODO: Catch errors and pass to error handler (default one can just raise)
(defn wrap-provider
  "Middleware making the provider available in the request, and converting
   synchronous handlers to asynchronous ones."
  [handler provider]
  {:pre [(provider? provider)]}
  (fn wrapped
    ([request] (await-handler wrapped request (:request-timeout provider)))
    ([request respond raise]
     (handler (provider-request request provider) respond raise))))

;; TODO: Provider some convenience to create simple hooks.
;; Maybe in a .hooks namespace?

(defn- wrap-hook
  [handler provider key]
  (if-some [hook (get provider key)]
    (hook handler)
    handler))

(defn make-authenticate-handler
  "Returns an asynchronous ring handler to authenticate the end-user."
  [provider]
  {:pre [(provider? provider)]}
  (-> (:authentication-response-handler provider)
      (wrap-hook provider :authentication-hook)
      (wrap-provider provider)))

(defn make-callback-handler
  "Returns an asynchronous ring handler to handle callbacks in the Code
   Flow."
  [provider]
  {:pre [(provider? provider)]}
  (-> (:callback-response-handler provider)
      (wrap-hook provider :userinfo-hook)
      ;; TODO: fetch and validate user info
      (wrap-hook provider :token-hook)
      (wrap-validate-id-token)
      (wrap-fetch-tokens)
      (wrap-hook provider :authorization-code-hook)
      (wrap-validate-callback)
      (wrap-provider provider)))

;;;; Provider Configuration

(def defaults
  {;; OP Issuer URI
   ;:issuer nil

   ;; Endpoints, can be discovered
   ;:authorization-endpoint nil
   ;:token-endpoint nil
   ;:userinfo-endpoint nil

   ;; Client credentials
   ;:client-id nil
   ;:client-secret nil

   ;; Auth method to use for the token endpoint, supported values are
   ;; :basic or :post
   :token-auth :basic

   ;; Scopes to request.  At least :openid is required.
   :scopes [:openid :profile :email]

   ;; URI of the callback endpoint, to be passed to the OP as
   ;; redirect_uri.  Can also be a fn request => uri
   ;:callback-uri nil

   ;; URI to redirect to when the authentication flow is complete.
   ;; Can also be a fn request => uri
   :completion-uri "/"

   ;; FIXME: Support max_age, nonce

   ;; Whether to require provider endpoints to use SSL as required by
   ;; the OIDC spec.
   ;; TODO: Support :require-tls?
   :require-tls? true

   ;; Whether to validate the ID Token.
   :validate-id-token? true

   ;; Whether to fetch user info.
   :fetch-userinfo? true

   ;; Maximum acceptable clock skew when validating timestamps.
   :max-clock-skew-secs 30

   ;; Hooks
   ;:authentication-hook nil
   ;:authorization-code-hook nil
   ;:token-hook nil
   ;:userinfo-hook nil

   ;; Handlers
   :authentication-response-handler authentication-response-handler
   :callback-response-handler completion-handler

   ;; Timeout in milliseconds for requests
   :request-timeout (* 60 1000)})

;; TODO: Validate options (spec!)
(defn provider
  "Creates a new provider configuration for the given options."
  [opts]
  (-> (merge defaults opts)
      (assoc ::valid true)))

;;;; Ring middleware entrypoint

;; TODO: Support multiple providers?
(defn- oidc-request [{:keys [session] :as request}]
  (let [tokens (::tokens session)
        id-token (::id-token session)]
    (cond-> request
      (some? tokens) (assoc :oidc/tokens tokens)
      (some? id-token) (assoc :oidc/id-token id-token))))

(defn- make-handler-map
  [opts]
  {:pre [(every? opts [:authenticate-uri :callback-uri])]}
  (let [provider (provider opts)
        uris ((juxt :authenticate-uri :callback-uri) provider)
        handlers ((juxt make-authenticate-handler
                        make-callback-handler)
                  provider)]
    (zipmap uris handlers)))

(defn wrap-oidc
  "Middleware that includes routing to authentication and callback
   handlers, similar to ring-oauth2."
  [handler profiles]
  (let [handler-map (into {} (mapcat make-handler-map) (vals profiles))]
    (fn [{:keys [uri] :as request} & args]
      (if-let [h (handler-map uri)]
        (apply h request args)
        (apply handler (oidc-request request) args)))))
