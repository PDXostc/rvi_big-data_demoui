; Copyright 2015, ATS Advanced Telematic Systems GmbH
; All Rights Reserved

(defproject rvi-demo "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src/clj" "src/cljs"]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2850"]
                 [ring "1.3.1"]
                 [compojure "1.2.0"]
                 [enlive "1.1.5"]
                 [org.omcljs/om "0.8.8"]
                 [racehub/om-bootstrap "0.4.0"]
                 [cljs-wsock "0.4.0"]
                 [riddley  "0.1.7"]
                 [environ "1.0.0"]
                 [leiningen "2.5.0"]
                 [figwheel "0.2.4-SNAPSHOT"]
                 [secretary "1.2.1"]
                 [cljsjs/d3 "3.5.3-0"]
                 [cljs-ajax "0.3.9"]
                 [com.cemerick/piggieback "0.1.5"]
                 [weasel "0.6.0-SNAPSHOT"]
                 [com.andrewmcveigh/cljs-time "0.3.2"]]

  :plugins [[lein-cljsbuild "1.0.5"]
            [lein-environ "1.0.0"]
            [com.palletops/uberimage "0.4.1"]]

  :min-lein-version "2.5.0"

  :uberjar-name "rvi-demo.jar"

  :main rvi-demo.server

  :cljsbuild {:builds {:app {:source-paths ["src/cljs"]
                             :compiler {:output-to     "resources/public/js/app.js"
                                        :output-dir    "resources/public/js/out"
                                        :source-map    "resources/public/js/out.js.map"
                                        :optimizations :none
                                        :verbose true
                                        :pretty-print  true}}}}
  :uberimage {:tag "advancedtelematic/rvi_demo_ui:0.4.0"}

  :profiles {:dev {:repl-options {:init-ns rvi-demo.server
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

                   :plugins [[lein-figwheel "0.2.4-SNAPSHOT"]]

                   :figwheel {:http-server-root "public"
                              :port 3449
                              :css-dirs ["resources/public/css"]}

                   :env {:is-dev true
                         :traces-uri "ws://localhost:9000/ws"
                         :api-uri "http://localhost:9000/"}

                   :cljsbuild {:builds {:app {:source-paths ["env/dev/cljs"]}}}}

             :uberjar {:hooks [leiningen.cljsbuild]
                       :env {:production true}
                       :omit-source true
                       :aot :all
                       :cljsbuild {:builds {:app
                                            {:source-paths ["env/prod/cljs"]
                                             :compiler
                                             {:optimizations :none
                                              :pretty-print false}}}}}})
