(ns rvi-demo.server
  (:require [clojure.java.io :as io]
            [rvi-demo.dev :refer [is-dev? browser-repl start-figwheel]]
            [compojure.core :refer [GET defroutes]]
            [compojure.route :refer [resources]]
            [compojure.handler :refer [api]]
            [net.cgrand.enlive-html :refer [deftemplate set-attr prepend append html]]
            [ring.middleware.reload :as reload]
            [environ.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:gen-class))

(defn- mk-config-js
  []
  (str "var conf = { traces_uri: \"" (env :traces-uri) "\", api_uri: \"" (env :api-uri) "\"}"))

(def inject-html
  (comp
    (if is-dev? (set-attr :class "is-dev") identity)
    (prepend (html [:script {:type "text/javascript" :src "/js/out/goog/base.js"}]))
    (prepend (html [:script {:type "text/javascript"} (mk-config-js)]))
    (append  (html [:script {:type "text/javascript"}
                    (str "goog.require('rvi_demo." (if is-dev? "dev" "prod") "')")]))))

(deftemplate page
  (io/resource "index.html") [] [:body] inject-html)

(defroutes routes
  (resources "/")
  #_(resources "/react" {:root "react"})
  (GET "/*" req (page)))

(def http-handler
  (if is-dev?
    (reload/wrap-reload (api #'routes))
    (api routes)))

(defn run [& [port]]
  (defonce ^:private server
    (do
      (if is-dev? (start-figwheel))
      (let [port (Integer. (or port (env :port) 10555))]
        (print "Starting web server on port" port ".\n")
        (run-jetty http-handler {:port port
                          :join? false}))))
  server)

(defn -main [& [port]]
  (run port))
