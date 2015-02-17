(ns rvi-demo.nav
  (:require [om-bootstrap.nav :as n]
            [om-tools.dom :as d :include-macros true]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [secretary.core :as sec :include-macros true])
  (:import [goog.history Html5History]))

(let [history (Html5History.)
      navigation EventType/NAVIGATE]
  (goog.events/listen history
                      navigation
                      #(-> % .-token sec/dispatch!))
  (doto history
    (.setEnabled true)))

(defn client-nav!
  "This trick comes from here:
   https://github.com/theJohnnyBrown/matchcolor/blob/master/src/matchcolor/views.cljs.
   This function is meant to be used as the :on-click event of an
   anchor tag."
  [e]
  (.setToken history
             (-> e .-target (.getAttribute "href"))
             (-> e .-target .-title))
  (.preventDefault e))

(defn navigation
  [page]
  (n/navbar
    {:component-fn (fn [opts & c]
                     (d/header opts c))
     :brand (d/a {:href ""
                  :on-click (fn [e]
                              (.preventDefault e)
                              (client-nav! e))}
                 "RVI Big Data")
     :static-top? true
     :class "bs-docs-nav"
     :role "banner"
     :toggle-nav-key 0}
    (n/nav {:class "bs-navbar-collapse"
            :role "navigation"
            :key 0
            :id "top"}
           (n/nav-item {:key 1 :href "#/live" :active? (= "live" page)} "Live")
           (n/nav-item {:key 2 :href "#/fleet" :active? (= "fleet" page)} "Fleet Position")
           (n/nav-item {:key 3 :href "#/historical" :active? (= "historical" page)} "Historical Data"))))
