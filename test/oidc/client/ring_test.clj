(ns oidc.client.ring-test
  (:require [clojure.test :as test :refer [deftest is testing use-fixtures]]
            [org.httpkit.client :as http]
            [ring.mock.request :as mock]
            [oidc.client.ring :as oidc]
            [ring.util.response :as resp]))

(def requests (atom []))
(def request-stubs (atom []))

(defn stub! [re resp]
  (swap! request-stubs conj [re resp]))

(defn find-response [{:keys [url]}]
  (first (keep (fn [[re resp]] (when (re-matches re url) resp))
               @request-stubs)))

(defn request-mock [opts & [callback]]
  (let [resp (find-response opts)]
    (swap! requests conj opts)
    (deliver (promise) ((or callback identity) resp))))

(defn mock-http-request [f]
  (with-redefs [http/request request-mock]
    (f)))

(defn reset-mocks! [f]
  (let [stubs @request-stubs]
    (try (f)
         (finally
           (reset! requests [])
           (reset! request-stubs stubs)))))

(use-fixtures :once mock-http-request)
(use-fixtures :each reset-mocks!)

(def issuer "https://op.test")
(def provider-opts {:issuer issuer
                    :authorization-endpoint (str issuer "/auth")
                    :token-endpoint (str issuer "/token")
                    :client-id "oidc-client-ring"
                    :client-secret "sauce"})

(defn dummy-handler
  ([_request]
   (resp/response "sync"))
  ([_request _respond _raise]
   (resp/response "async")))

(deftest wrap-oidc-test
  (let [handler (oidc/wrap-oidc dummy-handler
                                {:test (assoc provider-opts
                                              :authenticate-uri "/auth"
                                              :callback-uri "/cb")})]

    (testing "redirects to authorization endpoint"
      (let [resp (handler (mock/request :get "/auth"))]
        (is (= 302 (:status resp)))))))

