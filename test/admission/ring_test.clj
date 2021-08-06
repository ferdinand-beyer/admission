(ns admission.ring-test
  (:require [admission.ring :as ring]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [lambdaisland.uri :as uri]
            [org.httpkit.fake :as fake :refer [with-fake-http]]
            [ring.mock.request :as mock]
            [ring.util.codec :as codec]
            [ring.util.response :as resp]))

(def issuer "https://idp.test")

(def client-id "admission-test")

(def provider-opts {:issuer issuer
                    :authorization-endpoint (str issuer "/auth")
                    :token-endpoint (str issuer "/token")
                    :client-id client-id
                    :client-secret "sauce"})

(def profiles {:test (assoc provider-opts
                            :authenticate-uri "/auth"
                            :callback-uri "/cb")})

(def id-token-claims {:iss issuer
                      :aud client-id
                      :exp (+ (quot (System/currentTimeMillis) 1000) 3600)})


(defn jwt [payload]
  (str "header."
       (-> (json/write-str payload)
           .getBytes
           (codec/base64-encode))
       ".signature"))

(def tokens {:access_token (jwt {:black :box})
             :id_token (jwt id-token-claims)})

(defn json-response [data]
  {:status 200
   :headers {:content-type "application/json"}
   :body (.getBytes (json/write-str data))})

(defn dummy-handler
  ([_request]
   (resp/response "sync"))
  ([_request _respond _raise]
   (resp/response "async")))

(deftest test-wrap-oidc
  (with-fake-http [(:token-endpoint provider-opts)
                   (json-response tokens)]
    (let [handler (ring/wrap-oidc dummy-handler profiles)]

      (testing "redirect to authorization endpoint"
        (let [resp (handler (mock/request :get "/auth"))
              loc (get-in resp [:headers "Location"])
              params (uri/query-map loc)]
          (is (= 302 (:status resp)))
          (is (str/starts-with? loc "https://idp.test/auth?"))
          (is (= "code" (:response_type params)))
          (is (= client-id (:client_id params)))
          (is (= "http://localhost/cb" (:redirect_uri params)))
          (is (= "openid profile email" (:scope params)))
          (is (contains? params :state))))
      
      (testing "redirects to completion URI"
        (let [resp (handler (-> (mock/request :get "/cb")
                                (assoc :query-params {"code" "temporary"
                                                      "state" "original"}
                                       :session {::ring/state "original"})))]
          (is (= 302 (:status resp)))
          (is (= "http://localhost/" (get-in resp [:headers "Location"])))
          (is (= #::ring{:tokens tokens
                         :id-token id-token-claims}
                 (:session resp)))
          ;
          ))
      ;
      )))

