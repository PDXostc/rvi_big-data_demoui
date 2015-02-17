(ns rvi-demo.core

  (:require [om.core :as om :include-macros true]
            [om-tools.dom :as dom :include-macros true]
            [om-tools.core :refer-macros [defcomponent defcomponentk]]
            [secretary.core :as sec :include-macros true]
            [rvi-demo.nav :as n]
            [rvi-demo.live-data :as ld]
            [rvi-demo.fleet :as fl]))

(sec/set-config! :prefix "#")

(defn load-om [active-page component state]
  (om/root
    (fn [data _]
      (om/component
        (dom/div {}
             (n/navigation active-page)
             (om/build component data))))
    state
    {:target (. js/document (getElementById "app"))}))

(defn main []
  (-> js/document
      .-location
      (set! "#/live")))

(sec/defroute live-data "/live" []
              (load-om "live" ld/grid ld/map-model))


(sec/defroute fleet-position "/fleet" []
              (load-om "fleet" fl/fleet  {:map {:leaflet-map nil
                                                :map {:lat 39.74739, :lng -105}}
                                          :time-extent {:selected-date (js/Date. 2008 4 18)}
                                          :osm {:url "https://{s}.tiles.mapbox.com/v3/{id}/{z}/{x}/{y}.png"
                                                :attrib "Map data © OpenStreetMap contributors"}}))