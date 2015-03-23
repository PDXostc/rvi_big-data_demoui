; Copyright 2015, ATS Advanced Telematic Systems GmbH
; All Rights Reserved

(ns rvi-demo.core

  (:require [om.core :as om :include-macros true]
            [om-tools.dom :as dom :include-macros true]
            [om-tools.core :refer-macros [defcomponent defcomponentk]]
            [secretary.core :as sec :include-macros true]
            [rvi-demo.nav :as n]
            [rvi-demo.live-data :as ld]
            [rvi-demo.fleet :as fl]
            [rvi-demo.pickups :as pick]))

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
              (load-om "live" ld/live-data {:map     {:leaflet-map nil
                                                      :ws          nil
                                                      :map         {:lat 37.75122, :lng -122.39522}}
                                            :markers {}
                                            :traces  {}
                                            :filter  {:speed {:range [0 250]
                                                              :value [0 250]}}
                                            :osm     {:url    "http://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
                                                      :attrib "Map data © OpenStreetMap contributors"}
                                            :socket  nil}))

(sec/defroute fleet-position "/fleet" []
              (load-om "fleet" fl/fleet {:time-extent {:selected-date [(js/Date. 2008 4 20)]}
                                         :area        []}))

(sec/defroute pickups-dropoffs "/pickups" []
              (load-om "pickups" pick/pickups-dropoffs {:positions  []
                                                        :hours      [12 13]
                                                        :date-range {:from [(js/Date. 2008 4 18)]
                                                                     :to   [(js/Date. 2008 4 19)]}
                                                        :area       []}))